-- Dedicated reshipment is separate from original purchase reservations and BASIC shipments.
ALTER TABLE inventory_exchange_reservation DROP CONSTRAINT inventory_exchange_reservation_status_check;
ALTER TABLE inventory_exchange_reservation ADD CONSTRAINT inventory_exchange_reservation_status_check CHECK(status IN ('RESERVED','COMMITTED'));
ALTER TABLE claim_exchange_request DROP CONSTRAINT claim_exchange_request_state_check;
ALTER TABLE claim_exchange_request ADD CONSTRAINT claim_exchange_request_state_check CHECK(state IN ('REQUESTED','RESPONSIBILITY_PENDING','APPROVED','COLLECTING','READY','REVIEW_REQUIRED','DISPATCH_PENDING','DISPATCHED','COMPLETED','REJECTED','WITHDRAWN','EXPIRED','REFUND_PENDING','REFUNDED'));
CREATE TABLE claim_exchange_dispatch_request (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_request(id),version bigint NOT NULL,command_key varchar(100) NOT NULL,requested_at timestamptz NOT NULL,
 FOREIGN KEY(exchange_id,command_key) REFERENCES claim_exchange_command(exchange_id,command_key) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE inventory_exchange_commit (
 reservation_id uuid PRIMARY KEY REFERENCES inventory_exchange_reservation(id),dispatch_id uuid NOT NULL REFERENCES claim_exchange_dispatch_request(id),
 before_on_hand bigint NOT NULL,after_on_hand bigint NOT NULL,before_reserved bigint NOT NULL,after_reserved bigint NOT NULL,before_version bigint NOT NULL,version bigint NOT NULL,committed_at timestamptz NOT NULL,
 CHECK(after_on_hand=before_on_hand-1 AND after_on_hand>=0 AND after_reserved=before_reserved-1 AND after_reserved>=0 AND version=before_version+1)
);
CREATE TABLE fulfillment_exchange_shipment (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_request(id),dispatch_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_dispatch_request(id),
 order_id uuid NOT NULL,seller_order_id uuid NOT NULL,state varchar(20) NOT NULL CHECK(state IN ('SHIPPED','IN_TRANSIT','DELIVERED')),version bigint NOT NULL,
 carrier varchar(20) NOT NULL DEFAULT 'MOCK',tracking_number varchar(100) NOT NULL UNIQUE,shipped_at timestamptz NOT NULL,delivered_at timestamptz,
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),UNIQUE(id,exchange_id)
);
CREATE TABLE fulfillment_exchange_event (
 id uuid PRIMARY KEY,shipment_id uuid NOT NULL REFERENCES fulfillment_exchange_shipment(id),version bigint NOT NULL,state varchar(20) NOT NULL,occurred_at timestamptz NOT NULL,UNIQUE(shipment_id,version),UNIQUE(id,shipment_id)
);
CREATE TABLE fulfillment_exchange_allocation (
 id uuid PRIMARY KEY,shipment_id uuid NOT NULL,exchange_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,predecessor_id uuid NOT NULL,physical_id uuid NOT NULL UNIQUE,source_id uuid NOT NULL REFERENCES inventory_committed_unit(id),reservation_id uuid NOT NULL UNIQUE REFERENCES inventory_exchange_reservation(id),
 FOREIGN KEY(shipment_id,exchange_id) REFERENCES fulfillment_exchange_shipment(id,exchange_id),FOREIGN KEY(exchange_id,item_id,ordinal,predecessor_id) REFERENCES order_exchange_physical_unit(exchange_id,item_id,ordinal,physical_id),
 UNIQUE(shipment_id,item_id,ordinal),UNIQUE(id,physical_id)
);
CREATE TABLE fulfillment_exchange_progress_schedule (
 shipment_id uuid NOT NULL REFERENCES fulfillment_exchange_shipment(id),expected_version bigint NOT NULL CHECK(expected_version IN(2,3)),job_id uuid NOT NULL UNIQUE REFERENCES job(id),scheduled_at timestamptz NOT NULL,
 PRIMARY KEY(shipment_id,expected_version)
);
CREATE TABLE claim_exchange_dispatch_effect (
 exchange_id uuid PRIMARY KEY REFERENCES claim_exchange_request(id),dispatch_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_dispatch_request(id),shipment_id uuid NOT NULL UNIQUE REFERENCES fulfillment_exchange_shipment(id),
 version bigint NOT NULL,job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,recorded_at timestamptz NOT NULL
);
CREATE TABLE order_exchange_dispatch (
 exchange_id uuid PRIMARY KEY REFERENCES order_exchange_application(id),shipment_id uuid NOT NULL UNIQUE REFERENCES fulfillment_exchange_shipment(id),event_id uuid NOT NULL UNIQUE REFERENCES fulfillment_exchange_event(id),dispatched_at timestamptz NOT NULL
);
CREATE TABLE order_exchange_delivery_bucket (
 bucket_id uuid PRIMARY KEY,shipment_id uuid NOT NULL UNIQUE REFERENCES fulfillment_exchange_shipment(id),kind varchar(10) NOT NULL DEFAULT 'EXCHANGE' CHECK(kind='EXCHANGE'),CHECK(bucket_id=shipment_id),FOREIGN KEY(bucket_id,kind) REFERENCES order_delivery_bucket(id,kind)
);
CREATE OR REPLACE FUNCTION exchange_physical_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;a order_exchange_application;c claim_exchange_request;n integer;v_lost boolean;v_ready boolean;v_inspection uuid;h claim_exchange_inspection;v_saleable integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_reservation_physical_source' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
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
  IF c.state NOT IN ('DISPATCH_PENDING','DISPATCHED','COMPLETED','REFUND_PENDING','REFUNDED','EXPIRED') AND c.state IS DISTINCT FROM (CASE WHEN v_lost THEN 'REVIEW_REQUIRED' WHEN v_ready AND c.approved_at IS NOT NULL THEN 'READY' ELSE 'COLLECTING' END) THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_PHYSICAL_PROGRESS_REQUIRED' USING ERRCODE='23514';END IF;
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

