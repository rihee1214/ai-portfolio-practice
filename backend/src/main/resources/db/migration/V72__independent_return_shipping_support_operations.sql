-- SHIPPING has its own revision; completed RETURN/ITEM/fee/first buyer consent stay immutable.
ALTER TABLE claim_shipping_obligation ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK(version>=0);
ALTER TABLE claim_shipping_obligation DROP CONSTRAINT claim_shipping_obligation_state_check;
ALTER TABLE claim_shipping_obligation ADD CONSTRAINT claim_shipping_obligation_state_check CHECK(state IN ('RESERVED','SUCCEEDED','FAILED','UNKNOWN'));
CREATE FUNCTION shipping_obligation_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.seller_order_id,NEW.order_id,NEW.claim_id,NEW.kind,NEW.amount,NEW.created_at)
  IS DISTINCT FROM (OLD.seller_order_id,OLD.order_id,OLD.claim_id,OLD.kind,OLD.amount,OLD.created_at)
 THEN RAISE EXCEPTION 'IMMUTABLE_ORIGINAL_SHIPPING_OBLIGATION' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_financial_intent WHERE id=OLD.claim_id AND kind='RETURN') AND NEW.version<>OLD.version+1
 THEN RAISE EXCEPTION 'SHIPPING_OPERATION_REVISION_MUST_ADVANCE' USING ERRCODE='23514';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER shipping_obligation_identity BEFORE UPDATE ON claim_shipping_obligation FOR EACH ROW EXECUTE FUNCTION shipping_obligation_identity_guard();
