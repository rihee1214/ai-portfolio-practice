CREATE TABLE order_return_completion (
 return_id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL UNIQUE,cause_id uuid NOT NULL UNIQUE,
 payment_id uuid NOT NULL,item_attempt_id uuid NOT NULL UNIQUE,reference varchar(200) NOT NULL,fingerprint varchar(64) NOT NULL,
 UNIQUE(return_id,order_id,seller_order_id),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 FOREIGN KEY(hold_id,order_id) REFERENCES order_unit_hold(id,order_id),
 FOREIGN KEY(payment_id,order_id) REFERENCES payment_attempt(id,order_id)
);
CREATE TABLE order_return_completion_unit (
 return_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 PRIMARY KEY(item_id,ordinal),
 FOREIGN KEY(return_id,order_id,seller_order_id) REFERENCES order_return_completion(return_id,order_id,seller_order_id),
 FOREIGN KEY(item_id,ordinal,order_id) REFERENCES order_unit_lifecycle(item_id,ordinal,order_id)
);
CREATE TABLE order_shipping_decision (
 decision_id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,revision bigint NOT NULL CHECK(revision>0),
 state varchar(20) NOT NULL CHECK(state IN ('PENDING','RETAINED','REFUND_REQUIRED')),reason varchar(40) NOT NULL,
 original_shipping bigint NOT NULL CHECK(original_shipping>=0),fingerprint varchar(64) NOT NULL,units jsonb NOT NULL,
 UNIQUE(seller_order_id,revision),FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id)
);
CREATE TABLE order_shipping_current (
 seller_order_id uuid PRIMARY KEY REFERENCES seller_order(id),decision_id uuid NOT NULL UNIQUE REFERENCES order_shipping_decision(decision_id)
);
CREATE TABLE order_aftercare_shipping (
 seller_order_id uuid PRIMARY KEY,order_id uuid NOT NULL,cause_id uuid NOT NULL,payment_id uuid NOT NULL,
 refund_attempt_id uuid NOT NULL UNIQUE,amount bigint NOT NULL CHECK(amount>=0),reference varchar(200) NOT NULL,fingerprint varchar(64) NOT NULL,
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),FOREIGN KEY(payment_id,order_id) REFERENCES payment_attempt(id,order_id)
);
CREATE TABLE claim_shipping_result (
 attempt_id uuid NOT NULL REFERENCES claim_return_shipping_binding(attempt_id),result_version bigint NOT NULL CHECK(result_version>0),
 seller_order_id uuid NOT NULL REFERENCES claim_shipping_obligation(seller_order_id),intent_id uuid NOT NULL REFERENCES claim_financial_intent(id),
 result_state varchar(16) NOT NULL CHECK(result_state IN ('SUCCEEDED','FAILED','UNKNOWN')),reference varchar(200),result_at timestamptz,
 fingerprint varchar(64) NOT NULL,job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,occurred_at timestamptz NOT NULL,
 PRIMARY KEY(attempt_id,result_version)
);
CREATE TABLE claim_return_completion (
 return_id uuid PRIMARY KEY REFERENCES claim_return_item_ready(return_id),decision_id uuid NOT NULL REFERENCES claim_seller_shipping_decision(id),
 workflow_revision bigint NOT NULL CHECK(workflow_revision>0),fingerprint varchar(64) NOT NULL,receipt jsonb NOT NULL,
 job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,occurred_at timestamptz NOT NULL,
 FOREIGN KEY(return_id,workflow_revision) REFERENCES claim_return_history(return_id,version) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE claim_return_command DROP CONSTRAINT claim_return_command_action_check;
ALTER TABLE claim_return_command ADD CONSTRAINT claim_return_command_action_check CHECK(action IN ('APPROVED','REJECTED','WITHDRAWN','COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT','REFUND_PENDING','DUE_PENDING','COMPLETED'));
ALTER TABLE order_aftercare_closure DROP CONSTRAINT order_aftercare_closure_reason_check;
ALTER TABLE order_aftercare_closure ADD CONSTRAINT order_aftercare_closure_reason_check CHECK(reason IN ('ALL_UNSHIPPED_CANCEL','ALL_CONFIRMED','MIXED_CONFIRMED_CANCEL','ALL_RETURNED','MIXED_AFTERCARE'));
ALTER TABLE order_aftercare_closure DROP CONSTRAINT order_aftercare_closure_check;
ALTER TABLE order_aftercare_closure ADD CONSTRAINT order_aftercare_closure_check CHECK(
 (reason='ALL_CONFIRMED' AND payment_id IS NULL AND target='CLOSED')
 OR(reason='ALL_UNSHIPPED_CANCEL' AND payment_id IS NOT NULL AND target='CANCELED')
 OR(reason IN ('MIXED_CONFIRMED_CANCEL','ALL_RETURNED','MIXED_AFTERCARE') AND payment_id IS NOT NULL AND target='CLOSED'));
DO $$ DECLARE tbl text;BEGIN
 FOREACH tbl IN ARRAY ARRAY['order_return_completion','order_return_completion_unit','order_shipping_decision','order_aftercare_shipping','claim_shipping_result','claim_return_completion'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_receipt BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION return_application_immutable()',tbl);
 END LOOP;
END $$;

CREATE FUNCTION verify_return_completion(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r marketplace.claim_return_request%ROWTYPE;c marketplace.claim_return_completion%ROWTYPE;ready marketplace.claim_return_item_ready%ROWTYPE;
BEGIN
 SELECT * INTO r FROM marketplace.claim_return_request WHERE id=v_id;
 SELECT * INTO c FROM marketplace.claim_return_completion WHERE return_id=v_id;
 IF NOT FOUND THEN
  IF r.state='COMPLETED' OR EXISTS(SELECT 1 FROM marketplace.order_return_completion WHERE return_id=v_id)
   OR EXISTS(SELECT 1 FROM marketplace.order_unit_hold WHERE id=r.hold_id AND state='REFUNDED') THEN RAISE EXCEPTION 'ACTUAL_RETURN_COMPLETION_REQUIRED' USING ERRCODE='23514';END IF;
  RETURN;
 END IF;
 SELECT * INTO STRICT ready FROM marketplace.claim_return_item_ready WHERE return_id=v_id;
 PERFORM marketplace.verify_return_item_ready(v_id);
 IF r.state<>'COMPLETED' OR r.version<>c.workflow_revision OR NOT EXISTS(
  SELECT 1 FROM marketplace.order_return_completion o
  JOIN marketplace.order_unit_hold h ON h.id=o.hold_id AND h.order_id=o.order_id AND h.cause_id=o.cause_id
  JOIN marketplace.payment_refund_attempt p ON p.id=o.item_attempt_id
  JOIN marketplace.claim_return_command cmd ON cmd.return_id=r.id AND cmd.version=c.workflow_revision
  JOIN marketplace.claim_return_history hist ON hist.return_id=r.id AND hist.version=c.workflow_revision
  JOIN marketplace.claim_seller_shipping_decision d ON d.id=c.decision_id
  JOIN marketplace.job j ON j.id=c.job_id
  WHERE o.return_id=r.id AND o.order_id=r.order_id AND o.seller_order_id=r.seller_order_id AND o.hold_id=r.hold_id AND o.cause_id=r.cause_id
   AND h.kind='RETURN' AND h.state='REFUNDED' AND h.outcome=o.reference
   AND p.id=ready.attempt_id AND p.claim_id=r.id AND p.order_id=r.order_id AND p.seller_order_id=r.seller_order_id AND p.purpose='ITEM' AND p.state='SUCCEEDED' AND p.result_reference=o.reference AND p.payment_id=o.payment_id
   AND cmd.action='COMPLETED' AND cmd.fingerprint=c.fingerprint AND cmd.receipt=c.receipt
   AND hist.action='COMPLETED' AND hist.actor='RECORDED_WORKER' AND hist.reason='RETURN_COMPLETED' AND hist.occurred_at=c.occurred_at
   AND d.seller_order_id=r.seller_order_id AND d.order_id=r.order_id
   AND (d.state IN ('PENDING','RETAINED') OR EXISTS(SELECT 1 FROM marketplace.order_aftercare_shipping s WHERE s.seller_order_id=r.seller_order_id AND s.order_id=r.order_id))
   AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=r.seller_order_id::text AND j.status='RUNNING' AND j.owner_token=c.owner_token
 ) OR EXISTS(SELECT item_id,ordinal FROM marketplace.claim_return_unit WHERE return_id=v_id EXCEPT SELECT item_id,ordinal FROM marketplace.order_return_completion_unit WHERE return_id=v_id)
 OR EXISTS(SELECT item_id,ordinal FROM marketplace.order_return_completion_unit WHERE return_id=v_id EXCEPT SELECT item_id,ordinal FROM marketplace.claim_return_unit WHERE return_id=v_id)
 OR EXISTS(SELECT 1 FROM marketplace.order_return_completion_unit u JOIN marketplace.order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal WHERE u.return_id=v_id AND (l.state<>'REFUNDED' OR l.refund_cause_id IS DISTINCT FROM r.cause_id))
 OR NOT EXISTS(SELECT 1 FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id WHERE j.type='RETURN_RECORDED.v1' AND j.cause_key=v_id::text AND j.payload=v_id::text AND j.dedupe_key='return-command:'||v_id::text||':'||c.workflow_revision::text)
 THEN RAISE EXCEPTION 'ACTUAL_RETURN_COMPLETION_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION return_completion_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;
BEGIN
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='order_unit_hold' THEN SELECT id INTO v_id FROM marketplace.claim_return_request WHERE hold_id=NEW.id;
 ELSE v_id:=NEW.return_id;END IF;
 IF v_id IS NOT NULL THEN PERFORM marketplace.verify_return_completion(v_id);END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_completion_receipt_graph AFTER INSERT ON claim_return_completion DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_completion_graph_guard();
CREATE CONSTRAINT TRIGGER return_completion_order_graph AFTER INSERT ON order_return_completion DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_completion_graph_guard();
CREATE CONSTRAINT TRIGGER return_completion_units_graph AFTER INSERT ON order_return_completion_unit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_completion_graph_guard();
CREATE CONSTRAINT TRIGGER return_completion_state_graph AFTER UPDATE ON claim_return_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_completion_graph_guard();
CREATE CONSTRAINT TRIGGER return_completion_hold_graph AFTER UPDATE ON order_unit_hold DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_completion_graph_guard();

CREATE FUNCTION shipping_result_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE x marketplace.claim_shipping_result%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='claim_shipping_result' THEN SELECT * INTO STRICT x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.attempt_id AND result_version=NEW.result_version;
 ELSE SELECT * INTO x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.refund_attempt_id AND result_state='SUCCEEDED' ORDER BY result_version DESC LIMIT 1;IF NOT FOUND THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_REQUIRED' USING ERRCODE='23514';END IF;END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_shipping_binding b
  JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=b.seller_order_id
  JOIN marketplace.payment_refund_attempt p ON p.id=b.attempt_id
  JOIN marketplace.job j ON j.id=x.job_id
  WHERE b.attempt_id=x.attempt_id AND b.intent_id=x.intent_id AND b.seller_order_id=x.seller_order_id
   AND o.claim_id=b.intent_id AND o.refund_attempt_id=p.id AND o.amount=p.amount AND p.purpose='SHIPPING'
   AND p.version=x.result_version AND p.state=x.result_state AND p.result_reference IS NOT DISTINCT FROM x.reference AND p.result_at IS NOT DISTINCT FROM x.result_at
   AND j.type='REFUND_RESULT_RECORDED.v1' AND j.cause_key=p.id::text AND j.status='RUNNING' AND j.owner_token=x.owner_token
   AND (x.result_state<>'SUCCEEDED' OR(o.state='SUCCEEDED' AND EXISTS(SELECT 1 FROM marketplace.order_aftercare_shipping s WHERE s.seller_order_id=o.seller_order_id AND s.order_id=o.order_id AND s.refund_attempt_id=p.id AND s.payment_id=p.payment_id AND s.cause_id=o.claim_id AND s.amount=p.amount AND s.reference=p.result_reference)))
 ) THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER shipping_result_receipt_graph AFTER INSERT ON claim_shipping_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_result_graph_guard();
CREATE CONSTRAINT TRIGGER shipping_settlement_receipt_graph AFTER INSERT ON order_aftercare_shipping DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_result_graph_guard();

CREATE FUNCTION shipping_projection_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;d marketplace.claim_seller_shipping_decision%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='order_shipping_decision' THEN target:=NEW.decision_id;
 ELSIF TG_TABLE_NAME='claim_seller_shipping_decision' THEN target:=NEW.id;
 ELSE target:=NEW.decision_id;END IF;
 SELECT * INTO STRICT d FROM marketplace.claim_seller_shipping_decision WHERE id=target;
 IF NOT EXISTS(SELECT 1 FROM marketplace.order_shipping_decision p JOIN marketplace.seller_order s ON s.id=p.seller_order_id AND s.order_id=p.order_id
  WHERE p.decision_id=d.id AND p.seller_order_id=d.seller_order_id AND p.order_id=d.order_id AND p.revision=d.revision
   AND p.state=d.state AND p.reason=d.reason AND p.fingerprint=d.fingerprint AND p.original_shipping=s.shipping_base-s.shipping_discount
   AND jsonb_array_length(p.units)=(SELECT count(*) FROM marketplace.order_unit_lifecycle WHERE seller_order_id=d.seller_order_id)
 ) OR EXISTS(SELECT 1 FROM marketplace.order_shipping_current o FULL JOIN marketplace.claim_seller_shipping_current c ON c.seller_order_id=o.seller_order_id WHERE COALESCE(o.seller_order_id,c.seller_order_id)=d.seller_order_id AND o.decision_id IS DISTINCT FROM c.decision_id)
 THEN RAISE EXCEPTION 'MATCHING_ORDER_SHIPPING_PROJECTION_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER claim_shipping_projection_graph AFTER INSERT ON claim_seller_shipping_decision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_projection_guard();
CREATE CONSTRAINT TRIGGER order_shipping_projection_graph AFTER INSERT ON order_shipping_decision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_projection_guard();
CREATE CONSTRAINT TRIGGER claim_shipping_current_graph AFTER INSERT OR UPDATE ON claim_seller_shipping_current DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_projection_guard();
CREATE CONSTRAINT TRIGGER order_shipping_current_graph AFTER INSERT OR UPDATE ON order_shipping_current DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shipping_projection_guard();

-- Preserve the approved active finance contracts; completion needs its own terminal proof.
ALTER FUNCTION verify_return_financial_consent(uuid) RENAME TO verify_return_financial_consent_before_completion;
CREATE FUNCTION verify_return_financial_consent(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_request WHERE id=v_id AND state='COMPLETED') THEN PERFORM marketplace.verify_return_completion(v_id);
 ELSE PERFORM marketplace.verify_return_financial_consent_before_completion(v_id);END IF;
END $$;
ALTER FUNCTION verify_return_financial_operation(uuid) RENAME TO verify_return_financial_operation_before_completion;
CREATE FUNCTION verify_return_financial_operation(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_request WHERE id=v_id AND state='COMPLETED') THEN PERFORM marketplace.verify_return_completion(v_id);
 ELSE PERFORM marketplace.verify_return_financial_operation_before_completion(v_id);END IF;
END $$;


-- Active physical projection is unchanged; completed requests require the new actual proof.
CREATE OR REPLACE FUNCTION return_collection_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;v_request marketplace.claim_return_request%ROWTYPE;v_command marketplace.claim_return_command%ROWTYPE;
 v_lost boolean;v_ready boolean;v_financed boolean;
BEGIN
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='payment_refund_attempt' THEN v_id:=NEW.claim_id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN v_id:=NEW.intent_id;
 ELSE v_id:=NEW.return_id;END IF;
 SELECT * INTO v_request FROM marketplace.claim_return_request WHERE id=v_id;
 IF NOT FOUND THEN RETURN NULL;END IF;
 IF v_request.state='COMPLETED' THEN PERFORM marketplace.verify_return_completion(v_id);RETURN NULL;END IF;
 v_financed:=EXISTS(SELECT 1 FROM marketplace.claim_return_financial_consent c WHERE c.return_id=v_id);
 IF v_financed OR v_request.state IN ('REFUND_PENDING','DUE_PENDING') THEN PERFORM marketplace.verify_return_financial_consent(v_id);END IF;
 IF NOT v_financed AND v_request.state IN ('REQUESTED','APPROVED','COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') AND (
  EXISTS(SELECT 1 FROM marketplace.payment_refund_attempt a WHERE a.claim_id=v_id)
  OR EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.intent_id=v_id)
 ) THEN RAISE EXCEPTION 'RETURN_PHYSICAL_FACT_IS_NOT_FINANCIAL_CONSENT' USING ERRCODE='23514';END IF;
 IF v_request.state NOT IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT','REFUND_PENDING','DUE_PENDING') THEN
  IF EXISTS(SELECT 1 FROM marketplace.claim_return_collection c WHERE c.return_id=v_id)
   OR EXISTS(SELECT 1 FROM marketplace.claim_return_inspection i WHERE i.return_id=v_id) THEN RAISE EXCEPTION 'RETURN_PHYSICAL_STATE_REQUIRED' USING ERRCODE='23514';END IF;
  RETURN NULL;
 END IF;
 SELECT COALESCE(bool_or(COALESCE(c.status,'PENDING')='LOST_REPORTED'),false),
  COALESCE(bool_and(COALESCE(c.status,'PENDING')='RECEIVED' AND i.inspection_id IS NOT NULL),false)
 INTO v_lost,v_ready
 FROM marketplace.claim_return_unit u
 LEFT JOIN LATERAL(SELECT c.status FROM marketplace.claim_return_collection c WHERE c.return_id=u.return_id AND c.item_id=u.item_id AND c.ordinal=u.ordinal ORDER BY c.version DESC LIMIT 1)c ON true
 LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=u.return_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal
 WHERE u.return_id=v_id;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_collection c WHERE c.return_id=v_id)
  OR (NOT v_financed AND v_request.state IS DISTINCT FROM (CASE WHEN v_lost THEN 'REVIEW_REQUIRED' WHEN v_ready THEN 'AWAITING_CONSENT' ELSE 'COLLECTING' END)) THEN
  RAISE EXCEPTION 'RETURN_CURRENT_PHYSICAL_FACT_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_collection c LEFT JOIN marketplace.claim_return_command x ON x.return_id=c.return_id AND x.version=c.version
  WHERE c.return_id=v_id AND(x.action NOT IN ('COLLECTING','REVIEW_REQUIRED') OR x.return_id IS NULL)) THEN RAISE EXCEPTION 'RETURN_COLLECTION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_inspection i LEFT JOIN marketplace.claim_return_command x ON x.return_id=i.return_id AND x.version=i.version
  WHERE i.return_id=v_id AND(x.action NOT IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') OR x.return_id IS NULL)) THEN RAISE EXCEPTION 'RETURN_INSPECTION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 FOR v_command IN SELECT * FROM marketplace.claim_return_command WHERE return_id=v_id AND action IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') LOOP
  IF jsonb_typeof(v_command.receipt->'physical') IS DISTINCT FROM 'array'
   OR jsonb_array_length(v_command.receipt->'physical')<>(SELECT count(*) FROM marketplace.claim_return_unit WHERE return_id=v_id)
   OR (SELECT count(DISTINCT(j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_command.receipt->'physical')j(value))<>(SELECT count(*) FROM marketplace.claim_return_unit WHERE return_id=v_id)
   OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_command.receipt->'physical')j(value)
    LEFT JOIN marketplace.claim_return_unit u ON u.return_id=v_id AND u.item_id::text=j.value->>'itemId' AND u.ordinal::text=j.value->>'ordinal'
    LEFT JOIN LATERAL(SELECT c.id,c.status FROM marketplace.claim_return_collection c WHERE c.return_id=u.return_id AND c.item_id=u.item_id AND c.ordinal=u.ordinal AND c.version<=v_command.version ORDER BY c.version DESC LIMIT 1)c ON true
    LEFT JOIN LATERAL(SELECT x.inspection_id,x.condition,x.responsibility,x.restitution_id FROM marketplace.claim_return_inspection_unit x JOIN marketplace.claim_return_inspection h ON h.id=x.inspection_id WHERE x.return_id=u.return_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal AND h.version<=v_command.version)i ON true
    WHERE u.item_id IS NULL OR j.value->>'collection' IS DISTINCT FROM COALESCE(c.status,'PENDING')
     OR j.value->>'collectionId' IS DISTINCT FROM c.id::text OR j.value->>'inspection' IS DISTINCT FROM COALESCE(i.condition,'NOT_INSPECTED')
     OR j.value->>'inspectionId' IS DISTINCT FROM i.inspection_id::text OR j.value->>'restitutionId' IS DISTINCT FROM i.restitution_id::text OR j.value->>'responsibility' IS DISTINCT FROM i.responsibility
   ) THEN RAISE EXCEPTION 'RETURN_PHYSICAL_RECEIPT_FACT_MISMATCH' USING ERRCODE='23514';END IF;
 END LOOP;
 RETURN NULL;
END $$;