-- The old purchase branch stays byte-for-byte in its own function; successor proof uses concrete exchange allocations.
ALTER FUNCTION verify_order_physical_source(uuid) RENAME TO verify_original_physical_source_v80;
CREATE FUNCTION verify_order_physical_source(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE p order_physical_source;b order_physical_source;s inventory_committed_unit;
BEGIN
 SELECT * INTO p FROM order_physical_source WHERE id=p_id;
 IF p.round=0 THEN PERFORM verify_original_physical_source_v80(p_id);RETURN;END IF;
 SELECT * INTO b FROM order_physical_source WHERE id=p.predecessor_id;SELECT * INTO s FROM inventory_committed_unit WHERE id=p.committed_source_id;
 IF p.id IS NULL OR b.id IS NULL OR s.id IS NULL OR p.round<>b.round+1 OR (p.order_id,p.seller_order_id,p.item_id,p.ordinal,p.original_physical_id) IS DISTINCT FROM (b.order_id,b.seller_order_id,b.item_id,b.ordinal,b.original_physical_id)
 OR s.kind<>'EXCHANGE' OR (s.item_id,s.ordinal) IS DISTINCT FROM (p.item_id,p.ordinal)
 OR NOT EXISTS(SELECT 1 FROM fulfillment_exchange_allocation a JOIN fulfillment_exchange_shipment h ON h.id=a.shipment_id JOIN inventory_exchange_source x ON x.source_id=a.source_id JOIN inventory_exchange_commit c ON c.reservation_id=x.reservation_id JOIN order_exchange_dispatch d ON d.exchange_id=a.exchange_id WHERE a.physical_id=p.id AND a.predecessor_id=p.predecessor_id AND a.source_id=p.committed_source_id AND a.reservation_id=x.reservation_id AND h.dispatch_id=c.dispatch_id AND h.order_id=p.order_id AND h.seller_order_id=p.seller_order_id AND d.shipment_id=h.id AND d.dispatched_at=p.created_at)
 THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_DISPATCH_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE OR REPLACE FUNCTION order_physical_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;v_bucket uuid;d order_physical_delivery;p order_physical_source;b order_delivery_bucket;
BEGIN
 IF TG_TABLE_NAME='order_physical_source' THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME IN ('order_physical_current','order_exchange_physical_unit','order_physical_delivery') THEN v_id=NEW.physical_id;
 ELSIF TG_TABLE_NAME='order_delivery_bucket' THEN v_bucket=NEW.id;ELSE v_bucket=NEW.bucket_id;END IF;
 IF v_id IS NOT NULL THEN
  PERFORM verify_order_physical_source(v_id);SELECT * INTO p FROM order_physical_source WHERE id=v_id;SELECT * INTO d FROM order_physical_delivery WHERE physical_id=v_id;
  IF d.physical_id IS NOT NULL THEN
   SELECT * INTO b FROM order_delivery_bucket WHERE id=d.bucket_id;
   IF (b.order_id,b.seller_order_id) IS DISTINCT FROM (p.order_id,p.seller_order_id) OR p.round=0 AND (b.kind<>'BASIC' OR NOT EXISTS(SELECT 1 FROM order_physical_unit o WHERE o.id=p.original_physical_id AND o.delivery_event_id=d.event_id AND o.delivery_bucket_id=d.bucket_id AND o.delivered_at=d.delivered_at))
   OR p.round>0 AND (b.kind<>'EXCHANGE' OR NOT EXISTS(SELECT 1 FROM fulfillment_exchange_allocation a JOIN fulfillment_exchange_event e ON e.shipment_id=a.shipment_id WHERE a.physical_id=p.id AND a.shipment_id=b.id AND e.id=d.event_id AND e.state='DELIVERED' AND e.occurred_at=d.delivered_at))
   THEN RAISE EXCEPTION 'EXACT_PHYSICAL_DELIVERY_BUCKET_REQUIRED' USING ERRCODE='23514';END IF;v_bucket=d.bucket_id;
  END IF;
 END IF;
 IF v_bucket IS NOT NULL THEN
  SELECT * INTO b FROM order_delivery_bucket WHERE id=v_bucket;
  IF b.kind='BASIC' THEN
   IF NOT EXISTS(SELECT 1 FROM order_basic_delivery_bucket x JOIN shipment s ON s.id=x.shipment_id WHERE x.bucket_id=v_bucket AND s.order_id=b.order_id AND s.seller_order_id=b.seller_order_id AND x.shipment_id=b.id) THEN RAISE EXCEPTION 'CONCRETE_BASIC_DELIVERY_BUCKET_REQUIRED' USING ERRCODE='23514';END IF;
  ELSIF NOT EXISTS(SELECT 1 FROM order_exchange_delivery_bucket x JOIN fulfillment_exchange_shipment s ON s.id=x.shipment_id WHERE x.bucket_id=v_bucket AND s.order_id=b.order_id AND s.seller_order_id=b.seller_order_id AND x.shipment_id=b.id) THEN RAISE EXCEPTION 'CONCRETE_EXCHANGE_DELIVERY_BUCKET_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_delivery_bucket_exact AFTER INSERT ON order_exchange_delivery_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_physical_graph_guard();

CREATE FUNCTION exchange_reship_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;c claim_exchange_request;r claim_exchange_dispatch_request;h fulfillment_exchange_shipment;e claim_exchange_dispatch_effect;n integer;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_request' THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_commit' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE v_id=NEW.exchange_id;END IF;
 SELECT * INTO c FROM claim_exchange_request WHERE id=v_id;SELECT * INTO r FROM claim_exchange_dispatch_request WHERE exchange_id=v_id;
 IF r.id IS NULL THEN
  IF c.state IN('DISPATCH_PENDING','DISPATCHED') THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_DISPATCH_REQUEST_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_command z JOIN claim_exchange_history t ON t.exchange_id=z.exchange_id AND t.version=z.version WHERE z.exchange_id=v_id AND z.command_key=r.command_key AND z.action='DISPATCH_PENDING' AND z.version=r.version AND t.action=z.action)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=v_id AND NOT EXISTS(SELECT 1 FROM claim_exchange_collection x JOIN claim_exchange_inspection_unit i ON i.collection_id=x.id AND i.exchange_id=x.exchange_id AND i.item_id=x.item_id AND i.ordinal=x.ordinal AND i.physical_id=x.physical_id WHERE x.exchange_id=v_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal AND x.status='RECEIVED'))
 THEN RAISE EXCEPTION 'EXACT_INSPECTED_EXCHANGE_DISPATCH_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO h FROM fulfillment_exchange_shipment WHERE exchange_id=v_id;SELECT * INTO e FROM claim_exchange_dispatch_effect WHERE exchange_id=v_id;
 IF h.id IS NULL THEN
  IF c.state<>'DISPATCH_PENDING' OR e.exchange_id IS NOT NULL OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=v_id AND status<>'RESERVED') THEN RAISE EXCEPTION 'PENDING_EXCHANGE_CANNOT_COMMIT_STOCK' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=v_id;
 IF e.exchange_id IS NULL OR e.dispatch_id<>r.id OR e.shipment_id<>h.id OR h.dispatch_id<>r.id OR h.order_id<>c.order_id OR h.seller_order_id<>c.seller_order_id
 OR c.state NOT IN('DISPATCHED','COMPLETED') OR n<>(SELECT count(*) FROM fulfillment_exchange_allocation WHERE exchange_id=v_id)
 OR n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=v_id AND status='COMMITTED')
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=e.job_id AND j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR NOT EXISTS(SELECT 1 FROM order_exchange_dispatch d JOIN fulfillment_exchange_event f ON f.id=d.event_id WHERE d.exchange_id=v_id AND d.shipment_id=h.id AND d.dispatched_at=h.shipped_at AND f.shipment_id=h.id AND f.state='SHIPPED' AND f.occurred_at=h.shipped_at)
 OR EXISTS(SELECT 1 FROM inventory_exchange_reservation x LEFT JOIN inventory_exchange_commit z ON z.reservation_id=x.id LEFT JOIN inventory_exchange_source source ON source.reservation_id=x.id LEFT JOIN fulfillment_exchange_allocation a ON a.reservation_id=x.id WHERE x.exchange_id=v_id AND (z.reservation_id IS NULL OR z.dispatch_id<>r.id OR z.committed_at>=x.expires_at OR source.source_id IS NULL OR a.exchange_id IS DISTINCT FROM v_id OR a.source_id IS DISTINCT FROM source.source_id OR a.item_id<>x.item_id OR a.ordinal<>x.ordinal OR NOT EXISTS(SELECT 1 FROM order_physical_source p WHERE p.id=a.physical_id AND p.predecessor_id=a.predecessor_id AND p.committed_source_id=a.source_id)))
 THEN RAISE EXCEPTION 'EXACT_COMMITTED_EXCHANGE_SHIPMENT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_reship_workflow_graph AFTER INSERT OR UPDATE ON claim_exchange_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_reship_graph();
