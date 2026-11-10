-- Legacy original-delivery JSON, FKs and restitution evidence retain their original meaning.
ALTER TABLE order_return_application ADD COLUMN source_schema_version integer NOT NULL DEFAULT 1 CHECK(source_schema_version IN(1,2));
CREATE TABLE order_return_physical_unit (
 return_id uuid NOT NULL REFERENCES order_return_application(id),item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,source_snapshot jsonb NOT NULL,
 PRIMARY KEY(return_id,item_id,ordinal),UNIQUE(return_id,item_id,ordinal,physical_id),FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_source(id,item_id,ordinal)
);
CREATE TABLE claim_return_physical_source (
 return_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,source_snapshot jsonb NOT NULL,
 PRIMARY KEY(return_id,item_id,ordinal),UNIQUE(return_id,item_id,ordinal,physical_id),FOREIGN KEY(return_id,item_id,ordinal) REFERENCES claim_return_unit(return_id,item_id,ordinal),
 FOREIGN KEY(return_id,item_id,ordinal,physical_id) REFERENCES order_return_physical_unit(return_id,item_id,ordinal,physical_id)
);
ALTER TABLE claim_return_collection ADD COLUMN physical_id uuid;
ALTER TABLE claim_return_collection ADD CONSTRAINT return_received_physical_identity UNIQUE(id,return_id,item_id,ordinal,physical_id,status);
ALTER TABLE claim_return_collection ADD CONSTRAINT return_collection_exact_physical FOREIGN KEY(return_id,item_id,ordinal,physical_id) REFERENCES claim_return_physical_source(return_id,item_id,ordinal,physical_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_return_inspection_unit ADD COLUMN physical_id uuid;
ALTER TABLE claim_return_inspection_unit ADD CONSTRAINT return_inspection_exact_physical FOREIGN KEY(return_id,item_id,ordinal,physical_id) REFERENCES claim_return_physical_source(return_id,item_id,ordinal,physical_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_return_inspection_unit ADD CONSTRAINT return_inspection_received_physical FOREIGN KEY(collection_id,return_id,item_id,ordinal,physical_id,collection_status) REFERENCES claim_return_collection(id,return_id,item_id,ordinal,physical_id,status) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER order_return_physical_immutable BEFORE UPDATE OR DELETE ON order_return_physical_unit FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE TRIGGER claim_return_physical_immutable BEFORE UPDATE OR DELETE ON claim_return_physical_source FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION return_current_physical_at_acceptance() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM order_return_application a JOIN order_physical_current c ON c.item_id=NEW.item_id AND c.ordinal=NEW.ordinal WHERE a.id=NEW.return_id AND a.source_schema_version=2 AND c.physical_id=NEW.physical_id) THEN RAISE EXCEPTION 'RETURN_CURRENT_PHYSICAL_AT_ACCEPTANCE_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER return_current_physical_before_acceptance BEFORE INSERT ON order_return_physical_unit FOR EACH ROW EXECUTE FUNCTION return_current_physical_at_acceptance();
CREATE FUNCTION verify_return_physical_source(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_app order_return_application;v_count integer;v_unit record;v_physical order_physical_source;v_stock inventory_committed_unit;v_delivery order_physical_delivery;v_json jsonb;v_reservation uuid;v_reservation_unit integer;
BEGIN
 SELECT * INTO v_app FROM order_return_application WHERE id=p_id;IF v_app.id IS NULL THEN RAISE EXCEPTION 'ACTUAL_RETURN_APPLICATION_REQUIRED' USING ERRCODE='23514';END IF;
 IF v_app.source_schema_version=1 THEN
  IF EXISTS(SELECT 1 FROM order_return_physical_unit WHERE return_id=p_id) OR EXISTS(SELECT 1 FROM claim_return_physical_source WHERE return_id=p_id) OR EXISTS(SELECT 1 FROM claim_return_collection WHERE return_id=p_id AND physical_id IS NOT NULL) OR EXISTS(SELECT 1 FROM claim_return_inspection_unit WHERE return_id=p_id AND physical_id IS NOT NULL)
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_app.source_snapshot->'units') x WHERE COALESCE(x->'physical','null'::jsonb)<>'null') THEN RAISE EXCEPTION 'LEGACY_RETURN_SOURCE_MEANING_IMMUTABLE' USING ERRCODE='23514';END IF;RETURN;
 END IF;
 SELECT count(*) INTO v_count FROM claim_return_unit WHERE return_id=p_id;
 IF v_count=0 OR v_count<>(SELECT count(*) FROM order_return_physical_unit WHERE return_id=p_id) OR v_count<>(SELECT count(*) FROM claim_return_physical_source WHERE return_id=p_id) OR v_count<>jsonb_array_length(v_app.source_snapshot->'units') THEN RAISE EXCEPTION 'EXACT_RETURN_FIXED_PHYSICAL_SET_REQUIRED' USING ERRCODE='23514';END IF;
 FOR v_unit IN SELECT * FROM order_return_physical_unit WHERE return_id=p_id LOOP
  SELECT * INTO v_physical FROM order_physical_source WHERE id=v_unit.physical_id;PERFORM verify_order_physical_source(v_unit.physical_id);SELECT * INTO v_stock FROM inventory_committed_unit WHERE id=v_physical.committed_source_id;SELECT * INTO v_delivery FROM order_physical_delivery WHERE physical_id=v_physical.id;v_json=v_unit.source_snapshot;
  IF v_physical.id IS NULL OR v_stock.id IS NULL OR v_delivery.physical_id IS NULL OR (v_physical.order_id,v_physical.seller_order_id,v_physical.item_id,v_physical.ordinal) IS DISTINCT FROM (v_app.order_id,v_app.seller_order_id,v_unit.item_id,v_unit.ordinal)
  OR v_app.requested_at<v_delivery.delivered_at OR v_app.requested_at>=v_delivery.delivered_at+make_interval(secs=>v_app.window_seconds::double precision)
  OR v_json->>'id' IS DISTINCT FROM v_physical.id::text OR v_json->>'orderId' IS DISTINCT FROM v_physical.order_id::text OR v_json->>'sellerOrderId' IS DISTINCT FROM v_physical.seller_order_id::text OR v_json->>'itemId' IS DISTINCT FROM v_physical.item_id::text OR (v_json->>'ordinal')::integer IS DISTINCT FROM v_physical.ordinal OR (v_json->>'round')::integer IS DISTINCT FROM v_physical.round OR v_json->>'predecessorId' IS DISTINCT FROM v_physical.predecessor_id::text OR v_json->>'originalPhysicalId' IS DISTINCT FROM v_physical.original_physical_id::text
  OR v_json->'stock'->>'id' IS DISTINCT FROM v_stock.id::text OR v_json->'stock'->>'kind' IS DISTINCT FROM v_stock.kind OR v_json->'stock'->>'skuId' IS DISTINCT FROM v_stock.sku_id::text OR v_json->'stock'->>'checkoutId' IS DISTINCT FROM v_stock.checkout_id::text OR v_json->'stock'->>'itemId' IS DISTINCT FROM v_stock.item_id::text OR (v_json->'stock'->>'ordinal')::integer IS DISTINCT FROM v_stock.ordinal
  OR v_json->'delivery'->>'bucketId' IS DISTINCT FROM v_delivery.bucket_id::text OR v_json->'delivery'->>'shipmentId' IS DISTINCT FROM v_delivery.bucket_id::text OR v_json->'delivery'->>'kind' IS DISTINCT FROM (SELECT kind FROM order_delivery_bucket WHERE id=v_delivery.bucket_id) OR v_json->'delivery'->>'eventId' IS DISTINCT FROM v_delivery.event_id::text OR (v_json->'delivery'->>'deliveredAt')::timestamptz IS DISTINCT FROM v_delivery.delivered_at
  OR NOT EXISTS(SELECT 1 FROM claim_return_physical_source c WHERE c.return_id=p_id AND c.item_id=v_unit.item_id AND c.ordinal=v_unit.ordinal AND c.physical_id=v_unit.physical_id AND c.source_snapshot=v_json)
  OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(v_app.source_snapshot->'units') x WHERE (x->>'itemId')::uuid=v_unit.item_id AND (x->>'ordinal')::integer=v_unit.ordinal AND x->'physical'=v_json)
  THEN RAISE EXCEPTION 'EXACT_RETURN_FIXED_PHYSICAL_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
  IF v_stock.kind='PURCHASE' THEN SELECT reservation_id,reservation_unit INTO v_reservation,v_reservation_unit FROM inventory_purchase_source WHERE source_id=v_stock.id;ELSE SELECT reservation_id,reservation_unit INTO v_reservation,v_reservation_unit FROM inventory_exchange_source WHERE source_id=v_stock.id;END IF;
  IF v_json->'stock'->>'reservationId' IS DISTINCT FROM v_reservation::text OR (v_json->'stock'->>'reservationUnit')::integer IS DISTINCT FROM v_reservation_unit THEN RAISE EXCEPTION 'EXACT_RETURN_COMMITTED_RESERVATION_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM claim_return_collection WHERE return_id=p_id AND physical_id IS NULL) OR EXISTS(SELECT 1 FROM claim_return_inspection_unit WHERE return_id=p_id AND physical_id IS NULL) THEN RAISE EXCEPTION 'RETURN_COLLECTION_INSPECTION_PHYSICAL_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION return_physical_source_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;
