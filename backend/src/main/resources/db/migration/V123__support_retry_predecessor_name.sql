SET search_path TO marketplace;
CREATE OR REPLACE FUNCTION check_support_retry() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim_support_refund_result r
 JOIN payment_refund_attempt predecessor ON predecessor.id=r.attempt_id AND predecessor.version=r.result_version AND predecessor.state='FAILED'
 JOIN payment_refund_attempt p ON p.id=NEW.attempt_id AND p.claim_id=r.approval_id AND p.sequence=predecessor.sequence+1
 JOIN claim_financial_refund_binding b ON b.attempt_id=p.id AND b.intent_id=r.approval_id AND b.intent_kind='SUPPORT'
 WHERE r.state='FAILED' AND r.attempt_id=NEW.prior_attempt AND r.result_version=NEW.prior_version AND r.approval_id=NEW.approval_id
 AND predecessor.purpose=r.purpose AND r.purpose=NEW.purpose AND p.purpose=r.purpose AND p.payment_id=predecessor.payment_id
 AND p.amount=predecessor.amount AND p.original_reference=predecessor.original_reference AND p.cause_id=NEW.id AND p.sequence=NEW.sequence
 AND (NEW.purpose='ITEM' OR EXISTS(SELECT 1 FROM claim_shipping_obligation s WHERE s.claim_id=r.approval_id
 AND s.refund_attempt_id=p.id AND s.amount=p.amount AND s.seller_order_id=p.seller_order_id))) THEN
 RAISE EXCEPTION 'SUPPORT_RETRY_EXACT_FAILED_PREDECESSOR_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
