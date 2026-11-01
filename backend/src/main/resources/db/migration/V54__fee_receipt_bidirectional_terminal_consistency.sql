SET search_path TO marketplace;

CREATE FUNCTION check_fee_terminal_state(assessment uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
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
 FOR bucket IN SELECT bucket_id FROM claim_fee_assessment_bucket WHERE assessment_id=a.id LOOP PERFORM check_fee_bucket(bucket);END LOOP;
END $$;
CREATE OR REPLACE FUNCTION validate_fee_terminal_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN PERFORM check_fee_terminal_state(NEW.id);RETURN NULL;END $$;
CREATE FUNCTION validate_fee_receipt_terminal() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN PERFORM check_fee_terminal_state(NEW.assessment_id);RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER fee_application_terminal_consistent AFTER INSERT ON claim_fee_application DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_fee_receipt_terminal();
CREATE CONSTRAINT TRIGGER fee_withdrawal_terminal_consistent AFTER INSERT ON claim_fee_withdrawal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_fee_receipt_terminal();
-- Existing inconsistent evidence is never silently promoted to completion during an upgrade.
DO $$ DECLARE existing uuid;BEGIN FOR existing IN SELECT id FROM claim_fee_assessment LOOP PERFORM check_fee_terminal_state(existing);END LOOP;END $$;