-- Earlier immutable receipts remain unversioned historical evidence; new results must carry their S operation revision.
ALTER TABLE claim_shipping_result ADD COLUMN workflow_revision bigint CHECK(workflow_revision>0);
CREATE UNIQUE INDEX shipping_result_operation_revision ON claim_shipping_result(seller_order_id,workflow_revision);
CREATE TABLE claim_shipping_support_command (
 id uuid PRIMARY KEY,return_id uuid NOT NULL,seller_order_id uuid NOT NULL,order_id uuid NOT NULL,
 previous_attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),
 action varchar(20) NOT NULL CHECK(action IN ('RETRY_FAILED','QUERY_UNKNOWN')),
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,actor_id uuid NOT NULL REFERENCES identity_user(id),actor_version bigint NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),
 original_result_version bigint NOT NULL CHECK(original_result_version>0),query_cause_id uuid,
 receipt jsonb NOT NULL,accepted_at timestamptz NOT NULL,
 UNIQUE(seller_order_id,request_key),UNIQUE(seller_order_id,revision),
 FOREIGN KEY(return_id,seller_order_id,order_id) REFERENCES claim_return_item_ready(return_id,seller_order_id,order_id),
 FOREIGN KEY(query_cause_id,attempt_id) REFERENCES payment_refund_query_request(cause_id,attempt_id),
 CHECK((action='RETRY_FAILED' AND previous_attempt_id<>attempt_id AND query_cause_id IS NULL)
  OR(action='QUERY_UNKNOWN' AND previous_attempt_id=attempt_id AND query_cause_id=id AND query_cause_id IS NOT NULL))
);
CREATE TRIGGER shipping_support_command_immutable BEFORE UPDATE OR DELETE ON claim_shipping_support_command FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION shipping_support_effect_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_shipping_obligation o
  JOIN marketplace.claim_return_request r ON r.id=o.claim_id
  JOIN marketplace.claim_return_item_ready ready ON ready.return_id=r.id
  JOIN marketplace.payment_refund_attempt old_attempt ON old_attempt.id=NEW.previous_attempt_id
  JOIN marketplace.payment_refund_attempt next_attempt ON next_attempt.id=NEW.attempt_id
  JOIN marketplace.claim_return_shipping_binding b ON b.attempt_id=next_attempt.id AND b.intent_id=r.id
  WHERE o.seller_order_id=NEW.seller_order_id AND o.order_id=NEW.order_id AND o.claim_id=NEW.return_id
   AND r.seller_order_id=o.seller_order_id AND r.order_id=o.order_id AND r.state IN ('REFUND_PENDING','COMPLETED')
   AND o.refund_attempt_id=next_attempt.id AND o.version=NEW.revision
   AND old_attempt.claim_id=r.id AND old_attempt.order_id=r.order_id AND old_attempt.seller_order_id=r.seller_order_id AND old_attempt.purpose='SHIPPING'
   AND old_attempt.version=NEW.original_result_version AND next_attempt.claim_id=old_attempt.claim_id AND next_attempt.order_id=old_attempt.order_id
   AND next_attempt.seller_order_id=old_attempt.seller_order_id AND next_attempt.purpose='SHIPPING'
   AND next_attempt.payment_id=old_attempt.payment_id AND next_attempt.original_reference=old_attempt.original_reference AND next_attempt.amount=old_attempt.amount AND next_attempt.amount=o.amount
   AND NEW.receipt->>'commandId'=NEW.id::text AND NEW.receipt->>'returnId'=r.id::text AND NEW.receipt->>'refundAttemptId'=next_attempt.id::text
   AND NEW.receipt->>'purpose'='SHIPPING' AND NEW.receipt->>'action'=NEW.action AND (NEW.receipt->>'revision')::bigint=NEW.revision
   AND NEW.receipt->'progress'->>'state'=r.state AND (NEW.receipt->'progress'->>'version')::bigint=r.version
   AND ((NEW.action='RETRY_FAILED' AND o.state='RESERVED' AND old_attempt.state='FAILED' AND next_attempt.state='RESERVED'
     AND next_attempt.sequence=old_attempt.sequence+1 AND next_attempt.cause_id=NEW.id)
    OR(NEW.action='QUERY_UNKNOWN' AND o.state='UNKNOWN' AND old_attempt.state IN ('UNKNOWN','PROCESSING')
     AND EXISTS(SELECT 1 FROM marketplace.payment_refund_query_request q WHERE q.cause_id=NEW.query_cause_id AND q.attempt_id=old_attempt.id AND q.original_version=old_attempt.version AND q.original_state=old_attempt.state AND q.reason=NEW.reason)))
 ) OR NOT EXISTS(SELECT 1 FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id
  WHERE j.type='RETURN_RECORDED.v1' AND j.cause_key=NEW.return_id::text AND j.payload=NEW.return_id::text AND j.dedupe_key='return-shipping-support:'||NEW.id::text)
 THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_SUPPORT_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER shipping_support_effect_receipt AFTER INSERT ON claim_shipping_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_support_effect_guard();
CREATE FUNCTION shipping_support_primitive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p marketplace.payment_refund_attempt%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='payment_refund_attempt' THEN p:=NEW;
 ELSE SELECT * INTO STRICT p FROM marketplace.payment_refund_attempt WHERE id=NEW.attempt_id;END IF;
 IF p.purpose<>'SHIPPING' OR NOT EXISTS(SELECT 1 FROM marketplace.claim_financial_intent WHERE id=p.claim_id AND kind='RETURN') THEN RETURN NULL;END IF;
 IF TG_TABLE_NAME='payment_refund_attempt' THEN
  IF p.sequence>1 AND NOT EXISTS(SELECT 1 FROM marketplace.claim_shipping_support_command c WHERE c.id=p.cause_id AND c.return_id=p.claim_id AND c.attempt_id=p.id AND c.action='RETRY_FAILED') THEN RAISE EXCEPTION 'SHIPPING_RETRY_SUPPORT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM marketplace.claim_shipping_support_command c WHERE c.query_cause_id=NEW.cause_id AND c.return_id=p.claim_id AND c.attempt_id=p.id AND c.action='QUERY_UNKNOWN') THEN RAISE EXCEPTION 'SHIPPING_QUERY_SUPPORT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER shipping_retry_support_receipt AFTER INSERT ON payment_refund_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_support_primitive_guard();
