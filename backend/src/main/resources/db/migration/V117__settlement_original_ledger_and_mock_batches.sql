SET search_path TO marketplace;

CREATE TABLE settlement_policy (
 version varchar(40) PRIMARY KEY CHECK(version='SETTLEMENT_V1'),
 rate_basis_points integer NOT NULL CHECK(rate_basis_points=500),
 effective_at timestamptz NOT NULL DEFAULT clock_timestamp(),zone varchar(40) NOT NULL CHECK(zone='Asia/Seoul')
);
INSERT INTO settlement_policy(version,rate_basis_points,zone) VALUES('SETTLEMENT_V1',500,'Asia/Seoul');
CREATE TABLE order_settlement_policy (
 order_id uuid PRIMARY KEY REFERENCES purchase_order(id),version varchar(40) NOT NULL REFERENCES settlement_policy(version)
);
CREATE FUNCTION capture_order_settlement_policy() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 INSERT INTO order_settlement_policy(order_id,version) SELECT NEW.id,version FROM settlement_policy;
 RETURN NEW;
END $$;
CREATE TRIGGER order_settlement_policy AFTER INSERT ON purchase_order FOR EACH ROW EXECUTE FUNCTION capture_order_settlement_policy();
CREATE FUNCTION order_settlement_policy_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF pg_trigger_depth()<2 THEN RAISE EXCEPTION 'ORDER_INSERT_POLICY_SNAPSHOT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER order_policy_from_insert BEFORE INSERT ON order_settlement_policy FOR EACH ROW EXECUTE FUNCTION order_settlement_policy_insert_guard();

