-- Concrete actual-delivery identity. BASIC keeps its original row/FK/balance/receipts.
ALTER TABLE claim_fee_quote ADD COLUMN source_schema_version integer NOT NULL DEFAULT 1 CHECK(source_schema_version IN(1,2));
ALTER TABLE claim_fee_assessment ADD COLUMN source_schema_version integer NOT NULL DEFAULT 1 CHECK(source_schema_version IN(1,2));
CREATE TABLE claim_delivery_fee_bucket (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,delivery_bucket_id uuid NOT NULL,source_kind varchar(10) NOT NULL CHECK(source_kind IN('BASIC','EXCHANGE')),
 family varchar(100) NOT NULL CHECK(family='P09-RETURN-PRODUCT-MAX-1'),UNIQUE(order_id,seller_order_id,delivery_bucket_id,family),UNIQUE(id,delivery_bucket_id,source_kind),
 FOREIGN KEY(delivery_bucket_id,order_id,seller_order_id) REFERENCES order_delivery_bucket(id,order_id,seller_order_id),FOREIGN KEY(delivery_bucket_id,source_kind) REFERENCES order_delivery_bucket(id,kind)
);
CREATE TABLE claim_basic_fee_bucket (
 id uuid PRIMARY KEY REFERENCES claim_fee_bucket(id),delivery_bucket_id uuid NOT NULL REFERENCES order_basic_delivery_bucket(bucket_id),source_kind varchar(10) NOT NULL CHECK(source_kind='BASIC'),
 FOREIGN KEY(id,delivery_bucket_id,source_kind) REFERENCES claim_delivery_fee_bucket(id,delivery_bucket_id,source_kind)
);
CREATE TABLE claim_exchange_fee_bucket (
 id uuid PRIMARY KEY,delivery_bucket_id uuid NOT NULL REFERENCES order_exchange_delivery_bucket(bucket_id),source_kind varchar(10) NOT NULL CHECK(source_kind='EXCHANGE'),
 assessed_total bigint NOT NULL DEFAULT 0 CHECK(assessed_total>=0),reserved_increment bigint NOT NULL DEFAULT 0 CHECK(reserved_increment>=0),
 deducted_applied bigint NOT NULL DEFAULT 0 CHECK(deducted_applied>=0),additional_collected bigint NOT NULL DEFAULT 0 CHECK(additional_collected>=0),additional_outstanding bigint NOT NULL DEFAULT 0 CHECK(additional_outstanding>=0),active_assessment_id uuid,revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 CHECK(assessed_total<=9223372036854775807-reserved_increment),CHECK(deducted_applied::numeric+additional_collected+additional_outstanding=assessed_total),CHECK(active_assessment_id IS NOT NULL OR reserved_increment=0),
 FOREIGN KEY(id,delivery_bucket_id,source_kind) REFERENCES claim_delivery_fee_bucket(id,delivery_bucket_id,source_kind)
);
ALTER TABLE order_physical_delivery ADD CONSTRAINT physical_delivery_bucket_identity UNIQUE(physical_id,bucket_id);
CREATE TABLE claim_fee_quote_physical_unit (
 quote_id uuid NOT NULL REFERENCES claim_fee_quote(id),item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,delivery_bucket_id uuid NOT NULL,source_kind varchar(10) NOT NULL,source_snapshot jsonb NOT NULL,
 PRIMARY KEY(quote_id,item_id,ordinal),FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_source(id,item_id,ordinal),
 FOREIGN KEY(physical_id,delivery_bucket_id) REFERENCES order_physical_delivery(physical_id,bucket_id),FOREIGN KEY(delivery_bucket_id,source_kind) REFERENCES order_delivery_bucket(id,kind)
);
CREATE TABLE claim_exchange_fee_assessment_bucket (LIKE claim_fee_assessment_bucket INCLUDING ALL);
ALTER TABLE claim_exchange_fee_assessment_bucket ALTER COLUMN original_shipment_id DROP NOT NULL;
ALTER TABLE claim_exchange_fee_assessment_bucket ADD CHECK(original_shipment_id IS NULL);
ALTER TABLE claim_exchange_fee_assessment_bucket ADD FOREIGN KEY(assessment_id,order_id,seller_order_id) REFERENCES claim_fee_assessment(id,order_id,seller_order_id);
ALTER TABLE claim_exchange_fee_assessment_bucket ADD FOREIGN KEY(bucket_id) REFERENCES claim_exchange_fee_bucket(id);
CREATE TABLE claim_exchange_fee_assessment_unit (LIKE claim_fee_assessment_unit INCLUDING ALL);
ALTER TABLE claim_exchange_fee_assessment_unit ADD FOREIGN KEY(assessment_id,intent_id,order_id,seller_order_id) REFERENCES claim_fee_assessment(id,intent_id,order_id,seller_order_id);
ALTER TABLE claim_exchange_fee_assessment_unit ADD FOREIGN KEY(intent_id,item_id,ordinal) REFERENCES claim_financial_intent_unit(intent_id,item_id,ordinal);
ALTER TABLE claim_exchange_fee_assessment_unit ADD FOREIGN KEY(assessment_id,bucket_id) REFERENCES claim_exchange_fee_assessment_bucket(assessment_id,bucket_id);
ALTER TABLE claim_exchange_fee_bucket ADD FOREIGN KEY(active_assessment_id,id) REFERENCES claim_exchange_fee_assessment_bucket(assessment_id,bucket_id) DEFERRABLE INITIALLY DEFERRED;
CREATE VIEW claim_all_fee_assessment_bucket AS
 SELECT b.*,b.original_shipment_id AS delivery_bucket_id,'BASIC'::varchar AS source_kind FROM claim_fee_assessment_bucket b
 UNION ALL SELECT b.*,r.delivery_bucket_id,'EXCHANGE'::varchar AS source_kind FROM claim_exchange_fee_assessment_bucket b JOIN claim_delivery_fee_bucket r ON r.id=b.bucket_id;
