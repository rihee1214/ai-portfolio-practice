SET search_path TO marketplace;
ALTER TABLE settlement_batch ADD store_balances jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(store_balances)='array');
ALTER TABLE settlement_batch_revision ADD store_balances jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(store_balances)='array');

CREATE FUNCTION settlement_store_balances(target uuid) RETURNS jsonb LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 WITH current_net AS (
   SELECT l.store_id,sum(l.amount::numeric-l.fee)::bigint AS economic
   FROM settlement_batch_item i JOIN settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=target GROUP BY l.store_id
 ), last_paid AS (
   SELECT DISTINCT ON ((entry->>'storeId')::uuid) (entry->>'storeId')::uuid AS store_id,(entry->>'closing')::bigint AS debt
   FROM settlement_batch b CROSS JOIN LATERAL jsonb_array_elements(b.store_balances) entry
   WHERE b.state='PAID_MOCK' AND b.week_start<(SELECT week_start FROM settlement_batch WHERE id=target)
   ORDER BY (entry->>'storeId')::uuid,b.week_start DESC
 ), balances AS (
   SELECT coalesce(n.store_id,l.store_id) AS store_id,coalesce(n.economic,0) AS net,coalesce(l.debt,0) AS opening
   FROM current_net n FULL JOIN last_paid l USING(store_id) WHERE n.store_id IS NOT NULL OR l.debt>0
 )
 SELECT coalesce(jsonb_agg(jsonb_build_object('storeId',store_id,'net',net::text,'opening',opening::text,
 'payout',greatest(0::numeric,net::numeric-opening)::bigint::text,'closing',greatest(0::numeric,opening::numeric-net)::bigint::text)
 ORDER BY store_id),'[]') FROM balances
$$;
CREATE FUNCTION settlement_payout(balances jsonb) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
 SELECT coalesce(sum((entry->>'payout')::bigint),0)::bigint FROM jsonb_array_elements(balances) entry
$$;
CREATE FUNCTION settlement_balance_archive() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 SELECT store_balances INTO STRICT NEW.store_balances FROM settlement_batch WHERE id=NEW.batch_id;
 RETURN NEW;
END $$;
CREATE TRIGGER settlement_balance_archive BEFORE INSERT ON settlement_batch_revision FOR EACH ROW EXECUTE FUNCTION settlement_balance_archive();
CREATE FUNCTION settlement_balance_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF OLD.state<>'DRAFT' AND NEW.store_balances<>OLD.store_balances THEN
  RAISE EXCEPTION 'SETTLEMENT_REVIEWED_BALANCES_IMMUTABLE' USING ERRCODE='23514';
 END IF;
 IF NEW.readiness_version=2 AND NEW.state IN('REVIEWED','PAID_MOCK') AND
 (NEW.store_balances<>settlement_store_balances(NEW.id) OR EXISTS(
   SELECT 1 FROM settlement_batch b CROSS JOIN LATERAL jsonb_array_elements(b.store_balances) later
   CROSS JOIN LATERAL jsonb_array_elements(NEW.store_balances) current_balance
   WHERE b.state='PAID_MOCK' AND b.week_start>NEW.week_start AND later->>'storeId'=current_balance->>'storeId'
   AND current_balance->>'closing'<>current_balance->>'opening')) THEN
  RAISE EXCEPTION 'SETTLEMENT_STORE_BALANCE_CHANGED' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER settlement_balance_guard BEFORE UPDATE ON settlement_batch FOR EACH ROW EXECUTE FUNCTION settlement_balance_guard();
CREATE OR REPLACE FUNCTION verify_settlement_batch() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b settlement_batch%ROWTYPE;v_id uuid;v_gross numeric;v_fee numeric;v_count bigint;v_item record;
BEGIN
 IF TG_TABLE_NAME='settlement_batch' THEN v_id=NEW.id;
 ELSIF TG_OP='DELETE' THEN v_id=OLD.batch_id;ELSE v_id=NEW.batch_id;END IF;
 SELECT * INTO b FROM settlement_batch WHERE id=v_id;
 SELECT count(*),COALESCE(sum(l.amount),0),COALESCE(sum(l.fee),0) INTO v_count,v_gross,v_fee FROM settlement_batch_item i JOIN settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=v_id;
 IF b.readiness_version=2 AND b.store_balances<>settlement_store_balances(b.id) OR b.readiness_version=1 AND b.store_balances<>'[]'::jsonb OR b.gross<>v_gross OR b.fee<>v_fee OR b.net<>v_gross-v_fee OR b.readiness_version=1 AND b.net<0
 OR v_count=0 AND b.store_balances='[]'::jsonb AND (b.state<>'DRAFT' OR jsonb_array_length(b.held)=0 OR NOT EXISTS(SELECT 1 FROM settlement_batch_revision r WHERE r.batch_id=v_id AND cardinality(r.ledger_ids)>0))
 OR b.state='PAID_MOCK' AND NOT EXISTS(SELECT 1 FROM settlement_mock_payment p WHERE p.batch_id=v_id AND p.amount=CASE WHEN b.readiness_version=1 THEN b.net ELSE settlement_payout(b.store_balances) END)
 OR b.state<>'PAID_MOCK' AND EXISTS(SELECT 1 FROM settlement_mock_payment p WHERE p.batch_id=v_id)
 THEN RAISE EXCEPTION 'SETTLEMENT_BATCH_RECONCILIATION' USING ERRCODE='23514';END IF;
 IF b.state IN('REVIEWED','PAID_MOCK') THEN
  FOR v_item IN SELECT * FROM settlement_batch_item WHERE batch_id=v_id LOOP
   PERFORM verify_settlement_membership(v_item.batch_id,v_item.ledger_id,v_item.eligible_at,v_item.hold_evidence,v_item.reservation_evidence);
  END LOOP;
 END IF;RETURN NULL;
END $$;
CREATE OR REPLACE FUNCTION settlement_reservation_affects(v_kind text,v_id uuid,v_item uuid,v_ordinal integer)
RETURNS boolean LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT CASE
 WHEN v_item IS NULL THEN true
 WHEN v_kind='REFUND' AND EXISTS(SELECT 1 FROM payment_refund_attempt p WHERE p.id=v_id AND p.purpose='SHIPPING') THEN false
 WHEN v_kind='REFUND' THEN NOT EXISTS(SELECT 1 FROM claim_financial_refund_binding b JOIN claim_financial_intent_unit u ON u.intent_id=b.intent_id WHERE b.attempt_id=v_id)
 OR EXISTS(SELECT 1 FROM claim_financial_refund_binding b JOIN claim_financial_intent_unit u ON u.intent_id=b.intent_id WHERE b.attempt_id=v_id AND u.item_id=v_item AND u.ordinal=v_ordinal)
 WHEN v_kind='CLAIM_FEE' THEN NOT EXISTS(SELECT 1 FROM claim_all_fee_assessment_unit u WHERE u.assessment_id=v_id)
 OR EXISTS(SELECT 1 FROM claim_all_fee_assessment_unit u WHERE u.assessment_id=v_id AND u.item_id=v_item AND u.ordinal=v_ordinal)
 ELSE true END
$$;