CREATE TABLE settlement_ledger (
 id uuid PRIMARY KEY,policy_version varchar(40) NOT NULL REFERENCES settlement_policy(version),
 store_id uuid NOT NULL REFERENCES seller_store(id),order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 item_id uuid,ordinal integer,cause_id uuid NOT NULL,payment_id uuid NOT NULL,refund_attempt_id uuid,
 kind varchar(30) NOT NULL CHECK(kind IN('PRODUCT','REFUND','COMMISSION','COMMISSION_REVERSAL','SHIPPING','SHIPPING_REFUND','RECOVERY_FEE')),
 amount bigint NOT NULL,fee bigint NOT NULL,occurred_at timestamptz NOT NULL,eligible_at timestamptz NOT NULL CHECK(eligible_at>=occurred_at),source_key varchar(250) NOT NULL UNIQUE,
 fingerprint varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 FOREIGN KEY(payment_id,order_id) REFERENCES payment_attempt(id,order_id),
 FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal),
 FOREIGN KEY(refund_attempt_id) REFERENCES payment_refund_attempt(id),
 CONSTRAINT settlement_semantic_identity UNIQUE NULLS NOT DISTINCT(seller_order_id,item_id,ordinal,cause_id,kind),
 CHECK(source_key=seller_order_id::text||'|'||COALESCE(item_id::text,'null')||'|'||COALESCE(ordinal::text,'null')||'|'||kind||'|'||cause_id::text),
 CHECK((item_id IS NULL)=(ordinal IS NULL)),
 CHECK((kind IN('PRODUCT','REFUND','COMMISSION','COMMISSION_REVERSAL'))=(item_id IS NOT NULL)),
 CHECK((kind IN('PRODUCT','SHIPPING','RECOVERY_FEE') AND amount>=0 AND fee=0)
 OR(kind IN('REFUND','SHIPPING_REFUND') AND amount<=0 AND fee=0 AND refund_attempt_id IS NOT NULL)
 OR(kind='COMMISSION' AND amount=0 AND fee>=0)
 OR(kind='COMMISSION_REVERSAL' AND amount=0 AND fee<=0 AND refund_attempt_id IS NOT NULL))
);
CREATE TABLE settlement_batch (
 id uuid PRIMARY KEY,week_start date NOT NULL UNIQUE,period_start timestamptz NOT NULL,period_end timestamptz NOT NULL,
 state varchar(20) NOT NULL CHECK(state IN('DRAFT','REVIEWED','PAID_MOCK')),version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 gross bigint NOT NULL DEFAULT 0,fee bigint NOT NULL DEFAULT 0,net bigint NOT NULL DEFAULT 0 CHECK(net=gross-fee),
 held jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(held)='array'),
 created_by uuid NOT NULL REFERENCES identity_user(id),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 reviewed_by uuid REFERENCES identity_user(id),reviewed_at timestamptz,paid_at timestamptz,
 CHECK(extract(isodow FROM week_start)=1),
 CHECK(period_start=week_start::timestamp AT TIME ZONE 'Asia/Seoul' AND period_end=(week_start+7)::timestamp AT TIME ZONE 'Asia/Seoul'),
 CHECK((state='DRAFT' AND reviewed_by IS NULL AND reviewed_at IS NULL AND paid_at IS NULL)
 OR(state='REVIEWED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND paid_at IS NULL)
 OR(state='PAID_MOCK' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND paid_at IS NOT NULL))
);
CREATE TABLE settlement_batch_item (
 batch_id uuid NOT NULL REFERENCES settlement_batch(id),ledger_id uuid NOT NULL UNIQUE REFERENCES settlement_ledger(id),
 eligible_at timestamptz NOT NULL,hold_evidence jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(hold_evidence)='array'),
 reservation_evidence jsonb NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(reservation_evidence)='array'),
 PRIMARY KEY(batch_id,ledger_id)
);
CREATE TABLE settlement_command (
 request_key uuid PRIMARY KEY,batch_id uuid NOT NULL REFERENCES settlement_batch(id),action varchar(20) NOT NULL,
 expected_version bigint,actor_id uuid NOT NULL REFERENCES identity_user(id),fingerprint varchar(64) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE settlement_mock_payment (
 id uuid PRIMARY KEY,batch_id uuid NOT NULL UNIQUE REFERENCES settlement_batch(id),amount bigint NOT NULL CHECK(amount>=0),
 actor_id uuid NOT NULL REFERENCES identity_user(id),occurred_at timestamptz NOT NULL
);
CREATE TABLE settlement_batch_revision (
 batch_id uuid NOT NULL REFERENCES settlement_batch(id),version bigint NOT NULL,state varchar(20) NOT NULL,
 gross bigint NOT NULL,fee bigint NOT NULL,net bigint NOT NULL,held jsonb NOT NULL,ledger_ids uuid[] NOT NULL,eligibility jsonb NOT NULL,
 archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(batch_id,version)
);
CREATE FUNCTION settlement_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'SETTLEMENT_APPEND_ONLY' USING ERRCODE='23514';END $$;
CREATE TRIGGER settlement_policy_immutable BEFORE UPDATE OR DELETE ON settlement_policy FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER order_settlement_policy_immutable BEFORE UPDATE OR DELETE ON order_settlement_policy FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER settlement_ledger_immutable BEFORE UPDATE OR DELETE ON settlement_ledger FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER settlement_item_immutable BEFORE UPDATE ON settlement_batch_item FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER settlement_command_immutable BEFORE UPDATE OR DELETE ON settlement_command FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER settlement_payment_immutable BEFORE UPDATE OR DELETE ON settlement_mock_payment FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE TRIGGER settlement_revision_immutable BEFORE UPDATE OR DELETE ON settlement_batch_revision FOR EACH ROW EXECUTE FUNCTION settlement_immutable();
CREATE FUNCTION archive_settlement_batch(v_id uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 INSERT INTO settlement_batch_revision(batch_id,version,state,gross,fee,net,held,ledger_ids,eligibility)
 SELECT b.id,b.version,b.state,b.gross,b.fee,b.net,b.held,
 ARRAY(SELECT i.ledger_id FROM settlement_batch_item i WHERE i.batch_id=b.id ORDER BY i.ledger_id),
 COALESCE((SELECT jsonb_agg(jsonb_build_object('ledgerId',i.ledger_id,'eligibleAt',i.eligible_at,'holds',i.hold_evidence,'reservations',i.reservation_evidence) ORDER BY i.ledger_id) FROM settlement_batch_item i WHERE i.batch_id=b.id),'[]'::jsonb)
 FROM settlement_batch b WHERE b.id=v_id ON CONFLICT(batch_id,version) DO NOTHING;
END $$;
-- Canonical order-owned, irreversible hold intervals. A later shipping reevaluation is not a hold.
CREATE FUNCTION settlement_hold_facts(v_order uuid,v_seller uuid)
RETURNS TABLE(id uuid,acquired_at timestamptz,released_at timestamptz) LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT DISTINCT h.id,h.acquired_at,e.occurred_at FROM order_unit_hold h
 JOIN order_hold_unit hu ON hu.hold_id=h.id JOIN order_item i ON i.id=hu.item_id
 JOIN order_lifecycle_event e ON e.cause_id=h.id AND e.action=h.state
 WHERE h.order_id=v_order AND i.seller_order_id=v_seller AND h.state IN('RELEASED','REFUNDED')
$$;
CREATE FUNCTION settlement_refund_intervals(v_payment uuid,v_seller uuid)
RETURNS TABLE(id uuid,acquired_at timestamptz,released_at timestamptz) LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT p.id,p.created_at,CASE WHEN p.state IN('SUCCEEDED','FAILED','WITHDRAWN') THEN greatest(p.result_at,
  (SELECT max(e.occurred_at) FROM payment_refund_event e WHERE e.attempt_id=p.id AND e.action=p.state)) END
 FROM payment_refund_attempt p WHERE p.payment_id=v_payment AND p.seller_order_id=v_seller
$$;
CREATE FUNCTION settlement_claim_intervals(v_seller uuid)
RETURNS TABLE(id uuid,acquired_at timestamptz,released_at timestamptz) LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT a.id,a.accepted_at,CASE WHEN a.state='WITHDRAWN' THEN w.withdrawn_at
  WHEN a.state='APPLIED' AND a.additional_due=0 THEN app.applied_at END
 FROM claim_fee_assessment a LEFT JOIN claim_fee_application app ON app.assessment_id=a.id
 LEFT JOIN claim_fee_withdrawal w ON w.assessment_id=a.id WHERE a.seller_order_id=v_seller
$$;
CREATE FUNCTION settlement_reservation_facts(v_payment uuid,v_seller uuid)
RETURNS TABLE(kind text,id uuid,acquired_at timestamptz,released_at timestamptz) LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
 SELECT 'REFUND'::text,r.* FROM settlement_refund_intervals(v_payment,v_seller) r
 UNION ALL SELECT 'CLAIM_FEE'::text,r.* FROM settlement_claim_intervals(v_seller) r
$$;
-- Fixed economic base plus actual overlapping/continuous holds. Ordinary unprocessed prior weeks never move.
CREATE FUNCTION settlement_readiness(v_ledger uuid)
RETURNS TABLE(eligible_at timestamptz,hold_ids uuid[],reservation_keys text[]) LANGUAGE plpgsql STABLE SET search_path FROM CURRENT AS $$
DECLARE l settlement_ledger%ROWTYPE;v_boundary timestamptz;v_ready timestamptz;v_next timestamptz;v_reservation record;
BEGIN
 SELECT * INTO l FROM settlement_ledger WHERE id=v_ledger;
 IF NOT FOUND THEN RAISE EXCEPTION 'SETTLEMENT_LEDGER_REQUIRED' USING ERRCODE='23514';END IF;
 v_boundary=(date_trunc('week',l.eligible_at AT TIME ZONE 'Asia/Seoul')+interval '1 week') AT TIME ZONE 'Asia/Seoul';
 v_ready=l.eligible_at;
 LOOP
  SELECT greatest(v_ready,max(h.released_at)) INTO v_next FROM (
   SELECT acquired_at,released_at FROM settlement_hold_facts(l.order_id,l.seller_order_id)
   UNION ALL SELECT acquired_at,released_at FROM settlement_reservation_facts(l.payment_id,l.seller_order_id)
  ) h WHERE h.released_at>l.eligible_at AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready);
  EXIT WHEN v_next=v_ready;v_ready=v_next;
 END LOOP;
 IF EXISTS(SELECT 1 FROM order_unit_hold h JOIN order_hold_unit hu ON hu.hold_id=h.id
  JOIN order_item i ON i.id=hu.item_id WHERE h.order_id=l.order_id AND i.seller_order_id=l.seller_order_id AND h.state='ACTIVE'
  AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready))
 THEN RAISE EXCEPTION 'SETTLEMENT_ACTIVE_HOLD_AT_CUTOFF' USING ERRCODE='23514';END IF;
 FOR v_reservation IN SELECT * FROM settlement_reservation_facts(l.payment_id,l.seller_order_id) LOOP
  IF(v_reservation.acquired_at<v_boundary OR v_reservation.acquired_at<=v_ready)
   AND(v_reservation.released_at IS NULL OR v_reservation.released_at>=v_boundary AND v_reservation.released_at>v_ready)
  THEN RAISE EXCEPTION 'SETTLEMENT_UNRESOLVED_RESERVATION_AT_CUTOFF' USING ERRCODE='23514';END IF;
 END LOOP;
 RETURN QUERY SELECT v_ready,ARRAY(SELECT h.id FROM settlement_hold_facts(l.order_id,l.seller_order_id) h
  WHERE h.released_at>l.eligible_at AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready) ORDER BY h.id::text),
  ARRAY(SELECT h.kind||'|'||h.id::text FROM settlement_reservation_facts(l.payment_id,l.seller_order_id) h
  WHERE h.released_at>l.eligible_at AND(h.acquired_at<v_boundary OR h.acquired_at<=v_ready) ORDER BY h.kind,h.id::text);
