-- Applied V82/V83 remain immutable. Every exchange progress fact requires the exact scheduled job effect.
CREATE FUNCTION verify_exchange_shipping(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE h fulfillment_exchange_shipment;e fulfillment_exchange_event;z fulfillment_exchange_progress_effect;s fulfillment_exchange_progress_schedule;n integer;last_version bigint;expected_state text;
BEGIN
 SELECT * INTO h FROM fulfillment_exchange_shipment WHERE id=p_id;IF h.id IS NULL THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_SHIPMENT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM fulfillment_exchange_allocation WHERE shipment_id=p_id;
 IF n=0 OR n<>(SELECT count(*) FROM claim_exchange_unit WHERE exchange_id=h.exchange_id) OR 2<>(SELECT count(*) FROM fulfillment_exchange_progress_schedule WHERE shipment_id=p_id)
 OR EXISTS(SELECT 1 FROM fulfillment_exchange_progress_schedule sch LEFT JOIN job j ON j.id=sch.job_id LEFT JOIN outbox_event o ON o.job_id=j.id WHERE sch.shipment_id=p_id AND(j.id IS NULL OR o.id IS NULL OR j.type<>'EXCHANGE_SHIPMENT_PROGRESS.v1' OR j.dedupe_key<>'exchange-progress:'||p_id::text||':'||sch.expected_version::text OR j.cause_key<>p_id::text OR j.payload<>p_id::text||'|'||sch.expected_version::text OR j.scheduled_at<>sch.scheduled_at OR sch.scheduled_at<>h.shipped_at))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_SCHEDULE_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT max(version) INTO last_version FROM fulfillment_exchange_event WHERE shipment_id=p_id;
 IF last_version IS NULL OR last_version NOT IN(1,2,3) OR h.version<>last_version OR last_version<>(SELECT count(*) FROM fulfillment_exchange_event WHERE shipment_id=p_id)
 OR h.state<>(CASE last_version WHEN 1 THEN 'SHIPPED' WHEN 2 THEN 'IN_TRANSIT' ELSE 'DELIVERED' END)
 OR NOT EXISTS(SELECT 1 FROM fulfillment_exchange_event f WHERE f.shipment_id=p_id AND f.version=last_version AND f.state=h.state AND (last_version<3 AND h.delivered_at IS NULL OR last_version=3 AND h.delivered_at=f.occurred_at))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_SHIPPING_HEADER_EVENT_REQUIRED' USING ERRCODE='23514';END IF;
 FOR e IN SELECT * FROM fulfillment_exchange_event WHERE shipment_id=p_id ORDER BY version LOOP
  expected_state=CASE e.version WHEN 1 THEN 'SHIPPED' WHEN 2 THEN 'IN_TRANSIT' WHEN 3 THEN 'DELIVERED' ELSE 'INVALID' END;
  IF e.state<>expected_state OR e.version=1 AND e.occurred_at<>h.shipped_at OR e.version>1 AND NOT EXISTS(SELECT 1 FROM fulfillment_exchange_event p WHERE p.shipment_id=p_id AND p.version=e.version-1 AND p.occurred_at<=e.occurred_at) THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_STATE_TIME_REQUIRED' USING ERRCODE='23514';END IF;
  IF e.version=1 THEN
   IF EXISTS(SELECT 1 FROM fulfillment_exchange_progress_effect WHERE event_id=e.id) OR NOT EXISTS(SELECT 1 FROM order_exchange_dispatch d JOIN claim_exchange_dispatch_effect c ON c.exchange_id=d.exchange_id WHERE d.exchange_id=h.exchange_id AND d.shipment_id=h.id AND d.event_id=e.id AND c.shipment_id=h.id AND c.dispatch_id=h.dispatch_id)
   THEN RAISE EXCEPTION 'EXACT_EXCHANGE_DISPATCH_EVENT_REQUIRED' USING ERRCODE='23514';END IF;
  ELSE
   SELECT * INTO z FROM fulfillment_exchange_progress_effect WHERE shipment_id=p_id AND version=e.version;SELECT * INTO s FROM fulfillment_exchange_progress_schedule WHERE shipment_id=p_id AND expected_version=e.version;
   IF z.shipment_id IS NULL OR s.shipment_id IS NULL OR z.event_id<>e.id OR z.job_id<>s.job_id OR e.occurred_at<s.scheduled_at OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=z.job_id AND j.type='EXCHANGE_SHIPMENT_PROGRESS.v1' AND j.dedupe_key='exchange-progress:'||p_id::text||':'||e.version::text AND j.cause_key=p_id::text AND j.payload=p_id::text||'|'||e.version::text AND j.scheduled_at=s.scheduled_at)
   THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_JOB_EFFECT_REQUIRED' USING ERRCODE='23514';END IF;
   IF e.version=3 THEN
    IF n<>(SELECT count(*) FROM fulfillment_exchange_allocation a JOIN order_physical_delivery d ON d.physical_id=a.physical_id AND d.bucket_id=p_id AND d.event_id=e.id AND d.delivered_at=e.occurred_at WHERE a.shipment_id=p_id)
    OR NOT EXISTS(SELECT 1 FROM order_exchange_delivery_bucket b WHERE b.bucket_id=p_id AND b.shipment_id=p_id)
    OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_DELIVERED.v1' AND j.dedupe_key='exchange-delivered:'||h.exchange_id::text||':'||e.id::text AND j.cause_key=h.exchange_id::text AND j.payload=h.exchange_id::text||'|'||e.id::text AND j.scheduled_at=e.occurred_at)
    THEN RAISE EXCEPTION 'EXACT_EXCHANGE_DELIVERY_AND_NOTIFICATION_REQUIRED' USING ERRCODE='23514';END IF;
   END IF;
  END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM fulfillment_exchange_progress_effect effect_row LEFT JOIN fulfillment_exchange_event event_row ON event_row.id=effect_row.event_id WHERE effect_row.shipment_id=p_id AND(event_row.id IS NULL OR event_row.shipment_id<>p_id OR event_row.version<>effect_row.version OR event_row.version NOT IN(2,3)))
 OR EXISTS(SELECT 1 FROM order_physical_delivery d JOIN fulfillment_exchange_allocation a ON a.physical_id=d.physical_id LEFT JOIN fulfillment_exchange_event actual_event ON actual_event.id=d.event_id WHERE a.shipment_id=p_id AND(d.bucket_id<>p_id OR actual_event.id IS NULL OR actual_event.shipment_id<>p_id OR actual_event.version<>3 OR actual_event.state<>'DELIVERED' OR d.delivered_at<>actual_event.occurred_at))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_REVERSE_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION exchange_shipping_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;
