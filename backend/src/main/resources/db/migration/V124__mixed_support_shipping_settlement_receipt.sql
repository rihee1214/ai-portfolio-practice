SET search_path TO marketplace;

-- A mixed physical completion consumes the order receipt for the same seller shipping obligation.
-- RETURN and EXCHANGE_REFUND keep their existing result, delivery and lease proof.
DROP TRIGGER shipping_settlement_receipt_graph ON order_aftercare_shipping;
CREATE CONSTRAINT TRIGGER shipping_settlement_receipt_graph AFTER INSERT ON order_aftercare_shipping
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 WHEN (NOT is_support_settlement_attempt(NEW.refund_attempt_id)) EXECUTE FUNCTION shipping_result_graph_guard();

CREATE FUNCTION verify_mixed_support_shipping_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS (
  SELECT 1 FROM claim_support_refund_result r
  JOIN claim_support_refund_approval a ON a.id=r.approval_id
  JOIN claim_shipping_obligation o ON o.claim_id=a.id AND o.seller_order_id=a.seller_order_id AND o.order_id=a.order_id
  JOIN claim_financial_refund_binding b ON b.intent_id=a.id AND b.attempt_id=o.refund_attempt_id AND b.intent_kind='SUPPORT'
  JOIN payment_refund_attempt p ON p.id=b.attempt_id AND p.version=r.result_version AND p.claim_id=a.id
  JOIN order_shipping_current cur ON cur.seller_order_id=o.seller_order_id
  JOIN order_shipping_decision d ON d.decision_id=cur.decision_id AND d.order_id=o.order_id AND d.seller_order_id=o.seller_order_id
  WHERE p.id=NEW.refund_attempt_id AND r.attempt_id=p.id AND p.purpose='SHIPPING' AND r.purpose=p.purpose AND b.purpose=p.purpose
   AND p.state='SUCCEEDED' AND o.state=p.state AND r.state=p.state AND p.result_at=r.result_at AND p.result_reference=r.reference
   AND b.order_id=NEW.order_id AND o.order_id=NEW.order_id AND p.order_id=NEW.order_id
   AND b.seller_order_id=NEW.seller_order_id AND o.seller_order_id=NEW.seller_order_id AND p.seller_order_id=NEW.seller_order_id
   AND o.claim_id=NEW.cause_id AND p.payment_id=NEW.payment_id AND b.payment_id=NEW.payment_id
   AND o.amount=NEW.amount AND p.amount=NEW.amount AND b.amount=NEW.amount AND r.amount=NEW.amount
   AND p.result_reference=NEW.reference AND b.original_reference=p.original_reference
   AND d.original_shipping=NEW.amount AND d.state='REFUND_REQUIRED'
   AND (EXISTS(SELECT 1 FROM claim_return_item_ready i WHERE i.seller_order_id=o.seller_order_id AND i.order_id=o.order_id)
     OR EXISTS(SELECT 1 FROM claim_exchange_refund_item_ready e WHERE e.seller_order_id=o.seller_order_id AND e.order_id=o.order_id))
 ) THEN RAISE EXCEPTION 'EXACT_MIXED_SUPPORT_SHIPPING_SETTLEMENT_REQUIRED' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER support_shipping_settlement_receipt_graph AFTER INSERT ON order_aftercare_shipping
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 WHEN (is_support_settlement_attempt(NEW.refund_attempt_id)) EXECUTE FUNCTION verify_mixed_support_shipping_receipt();
