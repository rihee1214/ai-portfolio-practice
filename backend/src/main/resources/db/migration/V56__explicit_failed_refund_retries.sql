SET search_path TO marketplace;

ALTER TABLE claim_support_command DROP CONSTRAINT claim_support_command_action_check;
ALTER TABLE claim_support_command DROP CONSTRAINT claim_support_command_id_attempt_id_fkey;
ALTER TABLE claim_support_command ADD COLUMN previous_attempt_id uuid REFERENCES payment_refund_attempt(id),ADD COLUMN query_cause_id uuid;
DROP TRIGGER claim_support_command_immutable ON claim_support_command;
UPDATE claim_support_command SET previous_attempt_id=attempt_id,query_cause_id=id;
CREATE TRIGGER claim_support_command_immutable BEFORE UPDATE OR DELETE ON claim_support_command FOR EACH ROW EXECUTE FUNCTION immutable_claim_result_receipt();
ALTER TABLE claim_support_command ALTER COLUMN previous_attempt_id SET NOT NULL;
ALTER TABLE claim_support_command ADD CONSTRAINT support_command_action CHECK((action='QUERY' AND query_cause_id IS NOT NULL AND query_cause_id=id AND previous_attempt_id=attempt_id) OR(action='RETRY_FAILED' AND query_cause_id IS NULL AND previous_attempt_id<>attempt_id));
ALTER TABLE claim_support_command ADD CONSTRAINT support_query_cause FOREIGN KEY(query_cause_id,attempt_id) REFERENCES payment_refund_query_request(cause_id,attempt_id);
CREATE OR REPLACE FUNCTION validate_claim_support_query() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NEW.action='QUERY' THEN
  IF NOT EXISTS(SELECT 1 FROM claim c JOIN payment_refund_attempt p ON p.claim_id=c.id JOIN claim_financial_refund_binding b ON b.attempt_id=p.id JOIN payment_refund_query_request q ON q.cause_id=NEW.id AND q.attempt_id=p.id AND q.reason=NEW.reason
  WHERE c.id=NEW.claim_id AND c.version=NEW.original_revision AND c.state NOT IN ('COMPLETED','WITHDRAWN') AND p.id=NEW.attempt_id AND p.purpose=NEW.purpose AND p.state IN ('PROCESSING','UNKNOWN') AND p.order_id=c.order_id AND p.seller_order_id=c.seller_order_id AND b.intent_kind='CANCEL'
  AND((NEW.purpose='ITEM' AND c.refund_attempt_id=p.id AND c.item_amount=p.amount) OR(NEW.purpose='SHIPPING' AND c.original_shipping=p.amount AND EXISTS(SELECT 1 FROM claim_shipping_obligation s WHERE s.claim_id=c.id AND s.refund_attempt_id=p.id))))
  THEN RAISE EXCEPTION 'SUPPORT_QUERY_CURRENT_CLAIM_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM claim c JOIN payment_refund_attempt old_attempt ON old_attempt.claim_id=c.id JOIN payment_refund_attempt next_attempt ON next_attempt.claim_id=c.id JOIN claim_financial_refund_binding b ON b.attempt_id=next_attempt.id
  WHERE c.id=NEW.claim_id AND c.version=NEW.original_revision AND c.state NOT IN ('COMPLETED','WITHDRAWN') AND old_attempt.id=NEW.previous_attempt_id AND old_attempt.state='FAILED' AND next_attempt.id=NEW.attempt_id AND next_attempt.state='RESERVED' AND next_attempt.cause_id=NEW.id AND next_attempt.sequence=old_attempt.sequence+1 AND b.intent_kind='CANCEL'
  AND next_attempt.order_id=c.order_id AND next_attempt.seller_order_id=c.seller_order_id AND next_attempt.payment_id=old_attempt.payment_id AND next_attempt.purpose=old_attempt.purpose AND next_attempt.purpose=NEW.purpose AND next_attempt.amount=old_attempt.amount AND next_attempt.original_reference=old_attempt.original_reference
  AND((NEW.purpose='ITEM' AND c.refund_attempt_id=old_attempt.id AND c.item_amount=next_attempt.amount) OR(NEW.purpose='SHIPPING' AND c.original_shipping=next_attempt.amount AND EXISTS(SELECT 1 FROM claim_shipping_obligation s WHERE s.claim_id=c.id AND s.refund_attempt_id=old_attempt.id))))
  THEN RAISE EXCEPTION 'SUPPORT_RETRY_DEFINITIVE_FAILURE_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;RETURN NEW;
END $$;
CREATE TABLE claim_fee_support_command (
 id uuid PRIMARY KEY,assessment_id uuid NOT NULL REFERENCES claim_fee_assessment(id),previous_attempt_id uuid NOT NULL,attempt_id uuid NOT NULL,
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,actor_id uuid NOT NULL REFERENCES identity_user(id),reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),
 original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),receipt jsonb NOT NULL,accepted_at timestamptz NOT NULL,
 UNIQUE(assessment_id,request_key),CHECK(previous_attempt_id<>attempt_id),FOREIGN KEY(assessment_id,previous_attempt_id) REFERENCES claim_fee_binding_history(assessment_id,attempt_id),FOREIGN KEY(assessment_id,attempt_id) REFERENCES claim_fee_binding_history(assessment_id,attempt_id)
);
CREATE FUNCTION validate_fee_support_retry() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment a JOIN claim_fee_binding current_binding ON current_binding.assessment_id=a.id JOIN payment_refund_attempt old_attempt ON old_attempt.id=NEW.previous_attempt_id JOIN payment_refund_attempt next_attempt ON next_attempt.id=NEW.attempt_id
 WHERE a.id=NEW.assessment_id AND a.state='BOUND' AND a.version=NEW.original_revision AND current_binding.attempt_id=next_attempt.id AND old_attempt.state='FAILED' AND next_attempt.state='RESERVED' AND next_attempt.cause_id=NEW.id AND next_attempt.sequence=old_attempt.sequence+1
 AND next_attempt.claim_id=a.intent_id AND old_attempt.claim_id=a.intent_id AND next_attempt.order_id=a.order_id AND next_attempt.seller_order_id=a.seller_order_id AND next_attempt.payment_id=old_attempt.payment_id AND next_attempt.purpose='ITEM' AND old_attempt.purpose='ITEM' AND next_attempt.amount=a.refund AND old_attempt.amount=a.refund AND next_attempt.original_reference=a.quote_fingerprint AND old_attempt.original_reference=a.quote_fingerprint)
 THEN RAISE EXCEPTION 'FEE_SUPPORT_RETRY_SAME_ASSESSMENT_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fee_support_retry_valid BEFORE INSERT ON claim_fee_support_command FOR EACH ROW EXECUTE FUNCTION validate_fee_support_retry();
CREATE TRIGGER fee_support_command_immutable BEFORE UPDATE OR DELETE ON claim_fee_support_command FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