END $$;
CREATE FUNCTION settlement_shipping_time(v_seller uuid) RETURNS timestamptz LANGUAGE plpgsql STABLE SET search_path FROM CURRENT AS $$
DECLARE v_terminal timestamptz;v_decision timestamptz;v_refund timestamptz;v_state varchar(20);v_physical boolean;v_confirmed boolean;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM order_unit_lifecycle WHERE seller_order_id=v_seller)
 OR EXISTS(SELECT 1 FROM order_unit_lifecycle WHERE seller_order_id=v_seller AND state NOT IN('CONFIRMED','REFUNDED')) THEN RETURN NULL;END IF;
 SELECT max(greatest(c.confirmed_at,e.occurred_at,p.result_at)),bool_or(u.state='CONFIRMED') INTO v_terminal,v_confirmed
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
  SELECT current.state,min(d.occurred_at) INTO v_state,v_decision FROM claim_seller_shipping_current ptr
  JOIN claim_seller_shipping_decision current ON current.id=ptr.decision_id
  JOIN claim_seller_shipping_decision d ON d.seller_order_id=ptr.seller_order_id AND d.state=current.state AND d.amount=current.amount
  WHERE ptr.seller_order_id=v_seller AND current.state IN('RETAINED','REFUND_REQUIRED') GROUP BY current.state;
  IF v_decision IS NULL THEN RETURN NULL;END IF;
 END IF;
 SELECT max(p.result_at) INTO v_refund FROM (
  SELECT refund_attempt_id FROM order_cancellation_shipping WHERE seller_order_id=v_seller
  UNION ALL SELECT refund_attempt_id FROM order_aftercare_shipping WHERE seller_order_id=v_seller
 ) receipts JOIN payment_refund_attempt p ON p.id=receipts.refund_attempt_id AND p.state='SUCCEEDED' AND p.purpose='SHIPPING';
 IF (v_physical AND v_state='REFUND_REQUIRED' OR NOT v_physical AND NOT v_confirmed) AND v_refund IS NULL THEN RETURN NULL;END IF;
 RETURN greatest(v_terminal,v_decision,v_refund);
