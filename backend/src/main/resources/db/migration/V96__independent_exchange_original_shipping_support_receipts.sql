CREATE TABLE claim_exchange_shipping_support_command (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL,seller_order_id uuid NOT NULL,order_id uuid NOT NULL,
 previous_attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),
 action varchar(20) NOT NULL CHECK(action IN ('RETRY_FAILED','QUERY_UNKNOWN')),
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,actor_id uuid NOT NULL REFERENCES identity_user(id),actor_version bigint NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),
 original_result_version bigint NOT NULL CHECK(original_result_version>0),query_cause_id uuid,
 receipt jsonb NOT NULL,accepted_at timestamptz NOT NULL,
 UNIQUE(seller_order_id,request_key),UNIQUE(seller_order_id,revision),
 FOREIGN KEY(exchange_id,seller_order_id,order_id) REFERENCES claim_exchange_refund_item_ready(exchange_id,seller_order_id,order_id),
 FOREIGN KEY(query_cause_id,attempt_id) REFERENCES payment_refund_query_request(cause_id,attempt_id),
 CHECK((action='RETRY_FAILED' AND previous_attempt_id<>attempt_id AND query_cause_id IS NULL)
  OR(action='QUERY_UNKNOWN' AND previous_attempt_id=attempt_id AND query_cause_id=id AND query_cause_id IS NOT NULL))
);
CREATE TRIGGER exchange_shipping_support_command_immutable BEFORE UPDATE OR DELETE ON claim_exchange_shipping_support_command FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE FUNCTION exchange_shipping_support_effect_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_shipping_obligation o
  JOIN marketplace.claim_exchange_request r ON r.id=o.claim_id
  JOIN marketplace.claim_exchange_refund_item_ready ready ON ready.exchange_id=r.id
  JOIN marketplace.payment_refund_attempt old_attempt ON old_attempt.id=NEW.previous_attempt_id
  JOIN marketplace.payment_refund_attempt next_attempt ON next_attempt.id=NEW.attempt_id
  JOIN marketplace.claim_exchange_refund_shipping_binding b ON b.attempt_id=next_attempt.id AND b.intent_id=r.id
  WHERE o.seller_order_id=NEW.seller_order_id AND o.order_id=NEW.order_id AND o.claim_id=NEW.exchange_id
   AND r.seller_order_id=o.seller_order_id AND r.order_id=o.order_id AND r.state IN ('REFUND_PENDING','REFUNDED')
   AND o.refund_attempt_id=next_attempt.id AND o.version=NEW.revision
   AND old_attempt.claim_id=r.id AND old_attempt.order_id=r.order_id AND old_attempt.seller_order_id=r.seller_order_id AND old_attempt.purpose='SHIPPING'
   AND(old_attempt.state='PROCESSING' OR EXISTS(SELECT 1 FROM marketplace.claim_shipping_result prior WHERE prior.intent_id=r.id AND prior.seller_order_id=r.seller_order_id AND prior.attempt_id=old_attempt.id AND prior.result_version=NEW.original_result_version AND prior.result_state=old_attempt.state AND prior.workflow_revision=NEW.original_revision))
   AND old_attempt.version=NEW.original_result_version AND next_attempt.claim_id=old_attempt.claim_id AND next_attempt.order_id=old_attempt.order_id
   AND next_attempt.seller_order_id=old_attempt.seller_order_id AND next_attempt.purpose='SHIPPING'
   AND next_attempt.payment_id=old_attempt.payment_id AND next_attempt.original_reference=old_attempt.original_reference AND next_attempt.amount=old_attempt.amount AND next_attempt.amount=o.amount
   AND NEW.receipt->>'commandId'=NEW.id::text AND NEW.receipt->>'exchangeId'=r.id::text AND NEW.receipt->>'refundAttemptId'=next_attempt.id::text
   AND NEW.receipt->>'purpose'='SHIPPING' AND NEW.receipt->>'action'=NEW.action AND (NEW.receipt->>'revision')::bigint=NEW.revision
   AND NEW.receipt->'progress'->>'state'=r.state AND (NEW.receipt->'progress'->>'version')::bigint=r.version
   AND ((NEW.action='RETRY_FAILED' AND o.state='RESERVED' AND old_attempt.state='FAILED' AND next_attempt.state='RESERVED'
     AND next_attempt.sequence=old_attempt.sequence+1 AND next_attempt.cause_id=NEW.id)
    OR(NEW.action='QUERY_UNKNOWN' AND o.state='UNKNOWN' AND old_attempt.state IN ('UNKNOWN','PROCESSING')
     AND EXISTS(SELECT 1 FROM marketplace.payment_refund_query_request q WHERE q.cause_id=NEW.query_cause_id AND q.attempt_id=old_attempt.id AND q.original_version=old_attempt.version AND q.original_state=old_attempt.state AND q.reason=NEW.reason)))
 ) OR NOT EXISTS(SELECT 1 FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id
  WHERE j.type='EXCHANGE_RECORDED.v1' AND j.cause_key=NEW.exchange_id::text AND j.payload=NEW.exchange_id::text AND j.dedupe_key='exchange-refund-shipping-support:'||NEW.id::text)
 THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_SUPPORT_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_shipping_support_effect_receipt AFTER INSERT ON claim_exchange_shipping_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_support_effect_guard();
