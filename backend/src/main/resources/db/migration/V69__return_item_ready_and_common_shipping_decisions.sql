-- ITEM readiness is not a physical-return completion receipt and never releases its hold.
CREATE TABLE claim_return_item_ready (
 return_id uuid PRIMARY KEY REFERENCES claim_return_request(id),order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 attempt_id uuid NOT NULL,result_version bigint NOT NULL,assessment_id uuid NOT NULL,workflow_revision bigint NOT NULL,
 occurred_at timestamptz NOT NULL,
 FOREIGN KEY(return_id,order_id,seller_order_id) REFERENCES claim_return_request(id,order_id,seller_order_id),
 FOREIGN KEY(return_id,attempt_id,result_version) REFERENCES claim_return_financial_result(return_id,attempt_id,result_version),
 UNIQUE(return_id,seller_order_id,order_id)
);
CREATE TABLE claim_return_ready_unit (
 return_id uuid NOT NULL REFERENCES claim_return_item_ready(return_id),item_id uuid NOT NULL,ordinal integer NOT NULL,
 inspection_id uuid NOT NULL,collection_id uuid NOT NULL,responsibility varchar(10) NOT NULL CHECK(responsibility IN ('BUYER','SELLER')),
 PRIMARY KEY(return_id,item_id,ordinal),UNIQUE(item_id,ordinal),
 FOREIGN KEY(return_id,item_id,ordinal) REFERENCES claim_return_unit(return_id,item_id,ordinal),
 FOREIGN KEY(inspection_id,item_id,ordinal) REFERENCES claim_return_inspection_unit(inspection_id,item_id,ordinal)
);
CREATE TRIGGER return_item_ready_immutable BEFORE UPDATE OR DELETE ON claim_return_item_ready FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE TRIGGER return_ready_unit_immutable BEFORE UPDATE OR DELETE ON claim_return_ready_unit FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION verify_return_item_ready(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE ready marketplace.claim_return_item_ready%ROWTYPE;
BEGIN
 SELECT * INTO ready FROM marketplace.claim_return_item_ready WHERE return_id=v_id;
 IF NOT FOUND THEN RETURN;END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_financial_result x
  JOIN marketplace.claim_fee_assessment a ON a.id=x.assessment_id
  JOIN marketplace.claim_fee_application app ON app.assessment_id=a.id AND app.attempt_id=x.attempt_id AND app.result_version=x.result_version
  JOIN marketplace.claim_fee_binding b ON b.assessment_id=a.id AND b.attempt_id=x.attempt_id
  JOIN marketplace.payment_refund_attempt p ON p.id=x.attempt_id
  WHERE x.return_id=v_id AND x.attempt_id=ready.attempt_id AND x.result_version=ready.result_version
   AND x.assessment_id=ready.assessment_id AND x.workflow_revision=ready.workflow_revision AND x.recorded_at=ready.occurred_at
   AND x.result_state='SUCCEEDED' AND p.state='SUCCEEDED' AND p.purpose='ITEM' AND p.claim_id=v_id
   AND a.state='APPLIED' AND a.additional_due=0 AND app.result_reference=p.result_reference
 ) OR NOT EXISTS(SELECT 1 FROM marketplace.claim_return_ready_unit WHERE return_id=v_id)
 OR EXISTS(SELECT item_id,ordinal FROM marketplace.claim_return_unit WHERE return_id=v_id
  EXCEPT SELECT item_id,ordinal FROM marketplace.claim_return_ready_unit WHERE return_id=v_id)
 OR EXISTS(SELECT 1 FROM marketplace.claim_return_ready_unit u
  JOIN marketplace.claim_return_inspection_unit i ON i.inspection_id=u.inspection_id AND i.return_id=u.return_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal
  JOIN marketplace.claim_return_collection c ON c.id=i.collection_id
  WHERE u.return_id=v_id AND (u.collection_id<>i.collection_id OR u.responsibility<>i.responsibility OR c.status<>'RECEIVED'))
 OR NOT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.type='SELLER_AFTERCARE_REEVALUATE.v1'
  AND j.cause_key=ready.seller_order_id::text AND j.payload='RETURN_READY|'||v_id::text
  AND j.dedupe_key='seller-aftercare:'||ready.seller_order_id::text||':return-ready:'||v_id::text)
 THEN RAISE EXCEPTION 'ACTUAL_RETURN_ITEM_READY_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION return_item_ready_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM marketplace.verify_return_item_ready(NEW.return_id);RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER return_item_ready_graph AFTER INSERT ON claim_return_item_ready DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_item_ready_guard();
CREATE CONSTRAINT TRIGGER return_ready_unit_graph AFTER INSERT ON claim_return_ready_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_item_ready_guard();