CREATE CONSTRAINT TRIGGER exchange_reship_request_graph AFTER INSERT ON claim_exchange_dispatch_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_reship_graph();
CREATE CONSTRAINT TRIGGER exchange_reship_commit_graph AFTER INSERT ON inventory_exchange_commit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_reship_graph();
CREATE CONSTRAINT TRIGGER exchange_reship_shipment_graph AFTER INSERT OR UPDATE ON fulfillment_exchange_shipment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_reship_graph();
CREATE CONSTRAINT TRIGGER exchange_reship_allocation_graph AFTER INSERT ON fulfillment_exchange_allocation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_reship_graph();
CREATE CONSTRAINT TRIGGER exchange_reship_effect_graph AFTER INSERT ON claim_exchange_dispatch_effect DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_reship_graph();
CREATE FUNCTION exchange_dispatch_current_lease() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM job j WHERE j.id=NEW.job_id AND j.status='RUNNING' AND j.owner_token=NEW.owner_token AND j.lease_until>clock_timestamp()) THEN RAISE EXCEPTION 'EXCHANGE_DISPATCH_CURRENT_JOB_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER exchange_dispatch_new_effect_lease BEFORE INSERT ON claim_exchange_dispatch_effect FOR EACH ROW EXECUTE FUNCTION exchange_dispatch_current_lease();
CREATE TRIGGER claim_exchange_dispatch_request_immutable BEFORE UPDATE OR DELETE ON claim_exchange_dispatch_request FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER inventory_exchange_commit_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_commit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER fulfillment_exchange_event_immutable BEFORE UPDATE OR DELETE ON fulfillment_exchange_event FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER fulfillment_exchange_allocation_immutable BEFORE UPDATE OR DELETE ON fulfillment_exchange_allocation FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER fulfillment_exchange_progress_schedule_immutable BEFORE UPDATE OR DELETE ON fulfillment_exchange_progress_schedule FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_dispatch_effect_immutable BEFORE UPDATE OR DELETE ON claim_exchange_dispatch_effect FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER order_exchange_dispatch_immutable BEFORE UPDATE OR DELETE ON order_exchange_dispatch FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER order_exchange_delivery_bucket_immutable BEFORE UPDATE OR DELETE ON order_exchange_delivery_bucket FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();