CREATE VIEW claim_all_fee_assessment_unit AS SELECT * FROM claim_fee_assessment_unit UNION ALL SELECT * FROM claim_exchange_fee_assessment_unit;
CREATE TRIGGER actual_fee_root_immutable BEFORE UPDATE OR DELETE ON claim_delivery_fee_bucket FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER actual_fee_basic_immutable BEFORE UPDATE OR DELETE ON claim_basic_fee_bucket FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER actual_fee_quote_source_immutable BEFORE UPDATE OR DELETE ON claim_fee_quote_physical_unit FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER actual_fee_unit_immutable BEFORE UPDATE OR DELETE ON claim_exchange_fee_assessment_unit FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER actual_fee_bucket_content_immutable BEFORE UPDATE OR DELETE ON claim_exchange_fee_assessment_bucket FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER actual_fee_unit_insert_once BEFORE INSERT ON claim_exchange_fee_assessment_unit FOR EACH ROW EXECUTE FUNCTION fee_content_insert_once();
CREATE TRIGGER actual_fee_bucket_insert_once BEFORE INSERT ON claim_exchange_fee_assessment_bucket FOR EACH ROW EXECUTE FUNCTION fee_content_insert_once();
CREATE TRIGGER actual_fee_unit_quote_original BEFORE INSERT ON claim_exchange_fee_assessment_unit FOR EACH ROW EXECUTE FUNCTION fee_unit_original_quote_valid();
CREATE OR REPLACE FUNCTION check_fee_assessment(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE a claim_fee_assessment%ROWTYPE;b record;remaining bigint;u record;share bigint;
BEGIN
 SELECT * INTO a FROM claim_fee_assessment WHERE id=check_fee_assessment.target;IF NOT FOUND THEN RETURN;END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)
 OR NOT EXISTS(SELECT 1 FROM claim_fee_quote q WHERE q.id=a.quote_id AND q.body->>'net'=a.net::text AND q.body->>'increment'=a.increment::text AND q.body->>'refund'=a.refund::text AND q.body->>'additionalDue'=a.additional_due::text AND jsonb_array_length(q.body->'units')=(SELECT count(*) FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target))
 OR(SELECT COALESCE(sum(net),0) FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)<>a.net
 OR(SELECT COALESCE(sum(deducted),0) FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)<>a.net-a.refund
 OR(SELECT COALESCE(sum(increment),0) FROM claim_all_fee_assessment_bucket WHERE assessment_id=check_fee_assessment.target)<>a.increment
 OR(SELECT COALESCE(sum(additional_due),0) FROM claim_all_fee_assessment_bucket WHERE assessment_id=check_fee_assessment.target)<>a.additional_due
 OR EXISTS(SELECT item_id,ordinal FROM claim_financial_intent_unit WHERE intent_id=a.intent_id EXCEPT SELECT item_id,ordinal FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)
 OR NOT EXISTS(SELECT 1 FROM claim_fee_consent c JOIN claim_fee_quote q ON q.id=c.quote_id WHERE c.assessment_id=check_fee_assessment.target AND q.id=a.quote_id AND q.fingerprint=a.quote_fingerprint AND c.legacy_consent=q.legacy_consent_required AND c.policy_source=CASE WHEN c.legacy_consent THEN 'LEGACY_QUOTE_EXPLICIT_CONSENT' ELSE 'ORDER_ITEM_SNAPSHOT' END)
 THEN RAISE EXCEPTION 'FEE_ASSESSMENT_CONTENT_MISMATCH' USING ERRCODE='23514';END IF;
 FOR b IN SELECT * FROM claim_all_fee_assessment_bucket WHERE assessment_id=check_fee_assessment.target LOOP
  IF NOT EXISTS(SELECT 1 FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id)
  OR b.selected_maximum<>(SELECT COALESCE(max(policy_fee) FILTER(WHERE responsibility='BUYER'),0) FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id)
  OR b.deducted<>(SELECT COALESCE(sum(deducted),0) FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id) THEN RAISE EXCEPTION 'FEE_BUCKET_ALLOCATION_MISMATCH' USING ERRCODE='23514';END IF;
  remaining:=b.increment;
  FOR u IN SELECT * FROM claim_all_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id ORDER BY item_id::text,ordinal LOOP
   share:=CASE WHEN u.responsibility='BUYER' THEN LEAST(remaining,u.net) ELSE 0 END;
   IF u.deducted<>share THEN RAISE EXCEPTION 'FEE_CANONICAL_ALLOCATION_MISMATCH' USING ERRCODE='23514';END IF;remaining:=remaining-share;
  END LOOP;
  IF remaining<>b.additional_due THEN RAISE EXCEPTION 'FEE_ADDITIONAL_DUE_MISMATCH' USING ERRCODE='23514';END IF;
 END LOOP;
