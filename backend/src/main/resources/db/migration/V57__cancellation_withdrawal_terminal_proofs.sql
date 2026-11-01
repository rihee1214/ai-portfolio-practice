SET search_path TO marketplace;

CREATE FUNCTION check_cancellation_withdrawal_state(target_claim uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE c claim%ROWTYPE;
BEGIN
 SELECT * INTO c FROM claim WHERE id=target_claim;IF NOT FOUND THEN RETURN;END IF;
 IF(c.state='WITHDRAWN') IS DISTINCT FROM EXISTS(SELECT 1 FROM claim_withdrawal WHERE claim_id=c.id)
 THEN RAISE EXCEPTION 'CANCELLATION_WITHDRAWAL_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 IF c.state='WITHDRAWN' AND NOT EXISTS(SELECT 1 FROM claim_withdrawal w JOIN payment_refund_attempt p ON p.id=w.attempt_id JOIN order_unit_hold h ON h.id=c.hold_id
 WHERE w.claim_id=c.id AND p.id=c.refund_attempt_id AND p.claim_id=c.id AND p.order_id=c.order_id AND p.seller_order_id=c.seller_order_id AND p.purpose='ITEM' AND p.amount=c.item_amount
 AND((w.original_state='RESERVED' AND p.state='WITHDRAWN') OR(w.original_state='FAILED' AND p.state='FAILED')) AND h.order_id=c.order_id AND h.cause_id=c.id AND h.kind='CANCEL' AND h.state='RELEASED' AND h.outcome='WITHDRAWN'
 AND w.receipt->>'claimId'=c.id::text AND w.receipt->>'refundAttemptId'=p.id::text AND w.receipt->>'state'='WITHDRAWN' AND w.receipt->>'version'=c.version::text AND(w.receipt->>'withdrawnAt')::timestamptz=w.withdrawn_at
 AND NOT EXISTS(SELECT 1 FROM claim_refund_effect e WHERE e.claim_id=c.id))
 THEN RAISE EXCEPTION 'CANCELLATION_WITHDRAWAL_PROOF_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE OR REPLACE FUNCTION check_cancellation_withdrawal_terminal() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN PERFORM check_cancellation_withdrawal_state(NEW.id);RETURN NULL;END $$;
CREATE FUNCTION check_cancellation_withdrawal_receipt_terminal() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN PERFORM check_cancellation_withdrawal_state(NEW.claim_id);RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER cancellation_withdrawal_receipt_terminal AFTER INSERT ON claim_withdrawal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_cancellation_withdrawal_receipt_terminal();
DO $$ DECLARE existing uuid;BEGIN FOR existing IN SELECT id FROM claim LOOP PERFORM check_cancellation_withdrawal_state(existing);END LOOP;END $$;