BEGIN
 IF TG_TABLE_NAME='fulfillment_exchange_shipment' THEN target=NEW.id;
 ELSIF TG_TABLE_NAME='order_exchange_delivery_bucket' THEN target=NEW.shipment_id;
 ELSIF TG_TABLE_NAME='order_physical_delivery' THEN SELECT shipment_id INTO target FROM fulfillment_exchange_allocation WHERE physical_id=NEW.physical_id;
 ELSE target=NEW.shipment_id;END IF;
 IF target IS NOT NULL THEN PERFORM verify_exchange_shipping(target);END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_shipping_header_exact AFTER INSERT OR UPDATE ON fulfillment_exchange_shipment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_shipping_event_exact AFTER INSERT ON fulfillment_exchange_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_shipping_effect_exact AFTER INSERT ON fulfillment_exchange_progress_effect DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_shipping_schedule_exact AFTER INSERT ON fulfillment_exchange_progress_schedule DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_shipping_bucket_exact AFTER INSERT ON order_exchange_delivery_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_shipping_order_delivery_exact AFTER INSERT ON order_physical_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_graph_guard();
DO $$ DECLARE target uuid;BEGIN FOR target IN SELECT id FROM fulfillment_exchange_shipment LOOP PERFORM verify_exchange_shipping(target);END LOOP;END $$;