END $$;

CREATE OR REPLACE FUNCTION check_exchange_fee_bucket(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b claim_exchange_fee_bucket%ROWTYPE;
BEGIN
 SELECT * INTO b FROM claim_exchange_fee_bucket WHERE id=check_exchange_fee_bucket.target;IF NOT FOUND THEN RETURN;END IF;
 IF b.assessed_total<>(SELECT COALESCE(sum(x.increment),0) FROM claim_exchange_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_exchange_fee_bucket.target AND a.state='APPLIED')
 OR b.deducted_applied<>(SELECT COALESCE(sum(x.deducted),0) FROM claim_exchange_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_exchange_fee_bucket.target AND a.state='APPLIED')
 OR b.additional_collected<>0
 OR b.additional_outstanding<>(SELECT COALESCE(sum(x.additional_due),0) FROM claim_exchange_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_exchange_fee_bucket.target AND a.state='APPLIED')
 OR(b.active_assessment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM claim_exchange_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.assessment_id=b.active_assessment_id AND x.bucket_id=check_exchange_fee_bucket.target AND a.state IN ('RESERVED','BOUND') AND x.baseline=b.assessed_total AND x.increment=b.reserved_increment))
 OR EXISTS(SELECT 1 FROM claim_exchange_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_exchange_fee_bucket.target AND a.state IN ('RESERVED','BOUND') AND x.assessment_id IS DISTINCT FROM b.active_assessment_id)
 THEN RAISE EXCEPTION 'FEE_BUCKET_LEDGER_MISMATCH' USING ERRCODE='23514';END IF;
