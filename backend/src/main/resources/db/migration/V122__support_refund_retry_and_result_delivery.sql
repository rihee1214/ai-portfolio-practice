SET search_path TO marketplace;

CREATE TABLE claim_support_refund_retry (
 id uuid PRIMARY KEY,approval_id uuid NOT NULL REFERENCES claim_support_refund_approval(id),
 prior_attempt uuid NOT NULL UNIQUE, prior_version bigint NOT NULL,
 attempt_id uuid NOT NULL UNIQUE REFERENCES claim_financial_refund_binding(attempt_id) DEFERRABLE INITIALLY DEFERRED,
 purpose text NOT NULL CHECK(purpose IN('ITEM','SHIPPING')),sequence integer NOT NULL CHECK(sequence>1),
 reason varchar(500) NOT NULL CHECK(btrim(reason)<>''),actor_id uuid NOT NULL REFERENCES identity_user(id),
 UNIQUE(approval_id,purpose,sequence),FOREIGN KEY(prior_attempt,prior_version) REFERENCES claim_support_refund_result(attempt_id,result_version)
);
CREATE TRIGGER support_retry_preserved BEFORE UPDATE OR DELETE ON claim_support_refund_retry FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE FUNCTION check_support_retry() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim_support_refund_result r
 JOIN payment_refund_attempt old ON old.id=r.attempt_id AND old.version=r.result_version AND old.state='FAILED'
 JOIN payment_refund_attempt p ON p.id=NEW.attempt_id AND p.claim_id=r.approval_id AND p.sequence=old.sequence+1
 JOIN claim_financial_refund_binding b ON b.attempt_id=p.id AND b.intent_id=r.approval_id AND b.intent_kind='SUPPORT'
 WHERE r.state='FAILED' AND r.attempt_id=NEW.prior_attempt AND r.result_version=NEW.prior_version AND r.approval_id=NEW.approval_id
 AND old.purpose=r.purpose AND r.purpose=NEW.purpose AND p.purpose=r.purpose AND p.payment_id=old.payment_id
 AND p.amount=old.amount AND p.original_reference=old.original_reference AND p.cause_id=NEW.id AND p.sequence=NEW.sequence
 AND (NEW.purpose='ITEM' OR EXISTS(SELECT 1 FROM claim_shipping_obligation s WHERE s.claim_id=r.approval_id
 AND s.refund_attempt_id=p.id AND s.amount=p.amount AND s.seller_order_id=p.seller_order_id))) THEN
 RAISE EXCEPTION 'SUPPORT_RETRY_EXACT_FAILED_PREDECESSOR_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER support_retry_graph AFTER INSERT ON claim_support_refund_retry DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION check_support_retry();
CREATE OR REPLACE FUNCTION support_finance_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_TABLE_NAME='claim_support_refund_execution' THEN PERFORM verify_support_finance(NEW.approval_id);
 ELSIF TG_TABLE_NAME='claim_financial_intent' THEN IF NEW.kind='SUPPORT' THEN PERFORM verify_support_finance(NEW.id);END IF;
 ELSIF TG_TABLE_NAME='claim_financial_intent_unit' THEN IF NEW.hold_id IS NULL THEN PERFORM verify_support_finance(NEW.intent_id);END IF;
 ELSIF NEW.intent_kind='SUPPORT' THEN
  PERFORM verify_support_finance(NEW.intent_id);
  IF NOT EXISTS(SELECT 1 FROM claim_support_refund_retry r WHERE r.approval_id=NEW.intent_id AND r.attempt_id=NEW.attempt_id AND r.purpose=NEW.purpose)
  AND NOT (NEW.purpose='ITEM' AND EXISTS(SELECT 1 FROM claim_support_refund_execution x JOIN payment_refund_attempt p ON p.id=x.attempt_id
    WHERE x.approval_id=NEW.intent_id AND x.attempt_id=NEW.attempt_id AND p.sequence=1))
  AND NOT (NEW.purpose='SHIPPING' AND EXISTS(SELECT 1 FROM claim_shipping_obligation s JOIN payment_refund_attempt p ON p.id=s.refund_attempt_id
    WHERE s.claim_id=NEW.intent_id AND s.seller_order_id=NEW.seller_order_id AND s.amount=NEW.amount AND p.id=NEW.attempt_id AND p.sequence=1)) THEN
   RAISE EXCEPTION 'SUPPORT_EXACT_BINDING_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;
ALTER FUNCTION return_seller_delivery_identity(uuid,uuid,uuid) RENAME TO return_seller_delivery_before_support;
CREATE FUNCTION return_seller_delivery_identity(v_job uuid,v_seller uuid,v_order uuid) RETURNS boolean LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT return_seller_delivery_before_support(v_job,v_seller,v_order) OR EXISTS(
 SELECT 1 FROM job j JOIN claim_support_refund_result r ON j.payload='SUPPORT_RESULT|'||r.attempt_id::text||'|'||r.result_version::text
 JOIN claim_support_refund_approval a ON a.id=r.approval_id WHERE a.seller_order_id=v_seller AND a.order_id=v_order
 AND r.state='SUCCEEDED' AND j.id=v_job AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=v_seller::text
 AND j.dedupe_key='seller-aftercare:'||v_seller::text||':support-result:'||r.attempt_id::text||':'||r.result_version::text)
$$;
