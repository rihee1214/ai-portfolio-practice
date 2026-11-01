CREATE TABLE claim_refund_result_receipt (
 attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),result_version bigint NOT NULL CHECK(result_version>0),claim_id uuid NOT NULL REFERENCES claim(id),
 result_state varchar(16) NOT NULL CHECK(result_state IN ('SUCCEEDED','FAILED','UNKNOWN')),fingerprint varchar(64) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(attempt_id,result_version)
);
CREATE FUNCTION immutable_claim_result_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_CLAIM_RESULT_RECEIPT' USING ERRCODE='23514';END $$;
CREATE TRIGGER claim_result_receipt_immutable BEFORE UPDATE OR DELETE ON claim_refund_result_receipt FOR EACH ROW EXECUTE FUNCTION immutable_claim_result_receipt();
