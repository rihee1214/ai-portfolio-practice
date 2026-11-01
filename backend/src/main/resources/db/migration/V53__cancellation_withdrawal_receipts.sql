SET search_path TO marketplace;

CREATE TABLE claim_withdrawal (
 claim_id uuid PRIMARY KEY REFERENCES claim(id),attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),original_state varchar(16) NOT NULL CHECK(original_state IN ('RESERVED','FAILED')),
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),receipt jsonb NOT NULL,withdrawn_at timestamptz NOT NULL
);
CREATE FUNCTION validate_cancellation_withdrawal() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim c JOIN payment_refund_attempt p ON p.id=c.refund_attempt_id JOIN order_unit_hold h ON h.id=c.hold_id
 WHERE c.id=NEW.claim_id AND c.state IN ('APPROVED','REVIEW_REQUIRED') AND p.id=NEW.attempt_id AND p.claim_id=c.id AND p.order_id=c.order_id AND p.seller_order_id=c.seller_order_id AND p.purpose='ITEM' AND p.amount=c.item_amount
 AND((NEW.original_state='RESERVED' AND p.state='WITHDRAWN') OR(NEW.original_state='FAILED' AND p.state='FAILED')) AND h.kind='CANCEL' AND h.state='RELEASED' AND h.outcome='WITHDRAWN'
 AND NOT EXISTS(SELECT 1 FROM claim_refund_effect WHERE claim_id=c.id)) THEN RAISE EXCEPTION 'CANCELLATION_WITHDRAWAL_NO_EFFECT_PROOF_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER cancellation_withdrawal_valid BEFORE INSERT ON claim_withdrawal FOR EACH ROW EXECUTE FUNCTION validate_cancellation_withdrawal();
CREATE TRIGGER cancellation_withdrawal_immutable BEFORE UPDATE OR DELETE ON claim_withdrawal FOR EACH ROW EXECUTE FUNCTION immutable_claim_result_receipt();
CREATE FUNCTION check_cancellation_withdrawal_terminal() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM claim c WHERE c.id=NEW.id AND ((c.state='WITHDRAWN') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_withdrawal w WHERE w.claim_id=c.id)))
 THEN RAISE EXCEPTION 'CANCELLATION_WITHDRAWAL_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER cancellation_withdrawal_terminal AFTER INSERT OR UPDATE ON claim DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_cancellation_withdrawal_terminal();