END $$;
CREATE OR REPLACE FUNCTION check_fee_terminal_state(assessment uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE a claim_fee_assessment%ROWTYPE;bucket uuid;
BEGIN
 SELECT * INTO a FROM claim_fee_assessment WHERE id=assessment;IF NOT FOUND THEN RETURN;END IF;
 IF (a.state='APPLIED') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_fee_application WHERE assessment_id=a.id)
 OR (a.state='WITHDRAWN') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_fee_withdrawal WHERE assessment_id=a.id)
 THEN RAISE EXCEPTION 'FEE_TERMINAL_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 IF a.state='APPLIED' AND NOT EXISTS(SELECT 1 FROM claim_fee_application x JOIN claim_fee_binding b ON b.assessment_id=x.assessment_id AND b.attempt_id=x.attempt_id JOIN payment_refund_attempt p ON p.id=x.attempt_id
 WHERE x.assessment_id=a.id AND x.amount=a.refund AND x.increment=a.increment AND x.deducted::numeric=a.net::numeric-a.refund AND x.additional_due=a.additional_due AND p.state='SUCCEEDED' AND p.version=x.result_version AND p.result_reference=x.result_reference AND p.amount=x.amount)
 THEN RAISE EXCEPTION 'FEE_APPLIED_RESULT_REQUIRED' USING ERRCODE='23514';END IF;
 IF a.state='WITHDRAWN' AND NOT EXISTS(SELECT 1 FROM claim_fee_withdrawal x JOIN claim_fee_binding b ON b.assessment_id=x.assessment_id AND b.attempt_id=x.attempt_id JOIN payment_refund_attempt p ON p.id=x.attempt_id JOIN claim_financial_intent i ON i.id=a.intent_id JOIN order_unit_hold h ON h.id=i.hold_id
 WHERE x.assessment_id=a.id AND ((x.original_state='RESERVED' AND p.state='WITHDRAWN') OR(x.original_state='FAILED' AND p.state='FAILED')) AND h.state='RELEASED' AND h.outcome='WITHDRAWN')
 THEN RAISE EXCEPTION 'FEE_WITHDRAWN_RESULT_REQUIRED' USING ERRCODE='23514';END IF;
 FOR bucket IN SELECT bucket_id FROM claim_all_fee_assessment_bucket WHERE assessment_id=a.id LOOP IF EXISTS(SELECT 1 FROM claim_exchange_fee_bucket WHERE id=bucket) THEN PERFORM check_exchange_fee_bucket(bucket);ELSE PERFORM check_fee_bucket(bucket);END IF;END LOOP;
END $$;