CREATE CONSTRAINT TRIGGER shipping_query_support_receipt AFTER INSERT ON payment_refund_query_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_support_primitive_guard();
CREATE FUNCTION shipping_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o marketplace.claim_shipping_obligation%ROWTYPE;proofs integer;
BEGIN
 SELECT * INTO o FROM marketplace.claim_shipping_obligation WHERE seller_order_id=NEW.seller_order_id;
 IF o.version=0 OR NOT EXISTS(SELECT 1 FROM marketplace.claim_financial_intent WHERE id=o.claim_id AND kind='RETURN') THEN RETURN NULL;END IF;
 SELECT (SELECT count(*) FROM marketplace.claim_shipping_result x WHERE x.seller_order_id=o.seller_order_id AND x.intent_id=o.claim_id AND x.attempt_id=o.refund_attempt_id AND x.workflow_revision=o.version AND x.result_state=o.state)
  +(SELECT count(*) FROM marketplace.claim_shipping_support_command c WHERE c.seller_order_id=o.seller_order_id AND c.return_id=o.claim_id AND c.attempt_id=o.refund_attempt_id AND c.revision=o.version
    AND ((c.action='RETRY_FAILED' AND o.state='RESERVED') OR(c.action='QUERY_UNKNOWN' AND o.state='UNKNOWN'))) INTO proofs;
 IF proofs<>1 THEN RAISE EXCEPTION 'SHIPPING_CURRENT_OPERATION_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER shipping_current_operation AFTER UPDATE ON claim_shipping_obligation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_operation_guard();
CREATE CONSTRAINT TRIGGER shipping_result_operation AFTER INSERT ON claim_shipping_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_operation_guard();
CREATE CONSTRAINT TRIGGER shipping_support_operation AFTER INSERT ON claim_shipping_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_operation_guard();

-- New result receipts are the current independent S operation.
CREATE OR REPLACE FUNCTION shipping_result_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE x marketplace.claim_shipping_result%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='claim_shipping_result' THEN SELECT * INTO STRICT x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.attempt_id AND result_version=NEW.result_version;
 ELSE SELECT * INTO x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.refund_attempt_id AND result_state='SUCCEEDED' ORDER BY result_version DESC LIMIT 1;IF NOT FOUND THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_REQUIRED' USING ERRCODE='23514';END IF;END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_shipping_binding b
  JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=b.seller_order_id
  JOIN marketplace.payment_refund_attempt p ON p.id=b.attempt_id
  JOIN marketplace.job j ON j.id=x.job_id
  WHERE b.attempt_id=x.attempt_id AND b.intent_id=x.intent_id AND b.seller_order_id=x.seller_order_id
   AND o.claim_id=b.intent_id AND o.refund_attempt_id=p.id AND o.amount=p.amount AND p.purpose='SHIPPING' AND x.workflow_revision IS NOT NULL AND o.version=x.workflow_revision AND o.state=x.result_state
   AND p.version=x.result_version AND p.state=x.result_state AND p.result_reference IS NOT DISTINCT FROM x.reference AND p.result_at IS NOT DISTINCT FROM x.result_at
   AND j.type='REFUND_RESULT_RECORDED.v1' AND j.cause_key=p.id::text AND j.status='RUNNING' AND j.owner_token=x.owner_token
   AND (x.result_state<>'SUCCEEDED' OR(o.state='SUCCEEDED' AND EXISTS(SELECT 1 FROM marketplace.order_aftercare_shipping s WHERE s.seller_order_id=o.seller_order_id AND s.order_id=o.order_id AND s.refund_attempt_id=p.id AND s.payment_id=p.payment_id AND s.cause_id=o.claim_id AND s.amount=p.amount AND s.reference=p.result_reference)))
 ) THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