-- Preserve every existing C obligation and its original attempt, cause and amount.
ALTER TABLE claim_shipping_obligation DROP CONSTRAINT claim_shipping_obligation_claim_id_seller_order_id_order_i_fkey;
ALTER TABLE claim_shipping_obligation ADD CONSTRAINT shipping_common_financial_intent FOREIGN KEY(claim_id,seller_order_id,order_id) REFERENCES claim_financial_intent(id,seller_order_id,order_id);
CREATE TABLE claim_seller_shipping_decision (
 id uuid PRIMARY KEY,seller_order_id uuid NOT NULL,order_id uuid NOT NULL,revision bigint NOT NULL CHECK(revision>0),
 state varchar(20) NOT NULL CHECK(state IN ('PENDING','RETAINED','REFUND_REQUIRED')),
 reason varchar(40) NOT NULL,amount bigint NOT NULL CHECK(amount>=0),fingerprint varchar(64) NOT NULL,
 units jsonb NOT NULL CHECK(jsonb_typeof(units)='array' AND jsonb_array_length(units)>0),
 job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,occurred_at timestamptz NOT NULL,
 UNIQUE(seller_order_id,revision),UNIQUE(seller_order_id,fingerprint),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id)
);
CREATE TABLE claim_seller_shipping_current (
 seller_order_id uuid PRIMARY KEY REFERENCES seller_order(id),decision_id uuid NOT NULL UNIQUE REFERENCES claim_seller_shipping_decision(id)
);
CREATE TRIGGER seller_shipping_decision_immutable BEFORE UPDATE OR DELETE ON claim_seller_shipping_decision FOR EACH ROW EXECUTE FUNCTION return_application_immutable();

-- A separate typed SHIPPING receipt is mandatory; ITEM keeps its required fee assessment.
ALTER TABLE claim_financial_refund_binding DROP CONSTRAINT claim_financial_refund_binding_check;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_binding_kind_purpose CHECK(intent_kind IN ('CANCEL','RETURN'));
ALTER TABLE claim_financial_refund_binding DROP CONSTRAINT financial_return_fee_required;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_return_fee_required CHECK(
 (intent_kind='CANCEL' AND fee_assessment_id IS NULL)
 OR(intent_kind='RETURN' AND purpose='ITEM' AND fee_assessment_id IS NOT NULL)
 OR(intent_kind='RETURN' AND purpose='SHIPPING' AND fee_assessment_id IS NULL));
CREATE TABLE claim_return_shipping_binding (
 attempt_id uuid PRIMARY KEY REFERENCES claim_financial_refund_binding(attempt_id),intent_id uuid NOT NULL,
 seller_order_id uuid NOT NULL REFERENCES claim_shipping_obligation(seller_order_id),order_id uuid NOT NULL,
 payment_id uuid NOT NULL,purpose varchar(10) NOT NULL DEFAULT 'SHIPPING' CHECK(purpose='SHIPPING'),
 amount bigint NOT NULL CHECK(amount>=0),original_reference varchar(200) NOT NULL,
 FOREIGN KEY(intent_id,seller_order_id,order_id) REFERENCES claim_return_item_ready(return_id,seller_order_id,order_id),
 FOREIGN KEY(attempt_id,intent_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference)
  REFERENCES payment_refund_attempt(id,claim_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER return_shipping_binding_immutable BEFORE UPDATE OR DELETE ON claim_return_shipping_binding FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION return_shipping_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b marketplace.claim_financial_refund_binding%ROWTYPE;
BEGIN
 SELECT * INTO b FROM marketplace.claim_financial_refund_binding WHERE attempt_id=NEW.attempt_id;
 IF b.intent_kind='RETURN' AND b.purpose='SHIPPING' AND NOT EXISTS(
  SELECT 1 FROM marketplace.claim_return_shipping_binding s JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=s.seller_order_id
  JOIN marketplace.seller_order own ON own.id=s.seller_order_id AND own.order_id=s.order_id
  JOIN marketplace.claim_seller_shipping_current cur ON cur.seller_order_id=s.seller_order_id
  JOIN marketplace.claim_seller_shipping_decision d ON d.id=cur.decision_id
  WHERE s.attempt_id=b.attempt_id AND s.intent_id=b.intent_id AND s.order_id=b.order_id AND s.seller_order_id=b.seller_order_id
   AND s.payment_id=b.payment_id AND s.amount=b.amount AND s.original_reference=b.original_reference
   AND o.claim_id=s.intent_id AND o.order_id=s.order_id AND o.refund_attempt_id=s.attempt_id AND o.amount=s.amount
   AND own.shipping_base-own.shipping_discount=s.amount AND d.state='REFUND_REQUIRED' AND d.amount=s.amount
 ) THEN RAISE EXCEPTION 'ACTUAL_TYPED_RETURN_SHIPPING_BINDING_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_shipping_binding_graph AFTER INSERT ON claim_financial_refund_binding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_shipping_binding_guard();
CREATE CONSTRAINT TRIGGER return_typed_shipping_binding_graph AFTER INSERT ON claim_return_shipping_binding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_shipping_binding_guard();
