SET search_path TO marketplace;

CREATE TABLE claim_return_refund_result (
 attempt_id uuid NOT NULL,result_version bigint NOT NULL CHECK(result_version>0),intent_id uuid NOT NULL,assessment_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 amount bigint NOT NULL CHECK(amount>=0),result_state varchar(16) NOT NULL CHECK(result_state IN ('SUCCEEDED','FAILED','UNKNOWN')),result_reference varchar(200),result_at timestamptz,fingerprint varchar(64) NOT NULL,occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(attempt_id,result_version),FOREIGN KEY(intent_id,seller_order_id,order_id) REFERENCES claim_financial_intent(id,seller_order_id,order_id),
 FOREIGN KEY(assessment_id,attempt_id) REFERENCES claim_fee_binding_history(assessment_id,attempt_id),
 CHECK((result_state='SUCCEEDED' AND result_reference IS NOT NULL AND result_at IS NOT NULL) OR(result_state<>'SUCCEEDED' AND result_reference IS NULL AND result_at IS NULL)),
 UNIQUE(assessment_id,attempt_id,result_version,result_state,amount,result_reference)
);
CREATE INDEX return_financial_result_intent ON claim_return_refund_result(intent_id,occurred_at);
CREATE FUNCTION validate_return_financial_result() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_refund_attempt p JOIN claim_financial_refund_binding b ON b.attempt_id=p.id
 WHERE p.id=NEW.attempt_id AND p.version=NEW.result_version AND p.state=NEW.result_state AND p.amount=NEW.amount AND p.claim_id=NEW.intent_id AND p.order_id=NEW.order_id AND p.seller_order_id=NEW.seller_order_id AND p.purpose='ITEM'
 AND p.result_reference IS NOT DISTINCT FROM NEW.result_reference AND p.result_at IS NOT DISTINCT FROM NEW.result_at AND b.intent_kind='RETURN' AND b.fee_assessment_id=NEW.assessment_id)
 THEN RAISE EXCEPTION 'RETURN_FINANCIAL_RESULT_MISMATCH' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER return_financial_result_valid BEFORE INSERT ON claim_return_refund_result FOR EACH ROW EXECUTE FUNCTION validate_return_financial_result();
CREATE TRIGGER return_financial_result_immutable BEFORE UPDATE OR DELETE ON claim_return_refund_result FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE TABLE claim_return_result_event (
 attempt_id uuid NOT NULL,result_version bigint NOT NULL,action varchar(30) NOT NULL CHECK(action='RESULT_RETAINED'),occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(attempt_id,result_version),FOREIGN KEY(attempt_id,result_version) REFERENCES claim_return_refund_result(attempt_id,result_version)
);
CREATE TRIGGER return_result_event_immutable BEFORE UPDATE OR DELETE ON claim_return_result_event FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