CREATE FUNCTION exchange_shipping_support_primitive_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE p marketplace.payment_refund_attempt%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='payment_refund_attempt' THEN p:=NEW;
 ELSE SELECT * INTO STRICT p FROM marketplace.payment_refund_attempt WHERE id=NEW.attempt_id;END IF;
 IF p.purpose<>'SHIPPING' OR NOT EXISTS(SELECT 1 FROM marketplace.claim_financial_intent WHERE id=p.claim_id AND kind='EXCHANGE_REFUND') THEN RETURN NULL;END IF;
 IF TG_TABLE_NAME='payment_refund_attempt' THEN
  IF p.sequence>1 AND NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_shipping_support_command c WHERE c.id=p.cause_id AND c.exchange_id=p.claim_id AND c.attempt_id=p.id AND c.action='RETRY_FAILED') THEN RAISE EXCEPTION 'SHIPPING_RETRY_SUPPORT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_shipping_support_command c WHERE c.query_cause_id=NEW.cause_id AND c.exchange_id=p.claim_id AND c.attempt_id=p.id AND c.action='QUERY_UNKNOWN') THEN RAISE EXCEPTION 'SHIPPING_QUERY_SUPPORT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_shipping_retry_support_receipt AFTER INSERT ON payment_refund_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_support_primitive_guard();
CREATE CONSTRAINT TRIGGER exchange_shipping_query_support_receipt AFTER INSERT ON payment_refund_query_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_shipping_support_primitive_guard();

CREATE OR REPLACE FUNCTION shipping_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o marketplace.claim_shipping_obligation%ROWTYPE;proofs integer;
BEGIN
 SELECT * INTO o FROM marketplace.claim_shipping_obligation WHERE seller_order_id=NEW.seller_order_id;
 IF o.version=0 OR NOT EXISTS(SELECT 1 FROM marketplace.claim_financial_intent WHERE id=o.claim_id AND kind IN('RETURN','EXCHANGE_REFUND')) THEN RETURN NULL;END IF;
 SELECT (SELECT count(*) FROM marketplace.claim_shipping_result x WHERE x.seller_order_id=o.seller_order_id AND x.intent_id=o.claim_id AND x.attempt_id=o.refund_attempt_id AND x.workflow_revision=o.version AND x.result_state=o.state)
  +(SELECT count(*) FROM marketplace.claim_shipping_support_command c WHERE c.seller_order_id=o.seller_order_id AND c.return_id=o.claim_id AND c.attempt_id=o.refund_attempt_id AND c.revision=o.version
    AND ((c.action='RETRY_FAILED' AND o.state='RESERVED') OR(c.action='QUERY_UNKNOWN' AND o.state='UNKNOWN')))
  +(SELECT count(*) FROM marketplace.claim_exchange_shipping_support_command c WHERE c.seller_order_id=o.seller_order_id AND c.exchange_id=o.claim_id AND c.attempt_id=o.refund_attempt_id AND c.revision=o.version
    AND ((c.action='RETRY_FAILED' AND o.state='RESERVED') OR(c.action='QUERY_UNKNOWN' AND o.state='UNKNOWN'))) INTO proofs;
 IF proofs<>1 THEN RAISE EXCEPTION 'SHIPPING_CURRENT_OPERATION_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_shipping_support_operation AFTER INSERT ON claim_exchange_shipping_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_operation_guard();
