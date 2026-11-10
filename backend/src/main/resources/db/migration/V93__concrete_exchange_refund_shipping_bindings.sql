-- SHIPPING is independent from E ITEM/fee and its original EXCHANGE hold, including a terminal origin.
ALTER TABLE claim_financial_refund_binding DROP CONSTRAINT financial_return_fee_required;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_return_fee_required CHECK(
 (intent_kind='CANCEL' AND fee_assessment_id IS NULL)
 OR(intent_kind IN('RETURN','EXCHANGE_REFUND') AND purpose='ITEM' AND fee_assessment_id IS NOT NULL)
 OR(intent_kind IN('RETURN','EXCHANGE_REFUND') AND purpose='SHIPPING' AND fee_assessment_id IS NULL));
ALTER TABLE claim_exchange_refund_item_ready ADD UNIQUE(exchange_id,seller_order_id,order_id);
CREATE TABLE claim_exchange_refund_shipping_binding (
 attempt_id uuid PRIMARY KEY REFERENCES claim_financial_refund_binding(attempt_id),intent_id uuid NOT NULL,
 seller_order_id uuid NOT NULL REFERENCES claim_shipping_obligation(seller_order_id),order_id uuid NOT NULL,payment_id uuid NOT NULL,
 purpose varchar(10) NOT NULL DEFAULT 'SHIPPING' CHECK(purpose='SHIPPING'),amount bigint NOT NULL CHECK(amount>=0),original_reference varchar(200) NOT NULL,
 FOREIGN KEY(intent_id,seller_order_id,order_id) REFERENCES claim_exchange_refund_item_ready(exchange_id,seller_order_id,order_id),
 FOREIGN KEY(attempt_id,intent_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference) REFERENCES payment_refund_attempt(id,claim_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER exchange_shipping_binding_immutable BEFORE UPDATE OR DELETE ON claim_exchange_refund_shipping_binding FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
-- Concrete child keys remain compulsory in both directions, including existing RETURN receipts.
CREATE TABLE claim_typed_shipping_binding (
 attempt_id uuid PRIMARY KEY,kind varchar(32) NOT NULL CHECK(kind IN('RETURN','EXCHANGE_REFUND')),
 return_attempt_id uuid GENERATED ALWAYS AS(CASE WHEN kind='RETURN' THEN attempt_id END) STORED,
 exchange_attempt_id uuid GENERATED ALWAYS AS(CASE WHEN kind='EXCHANGE_REFUND' THEN attempt_id END) STORED,
 FOREIGN KEY(return_attempt_id) REFERENCES claim_return_shipping_binding(attempt_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_attempt_id) REFERENCES claim_exchange_refund_shipping_binding(attempt_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(attempt_id) REFERENCES claim_financial_refund_binding(attempt_id),UNIQUE(attempt_id,kind)
);
INSERT INTO claim_typed_shipping_binding(attempt_id,kind) SELECT attempt_id,'RETURN' FROM claim_return_shipping_binding;
ALTER TABLE claim_return_shipping_binding ADD FOREIGN KEY(attempt_id) REFERENCES claim_typed_shipping_binding(attempt_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_exchange_refund_shipping_binding ADD FOREIGN KEY(attempt_id) REFERENCES claim_typed_shipping_binding(attempt_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER typed_shipping_binding_immutable BEFORE UPDATE OR DELETE ON claim_typed_shipping_binding FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE FUNCTION register_typed_shipping_binding() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN INSERT INTO claim_typed_shipping_binding(attempt_id,kind) VALUES(NEW.attempt_id,CASE WHEN TG_TABLE_NAME='claim_return_shipping_binding' THEN 'RETURN' ELSE 'EXCHANGE_REFUND' END);RETURN NEW;END $$;
CREATE TRIGGER return_shipping_binding_registry AFTER INSERT ON claim_return_shipping_binding FOR EACH ROW EXECUTE FUNCTION register_typed_shipping_binding();
CREATE TRIGGER exchange_shipping_binding_registry AFTER INSERT ON claim_exchange_refund_shipping_binding FOR EACH ROW EXECUTE FUNCTION register_typed_shipping_binding();
ALTER TABLE claim_shipping_result DROP CONSTRAINT claim_shipping_result_attempt_id_fkey;
ALTER TABLE claim_shipping_result ADD FOREIGN KEY(attempt_id) REFERENCES claim_typed_shipping_binding(attempt_id);
CREATE VIEW claim_concrete_shipping_binding AS
 SELECT b.*, 'RETURN'::varchar(32) AS kind FROM claim_return_shipping_binding b
 UNION ALL SELECT b.*, 'EXCHANGE_REFUND'::varchar(32) AS kind FROM claim_exchange_refund_shipping_binding b;

CREATE OR REPLACE FUNCTION return_shipping_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b marketplace.claim_financial_refund_binding%ROWTYPE;
BEGIN
 SELECT * INTO b FROM marketplace.claim_financial_refund_binding WHERE attempt_id=NEW.attempt_id;
 IF b.intent_kind IN('RETURN','EXCHANGE_REFUND') AND b.purpose='SHIPPING' AND NOT EXISTS(
  SELECT 1 FROM marketplace.claim_concrete_shipping_binding s JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=s.seller_order_id
  JOIN marketplace.seller_order own ON own.id=s.seller_order_id AND own.order_id=s.order_id
  JOIN marketplace.claim_seller_shipping_current cur ON cur.seller_order_id=s.seller_order_id
  JOIN marketplace.claim_seller_shipping_decision d ON d.id=cur.decision_id
  WHERE s.kind=b.intent_kind AND s.attempt_id=b.attempt_id AND s.intent_id=b.intent_id AND s.order_id=b.order_id AND s.seller_order_id=b.seller_order_id
   AND s.payment_id=b.payment_id AND s.amount=b.amount AND s.original_reference=b.original_reference
   AND o.claim_id=s.intent_id AND o.order_id=s.order_id AND o.refund_attempt_id=s.attempt_id AND o.amount=s.amount
   AND own.shipping_base-own.shipping_discount=s.amount AND d.state='REFUND_REQUIRED' AND d.amount=s.amount
 ) THEN RAISE EXCEPTION 'ACTUAL_TYPED_RETURN_SHIPPING_BINDING_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_typed_shipping_binding_graph AFTER INSERT ON claim_exchange_refund_shipping_binding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_shipping_binding_guard();

CREATE OR REPLACE FUNCTION shipping_result_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE x marketplace.claim_shipping_result%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='claim_shipping_result' THEN SELECT * INTO STRICT x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.attempt_id AND result_version=NEW.result_version;
 ELSE SELECT * INTO x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.refund_attempt_id AND result_state='SUCCEEDED' ORDER BY result_version DESC LIMIT 1;IF NOT FOUND THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_REQUIRED' USING ERRCODE='23514';END IF;END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_concrete_shipping_binding b
  JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=b.seller_order_id
  JOIN marketplace.payment_refund_attempt p ON p.id=b.attempt_id
  JOIN marketplace.job j ON j.id=x.job_id
  WHERE b.attempt_id=x.attempt_id AND b.intent_id=x.intent_id AND b.seller_order_id=x.seller_order_id
   AND o.claim_id=b.intent_id AND o.refund_attempt_id=p.id AND o.amount=p.amount AND p.purpose='SHIPPING' AND x.workflow_revision IS NOT NULL AND o.version=x.workflow_revision AND o.state=x.result_state
   AND p.version=x.result_version AND p.state=x.result_state AND p.result_reference IS NOT DISTINCT FROM x.reference AND p.result_at IS NOT DISTINCT FROM x.result_at
   AND j.type='REFUND_RESULT_RECORDED.v1' AND j.cause_key=p.id::text AND j.status='RUNNING' AND j.owner_token=x.owner_token
   AND j.payload=p.id::text||'|'||p.version::text AND (
    j.dedupe_key='refund-result:'||p.id::text||':'||p.version::text
    OR(b.kind='RETURN' AND marketplace.exact_return_recovery_delivery(j.id,'SHIPPING_RESULT') AND EXISTS(
     SELECT 1 FROM marketplace.claim_return_recovery_delivery d WHERE d.job_id=j.id AND d.stage='SHIPPING_RESULT'
      AND d.return_id=b.intent_id AND d.order_id=b.order_id AND d.seller_order_id=b.seller_order_id AND d.attempt_id=p.id AND d.result_version=p.version
      AND d.payment_id=p.payment_id AND d.amount=p.amount AND d.original_reference=p.original_reference AND d.result_state=p.state
      AND d.result_reference IS NOT DISTINCT FROM p.result_reference AND d.result_at IS NOT DISTINCT FROM p.result_at)))
   AND (x.result_state<>'SUCCEEDED' OR(o.state='SUCCEEDED' AND EXISTS(SELECT 1 FROM marketplace.order_aftercare_shipping s WHERE s.seller_order_id=o.seller_order_id AND s.order_id=o.order_id AND s.refund_attempt_id=p.id AND s.payment_id=p.payment_id AND s.cause_id=o.claim_id AND s.amount=p.amount AND s.reference=p.result_reference)))
 ) THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION return_seller_delivery_identity(v_job uuid,v_seller uuid,v_order uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=v_job AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=v_seller::text AND (
  (marketplace.exact_return_recovery_delivery(j.id,'SELLER_REEVALUATE') AND EXISTS(
   SELECT 1 FROM marketplace.claim_return_recovery_delivery d JOIN marketplace.claim_return_item_ready r ON r.return_id=d.return_id
   WHERE d.job_id=j.id AND d.stage='SELLER_REEVALUATE' AND d.order_id=v_order AND d.seller_order_id=v_seller
    AND r.order_id=v_order AND r.seller_order_id=v_seller AND j.payload='RETURN_READY|'||r.return_id::text))
  OR EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_item_ready r WHERE r.seller_order_id=v_seller AND r.order_id=v_order AND j.payload='EXCHANGE_REFUND_READY|'||r.exchange_id::text AND j.dedupe_key='seller-aftercare:'||v_seller::text||':exchange-refund-ready:'||r.exchange_id::text)
  OR EXISTS(SELECT 1 FROM marketplace.claim_return_item_ready r WHERE r.seller_order_id=v_seller AND r.order_id=v_order
   AND j.payload='RETURN_READY|'||r.return_id::text AND j.dedupe_key='seller-aftercare:'||v_seller::text||':return-ready:'||r.return_id::text)
  OR EXISTS(SELECT 1 FROM marketplace.claim_shipping_result x JOIN marketplace.claim_financial_intent i ON i.id=x.intent_id
   WHERE x.seller_order_id=v_seller AND i.order_id=v_order AND j.payload='SHIPPING_RESULT|'||x.attempt_id::text||'|'||x.result_version::text
    AND j.dedupe_key='seller-aftercare:'||v_seller::text||':shipping-result:'||x.attempt_id::text||':'||x.result_version::text)
  OR EXISTS(SELECT 1 FROM marketplace.claim c JOIN marketplace.claim_refund_effect x ON x.claim_id=c.id AND x.purpose='ITEM'
   WHERE c.seller_order_id=v_seller AND c.order_id=v_order AND j.payload='CANCEL_ITEM|'||c.id::text
    AND j.dedupe_key='seller-aftercare:'||v_seller::text||':cancel-item:'||c.id::text)
  OR EXISTS(SELECT 1 FROM marketplace.order_confirmation c JOIN marketplace.order_confirmation_unit u ON u.confirmation_id=c.id AND u.order_id=c.order_id
   JOIN marketplace.order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal AND l.order_id=c.order_id
   WHERE c.order_id=v_order AND l.seller_order_id=v_seller AND l.confirmation_id=c.id AND l.state='CONFIRMED'
    AND j.payload='CONFIRMATION|'||c.id::text AND j.dedupe_key='seller-aftercare:'||v_seller::text||':confirmation:'||c.id::text)
 ))
$$;