END $$;
CREATE FUNCTION verify_settlement_membership(v_batch uuid,v_ledger uuid,v_at timestamptz,v_evidence jsonb,v_reservations jsonb)
RETURNS void LANGUAGE plpgsql STABLE SET search_path FROM CURRENT AS $$
DECLARE b settlement_batch%ROWTYPE;l settlement_ledger%ROWTYPE;r record;v_ids uuid[];v_keys text[];
BEGIN
 SELECT * INTO b FROM settlement_batch WHERE id=v_batch;SELECT * INTO l FROM settlement_ledger WHERE id=v_ledger;
 SELECT * INTO r FROM settlement_readiness(v_ledger);
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
CREATE FUNCTION settlement_original_graph_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
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
  v_business=settlement_shipping_time(NEW.seller_order_id);
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
CREATE TRIGGER settlement_original_graph BEFORE INSERT ON settlement_ledger FOR EACH ROW EXECUTE FUNCTION settlement_original_graph_guard();
CREATE FUNCTION settlement_batch_write_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'SETTLEMENT_BATCH_PRESERVED' USING ERRCODE='23514';END IF;
 IF NEW.id<>OLD.id OR NEW.week_start<>OLD.week_start OR NEW.period_start<>OLD.period_start OR NEW.period_end<>OLD.period_end
 OR NEW.created_by<>OLD.created_by OR NEW.created_at<>OLD.created_at OR NEW.version<>OLD.version+1
 OR OLD.state='PAID_MOCK' OR OLD.state='REVIEWED' AND (
  NEW.state NOT IN('DRAFT','PAID_MOCK') OR NEW.gross<>OLD.gross OR NEW.fee<>OLD.fee OR NEW.net<>OLD.net OR NEW.held<>OLD.held
  OR NEW.state='PAID_MOCK' AND (NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at)
  OR NEW.state='DRAFT' AND (NEW.reviewed_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL OR NEW.paid_at IS NOT NULL
    OR EXISTS(SELECT 1 FROM settlement_mock_payment WHERE batch_id=OLD.id)))
 OR OLD.state='DRAFT' AND NEW.state NOT IN('DRAFT','REVIEWED')
 THEN RAISE EXCEPTION 'SETTLEMENT_BATCH_TRANSITION' USING ERRCODE='23514';END IF;
 PERFORM archive_settlement_batch(OLD.id);
 RETURN NEW;