CREATE FUNCTION verify_actual_fee_quote(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE q claim_fee_quote;u record;p order_physical_source;d order_physical_delivery;stock inventory_committed_unit;j jsonb;v_count integer;
BEGIN
 SELECT * INTO q FROM claim_fee_quote WHERE id=p_id;IF q.id IS NULL THEN RAISE EXCEPTION 'ACTUAL_FEE_QUOTE_REQUIRED' USING ERRCODE='23514';END IF;
 IF q.source_schema_version=1 THEN IF EXISTS(SELECT 1 FROM claim_fee_quote_physical_unit WHERE quote_id=p_id) OR EXISTS(SELECT 1 FROM jsonb_array_elements(q.body->'units') x WHERE x ? 'physicalId') THEN RAISE EXCEPTION 'LEGACY_FEE_QUOTE_SOURCE_MEANING_IMMUTABLE' USING ERRCODE='23514';END IF;RETURN;END IF;
 SELECT count(*) INTO v_count FROM claim_fee_quote_physical_unit WHERE quote_id=p_id;
 IF v_count=0 OR v_count<>jsonb_array_length(q.body->'units') OR v_count<>(SELECT count(DISTINCT(x->>'itemId',x->>'ordinal')) FROM jsonb_array_elements(q.body->'units') x) THEN RAISE EXCEPTION 'EXACT_FEE_QUOTE_PHYSICAL_SET_REQUIRED' USING ERRCODE='23514';END IF;
 FOR u IN SELECT * FROM claim_fee_quote_physical_unit WHERE quote_id=p_id LOOP
  SELECT * INTO p FROM order_physical_source WHERE id=u.physical_id;SELECT * INTO d FROM order_physical_delivery WHERE physical_id=u.physical_id;SELECT * INTO stock FROM inventory_committed_unit WHERE id=p.committed_source_id;PERFORM verify_order_physical_source(p.id);
  SELECT x INTO j FROM jsonb_array_elements(q.body->'units') x WHERE (x->>'itemId')::uuid=u.item_id AND(x->>'ordinal')::integer=u.ordinal;
  IF p.id IS NULL OR d.physical_id IS NULL OR stock.id IS NULL OR (p.order_id,p.seller_order_id,p.item_id,p.ordinal) IS DISTINCT FROM(q.order_id,q.seller_order_id,u.item_id,u.ordinal)
   OR(u.delivery_bucket_id,u.source_kind) IS DISTINCT FROM(d.bucket_id,(SELECT kind FROM order_delivery_bucket WHERE id=d.bucket_id))
   OR j IS NULL OR j->>'physicalId' IS DISTINCT FROM p.id::text OR j->>'actualSkuId' IS DISTINCT FROM stock.sku_id::text OR j->>'deliveryBucketId' IS DISTINCT FROM d.bucket_id::text OR j->>'sourceKind' IS DISTINCT FROM u.source_kind
   OR NOT EXISTS(SELECT 1 FROM order_unit n JOIN order_item_return_policy policy ON policy.item_id=n.item_id JOIN order_initial_delivery_unit du ON du.item_id=n.item_id AND du.ordinal=n.ordinal JOIN order_initial_delivery initial ON initial.event_id=du.event_id WHERE n.item_id=u.item_id AND n.ordinal=u.ordinal AND initial.order_id=q.order_id AND initial.seller_order_id=q.seller_order_id AND j->>'originalShipmentId'=initial.shipment_id::text AND j->>'net'=n.net::text AND j->>'policyVersion'='P09-RETURN-PRODUCT-MAX-1' AND ((policy.status='ACTIVE' AND j->>'policyFee'=policy.policy_snapshot->>'amount' AND j->>'policySource'=policy.policy_snapshot->>'source' AND j->>'policyRevision'=policy.policy_snapshot->>'revision') OR(policy.status='UNRESOLVED' AND j->>'policyFee'='3000' AND j->>'policySource'='LEGACY_UNSPECIFIED' AND j->>'policyRevision'='0')))
   OR u.source_snapshot->>'id' IS DISTINCT FROM p.id::text OR u.source_snapshot->>'orderId' IS DISTINCT FROM p.order_id::text OR u.source_snapshot->>'sellerOrderId' IS DISTINCT FROM p.seller_order_id::text OR u.source_snapshot->>'itemId' IS DISTINCT FROM p.item_id::text OR(u.source_snapshot->>'ordinal')::integer IS DISTINCT FROM p.ordinal OR(u.source_snapshot->>'round')::integer IS DISTINCT FROM p.round OR u.source_snapshot->>'predecessorId' IS DISTINCT FROM p.predecessor_id::text OR u.source_snapshot->>'originalPhysicalId' IS DISTINCT FROM p.original_physical_id::text
   OR u.source_snapshot->'stock'->>'id' IS DISTINCT FROM stock.id::text OR u.source_snapshot->'stock'->>'kind' IS DISTINCT FROM stock.kind OR u.source_snapshot->'stock'->>'skuId' IS DISTINCT FROM stock.sku_id::text OR u.source_snapshot->'stock'->>'checkoutId' IS DISTINCT FROM stock.checkout_id::text OR u.source_snapshot->'stock'->>'itemId' IS DISTINCT FROM stock.item_id::text OR(u.source_snapshot->'stock'->>'ordinal')::integer IS DISTINCT FROM stock.ordinal
   OR NOT EXISTS(SELECT 1 FROM(SELECT source_id,reservation_id,reservation_unit FROM inventory_purchase_source UNION ALL SELECT source_id,reservation_id,reservation_unit FROM inventory_exchange_source) actual WHERE actual.source_id=stock.id AND u.source_snapshot->'stock'->>'reservationId'=actual.reservation_id::text AND(u.source_snapshot->'stock'->>'reservationUnit')::integer=actual.reservation_unit)
   OR u.source_snapshot->'delivery'->>'bucketId' IS DISTINCT FROM d.bucket_id::text OR u.source_snapshot->'delivery'->>'shipmentId' IS DISTINCT FROM d.bucket_id::text OR u.source_snapshot->'delivery'->>'kind' IS DISTINCT FROM u.source_kind OR u.source_snapshot->'delivery'->>'eventId' IS DISTINCT FROM d.event_id::text OR(u.source_snapshot->'delivery'->>'deliveredAt')::timestamptz IS DISTINCT FROM d.delivered_at
   OR NOT EXISTS(SELECT 1 FROM claim_delivery_fee_bucket r WHERE r.order_id=q.order_id AND r.seller_order_id=q.seller_order_id AND r.delivery_bucket_id=d.bucket_id AND r.source_kind=u.source_kind AND r.family='P09-RETURN-PRODUCT-MAX-1')
   OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(q.body->'buckets') b WHERE b->>'deliveryBucketId'=d.bucket_id::text AND b->>'sourceKind'=u.source_kind AND ((u.source_kind='BASIC' AND b->>'originalShipmentId'=d.bucket_id::text) OR(u.source_kind='EXCHANGE' AND b->>'originalShipmentId' IS NULL)))
   THEN RAISE EXCEPTION 'EXACT_ACTUAL_FEE_QUOTE_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
  IF q.purpose='INSPECTED_RETURN' AND NOT EXISTS(SELECT 1 FROM claim_return_fee_quote_context c JOIN claim_return_physical_source fixed ON fixed.return_id=c.return_id AND fixed.item_id=u.item_id AND fixed.ordinal=u.ordinal WHERE c.quote_id=q.id AND fixed.physical_id=u.physical_id AND fixed.source_snapshot=u.source_snapshot) THEN RAISE EXCEPTION 'INSPECTED_FEE_QUOTE_FIXED_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 END LOOP;
 IF jsonb_array_length(q.body->'buckets')<>(SELECT count(DISTINCT delivery_bucket_id) FROM claim_fee_quote_physical_unit WHERE quote_id=p_id) THEN RAISE EXCEPTION 'EXACT_ACTUAL_FEE_BUCKET_SET_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION actual_fee_quote_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF TG_TABLE_NAME='claim_fee_quote' THEN PERFORM verify_actual_fee_quote(NEW.id);ELSE PERFORM verify_actual_fee_quote(NEW.quote_id);END IF;RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER actual_fee_quote_graph AFTER INSERT ON claim_fee_quote DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_quote_guard();
