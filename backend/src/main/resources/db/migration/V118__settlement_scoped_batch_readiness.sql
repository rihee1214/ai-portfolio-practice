SET search_path TO marketplace;

-- Preserve every existing batch's readiness contract. Only an explicit rebuild upgrades a draft.
ALTER TABLE settlement_batch ADD COLUMN readiness_version smallint NOT NULL DEFAULT 1 CHECK (readiness_version IN (1,2));
ALTER TABLE settlement_batch ALTER COLUMN readiness_version SET DEFAULT 2;
ALTER TABLE settlement_batch_revision ADD COLUMN readiness_version smallint NOT NULL DEFAULT 1;

CREATE FUNCTION settlement_revision_policy() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 SELECT readiness_version INTO STRICT NEW.readiness_version FROM settlement_batch WHERE id=NEW.batch_id;
 RETURN NEW;
END $$;
CREATE TRIGGER settlement_revision_policy BEFORE INSERT ON settlement_batch_revision FOR EACH ROW EXECUTE FUNCTION settlement_revision_policy();

CREATE FUNCTION settlement_policy_transition() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF OLD.readiness_version<>1 OR NEW.readiness_version<>2 OR OLD.state<>'DRAFT' OR NEW.state<>'DRAFT' THEN
  RAISE EXCEPTION 'SETTLEMENT_DRAFT_POLICY_UPGRADE_REQUIRED' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER settlement_policy_transition BEFORE UPDATE OF readiness_version ON settlement_batch
 FOR EACH ROW WHEN (OLD.readiness_version IS DISTINCT FROM NEW.readiness_version) EXECUTE FUNCTION settlement_policy_transition();

-- Shared shipping remains subject to every interval. Missing unit attribution keeps the seller-wide hold.
CREATE FUNCTION settlement_reservation_affects(v_kind text,v_id uuid,v_item uuid,v_ordinal integer)
RETURNS boolean LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT CASE
 WHEN v_item IS NULL THEN true
 WHEN v_kind='REFUND' AND EXISTS(SELECT 1 FROM payment_refund_attempt p WHERE p.id=v_id AND p.purpose='SHIPPING') THEN false
 WHEN v_kind='REFUND' THEN NOT EXISTS(SELECT 1 FROM claim_financial_refund_binding b JOIN claim_financial_intent_unit u ON u.intent_id=b.intent_id WHERE b.attempt_id=v_id)
 OR EXISTS(SELECT 1 FROM claim_financial_refund_binding b JOIN claim_financial_intent_unit u ON u.intent_id=b.intent_id WHERE b.attempt_id=v_id AND u.item_id=v_item AND u.ordinal=v_ordinal)
 WHEN v_kind='CLAIM_FEE' THEN NOT EXISTS(SELECT 1 FROM claim_fee_assessment_unit u WHERE u.assessment_id=v_id)
 OR EXISTS(SELECT 1 FROM claim_fee_assessment_unit u WHERE u.assessment_id=v_id AND u.item_id=v_item AND u.ordinal=v_ordinal)
 ELSE true END