END $$;
CREATE TRIGGER settlement_batch_preserved BEFORE UPDATE OR DELETE ON settlement_batch FOR EACH ROW EXECUTE FUNCTION settlement_batch_write_guard();
CREATE FUNCTION settlement_item_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM settlement_batch b WHERE b.id=NEW.batch_id AND b.state='DRAFT')
 THEN RAISE EXCEPTION 'SETTLEMENT_DRAFT_ITEM_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM verify_settlement_membership(NEW.batch_id,NEW.ledger_id,NEW.eligible_at,NEW.hold_evidence,NEW.reservation_evidence);
 PERFORM archive_settlement_batch(NEW.batch_id);RETURN NEW;
END $$;
CREATE TRIGGER settlement_item_valid BEFORE INSERT ON settlement_batch_item FOR EACH ROW EXECUTE FUNCTION settlement_item_guard();
CREATE FUNCTION settlement_item_remove_guard() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM settlement_batch b WHERE b.id=OLD.batch_id AND b.state='DRAFT'
 AND NOT EXISTS(SELECT 1 FROM settlement_mock_payment p WHERE p.batch_id=b.id))
 THEN RAISE EXCEPTION 'SETTLEMENT_DRAFT_REBUILD_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM archive_settlement_batch(OLD.batch_id);RETURN OLD;
END $$;
CREATE TRIGGER settlement_item_remove BEFORE DELETE ON settlement_batch_item FOR EACH ROW EXECUTE FUNCTION settlement_item_remove_guard();
CREATE FUNCTION verify_settlement_batch() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b settlement_batch%ROWTYPE;v_id uuid;v_gross numeric;v_fee numeric;v_count bigint;v_item record;
BEGIN
 IF TG_TABLE_NAME='settlement_batch' THEN v_id=NEW.id;
 ELSIF TG_OP='DELETE' THEN v_id=OLD.batch_id;ELSE v_id=NEW.batch_id;END IF;
 SELECT * INTO b FROM settlement_batch WHERE id=v_id;
 SELECT count(*),COALESCE(sum(l.amount),0),COALESCE(sum(l.fee),0) INTO v_count,v_gross,v_fee FROM settlement_batch_item i JOIN settlement_ledger l ON l.id=i.ledger_id WHERE i.batch_id=v_id;
 IF b.gross<>v_gross OR b.fee<>v_fee OR b.net<>v_gross-v_fee OR b.net<0
 OR v_count=0 AND (b.state<>'DRAFT' OR jsonb_array_length(b.held)=0 OR NOT EXISTS(SELECT 1 FROM settlement_batch_revision r WHERE r.batch_id=v_id AND cardinality(r.ledger_ids)>0))
 OR b.state='PAID_MOCK' AND NOT EXISTS(SELECT 1 FROM settlement_mock_payment p WHERE p.batch_id=v_id AND p.amount=b.net)
 OR b.state<>'PAID_MOCK' AND EXISTS(SELECT 1 FROM settlement_mock_payment p WHERE p.batch_id=v_id)
 THEN RAISE EXCEPTION 'SETTLEMENT_BATCH_RECONCILIATION' USING ERRCODE='23514';END IF;
 IF b.state IN('REVIEWED','PAID_MOCK') THEN
  FOR v_item IN SELECT * FROM settlement_batch_item WHERE batch_id=v_id LOOP
   PERFORM verify_settlement_membership(v_item.batch_id,v_item.ledger_id,v_item.eligible_at,v_item.hold_evidence,v_item.reservation_evidence);
  END LOOP;
 END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER settlement_batch_reconciled AFTER INSERT OR UPDATE ON settlement_batch DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_settlement_batch();
CREATE CONSTRAINT TRIGGER settlement_items_reconciled AFTER INSERT ON settlement_batch_item DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_settlement_batch();
CREATE CONSTRAINT TRIGGER settlement_removed_items_reconciled AFTER DELETE ON settlement_batch_item DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_settlement_batch();
CREATE CONSTRAINT TRIGGER settlement_payment_reconciled AFTER INSERT ON settlement_mock_payment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_settlement_batch();
