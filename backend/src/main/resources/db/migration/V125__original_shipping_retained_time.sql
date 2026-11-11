SET search_path TO marketplace;

CREATE FUNCTION settlement_original_shipping_time(v_seller uuid) RETURNS timestamptz LANGUAGE plpgsql STABLE SET search_path FROM CURRENT AS $$
DECLARE v_terminal timestamptz;v_decision timestamptz;v_physical boolean;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM order_unit_lifecycle WHERE seller_order_id=v_seller)
 OR EXISTS(SELECT 1 FROM order_unit_lifecycle WHERE seller_order_id=v_seller AND state NOT IN('CONFIRMED','REFUNDED')) THEN RETURN NULL;END IF;
 SELECT max(greatest(c.confirmed_at,e.occurred_at,p.result_at)) INTO v_terminal
 FROM order_unit_lifecycle u LEFT JOIN order_confirmation c ON c.id=u.confirmation_id
 LEFT JOIN order_unit_hold h ON h.cause_id=u.refund_cause_id
 LEFT JOIN order_lifecycle_event e ON e.cause_id=h.id AND e.action='REFUNDED'
 LEFT JOIN order_cancellation_settlement cs ON cs.cause_id=u.refund_cause_id
 LEFT JOIN order_return_completion rc ON rc.cause_id=u.refund_cause_id
 LEFT JOIN order_exchange_refund_completion ec ON ec.cause_id=u.refund_cause_id
 LEFT JOIN payment_refund_attempt p ON p.id=COALESCE(cs.refund_attempt_id,rc.item_attempt_id,ec.item_attempt_id)
 WHERE u.seller_order_id=v_seller;
 v_physical=EXISTS(SELECT 1 FROM order_return_completion WHERE seller_order_id=v_seller)
  OR EXISTS(SELECT 1 FROM order_exchange_refund_completion WHERE seller_order_id=v_seller);
 IF v_physical THEN
  SELECT min(occurred_at) INTO v_decision FROM claim_seller_shipping_decision
  WHERE seller_order_id=v_seller AND state='RETAINED' AND reason='CONFIRMED_UNITS_REMAIN';
  IF v_decision IS NOT NULL THEN RETURN greatest(v_terminal,v_decision);END IF;
 END IF;
 RETURN settlement_shipping_time(v_seller);
END $$;