CREATE TABLE fulfillment_exchange_progress_effect (
 shipment_id uuid NOT NULL,version bigint NOT NULL,event_id uuid NOT NULL,job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,
 PRIMARY KEY(shipment_id,version),FOREIGN KEY(event_id,shipment_id) REFERENCES fulfillment_exchange_event(id,shipment_id),FOREIGN KEY(shipment_id,version) REFERENCES fulfillment_exchange_progress_schedule(shipment_id,expected_version)
);
CREATE TRIGGER exchange_progress_new_effect_lease BEFORE INSERT ON fulfillment_exchange_progress_effect FOR EACH ROW EXECUTE FUNCTION exchange_dispatch_current_lease();
CREATE TRIGGER exchange_progress_effect_immutable BEFORE UPDATE OR DELETE ON fulfillment_exchange_progress_effect FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();

CREATE TABLE order_exchange_completion (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE REFERENCES order_exchange_application(id),order_id uuid NOT NULL REFERENCES purchase_order(id),hold_id uuid NOT NULL UNIQUE REFERENCES order_unit_hold(id),completed_at timestamptz NOT NULL,UNIQUE(id,exchange_id)
);
CREATE TABLE order_exchange_completion_unit (
 exchange_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,
 PRIMARY KEY(exchange_id,item_id,ordinal),FOREIGN KEY(exchange_id,item_id,ordinal) REFERENCES order_exchange_unit(exchange_id,item_id,ordinal),FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_source(id,item_id,ordinal),
 FOREIGN KEY(exchange_id) REFERENCES order_exchange_completion(exchange_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE claim_exchange_completion (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_request(id),shipment_id uuid NOT NULL REFERENCES fulfillment_exchange_shipment(id),event_id uuid NOT NULL REFERENCES fulfillment_exchange_event(id),version bigint NOT NULL,job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,recorded_at timestamptz NOT NULL,
 FOREIGN KEY(id,exchange_id) REFERENCES order_exchange_completion(id,exchange_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE order_exchange_completion ADD CONSTRAINT actual_exchange_workflow_completion_required FOREIGN KEY(id) REFERENCES claim_exchange_completion(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER exchange_completion_new_effect_lease BEFORE INSERT ON claim_exchange_completion FOR EACH ROW EXECUTE FUNCTION exchange_dispatch_current_lease();
CREATE TABLE order_physical_confirmation_schedule (
 id uuid PRIMARY KEY,order_id uuid NOT NULL REFERENCES purchase_order(id),item_id uuid NOT NULL,ordinal integer NOT NULL,generation bigint NOT NULL CHECK(generation>=0),physical_id uuid NOT NULL,event_id uuid NOT NULL,policy_version varchar(100) NOT NULL,due_at timestamptz NOT NULL,scheduled_at timestamptz NOT NULL,
 UNIQUE(item_id,ordinal,generation),FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_source(id,item_id,ordinal),FOREIGN KEY(physical_id) REFERENCES order_physical_delivery(physical_id),FOREIGN KEY(event_id) REFERENCES fulfillment_exchange_event(id)
);
CREATE OR REPLACE FUNCTION validate_exchange_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;a order_exchange_application;c claim_exchange_request;n integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN target=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_event' THEN SELECT exchange_id INTO target FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE target=NEW.exchange_id;END IF;
 SELECT * INTO a FROM order_exchange_application WHERE id=target;SELECT * INTO c FROM claim_exchange_request WHERE id=target;
 IF a.id IS NULL OR c.id IS NULL OR a.source_snapshot<>c.source_snapshot OR c.source_snapshot->>'id'<>c.id::text OR c.receipt->>'exchangeId'<>c.id::text OR c.receipt->>'holdId'<>c.hold_id::text
 OR NOT EXISTS(SELECT 1 FROM order_unit_hold h WHERE h.id=a.hold_id AND h.kind='EXCHANGE' AND (h.state='ACTIVE' OR c.state='COMPLETED' AND h.state='RELEASED' AND h.outcome='EXCHANGED' AND EXISTS(SELECT 1 FROM order_exchange_completion z WHERE z.exchange_id=c.id AND z.hold_id=h.id)) AND h.cause_id=a.cause_id AND h.order_id=a.order_id)
 THEN RAISE EXCEPTION 'EXCHANGE_ACCEPTED_GRAPH' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=target;
 IF n=0 OR n<>(SELECT count(*) FROM order_exchange_unit WHERE exchange_id=target) OR n<>(SELECT count(*) FROM order_hold_unit WHERE hold_id=a.hold_id) OR n<>jsonb_array_length(a.source_snapshot->'units') OR n<>jsonb_array_length(c.receipt->'units')
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u JOIN order_exchange_unit e ON e.exchange_id=u.exchange_id AND e.item_id=u.item_id AND e.ordinal=u.ordinal JOIN order_physical_unit p ON p.id=e.physical_id JOIN order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal JOIN inventory_reservation r ON r.id=p.reservation_id JOIN order_initial_delivery d ON d.event_id=p.delivery_event_id JOIN order_item i ON i.id=u.item_id WHERE u.exchange_id=target AND (u.hold_id<>a.hold_id OR e.hold_id<>a.hold_id OR u.physical_id<>p.id OR p.order_id<>a.order_id OR p.seller_order_id<>a.seller_order_id OR c.state<>'COMPLETED' AND (l.state<>'HELD' OR l.active_hold_id IS DISTINCT FROM a.hold_id) OR r.status<>'COMMITTED' OR r.checkout_id<>p.checkout_id OR r.order_item_id<>p.item_id OR r.sku_id<>p.sku_id OR r.quantity<=p.ordinal OR d.shipment_id<>p.delivery_bucket_id OR d.occurred_at<>p.delivered_at OR d.order_id<>a.order_id OR d.seller_order_id<>a.seller_order_id OR i.sku_id<>p.sku_id OR NOT EXISTS(SELECT 1 FROM order_initial_delivery_unit du WHERE du.event_id=d.event_id AND du.item_id=p.item_id AND du.ordinal=p.ordinal)))
 THEN RAISE EXCEPTION 'EXCHANGE_EXACT_SOURCE_UNITS' USING ERRCODE='23514';END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=target AND h.version=0 AND h.action='REQUESTED' AND h.reason=c.reason AND h.occurred_at=c.requested_at)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-requested:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
 THEN RAISE EXCEPTION 'EXCHANGE_REQUEST_RECEIPT_OUTBOX' USING ERRCODE='23514';END IF;
 IF c.state='REQUESTED' AND EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=target) THEN RAISE EXCEPTION 'UNAPPROVED_EXCHANGE_RESERVATION' USING ERRCODE='23514';END IF;
 IF c.state='APPROVED' THEN
  IF n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=target AND status='RESERVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_history WHERE exchange_id=target AND version=1 AND action='APPROVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_command WHERE exchange_id=target AND version=1 AND action='APPROVED')
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-approved:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
  OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id JOIN order_physical_unit p ON p.id=u.physical_id WHERE r.exchange_id=target AND (r.order_id<>a.order_id OR r.seller_order_id<>a.seller_order_id OR r.checkout_id<>p.checkout_id OR r.item_id<>p.item_id OR r.ordinal<>p.ordinal OR r.source_reservation_id<>p.reservation_id OR r.source_sku_id<>p.sku_id OR r.target_sku_id<>u.target_sku_id OR r.approved_at<>c.approved_at OR r.expires_at<>c.expires_at OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=c.approved_at)))
  THEN RAISE EXCEPTION 'EXCHANGE_APPROVAL_RESERVATION_GRAPH' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;