BEGIN IF TG_TABLE_NAME IN('order_return_application','claim_return_request') THEN target=NEW.id;ELSE target=NEW.return_id;END IF;PERFORM verify_return_physical_source(target);RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER return_fixed_source_application_graph AFTER INSERT ON order_return_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_physical_source_graph_guard();
CREATE CONSTRAINT TRIGGER return_fixed_source_workflow_graph AFTER INSERT OR UPDATE ON claim_return_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_physical_source_graph_guard();
CREATE CONSTRAINT TRIGGER return_fixed_order_source_graph AFTER INSERT ON order_return_physical_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_physical_source_graph_guard();
CREATE CONSTRAINT TRIGGER return_fixed_claim_source_graph AFTER INSERT ON claim_return_physical_source DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_physical_source_graph_guard();
CREATE CONSTRAINT TRIGGER return_collection_physical_graph AFTER INSERT ON claim_return_collection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_physical_source_graph_guard();
CREATE CONSTRAINT TRIGGER return_inspection_physical_graph AFTER INSERT ON claim_return_inspection_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_physical_source_graph_guard();

ALTER TABLE inventory_physical_inbound ADD CONSTRAINT physical_inbound_concrete_identity UNIQUE(inspection_id,claim_id,checkout_id,kind);
CREATE TABLE inventory_return_physical_restitution (
 inspection_id uuid PRIMARY KEY,return_id uuid NOT NULL,checkout_id uuid NOT NULL,kind varchar(10) NOT NULL DEFAULT 'RETURN' CHECK(kind='RETURN'),
 FOREIGN KEY(inspection_id,return_id,checkout_id) REFERENCES claim_return_inspection(id,return_id,checkout_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(inspection_id,return_id,checkout_id,kind) REFERENCES inventory_physical_inbound(inspection_id,claim_id,checkout_id,kind) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER inventory_return_physical_immutable BEFORE UPDATE OR DELETE ON inventory_return_physical_restitution FOR EACH ROW EXECUTE FUNCTION inventory_return_receipt_immutable();
CREATE OR REPLACE FUNCTION return_application_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 v_id uuid;v_app marketplace.order_return_application%ROWTYPE;
 v_request marketplace.claim_return_request%ROWTYPE;v_hold marketplace.order_unit_hold%ROWTYPE;
 v_units integer;v_history_version bigint;v_snapshot jsonb;
BEGIN
 IF TG_TABLE_NAME IN ('order_return_application','claim_return_request') THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='order_unit_hold' THEN
  SELECT a.id INTO v_id FROM marketplace.order_return_application a WHERE a.hold_id=NEW.id;
 ELSIF TG_TABLE_NAME='payment_refund_attempt' THEN v_id:=NEW.claim_id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN v_id:=NEW.intent_id;
 ELSE v_id:=NEW.return_id;
 END IF;
 SELECT a.* INTO v_app FROM marketplace.order_return_application a WHERE a.id=v_id;
 IF NOT FOUND THEN
  IF TG_TABLE_NAME IN ('order_return_application','claim_return_request','claim_return_unit','claim_return_history') THEN
   RAISE EXCEPTION 'RETURN_APPLICATION_PROOF_MISSING' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
 END IF;
 SELECT r.* INTO v_request FROM marketplace.claim_return_request r WHERE r.id=v_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'RETURN_REQUEST_GRAPH_MISSING' USING ERRCODE='23514'; END IF;
 SELECT h.* INTO v_hold FROM marketplace.order_unit_hold h WHERE h.id=v_app.hold_id;
 IF NOT FOUND OR v_hold.kind<>'RETURN' OR v_hold.order_id<>v_app.order_id OR v_hold.cause_id<>v_app.cause_id
  OR (v_request.id,v_request.order_id,v_request.seller_order_id,v_request.hold_id,v_request.cause_id)
   IS DISTINCT FROM (v_app.id,v_app.order_id,v_app.seller_order_id,v_app.hold_id,v_app.cause_id)
  OR v_request.requested_at<>v_app.requested_at THEN
  RAISE EXCEPTION 'RETURN_APPLICATION_IDENTITY_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_financial_intent i
  WHERE i.id=v_id AND i.order_id=v_app.order_id AND i.seller_order_id=v_app.seller_order_id
   AND i.hold_id=v_app.hold_id AND i.cause_id=v_app.cause_id AND i.kind='RETURN') THEN
  RAISE EXCEPTION 'RETURN_FINANCIAL_IDENTITY_MISSING' USING ERRCODE='23514';
 END IF;
 IF v_request.state IN ('REJECTED','WITHDRAWN') THEN
  IF v_hold.state<>'RELEASED' OR v_hold.outcome<>v_request.state THEN
   RAISE EXCEPTION 'RETURN_RELEASE_RECEIPT_MISMATCH' USING ERRCODE='23514';
  END IF;
 ELSIF v_request.state='COMPLETED' THEN
  IF v_hold.state<>'REFUNDED' THEN RAISE EXCEPTION 'RETURN_TERMINAL_HOLD_MISSING' USING ERRCODE='23514'; END IF;
 ELSE
  IF v_hold.state<>'ACTIVE' THEN RAISE EXCEPTION 'RETURN_ACTIVE_HOLD_MISSING' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT count(*) INTO v_units FROM marketplace.claim_return_unit u WHERE u.return_id=v_id;
 v_snapshot:=v_app.source_snapshot;
 IF v_units=0 OR jsonb_typeof(v_snapshot->'units') IS DISTINCT FROM 'array'
  OR jsonb_array_length(v_snapshot->'units')<>v_units
  OR (SELECT count(*) FROM marketplace.order_hold_unit u WHERE u.hold_id=v_app.hold_id)<>v_units
  OR (SELECT count(*) FROM marketplace.claim_financial_intent_unit u WHERE u.intent_id=v_id)<>v_units
  OR (SELECT count(DISTINCT (j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_snapshot->'units') j(value))<>v_units THEN
  RAISE EXCEPTION 'RETURN_EXACT_UNIT_SET_REQUIRED' USING ERRCODE='23514';
 END IF;
 IF v_snapshot->>'orderId' IS DISTINCT FROM v_app.order_id::text
  OR v_snapshot->>'sellerOrderId' IS DISTINCT FROM v_app.seller_order_id::text
  OR NOT EXISTS(SELECT 1 FROM marketplace.purchase_order o WHERE o.id=v_app.order_id
    AND o.checkout_id::text=v_snapshot->>'checkoutId' AND o.mode=v_snapshot->>'mode')
  OR NOT EXISTS(SELECT 1 FROM marketplace.order_aftercare_policy p WHERE p.order_id=v_app.order_id
    AND p.status='ACTIVE' AND p.claim_window_seconds=v_app.window_seconds) THEN
  RAISE EXCEPTION 'RETURN_ORIGINAL_APPLICATION_SOURCE_REQUIRED' USING ERRCODE='23514';
 END IF;
 IF EXISTS(
  SELECT 1 FROM jsonb_array_elements(v_snapshot->'units') j(value)
  LEFT JOIN marketplace.claim_return_unit u ON u.return_id=v_id AND u.item_id::text=j.value->>'itemId' AND u.ordinal::text=j.value->>'ordinal'
  LEFT JOIN marketplace.order_item i ON i.id=u.item_id AND i.order_id=v_app.order_id AND i.seller_order_id=v_app.seller_order_id
  LEFT JOIN marketplace.order_unit n ON n.item_id=u.item_id AND n.ordinal=u.ordinal
  LEFT JOIN marketplace.order_initial_delivery_unit du ON du.order_id=v_app.order_id AND du.seller_order_id=v_app.seller_order_id AND du.item_id=u.item_id AND du.ordinal=u.ordinal
  LEFT JOIN marketplace.order_initial_delivery d ON d.event_id=du.event_id
  LEFT JOIN marketplace.order_item_return_policy p ON p.item_id=u.item_id
  WHERE u.item_id IS NULL OR i.id IS NULL OR n.item_id IS NULL OR d.event_id IS NULL OR p.item_id IS NULL
   OR (j.value->>'net')::bigint IS DISTINCT FROM n.net OR j.value->>'skuId' IS DISTINCT FROM i.sku_id::text
   OR j.value->>'originalShipmentId' IS DISTINCT FROM d.shipment_id::text
   OR j.value->>'deliveryEventId' IS DISTINCT FROM d.event_id::text
   OR (j.value->>'deliveredAt')::timestamptz IS DISTINCT FROM d.occurred_at
   OR j.value->>'policyStatus' IS DISTINCT FROM p.status
   OR COALESCE(j.value->'returnPolicy','null'::jsonb) IS DISTINCT FROM COALESCE(p.policy_snapshot,'null'::jsonb)
   OR v_app.source_schema_version=1 AND (v_app.requested_at<d.occurred_at OR v_app.requested_at>=d.occurred_at+v_app.window_seconds*interval '1 second')
 ) THEN RAISE EXCEPTION 'RETURN_APPLICATION_ELIGIBILITY_NOT_PROVEN' USING ERRCODE='23514'; END IF;
 IF v_hold.state='ACTIVE' AND EXISTS(SELECT 1 FROM marketplace.claim_return_unit u
  LEFT JOIN marketplace.order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal
  WHERE u.return_id=v_id AND (l.state IS DISTINCT FROM 'HELD' OR l.active_hold_id IS DISTINCT FROM v_app.hold_id)) THEN
  RAISE EXCEPTION 'RETURN_HELD_UNIT_GRAPH_REQUIRED' USING ERRCODE='23514';
 END IF;
 IF jsonb_typeof(v_request.receipt->'units') IS DISTINCT FROM 'array'
  OR jsonb_array_length(v_request.receipt->'units')<>v_units
  OR v_request.receipt->>'returnId' IS DISTINCT FROM v_id::text
  OR v_request.receipt->>'intentId' IS DISTINCT FROM v_id::text
  OR v_request.receipt->>'holdId' IS DISTINCT FROM v_app.hold_id::text
  OR v_request.receipt->>'state' IS DISTINCT FROM 'REQUESTED'
  OR v_request.receipt->>'version' IS DISTINCT FROM '0'
  OR v_request.receipt->>'reason' IS DISTINCT FROM v_request.reason
  OR v_request.receipt->>'orderRevision' IS DISTINCT FROM v_snapshot->>'revision'
  OR (v_request.receipt->>'requestedAt')::timestamptz IS DISTINCT FROM v_app.requested_at
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_request.receipt->'units') j(value)
    LEFT JOIN marketplace.claim_return_unit u ON u.return_id=v_id AND u.item_id::text=j.value->>'itemId' AND u.ordinal::text=j.value->>'ordinal'
    WHERE u.item_id IS NULL OR j.value->>'responsibility' IS DISTINCT FROM u.claimed_responsibility)
  OR (SELECT count(DISTINCT (j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_request.receipt->'units') j(value))<>v_units THEN
  RAISE EXCEPTION 'RETURN_IMMUTABLE_ACCEPTED_RECEIPT_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF (SELECT count(*) FROM marketplace.claim_return_history h WHERE h.return_id=v_id AND h.version=0
    AND h.action='REQUESTED' AND h.reason=v_request.reason AND h.occurred_at=v_app.requested_at)<>1 THEN
  RAISE EXCEPTION 'RETURN_APPLICATION_HISTORY_REQUIRED' USING ERRCODE='23514';
 END IF;
 SELECT max(h.version) INTO v_history_version FROM marketplace.claim_return_history h WHERE h.return_id=v_id;
 IF v_request.version IS DISTINCT FROM v_history_version OR (v_request.version=0 AND v_request.state<>'REQUESTED') THEN
  RAISE EXCEPTION 'RETURN_CURRENT_HISTORY_VERSION_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF v_request.state='REQUESTED' AND (
  EXISTS(SELECT 1 FROM marketplace.payment_refund_attempt a WHERE a.claim_id=v_id)
  OR EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.intent_id=v_id)
 ) THEN RAISE EXCEPTION 'RETURN_REQUEST_IS_NOT_FINANCIAL_CONSENT' USING ERRCODE='23514'; END IF;
 IF v_request.version=0 AND (SELECT count(*) FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id
  WHERE j.type='RETURN_RECORDED.v1' AND j.dedupe_key='return-requested:'||v_id::text
   AND j.cause_key=v_id::text AND j.payload=v_id::text)<>1 THEN
  RAISE EXCEPTION 'RETURN_APPLICATION_OUTBOX_REQUIRED' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
