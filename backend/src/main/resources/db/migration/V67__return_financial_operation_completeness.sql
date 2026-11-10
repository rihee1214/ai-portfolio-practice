-- The inverse of V66: financial money/workflow cannot exist without its own operation receipt.
CREATE FUNCTION verify_return_financial_operation(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r marketplace.claim_return_request%ROWTYPE;c marketplace.claim_return_financial_consent%ROWTYPE;
 a marketplace.claim_fee_assessment%ROWTYPE;
BEGIN
 SELECT * INTO c FROM marketplace.claim_return_financial_consent WHERE return_id=v_id;
 IF NOT FOUND THEN RETURN;END IF;
 SELECT * INTO STRICT r FROM marketplace.claim_return_request WHERE id=v_id;
 SELECT * INTO STRICT a FROM marketplace.claim_fee_assessment WHERE id=c.assessment_id;
 IF r.version=c.revision AND a.state='BOUND' THEN RETURN;END IF;
 IF r.version<=c.revision OR NOT EXISTS(
  SELECT 1 FROM marketplace.claim_return_financial_result x
  JOIN marketplace.claim_return_command cmd ON cmd.return_id=x.return_id AND cmd.version=x.workflow_revision
  JOIN marketplace.claim_fee_binding b ON b.assessment_id=x.assessment_id AND b.attempt_id=x.attempt_id
  JOIN marketplace.claim_return_refund_result original ON original.intent_id=x.return_id AND original.assessment_id=x.assessment_id AND original.attempt_id=x.attempt_id AND original.result_version=x.result_version AND original.result_state=x.result_state
  WHERE x.return_id=r.id AND x.assessment_id=a.id AND x.workflow_revision=r.version
   AND x.receipt=cmd.receipt AND x.fingerprint=cmd.fingerprint AND cmd.action=r.state
   AND ((x.result_state='SUCCEEDED' AND a.state='APPLIED' AND r.state IN ('REFUND_PENDING','DUE_PENDING'))
     OR(x.result_state IN ('FAILED','UNKNOWN') AND a.state='BOUND' AND r.state='REVIEW_REQUIRED'))
 ) THEN RAISE EXCEPTION 'RETURN_FINANCIAL_OPERATION_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION return_financial_operation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;
BEGIN
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN v_id:=NEW.intent_id;
 ELSIF TG_TABLE_NAME='claim_fee_binding' THEN SELECT c.return_id INTO v_id FROM marketplace.claim_return_financial_consent c WHERE c.assessment_id=NEW.assessment_id;
 ELSE v_id:=NEW.return_id;END IF;
 IF v_id IS NOT NULL THEN PERFORM marketplace.verify_return_financial_operation(v_id);END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_financial_operation_state AFTER INSERT OR UPDATE ON claim_return_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_operation_guard();
CREATE CONSTRAINT TRIGGER return_financial_operation_history AFTER INSERT ON claim_return_history DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_operation_guard();
CREATE CONSTRAINT TRIGGER return_financial_operation_command AFTER INSERT ON claim_return_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_operation_guard();
CREATE CONSTRAINT TRIGGER return_financial_operation_receipt AFTER INSERT ON claim_return_financial_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_operation_guard();
CREATE CONSTRAINT TRIGGER return_financial_operation_assessment AFTER INSERT OR UPDATE ON claim_fee_assessment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_operation_guard();
CREATE CONSTRAINT TRIGGER return_financial_operation_binding AFTER INSERT OR UPDATE ON claim_fee_binding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_operation_guard();
DO $$ DECLARE r record;BEGIN FOR r IN SELECT return_id FROM marketplace.claim_return_financial_consent LOOP PERFORM marketplace.verify_return_financial_operation(r.return_id);END LOOP;END $$;