CREATE FUNCTION exchange_completion_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;c claim_exchange_request;z claim_exchange_completion;o order_exchange_completion;n integer;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_request' THEN v_id=NEW.id;ELSE v_id=NEW.exchange_id;END IF;
 SELECT * INTO c FROM claim_exchange_request WHERE id=v_id;SELECT * INTO z FROM claim_exchange_completion WHERE exchange_id=v_id;SELECT * INTO o FROM order_exchange_completion WHERE exchange_id=v_id;
 IF z.id IS NULL THEN IF c.state='COMPLETED' OR o.id IS NOT NULL THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_COMPLETION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;END IF;
 SELECT count(*) INTO n FROM order_exchange_unit WHERE exchange_id=v_id;
 IF o.id IS DISTINCT FROM z.id OR c.state<>'COMPLETED' OR c.order_id<>o.order_id OR c.hold_id<>o.hold_id OR n=0 OR n<>(SELECT count(*) FROM order_exchange_completion_unit WHERE exchange_id=v_id)
 OR NOT EXISTS(SELECT 1 FROM order_unit_hold h WHERE h.id=o.hold_id AND h.state='RELEASED' AND h.outcome='EXCHANGED' AND h.kind='EXCHANGE' AND h.order_id=o.order_id)
 OR NOT EXISTS(SELECT 1 FROM fulfillment_exchange_shipment h JOIN fulfillment_exchange_event e ON e.shipment_id=h.id WHERE h.id=z.shipment_id AND h.exchange_id=v_id AND h.state='DELIVERED' AND e.id=z.event_id AND e.state='DELIVERED' AND e.occurred_at=h.delivered_at)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=v_id AND h.version=z.version AND h.action='COMPLETED')
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=z.job_id AND j.type='EXCHANGE_DELIVERED.v1' AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||z.event_id::text AND j.dedupe_key='exchange-delivered:'||v_id::text||':'||z.event_id::text)
 OR EXISTS(SELECT 1 FROM order_exchange_completion_unit u JOIN order_physical_source p ON p.id=u.physical_id LEFT JOIN order_physical_delivery d ON d.physical_id=p.id LEFT JOIN order_exchange_physical_unit a ON a.exchange_id=u.exchange_id AND a.item_id=u.item_id AND a.ordinal=u.ordinal WHERE u.exchange_id=v_id AND(p.predecessor_id IS DISTINCT FROM a.physical_id OR d.event_id IS DISTINCT FROM z.event_id OR d.bucket_id IS DISTINCT FROM z.shipment_id))
 THEN RAISE EXCEPTION 'EXACT_DELIVERED_EXCHANGE_COMPLETION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_terminal_workflow_graph AFTER INSERT OR UPDATE ON claim_exchange_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_completion_graph();
CREATE CONSTRAINT TRIGGER exchange_terminal_order_graph AFTER INSERT ON order_exchange_completion DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_completion_graph();
CREATE CONSTRAINT TRIGGER exchange_terminal_receipt_graph AFTER INSERT ON claim_exchange_completion DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_completion_graph();
CREATE CONSTRAINT TRIGGER exchange_terminal_units_graph AFTER INSERT ON order_exchange_completion_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_completion_graph();
CREATE TRIGGER order_exchange_completion_immutable BEFORE UPDATE OR DELETE ON order_exchange_completion FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER order_exchange_completion_unit_immutable BEFORE UPDATE OR DELETE ON order_exchange_completion_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER claim_exchange_completion_immutable BEFORE UPDATE OR DELETE ON claim_exchange_completion FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER order_physical_confirmation_schedule_immutable BEFORE UPDATE OR DELETE ON order_physical_confirmation_schedule FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