EXCEPTION WHEN invalid_text_representation OR invalid_datetime_format OR datetime_field_overflow OR numeric_value_out_of_range THEN
 RAISE EXCEPTION 'RETURN_APPLICATION_PROOF_FORMAT' USING ERRCODE='23514';
END $$;
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
  IF EXISTS(SELECT 1 FROM inventory_physical_inbound r WHERE r.inspection_id=NEW.inspection_id AND r.kind='RETURN') THEN RETURN NULL;END IF;
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
CREATE FUNCTION verify_physical_return_inspection_inbound(p_inspection uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_inspection marketplace.claim_return_inspection%ROWTYPE;v_saleable bigint;v_units bigint;
BEGIN
 SELECT * INTO v_inspection FROM marketplace.claim_return_inspection WHERE id=p_inspection;
 IF NOT FOUND THEN RAISE EXCEPTION 'ACTUAL_RETURN_INSPECTION_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM marketplace.verify_return_physical_source(v_inspection.return_id);
 IF NOT EXISTS(SELECT 1 FROM marketplace.order_return_application a JOIN marketplace.claim_return_request r ON r.id=a.id
  WHERE a.id=v_inspection.return_id AND a.order_id=v_inspection.order_id AND a.seller_order_id=v_inspection.seller_order_id
   AND a.source_snapshot->>'checkoutId'=v_inspection.checkout_id::text AND r.version>=v_inspection.version)
  OR NOT EXISTS(SELECT 1 FROM marketplace.claim_return_command c JOIN marketplace.claim_return_history h ON h.return_id=c.return_id AND h.version=c.version
   WHERE c.return_id=v_inspection.return_id AND c.version=v_inspection.version AND c.action IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT')
    AND h.action=c.action AND h.actor=v_inspection.actor AND h.reason=v_inspection.reason AND h.occurred_at=v_inspection.occurred_at) THEN
  RAISE EXCEPTION 'RETURN_INSPECTION_COMMAND_IDENTITY_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*),count(*) FILTER(WHERE condition='SALEABLE') INTO v_units,v_saleable FROM marketplace.claim_return_inspection_unit WHERE inspection_id=p_inspection;
 IF v_units=0 OR EXISTS(SELECT 1 FROM marketplace.claim_return_inspection_unit i
  LEFT JOIN marketplace.claim_return_collection c ON c.id=i.collection_id AND c.return_id=i.return_id AND c.item_id=i.item_id AND c.ordinal=i.ordinal
  WHERE i.inspection_id=p_inspection AND(i.return_id<>v_inspection.return_id OR c.status IS DISTINCT FROM 'RECEIVED' OR c.version>=v_inspection.version)) THEN
  RAISE EXCEPTION 'ACTUAL_RECEIVED_ORIGINAL_INSPECTION_UNITS_REQUIRED' USING ERRCODE='23514';END IF;
 IF v_saleable=0 THEN
  IF EXISTS(SELECT 1 FROM marketplace.inventory_return_restitution x WHERE x.inspection_id=p_inspection)
   OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution x WHERE x.cause_id=p_inspection) THEN
   RAISE EXCEPTION 'DEFECTIVE_INSPECTION_CANNOT_RESTOCK' USING ERRCODE='23514';END IF;
  RETURN;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.inventory_return_physical_restitution marker JOIN marketplace.inventory_physical_inbound receipt ON receipt.inspection_id=marker.inspection_id JOIN marketplace.inventory_restitution stock ON stock.cause_id=receipt.cause_id WHERE marker.inspection_id=p_inspection AND marker.return_id=v_inspection.return_id AND marker.checkout_id=v_inspection.checkout_id AND receipt.claim_id=v_inspection.return_id AND receipt.kind='RETURN' AND stock.kind='RETURN' AND stock.checkout_id=v_inspection.checkout_id)
 OR EXISTS(SELECT 1 FROM marketplace.inventory_return_restitution WHERE inspection_id=p_inspection)
 OR v_saleable<>(SELECT count(*) FROM marketplace.inventory_physical_inbound_unit WHERE inspection_id=p_inspection)
 OR EXISTS(SELECT 1 FROM marketplace.claim_return_inspection_unit inspected LEFT JOIN marketplace.order_return_physical_unit fixed ON fixed.return_id=inspected.return_id AND fixed.item_id=inspected.item_id AND fixed.ordinal=inspected.ordinal LEFT JOIN marketplace.order_physical_source physical ON physical.id=fixed.physical_id WHERE inspected.inspection_id=p_inspection AND inspected.condition='SALEABLE' AND(inspected.physical_id IS DISTINCT FROM physical.id OR NOT EXISTS(SELECT 1 FROM marketplace.inventory_physical_inbound_unit receipt JOIN marketplace.inventory_source_restitution restitution ON restitution.source_id=receipt.source_id AND restitution.cause_id=receipt.inspection_id WHERE receipt.inspection_id=p_inspection AND receipt.physical_id=physical.id AND receipt.source_id=physical.committed_source_id)))
 OR EXISTS(SELECT 1 FROM marketplace.inventory_physical_inbound_unit receipt LEFT JOIN marketplace.claim_return_inspection_unit inspected ON inspected.inspection_id=receipt.inspection_id AND inspected.physical_id=receipt.physical_id AND inspected.condition='SALEABLE' WHERE receipt.inspection_id=p_inspection AND inspected.inspection_id IS NULL)
 OR (SELECT count(*) FROM marketplace.inventory_restitution_movement WHERE cause_id=p_inspection)<>(SELECT count(DISTINCT source.sku_id) FROM marketplace.inventory_physical_inbound_unit receipt JOIN marketplace.inventory_committed_unit source ON source.id=receipt.source_id WHERE receipt.inspection_id=p_inspection)
 OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution_movement movement WHERE movement.cause_id=p_inspection AND movement.quantity<>(SELECT count(*) FROM marketplace.inventory_physical_inbound_unit receipt JOIN marketplace.inventory_committed_unit source ON source.id=receipt.source_id WHERE receipt.inspection_id=p_inspection AND source.sku_id=movement.sku_id))
 OR (SELECT count(*) FROM marketplace.inventory_restitution_unit WHERE cause_id=p_inspection)<>(SELECT count(*) FROM marketplace.inventory_physical_inbound_unit receipt JOIN marketplace.inventory_committed_unit source ON source.id=receipt.source_id WHERE receipt.inspection_id=p_inspection AND source.kind='PURCHASE')
 OR EXISTS(SELECT 1 FROM marketplace.inventory_restitution_unit legacy WHERE legacy.cause_id=p_inspection AND NOT EXISTS(SELECT 1 FROM marketplace.inventory_physical_inbound_unit receipt JOIN marketplace.inventory_purchase_source purchase ON purchase.source_id=receipt.source_id WHERE receipt.inspection_id=p_inspection AND purchase.reservation_id=legacy.reservation_id AND purchase.reservation_unit=legacy.ordinal AND purchase.item_id=legacy.item_id AND purchase.sku_id=legacy.sku_id))
 THEN RAISE EXCEPTION 'RETURN_EXACT_ACTUAL_PHYSICAL_INBOUND_REQUIRED' USING ERRCODE='23514';END IF;