$$;
CREATE FUNCTION settlement_unit_readiness(v_ledger uuid)
RETURNS TABLE(eligible_at timestamptz,hold_ids uuid[],reservation_keys text[]) LANGUAGE plpgsql STABLE SET search_path FROM CURRENT AS $$
DECLARE l settlement_ledger%ROWTYPE;v_boundary timestamptz;v_ready timestamptz;v_next timestamptz;v_reservation record;
BEGIN
 SELECT * INTO l FROM settlement_ledger WHERE id=v_ledger;
 IF NOT FOUND THEN RAISE EXCEPTION 'SETTLEMENT_LEDGER_REQUIRED' USING ERRCODE='23514';END IF;
 v_boundary=(date_trunc('week',l.eligible_at AT TIME ZONE 'Asia/Seoul')+interval '1 week') AT TIME ZONE 'Asia/Seoul';
 v_ready=l.eligible_at;
 LOOP
  SELECT greatest(v_ready,max(h.released_at)) INTO v_next FROM (
   SELECT acquired_at,released_at FROM (SELECT f.* FROM settlement_hold_facts(l.order_id,l.seller_order_id) f WHERE l.item_id IS NULL OR EXISTS(
    SELECT 1 FROM order_hold_unit u WHERE u.hold_id=f.id AND u.item_id=l.item_id AND u.ordinal=l.ordinal)) matched_holds
   UNION ALL SELECT acquired_at,released_at FROM (SELECT f.* FROM settlement_reservation_facts(l.payment_id,l.seller_order_id) f WHERE settlement_reservation_affects(f.kind,f.id,l.item_id,l.ordinal)) matched_reservations
  ) h WHERE h.released_at>l.eligible_at AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready);
  EXIT WHEN v_next=v_ready;v_ready=v_next;
 END LOOP;
 IF EXISTS(SELECT 1 FROM order_unit_hold h JOIN order_hold_unit hu ON hu.hold_id=h.id
  JOIN order_item i ON i.id=hu.item_id WHERE h.order_id=l.order_id AND i.seller_order_id=l.seller_order_id AND h.state='ACTIVE' AND (l.item_id IS NULL OR (hu.item_id=l.item_id AND hu.ordinal=l.ordinal))
  AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready))
 THEN RAISE EXCEPTION 'SETTLEMENT_ACTIVE_HOLD_AT_CUTOFF' USING ERRCODE='23514';END IF;
 FOR v_reservation IN SELECT * FROM (SELECT f.* FROM settlement_reservation_facts(l.payment_id,l.seller_order_id) f WHERE settlement_reservation_affects(f.kind,f.id,l.item_id,l.ordinal)) matched_reservations LOOP
  IF(v_reservation.acquired_at<v_boundary OR v_reservation.acquired_at<=v_ready)
   AND(v_reservation.released_at IS NULL OR v_reservation.released_at>=v_boundary AND v_reservation.released_at>v_ready)
  THEN RAISE EXCEPTION 'SETTLEMENT_UNRESOLVED_RESERVATION_AT_CUTOFF' USING ERRCODE='23514';END IF;
 END LOOP;
 RETURN QUERY SELECT v_ready,ARRAY(SELECT h.id FROM (SELECT f.* FROM settlement_hold_facts(l.order_id,l.seller_order_id) f WHERE l.item_id IS NULL OR EXISTS(
    SELECT 1 FROM order_hold_unit u WHERE u.hold_id=f.id AND u.item_id=l.item_id AND u.ordinal=l.ordinal)) h
  WHERE h.released_at>l.eligible_at AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready) ORDER BY h.id::text),
  ARRAY(SELECT h.kind||'|'||h.id::text FROM (SELECT f.* FROM settlement_reservation_facts(l.payment_id,l.seller_order_id) f WHERE settlement_reservation_affects(f.kind,f.id,l.item_id,l.ordinal)) h
  WHERE h.released_at>l.eligible_at AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready) ORDER BY h.kind,h.id::text);
END $$;
CREATE OR REPLACE FUNCTION verify_settlement_membership(v_batch uuid,v_ledger uuid,v_at timestamptz,v_evidence jsonb,v_reservations jsonb)
RETURNS void LANGUAGE plpgsql STABLE SET search_path FROM CURRENT AS $$
DECLARE b settlement_batch%ROWTYPE;l settlement_ledger%ROWTYPE;r record;v_ids uuid[];v_keys text[];
BEGIN
 SELECT * INTO b FROM settlement_batch WHERE id=v_batch;SELECT * INTO l FROM settlement_ledger WHERE id=v_ledger;
 IF b.readiness_version=2 THEN
  SELECT * INTO r FROM settlement_unit_readiness(v_ledger);
 ELSE
  SELECT * INTO r FROM settlement_readiness(v_ledger);
 END IF;
 SELECT ARRAY(SELECT (entry->>'id')::uuid FROM jsonb_array_elements(v_evidence) entry ORDER BY (entry->>'id')) INTO v_ids;
 SELECT ARRAY(SELECT (entry->>'kind')||'|'||((entry->>'id')::uuid)::text FROM jsonb_array_elements(v_reservations) entry ORDER BY (entry->>'kind'),((entry->>'id')::uuid)::text) INTO v_keys;
 IF v_at IS DISTINCT FROM r.eligible_at OR v_at<b.period_start OR v_at>=b.period_end OR v_ids IS DISTINCT FROM r.hold_ids OR v_keys IS DISTINCT FROM r.reservation_keys
 OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_evidence) entry
  WHERE entry-ARRAY['id','acquiredAt','releasedAt']<>'{}'::jsonb OR NOT EXISTS(
   SELECT 1 FROM settlement_hold_facts(l.order_id,l.seller_order_id) h WHERE h.id=(entry->>'id')::uuid
   AND h.acquired_at=(entry->>'acquiredAt')::timestamptz AND h.released_at=(entry->>'releasedAt')::timestamptz))
 OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_reservations) entry
  WHERE entry-ARRAY['id','kind','acquiredAt','releasedAt']<>'{}'::jsonb OR NOT EXISTS(
   SELECT 1 FROM settlement_reservation_facts(l.payment_id,l.seller_order_id) h WHERE h.id=(entry->>'id')::uuid AND h.kind=entry->>'kind'
   AND h.acquired_at=(entry->>'acquiredAt')::timestamptz AND h.released_at=(entry->>'releasedAt')::timestamptz))
 THEN RAISE EXCEPTION 'SETTLEMENT_PAYMENT_READINESS_GRAPH' USING ERRCODE='23514';END IF;
END $$;
