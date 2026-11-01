SET search_path TO marketplace;

CREATE TABLE claim_fee_application (
 assessment_id uuid PRIMARY KEY REFERENCES claim_fee_assessment(id),attempt_id uuid NOT NULL,result_version bigint NOT NULL,result_state varchar(16) NOT NULL DEFAULT 'SUCCEEDED' CHECK(result_state='SUCCEEDED'),amount bigint NOT NULL CHECK(amount>=0),result_reference varchar(200) NOT NULL,
 increment bigint NOT NULL CHECK(increment>=0),deducted bigint NOT NULL CHECK(deducted>=0),additional_due bigint NOT NULL CHECK(additional_due>=0),receipt jsonb NOT NULL,applied_at timestamptz NOT NULL,
 CHECK(increment::numeric=deducted::numeric+additional_due),
 FOREIGN KEY(assessment_id,attempt_id,result_version,result_state,amount,result_reference) REFERENCES claim_return_refund_result(assessment_id,attempt_id,result_version,result_state,amount,result_reference)
);
CREATE TABLE claim_fee_withdrawal (
 assessment_id uuid PRIMARY KEY REFERENCES claim_fee_assessment(id),attempt_id uuid NOT NULL,original_state varchar(16) NOT NULL CHECK(original_state IN ('RESERVED','FAILED')),receipt jsonb NOT NULL,withdrawn_at timestamptz NOT NULL,
 FOREIGN KEY(assessment_id,attempt_id) REFERENCES claim_fee_binding_history(assessment_id,attempt_id)
);
CREATE FUNCTION validate_fee_application() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment a JOIN claim_fee_binding b ON b.assessment_id=a.id JOIN payment_refund_attempt p ON p.id=b.attempt_id JOIN claim_financial_intent i ON i.id=a.intent_id JOIN order_unit_hold h ON h.id=i.hold_id
 WHERE a.id=NEW.assessment_id AND a.state='BOUND' AND p.id=NEW.attempt_id AND p.state='SUCCEEDED' AND p.version=NEW.result_version AND p.result_reference=NEW.result_reference AND p.amount=NEW.amount AND a.refund=NEW.amount AND a.increment=NEW.increment AND a.net::numeric-a.refund=NEW.deducted AND a.additional_due=NEW.additional_due AND h.kind='RETURN' AND h.state='ACTIVE')
 THEN RAISE EXCEPTION 'FEE_APPLICATION_RESULT_MISMATCH' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fee_application_valid BEFORE INSERT ON claim_fee_application FOR EACH ROW EXECUTE FUNCTION validate_fee_application();
CREATE FUNCTION validate_fee_withdrawal() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment a JOIN claim_fee_binding b ON b.assessment_id=a.id JOIN payment_refund_attempt p ON p.id=b.attempt_id JOIN claim_financial_intent i ON i.id=a.intent_id JOIN order_unit_hold h ON h.id=i.hold_id
 WHERE a.id=NEW.assessment_id AND a.state='BOUND' AND p.id=NEW.attempt_id AND ((NEW.original_state='RESERVED' AND p.state='WITHDRAWN') OR(NEW.original_state='FAILED' AND p.state='FAILED')) AND h.kind='RETURN' AND h.state='RELEASED' AND h.outcome='WITHDRAWN')
 THEN RAISE EXCEPTION 'FEE_WITHDRAWAL_RESULT_MISMATCH' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fee_withdrawal_valid BEFORE INSERT ON claim_fee_withdrawal FOR EACH ROW EXECUTE FUNCTION validate_fee_withdrawal();
CREATE TRIGGER fee_application_immutable BEFORE UPDATE OR DELETE ON claim_fee_application FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TRIGGER fee_withdrawal_immutable BEFORE UPDATE OR DELETE ON claim_fee_withdrawal FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE FUNCTION validate_fee_terminal_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE a claim_fee_assessment%ROWTYPE;bucket uuid;
BEGIN
 SELECT * INTO a FROM claim_fee_assessment WHERE id=NEW.id;
 IF (a.state='APPLIED') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_fee_application WHERE assessment_id=a.id)
 OR (a.state='WITHDRAWN') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_fee_withdrawal WHERE assessment_id=a.id)
 THEN RAISE EXCEPTION 'FEE_TERMINAL_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 FOR bucket IN SELECT bucket_id FROM claim_fee_assessment_bucket WHERE assessment_id=a.id LOOP PERFORM check_fee_bucket(bucket);END LOOP;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER fee_terminal_receipt_required AFTER INSERT OR UPDATE ON claim_fee_assessment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_fee_terminal_receipt();
