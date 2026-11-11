SET search_path TO marketplace;

CREATE FUNCTION is_support_settlement_attempt(target uuid) RETURNS boolean
LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT EXISTS(SELECT 1 FROM claim_financial_refund_binding
 WHERE attempt_id=target AND intent_kind='SUPPORT')
$$;

CREATE FUNCTION settlement_support_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE original_fee bigint;
BEGIN
 IF NOT EXISTS(
  SELECT 1 FROM claim_support_refund_approval a
  JOIN claim_support_refund_result r ON r.approval_id=a.id
  JOIN payment_refund_attempt p ON p.id=r.attempt_id AND p.version=r.result_version
  JOIN payment_attempt payment ON payment.id=p.payment_id
  JOIN seller_order seller ON seller.id=a.seller_order_id
  JOIN purchase_order parent ON parent.id=seller.order_id
  JOIN order_settlement_policy policy ON policy.order_id=parent.id
  JOIN order_unit_lifecycle unit ON unit.item_id=a.item_id AND unit.ordinal=a.ordinal
  WHERE a.id=NEW.cause_id AND a.order_id=NEW.order_id AND seller.order_id=NEW.order_id
  AND seller.id=NEW.seller_order_id AND seller.store_id=NEW.store_id AND policy.version=NEW.policy_version
  AND payment.id=NEW.payment_id AND payment.order_id=parent.id AND payment.amount=parent.amount
  AND payment.state='SUCCEEDED' AND payment.compensation='NONE'
  AND unit.state='CONFIRMED' AND unit.confirmation_id=a.confirmation_id
  AND p.id=NEW.refund_attempt_id AND p.state='SUCCEEDED' AND r.state='SUCCEEDED'
  AND r.result_at=NEW.occurred_at AND NEW.eligible_at=NEW.occurred_at
  AND ((r.purpose='ITEM' AND NEW.item_id=a.item_id AND NEW.ordinal=a.ordinal AND r.amount=a.net
        AND (NEW.kind='REFUND' AND NEW.amount=-a.net OR NEW.kind='COMMISSION_REVERSAL'))
    OR (r.purpose='SHIPPING' AND NEW.kind='SHIPPING_REFUND' AND NEW.amount=-r.amount
        AND r.amount=seller.shipping_base-seller.shipping_discount AND EXISTS(
           SELECT 1 FROM claim_shipping_obligation s WHERE s.seller_order_id=seller.id AND s.claim_id=a.id
           AND s.refund_attempt_id=p.id AND s.state='SUCCEEDED' AND s.amount=r.amount)))
 ) THEN RAISE EXCEPTION 'SETTLEMENT_SUPPORT_ORIGINAL_GRAPH' USING ERRCODE='23514';END IF;
 IF NEW.kind='COMMISSION_REVERSAL' THEN
  SELECT base+CASE WHEN rank<=extra THEN 1 ELSE 0 END INTO STRICT original_fee FROM (
   SELECT u.item_id,u.ordinal,u.net/20 AS base,
   row_number() OVER(ORDER BY u.net%20 DESC,u.item_id::text,u.ordinal) AS rank,
   sum(u.net) OVER()/20-sum(u.net/20) OVER() AS extra
   FROM order_item i JOIN order_unit u ON u.item_id=i.id WHERE i.seller_order_id=NEW.seller_order_id
  ) original WHERE item_id=NEW.item_id AND ordinal=NEW.ordinal;
  IF NEW.fee<>-original_fee THEN RAISE EXCEPTION 'SETTLEMENT_SUPPORT_ORIGINAL_COMMISSION' USING ERRCODE='23514';END IF;
 END IF;
 RETURN NEW;
END $$;

DROP TRIGGER settlement_original_graph ON settlement_ledger;
CREATE TRIGGER settlement_original_graph BEFORE INSERT ON settlement_ledger FOR EACH ROW
 WHEN (NOT is_support_settlement_attempt(NEW.refund_attempt_id)) EXECUTE FUNCTION settlement_original_graph_guard();
CREATE TRIGGER settlement_support_graph BEFORE INSERT ON settlement_ledger FOR EACH ROW
 WHEN (is_support_settlement_attempt(NEW.refund_attempt_id)) EXECUTE FUNCTION settlement_support_graph_guard();