CREATE CONSTRAINT TRIGGER actual_fee_quote_source_graph AFTER INSERT ON claim_fee_quote_physical_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_quote_guard();
CREATE FUNCTION verify_actual_fee_root(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE r claim_delivery_fee_bucket;
BEGIN
 SELECT * INTO r FROM claim_delivery_fee_bucket WHERE id=p_id;IF r.id IS NULL THEN RAISE EXCEPTION 'ACTUAL_FEE_ROOT_REQUIRED' USING ERRCODE='23514';END IF;
 IF(r.source_kind='BASIC') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_basic_fee_bucket WHERE id=r.id) OR(r.source_kind='EXCHANGE') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_exchange_fee_bucket WHERE id=r.id)
 OR(r.source_kind='BASIC' AND NOT EXISTS(SELECT 1 FROM claim_fee_bucket b WHERE b.id=r.id AND(b.order_id,b.seller_order_id,b.original_shipment_id,b.family)=(r.order_id,r.seller_order_id,r.delivery_bucket_id,r.family))) THEN RAISE EXCEPTION 'EXACT_CONCRETE_FEE_ROOT_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION actual_fee_root_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN PERFORM verify_actual_fee_root(NEW.id);IF TG_TABLE_NAME='claim_exchange_fee_bucket' THEN PERFORM check_exchange_fee_bucket(NEW.id);END IF;RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER actual_fee_root_graph AFTER INSERT ON claim_delivery_fee_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_root_guard();