END $$;

ALTER FUNCTION verify_return_inspection_inbound(uuid) RENAME TO verify_original_return_inspection_inbound_v63;
CREATE FUNCTION verify_return_inspection_inbound(p_inspection uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM claim_return_inspection inspection JOIN order_return_application application ON application.id=inspection.return_id WHERE inspection.id=p_inspection AND application.source_schema_version=2) THEN PERFORM verify_physical_return_inspection_inbound(p_inspection);ELSE PERFORM verify_original_return_inspection_inbound_v63(p_inspection);END IF;
END $$;
CREATE FUNCTION return_typed_inbound_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;
BEGIN
 IF TG_TABLE_NAME='inventory_source_restitution' THEN target=NEW.cause_id;ELSE target=NEW.inspection_id;END IF;
 IF EXISTS(SELECT 1 FROM inventory_physical_inbound h WHERE h.inspection_id=target AND h.kind='RETURN') THEN PERFORM verify_return_inspection_inbound(target);END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_typed_inbound_header_graph AFTER INSERT ON inventory_physical_inbound DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_typed_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_typed_inbound_unit_graph AFTER INSERT ON inventory_physical_inbound_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_typed_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_typed_inbound_concrete_graph AFTER INSERT ON inventory_return_physical_restitution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_typed_inbound_graph_guard();
CREATE CONSTRAINT TRIGGER return_typed_inbound_consumption_graph AFTER INSERT ON inventory_source_restitution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_typed_inbound_graph_guard();

DO $$ DECLARE target uuid;BEGIN FOR target IN SELECT id FROM order_return_application LOOP PERFORM verify_return_physical_source(target);END LOOP;FOR target IN SELECT id FROM claim_return_inspection LOOP PERFORM verify_return_inspection_inbound(target);END LOOP;END $$;