CREATE OR REPLACE FUNCTION settlement_original_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE v_net bigint;v_fee bigint;v_cause uuid;v_at timestamptz;v_business timestamptz;v_attempt uuid;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM seller_order s JOIN purchase_order o ON o.id=s.order_id
 JOIN order_settlement_policy policy ON policy.order_id=o.id AND policy.version=NEW.policy_version
 JOIN payment_attempt p ON p.id=NEW.payment_id AND p.order_id=o.id AND p.amount=o.amount
 WHERE s.id=NEW.seller_order_id AND s.order_id=NEW.order_id AND s.store_id=NEW.store_id
 AND p.state='SUCCEEDED' AND p.compensation='NONE')
 THEN RAISE EXCEPTION 'SETTLEMENT_ORIGINAL_PAYMENT_GRAPH' USING ERRCODE='23514';END IF;
 IF NEW.item_id IS NOT NULL THEN
  SELECT u.net,COALESCE(l.confirmation_id,l.refund_cause_id),c.confirmed_at
  INTO v_net,v_cause,v_at FROM order_unit u JOIN order_item i ON i.id=u.item_id
  JOIN order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal
  LEFT JOIN order_confirmation c ON c.id=l.confirmation_id
  WHERE u.item_id=NEW.item_id AND u.ordinal=NEW.ordinal AND i.seller_order_id=NEW.seller_order_id
  AND i.order_id=NEW.order_id AND l.state IN('CONFIRMED','REFUNDED');
  IF v_net IS NULL OR v_cause IS DISTINCT FROM NEW.cause_id THEN
   RAISE EXCEPTION 'SETTLEMENT_ORIGINAL_QUANTITY_GRAPH' USING ERRCODE='23514';END IF;
  SELECT attempt_id INTO v_attempt FROM (
   SELECT c.refund_attempt_id AS attempt_id FROM order_cancellation_settlement c
   JOIN order_cancellation_settlement_unit u ON u.cause_id=c.cause_id WHERE c.cause_id=v_cause AND u.item_id=NEW.item_id AND u.ordinal=NEW.ordinal
   UNION ALL SELECT c.item_attempt_id FROM order_return_completion c JOIN order_return_completion_unit u ON u.return_id=c.return_id WHERE c.cause_id=v_cause AND u.item_id=NEW.item_id AND u.ordinal=NEW.ordinal
   UNION ALL SELECT c.item_attempt_id FROM order_exchange_refund_completion c JOIN order_exchange_refund_completion_unit u ON u.exchange_id=c.exchange_id WHERE c.cause_id=v_cause AND u.item_id=NEW.item_id AND u.ordinal=NEW.ordinal
  ) receipts;
  IF v_at IS NULL THEN SELECT result_at INTO v_at FROM payment_refund_attempt WHERE id=v_attempt AND state='SUCCEEDED' AND purpose='ITEM';END IF;
  v_business=v_at;
  IF v_attempt IS NOT NULL THEN
   SELECT greatest(v_at,e.occurred_at) INTO v_business FROM order_unit_hold h
   JOIN order_lifecycle_event e ON e.cause_id=h.id AND e.action='REFUNDED' WHERE h.cause_id=v_cause;
  END IF;
  SELECT base+CASE WHEN rank<=extra THEN 1 ELSE 0 END INTO v_fee FROM (
   SELECT u.item_id,u.ordinal,u.net/20 AS base,
   row_number() OVER(ORDER BY u.net%20 DESC,u.item_id::text,u.ordinal) AS rank,
   sum(u.net) OVER()/20-sum(u.net/20) OVER() AS extra
   FROM order_item i JOIN order_unit u ON u.item_id=i.id WHERE i.seller_order_id=NEW.seller_order_id
  ) allocation WHERE item_id=NEW.item_id AND ordinal=NEW.ordinal;
  IF v_at IS DISTINCT FROM NEW.occurred_at OR NEW.eligible_at IS DISTINCT FROM v_business
  OR NEW.kind='PRODUCT' AND NEW.amount<>v_net
  OR NEW.kind='REFUND' AND (NEW.amount<>-v_net OR NEW.refund_attempt_id IS DISTINCT FROM v_attempt)
  OR NEW.kind='COMMISSION' AND NEW.fee<>v_fee
  OR NEW.kind='COMMISSION_REVERSAL' AND (NEW.fee<>-v_fee OR NEW.refund_attempt_id IS DISTINCT FROM v_attempt)
  THEN RAISE EXCEPTION 'SETTLEMENT_ORIGINAL_UNIT_AMOUNT' USING ERRCODE='23514';END IF;
 ELSIF NEW.kind='SHIPPING' THEN
  v_business=settlement_original_shipping_time(NEW.seller_order_id);
  IF v_business IS NULL OR NEW.occurred_at IS DISTINCT FROM v_business OR NEW.eligible_at IS DISTINCT FROM v_business
  OR NEW.cause_id<>NEW.seller_order_id OR NOT EXISTS(SELECT 1 FROM seller_order WHERE id=NEW.seller_order_id AND shipping_base-shipping_discount=NEW.amount)
  THEN RAISE EXCEPTION 'SETTLEMENT_ORIGINAL_SHIPPING' USING ERRCODE='23514';END IF;
 ELSIF NEW.kind='SHIPPING_REFUND' THEN
  v_business=settlement_shipping_time(NEW.seller_order_id);
  IF v_business IS NULL OR NEW.eligible_at IS DISTINCT FROM v_business OR NOT EXISTS(SELECT 1 FROM payment_refund_attempt p WHERE p.id=NEW.refund_attempt_id AND p.seller_order_id=NEW.seller_order_id
   AND p.payment_id=NEW.payment_id AND p.state='SUCCEEDED' AND p.purpose='SHIPPING' AND p.amount=-NEW.amount AND p.result_at=NEW.occurred_at
   AND(EXISTS(SELECT 1 FROM order_aftercare_shipping s WHERE s.seller_order_id=NEW.seller_order_id AND s.cause_id=NEW.cause_id AND s.refund_attempt_id=p.id)
    OR EXISTS(SELECT 1 FROM order_cancellation_shipping s WHERE s.seller_order_id=NEW.seller_order_id AND s.cause_id=NEW.cause_id AND s.refund_attempt_id=p.id)))
  THEN RAISE EXCEPTION 'SETTLEMENT_SHIPPING_REFUND_GRAPH' USING ERRCODE='23514';END IF;
 ELSIF NEW.kind='RECOVERY_FEE' THEN
  IF NEW.eligible_at IS DISTINCT FROM NEW.occurred_at OR NOT EXISTS(SELECT 1 FROM claim_fee_assessment a JOIN claim_fee_application x ON x.assessment_id=a.id
   JOIN payment_refund_attempt p ON p.id=x.attempt_id WHERE a.id=NEW.cause_id AND a.seller_order_id=NEW.seller_order_id
   AND x.attempt_id=NEW.refund_attempt_id AND x.deducted=NEW.amount AND x.applied_at=NEW.occurred_at AND a.state='APPLIED'
   AND p.payment_id=NEW.payment_id AND p.state='SUCCEEDED' AND p.purpose='ITEM')
  THEN RAISE EXCEPTION 'SETTLEMENT_APPLIED_RECOVERY_GRAPH' USING ERRCODE='23514';END IF;
 END IF;RETURN NEW;
END $$;
