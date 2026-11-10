-- Applied V78-V80 remain unchanged. Bind the entire original/physical identity and every inbound INSERT direction.
ALTER TABLE order_exchange_physical_unit ADD CONSTRAINT accepted_exchange_complete_physical_identity UNIQUE(exchange_id,item_id,ordinal,physical_id);
ALTER TABLE claim_exchange_collection ADD CONSTRAINT exchange_collection_exact_accepted_physical FOREIGN KEY(exchange_id,item_id,ordinal,physical_id) REFERENCES order_exchange_physical_unit(exchange_id,item_id,ordinal,physical_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_exchange_inspection ADD CONSTRAINT exchange_inspection_header_identity UNIQUE(id,exchange_id);
ALTER TABLE claim_exchange_inspection_unit ADD CONSTRAINT exchange_inspection_exact_header FOREIGN KEY(inspection_id,exchange_id) REFERENCES claim_exchange_inspection(id,exchange_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_exchange_inspection_unit ADD CONSTRAINT exchange_inspection_exact_accepted_physical FOREIGN KEY(exchange_id,item_id,ordinal,physical_id) REFERENCES order_exchange_physical_unit(exchange_id,item_id,ordinal,physical_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_exchange_collection ADD CONSTRAINT exchange_collection_received_physical_identity UNIQUE(id,exchange_id,item_id,ordinal,physical_id,status);
ALTER TABLE claim_exchange_inspection_unit ADD CONSTRAINT exchange_inspection_exact_received_physical FOREIGN KEY(collection_id,exchange_id,item_id,ordinal,physical_id,collection_status) REFERENCES claim_exchange_collection(id,exchange_id,item_id,ordinal,physical_id,status) DEFERRABLE INITIALLY DEFERRED;
CREATE OR REPLACE FUNCTION exchange_physical_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;a order_exchange_application;c claim_exchange_request;n integer;v_lost boolean;v_ready boolean;v_inspection uuid;h claim_exchange_inspection;v_saleable integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_reservation_physical_source' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSIF TG_TABLE_NAME IN ('inventory_restitution','inventory_restitution_unit','inventory_restitution_movement','inventory_source_restitution') THEN
  SELECT * INTO h FROM claim_exchange_inspection WHERE id=NEW.cause_id;
  IF h.id IS NULL THEN
   IF EXISTS(SELECT 1 FROM inventory_restitution WHERE cause_id=NEW.cause_id AND kind='EXCHANGE') THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_PHYSICAL_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;
   RETURN NULL;
  END IF;v_id=h.exchange_id;
 ELSIF TG_TABLE_NAME IN ('inventory_physical_inbound','inventory_physical_inbound_unit') THEN
  SELECT * INTO h FROM claim_exchange_inspection WHERE id=NEW.inspection_id;
  IF h.id IS NULL THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_PHYSICAL_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;v_id=h.exchange_id;
 ELSE v_id=NEW.exchange_id;END IF;
 SELECT * INTO a FROM order_exchange_application WHERE id=v_id;SELECT * INTO c FROM claim_exchange_request WHERE id=v_id;
 IF a.id IS NULL OR c.id IS NULL THEN RAISE EXCEPTION 'ACCEPTED_EXCHANGE_PHYSICAL_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=v_id;
 IF n=0 OR n<>(SELECT count(*) FROM order_exchange_physical_unit WHERE exchange_id=v_id)
 OR a.store_id IS DISTINCT FROM (SELECT store_id FROM seller_order WHERE id=a.seller_order_id)
 OR EXISTS(SELECT 1 FROM order_exchange_physical_unit u JOIN order_physical_source p ON p.id=u.physical_id JOIN inventory_committed_unit s ON s.id=p.committed_source_id LEFT JOIN order_physical_delivery d ON d.physical_id=p.id WHERE u.exchange_id=v_id AND(
  (p.order_id,p.seller_order_id,p.item_id,p.ordinal) IS DISTINCT FROM (a.order_id,a.seller_order_id,u.item_id,u.ordinal) OR d.physical_id IS NULL OR a.requested_at>=d.delivered_at+make_interval(secs=>(a.source_snapshot->>'windowSeconds')::double precision)
  OR u.source_snapshot->>'id'<>p.id::text OR u.source_snapshot->>'orderId'<>p.order_id::text OR u.source_snapshot->>'sellerOrderId'<>p.seller_order_id::text OR u.source_snapshot->>'itemId'<>p.item_id::text OR (u.source_snapshot->>'ordinal')::integer<>p.ordinal OR (u.source_snapshot->>'round')::integer<>p.round OR u.source_snapshot->'stock'->>'id'<>s.id::text OR u.source_snapshot->'stock'->>'skuId'<>s.sku_id::text OR u.source_snapshot->'delivery'->>'bucketId'<>d.bucket_id::text OR u.source_snapshot->'delivery'->>'eventId'<>d.event_id::text
 )) THEN RAISE EXCEPTION 'EXACT_FIXED_ACCEPTED_PHYSICAL_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 IF c.approved_at IS NOT NULL THEN
  IF EXISTS(SELECT 1 FROM inventory_exchange_reservation r LEFT JOIN inventory_exchange_reservation_physical_source x ON x.reservation_id=r.id LEFT JOIN order_exchange_physical_unit u ON u.exchange_id=r.exchange_id AND u.item_id=r.item_id AND u.ordinal=r.ordinal LEFT JOIN order_physical_source p ON p.id=u.physical_id WHERE r.exchange_id=v_id AND(x.reservation_id IS NULL OR x.physical_id IS DISTINCT FROM p.id OR x.source_id IS DISTINCT FROM p.committed_source_id))
  OR jsonb_array_length(c.approved_offers)<>(SELECT count(DISTINCT target_sku_id) FROM claim_exchange_unit WHERE exchange_id=v_id)
  OR EXISTS(SELECT 1 FROM claim_exchange_unit u JOIN order_item i ON i.id=u.item_id WHERE u.exchange_id=v_id AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(c.approved_offers) o WHERE o->>'skuId'=u.target_sku_id::text AND jsonb_typeof(o->'price')='string' AND o->>'price'=i.unit_price::text AND o->>'storeId'=a.store_id::text))
  THEN RAISE EXCEPTION 'EXACT_EXCHANGE_APPROVAL_SOURCE_AND_PRICE_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 SELECT COALESCE(bool_or(x.status='LOST_REPORTED'),false),COALESCE(bool_and(x.status='RECEIVED' AND i.inspection_id IS NOT NULL),false) INTO v_lost,v_ready FROM claim_exchange_unit u LEFT JOIN LATERAL(SELECT status FROM claim_exchange_collection x WHERE x.exchange_id=u.exchange_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal ORDER BY version DESC LIMIT 1)x ON true LEFT JOIN claim_exchange_inspection_unit i ON i.exchange_id=u.exchange_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal WHERE u.exchange_id=v_id;
 IF EXISTS(SELECT 1 FROM claim_exchange_collection WHERE exchange_id=v_id) THEN
  IF c.state NOT IN ('DISPATCHED','COMPLETED','REFUND_PENDING','REFUNDED','EXPIRED') AND c.state IS DISTINCT FROM (CASE WHEN v_lost THEN 'REVIEW_REQUIRED' WHEN v_ready AND c.approved_at IS NOT NULL THEN 'READY' ELSE 'COLLECTING' END) THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_PHYSICAL_PROGRESS_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM claim_exchange_collection x LEFT JOIN claim_exchange_command z ON z.exchange_id=x.exchange_id AND z.version=x.version LEFT JOIN claim_exchange_history t ON t.exchange_id=x.exchange_id AND t.version=x.version WHERE x.exchange_id=v_id AND(z.exchange_id IS NULL OR t.id IS NULL OR t.action<>z.action OR t.actor<>x.actor OR t.reason<>x.reason OR t.occurred_at<>x.occurred_at)) THEN RAISE EXCEPTION 'EXCHANGE_COLLECTION_OPERATION_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 FOR h IN SELECT * FROM claim_exchange_inspection WHERE exchange_id=v_id LOOP
  SELECT count(*) FILTER(WHERE condition='SALEABLE') INTO v_saleable FROM claim_exchange_inspection_unit WHERE inspection_id=h.id;
  IF NOT EXISTS(SELECT 1 FROM claim_exchange_command z JOIN claim_exchange_history t ON t.exchange_id=z.exchange_id AND t.version=z.version WHERE z.exchange_id=v_id AND z.version=h.version AND t.action=z.action AND t.actor=h.actor AND t.reason=h.reason AND t.occurred_at=h.occurred_at)
  OR NOT EXISTS(SELECT 1 FROM claim_exchange_inspection_unit WHERE inspection_id=h.id)
  OR EXISTS(SELECT 1 FROM claim_exchange_inspection_unit i JOIN claim_exchange_collection x ON x.id=i.collection_id WHERE i.inspection_id=h.id AND(i.physical_id<>x.physical_id OR x.status<>'RECEIVED' OR x.version>=h.version))
  THEN RAISE EXCEPTION 'ACTUAL_RECEIVED_EXCHANGE_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;
  IF v_saleable=0 THEN
   IF EXISTS(SELECT 1 FROM inventory_physical_inbound WHERE inspection_id=h.id) OR EXISTS(SELECT 1 FROM inventory_restitution WHERE cause_id=h.id) THEN RAISE EXCEPTION 'DEFECTIVE_EXCHANGE_CANNOT_RESTOCK' USING ERRCODE='23514';END IF;
  ELSE
   IF NOT EXISTS(SELECT 1 FROM inventory_physical_inbound r JOIN order_exchange_physical_unit u ON u.exchange_id=h.exchange_id JOIN order_physical_source p ON p.id=u.physical_id JOIN inventory_committed_unit s ON s.id=p.committed_source_id WHERE r.inspection_id=h.id AND r.claim_id=h.exchange_id AND r.kind='EXCHANGE' AND r.checkout_id=s.checkout_id)
   OR v_saleable<>(SELECT count(*) FROM inventory_physical_inbound_unit WHERE inspection_id=h.id)
   OR EXISTS(SELECT 1 FROM claim_exchange_inspection_unit i JOIN order_physical_source p ON p.id=i.physical_id WHERE i.inspection_id=h.id AND i.condition='SALEABLE' AND NOT EXISTS(SELECT 1 FROM inventory_physical_inbound_unit u JOIN inventory_source_restitution x ON x.source_id=u.source_id AND x.cause_id=u.inspection_id WHERE u.inspection_id=h.id AND u.physical_id=i.physical_id AND u.source_id=p.committed_source_id))
   OR EXISTS(SELECT 1 FROM inventory_physical_inbound_unit u WHERE u.inspection_id=h.id AND NOT EXISTS(SELECT 1 FROM claim_exchange_inspection_unit i WHERE i.inspection_id=h.id AND i.physical_id=u.physical_id AND i.condition='SALEABLE'))
   OR (SELECT count(*) FROM inventory_restitution_movement WHERE cause_id=h.id)<>(SELECT count(DISTINCT s.sku_id) FROM inventory_physical_inbound_unit u JOIN inventory_committed_unit s ON s.id=u.source_id WHERE u.inspection_id=h.id)
   OR EXISTS(SELECT 1 FROM inventory_restitution_movement m WHERE m.cause_id=h.id AND m.quantity<>(SELECT count(*) FROM inventory_physical_inbound_unit u JOIN inventory_committed_unit s ON s.id=u.source_id WHERE u.inspection_id=h.id AND s.sku_id=m.sku_id))
   THEN RAISE EXCEPTION 'EXACT_EXCHANGE_SALEABLE_INBOUND_REQUIRED' USING ERRCODE='23514';END IF;
  END IF;
 END LOOP;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_inbound_header_exact AFTER INSERT ON inventory_restitution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_inbound_purchase_unit_exact AFTER INSERT ON inventory_restitution_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_inbound_movement_exact AFTER INSERT ON inventory_restitution_movement DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE CONSTRAINT TRIGGER exchange_common_source_claim_exact AFTER INSERT ON inventory_source_restitution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_physical_graph_guard();
CREATE FUNCTION immutable_exchange_inbound_unit() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF EXISTS(SELECT 1 FROM inventory_restitution WHERE cause_id=OLD.cause_id AND kind='EXCHANGE') THEN RAISE EXCEPTION 'IMMUTABLE_EXCHANGE_INBOUND_UNIT' USING ERRCODE='23514';END IF;IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;END $$;
CREATE TRIGGER exchange_inbound_purchase_unit_immutable BEFORE UPDATE OR DELETE ON inventory_restitution_unit FOR EACH ROW EXECUTE FUNCTION immutable_exchange_inbound_unit();