CREATE CONSTRAINT TRIGGER actual_fee_basic_graph AFTER INSERT ON claim_basic_fee_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_root_guard();
CREATE CONSTRAINT TRIGGER actual_fee_exchange_graph AFTER INSERT OR UPDATE ON claim_exchange_fee_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_root_guard();
CREATE FUNCTION protect_actual_fee_ledger() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF TG_OP='DELETE' OR(NEW.id,NEW.delivery_bucket_id,NEW.source_kind) IS DISTINCT FROM(OLD.id,OLD.delivery_bucket_id,OLD.source_kind) OR NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'IMMUTABLE_ACTUAL_FEE_LEDGER_IDENTITY' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER actual_fee_ledger_identity BEFORE UPDATE OR DELETE ON claim_exchange_fee_bucket FOR EACH ROW EXECUTE FUNCTION protect_actual_fee_ledger();
CREATE FUNCTION actual_fee_assessment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE a claim_fee_assessment;q claim_fee_quote;v_id uuid;u record;
BEGIN
 IF TG_TABLE_NAME='claim_fee_assessment' THEN v_id=NEW.id;ELSE v_id=NEW.assessment_id;END IF;
 SELECT * INTO a FROM claim_fee_assessment WHERE id=v_id;SELECT * INTO q FROM claim_fee_quote WHERE id=a.quote_id;
 IF a.id IS NULL OR q.id IS NULL OR a.source_schema_version<>q.source_schema_version  THEN RAISE EXCEPTION 'EXACT_ACTUAL_FEE_ASSESSMENT_SCHEMA_REQUIRED' USING ERRCODE='23514';END IF;
 IF a.source_schema_version=1 THEN IF EXISTS(SELECT 1 FROM claim_exchange_fee_assessment_bucket WHERE assessment_id=v_id) OR EXISTS(SELECT 1 FROM claim_exchange_fee_assessment_unit WHERE assessment_id=v_id) THEN RAISE EXCEPTION 'LEGACY_FEE_ASSESSMENT_MEANING_IMMUTABLE' USING ERRCODE='23514';END IF;RETURN NULL;END IF;
 PERFORM verify_actual_fee_quote(q.id);PERFORM check_fee_assessment(a.id);
 FOR u IN SELECT units.*,bucket.source_kind,bucket.delivery_bucket_id FROM claim_all_fee_assessment_unit units JOIN claim_all_fee_assessment_bucket bucket ON bucket.assessment_id=units.assessment_id AND bucket.bucket_id=units.bucket_id WHERE units.assessment_id=v_id LOOP
  IF NOT EXISTS(SELECT 1 FROM claim_fee_quote_physical_unit source JOIN claim_delivery_fee_bucket root ON root.delivery_bucket_id=source.delivery_bucket_id AND root.source_kind=source.source_kind AND root.order_id=a.order_id AND root.seller_order_id=a.seller_order_id WHERE source.quote_id=q.id AND source.item_id=u.item_id AND source.ordinal=u.ordinal AND source.source_kind=u.source_kind AND source.delivery_bucket_id=u.delivery_bucket_id AND root.id=u.bucket_id) THEN RAISE EXCEPTION 'EXACT_ASSESSED_ACTUAL_DELIVERY_REQUIRED' USING ERRCODE='23514';END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM claim_all_fee_assessment_bucket b WHERE b.assessment_id=v_id AND(b.order_id,b.seller_order_id) IS DISTINCT FROM(a.order_id,a.seller_order_id)) THEN RAISE EXCEPTION 'EXACT_ACTUAL_FEE_ORDER_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE CONSTRAINT TRIGGER actual_fee_assessment_complete AFTER INSERT OR UPDATE ON claim_fee_assessment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_assessment_guard();
CREATE CONSTRAINT TRIGGER actual_fee_exchange_content_complete AFTER INSERT ON claim_exchange_fee_assessment_bucket DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_assessment_guard();
CREATE CONSTRAINT TRIGGER actual_fee_exchange_unit_complete AFTER INSERT ON claim_exchange_fee_assessment_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION actual_fee_assessment_guard();

CREATE FUNCTION protect_actual_fee_assessment_schema() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.source_schema_version<>OLD.source_schema_version THEN RAISE EXCEPTION 'IMMUTABLE_ACTUAL_FEE_ASSESSMENT_SCHEMA' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER actual_fee_assessment_schema_immutable BEFORE UPDATE ON claim_fee_assessment FOR EACH ROW EXECUTE FUNCTION protect_actual_fee_assessment_schema();
