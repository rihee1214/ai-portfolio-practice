-- Typed E ITEM effect/readiness and order completion, independent of original seller shipping.
ALTER TABLE claim_exchange_request DROP CONSTRAINT claim_exchange_request_state_check;
ALTER TABLE claim_exchange_request ADD CONSTRAINT claim_exchange_request_state_check CHECK(state IN('REQUESTED','RESPONSIBILITY_PENDING','APPROVED','COLLECTING','READY','REVIEW_REQUIRED','DISPATCH_PENDING','DISPATCHED','COMPLETED','REJECTED','WITHDRAWN','EXPIRED','REFUND_PENDING','SETTLEMENT_PENDING','REFUNDED'));
CREATE TABLE claim_exchange_refund_effect (
 exchange_id uuid NOT NULL REFERENCES claim_exchange_refund_consent(exchange_id),attempt_id uuid NOT NULL,result_version bigint NOT NULL,assessment_id uuid NOT NULL REFERENCES claim_fee_assessment(id),
 result_state varchar(16) NOT NULL CHECK(result_state IN('SUCCEEDED','FAILED','UNKNOWN')),
 mode varchar(24) NOT NULL CHECK(mode IN('STALE','REVIEW_REQUIRED','SETTLEMENT_PENDING','ITEM_READY')),
 original_revision bigint NOT NULL,workflow_revision bigint,ready_id uuid UNIQUE,fingerprint varchar(64) NOT NULL,receipt jsonb NOT NULL,
 job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,recorded_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(attempt_id,result_version),FOREIGN KEY(attempt_id,result_version) REFERENCES claim_exchange_refund_result(attempt_id,result_version),
 FOREIGN KEY(exchange_id,original_revision) REFERENCES claim_exchange_history(exchange_id,version),
 FOREIGN KEY(exchange_id,workflow_revision) REFERENCES claim_exchange_history(exchange_id,version) DEFERRABLE INITIALLY DEFERRED,
 CHECK((mode='STALE' AND workflow_revision IS NULL AND ready_id IS NULL) OR(mode<>'STALE' AND workflow_revision=original_revision+1 AND ((mode='ITEM_READY')=(ready_id IS NOT NULL)))),
 UNIQUE(ready_id,exchange_id,attempt_id,result_version,assessment_id,workflow_revision)
);
CREATE TABLE claim_exchange_refund_item_ready (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE REFERENCES claim_exchange_refund_consent(exchange_id),conversion_id uuid NOT NULL REFERENCES claim_exchange_refund_consent(id),intent_id uuid NOT NULL,
 order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL,cause_id uuid NOT NULL,payment_id uuid NOT NULL,
 attempt_id uuid NOT NULL,result_version bigint NOT NULL,assessment_id uuid NOT NULL,
 amount bigint NOT NULL CHECK(amount>=0),reference varchar(200) NOT NULL,result_state varchar(16) NOT NULL DEFAULT 'SUCCEEDED' CHECK(result_state='SUCCEEDED'),
 workflow_revision bigint NOT NULL,physical_fingerprint varchar(64) NOT NULL,physical_snapshot jsonb NOT NULL CHECK(jsonb_typeof(physical_snapshot)='array'),
 job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,occurred_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 CHECK(intent_id=exchange_id),
 FOREIGN KEY(exchange_id,order_id,seller_order_id,hold_id,cause_id) REFERENCES claim_exchange_request(id,order_id,seller_order_id,hold_id,cause_id),
 FOREIGN KEY(id,exchange_id,attempt_id,result_version,assessment_id,workflow_revision) REFERENCES claim_exchange_refund_effect(ready_id,exchange_id,attempt_id,result_version,assessment_id,workflow_revision) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(assessment_id,attempt_id,result_version,result_state,amount,reference) REFERENCES claim_exchange_refund_result(assessment_id,attempt_id,result_version,result_state,amount,result_reference),
 CONSTRAINT exchange_refund_ready_exact_completion_identity UNIQUE(id,exchange_id,conversion_id,intent_id,order_id,seller_order_id,hold_id,cause_id,payment_id,attempt_id,result_version,reference)
);
ALTER TABLE claim_exchange_refund_effect ADD FOREIGN KEY(ready_id) REFERENCES claim_exchange_refund_item_ready(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE claim_exchange_refund_ready_unit (
 exchange_id uuid NOT NULL REFERENCES claim_exchange_refund_item_ready(exchange_id),item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,collection_id uuid NOT NULL,inspection_id uuid NOT NULL,
 condition varchar(16) NOT NULL CHECK(condition IN('SALEABLE','DEFECTIVE')),responsibility varchar(10) NOT NULL CHECK(responsibility IN('BUYER','SELLER')),restitution_id uuid,
 PRIMARY KEY(exchange_id,item_id,ordinal),
 FOREIGN KEY(exchange_id,item_id,ordinal,physical_id) REFERENCES order_exchange_physical_unit(exchange_id,item_id,ordinal,physical_id),
 FOREIGN KEY(inspection_id,item_id,ordinal) REFERENCES claim_exchange_inspection_unit(inspection_id,item_id,ordinal)
);
CREATE TABLE order_exchange_refund_completion (
 exchange_id uuid PRIMARY KEY REFERENCES order_exchange_application(id),conversion_id uuid NOT NULL,intent_id uuid NOT NULL,ready_id uuid NOT NULL,shipping_decision_id uuid NOT NULL REFERENCES order_shipping_decision(decision_id),
 order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL,cause_id uuid NOT NULL,payment_id uuid NOT NULL,item_attempt_id uuid NOT NULL,result_version bigint NOT NULL CHECK(result_version>0),reference varchar(200) NOT NULL,fingerprint varchar(64) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(exchange_id,hold_id,seller_order_id,order_id) REFERENCES order_exchange_application(id,hold_id,seller_order_id,order_id),
 FOREIGN KEY(ready_id,exchange_id,conversion_id,intent_id,order_id,seller_order_id,hold_id,cause_id,payment_id,item_attempt_id,result_version,reference)
  REFERENCES claim_exchange_refund_item_ready(id,exchange_id,conversion_id,intent_id,order_id,seller_order_id,hold_id,cause_id,payment_id,attempt_id,result_version,reference),
 UNIQUE(exchange_id,order_id,seller_order_id)
);
CREATE TABLE order_exchange_refund_completion_unit (
 exchange_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 PRIMARY KEY(item_id,ordinal),FOREIGN KEY(exchange_id,order_id,seller_order_id) REFERENCES order_exchange_refund_completion(exchange_id,order_id,seller_order_id),
 FOREIGN KEY(exchange_id,item_id,ordinal) REFERENCES claim_exchange_refund_ready_unit(exchange_id,item_id,ordinal),
 FOREIGN KEY(item_id,ordinal,order_id) REFERENCES order_unit_lifecycle(item_id,ordinal,order_id)
);
CREATE TABLE claim_exchange_refund_completion (
 exchange_id uuid PRIMARY KEY REFERENCES claim_exchange_refund_item_ready(exchange_id),conversion_id uuid NOT NULL REFERENCES claim_exchange_refund_consent(id),ready_id uuid NOT NULL REFERENCES claim_exchange_refund_item_ready(id),decision_id uuid NOT NULL REFERENCES claim_seller_shipping_decision(id),
 workflow_revision bigint NOT NULL,fingerprint varchar(64) NOT NULL,receipt jsonb NOT NULL,job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,occurred_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(exchange_id,workflow_revision) REFERENCES claim_exchange_history(exchange_id,version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_id) REFERENCES order_exchange_refund_completion(exchange_id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE order_exchange_refund_completion ADD FOREIGN KEY(exchange_id) REFERENCES claim_exchange_refund_completion(exchange_id) DEFERRABLE INITIALLY DEFERRED;
DO $$ DECLARE tbl text;BEGIN
 FOREACH tbl IN ARRAY ARRAY['claim_exchange_refund_effect','claim_exchange_refund_item_ready','claim_exchange_refund_ready_unit','order_exchange_refund_completion','order_exchange_refund_completion_unit','claim_exchange_refund_completion'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_exchange_refund_evidence BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable()',tbl);
 END LOOP;
END $$;
ALTER TABLE order_aftercare_closure DROP CONSTRAINT order_aftercare_closure_reason_check;
ALTER TABLE order_aftercare_closure ADD CONSTRAINT order_aftercare_closure_reason_check CHECK(reason IN('ALL_UNSHIPPED_CANCEL','ALL_CONFIRMED','MIXED_CONFIRMED_CANCEL','ALL_RETURNED','MIXED_AFTERCARE','ALL_EXCHANGE_REFUNDED'));
ALTER TABLE order_aftercare_closure DROP CONSTRAINT order_aftercare_closure_check;
ALTER TABLE order_aftercare_closure ADD CONSTRAINT order_aftercare_closure_check CHECK(
 (reason='ALL_CONFIRMED' AND payment_id IS NULL AND target='CLOSED')
 OR(reason='ALL_UNSHIPPED_CANCEL' AND payment_id IS NOT NULL AND target='CANCELED')
 OR(reason IN('MIXED_CONFIRMED_CANCEL','ALL_RETURNED','MIXED_AFTERCARE','ALL_EXCHANGE_REFUNDED') AND payment_id IS NOT NULL AND target='CLOSED'));

CREATE OR REPLACE FUNCTION validate_exchange_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;a order_exchange_application;c claim_exchange_request;n integer;
BEGIN
 IF TG_TABLE_NAME IN ('order_exchange_application','claim_exchange_request') THEN target=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_event' THEN SELECT exchange_id INTO target FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE target=NEW.exchange_id;END IF;
 SELECT * INTO a FROM order_exchange_application WHERE id=target;SELECT * INTO c FROM claim_exchange_request WHERE id=target;
 IF a.id IS NULL OR c.id IS NULL OR a.source_snapshot<>c.source_snapshot OR c.source_snapshot->>'id'<>c.id::text OR c.receipt->>'exchangeId'<>c.id::text OR c.receipt->>'holdId'<>c.hold_id::text
 OR NOT EXISTS(SELECT 1 FROM order_unit_hold h WHERE h.id=a.hold_id AND h.kind='EXCHANGE' AND (h.state='ACTIVE' OR c.state='REFUNDED' AND h.state='REFUNDED' AND EXISTS(SELECT 1 FROM order_exchange_refund_completion done WHERE done.exchange_id=c.id AND done.hold_id=h.id AND done.cause_id=h.cause_id AND done.reference=h.outcome) OR c.state='COMPLETED' AND h.state='RELEASED' AND h.outcome='EXCHANGED' AND EXISTS(SELECT 1 FROM order_exchange_completion z WHERE z.exchange_id=c.id AND z.hold_id=h.id)) AND h.cause_id=a.cause_id AND h.order_id=a.order_id)
 THEN RAISE EXCEPTION 'EXCHANGE_ACCEPTED_GRAPH' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=target;
 IF n=0 OR n<>(SELECT count(*) FROM order_exchange_unit WHERE exchange_id=target) OR n<>(SELECT count(*) FROM order_hold_unit WHERE hold_id=a.hold_id) OR n<>jsonb_array_length(a.source_snapshot->'units') OR n<>jsonb_array_length(c.receipt->'units')
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u JOIN order_exchange_unit e ON e.exchange_id=u.exchange_id AND e.item_id=u.item_id AND e.ordinal=u.ordinal JOIN order_physical_unit p ON p.id=e.physical_id JOIN order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal JOIN inventory_reservation r ON r.id=p.reservation_id JOIN order_initial_delivery d ON d.event_id=p.delivery_event_id JOIN order_item i ON i.id=u.item_id WHERE u.exchange_id=target AND (u.hold_id<>a.hold_id OR e.hold_id<>a.hold_id OR u.physical_id<>p.id OR p.order_id<>a.order_id OR p.seller_order_id<>a.seller_order_id OR c.state NOT IN('COMPLETED','REFUNDED') AND (l.state<>'HELD' OR l.active_hold_id IS DISTINCT FROM a.hold_id) OR r.status<>'COMMITTED' OR r.checkout_id<>p.checkout_id OR r.order_item_id<>p.item_id OR r.sku_id<>p.sku_id OR r.quantity<=p.ordinal OR d.shipment_id<>p.delivery_bucket_id OR d.occurred_at<>p.delivered_at OR d.order_id<>a.order_id OR d.seller_order_id<>a.seller_order_id OR i.sku_id<>p.sku_id OR NOT EXISTS(SELECT 1 FROM order_initial_delivery_unit du WHERE du.event_id=d.event_id AND du.item_id=p.item_id AND du.ordinal=p.ordinal)))
 THEN RAISE EXCEPTION 'EXCHANGE_EXACT_SOURCE_UNITS' USING ERRCODE='23514';END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=target AND h.version=0 AND h.action='REQUESTED' AND h.reason=c.reason AND h.occurred_at=c.requested_at)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-requested:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
 THEN RAISE EXCEPTION 'EXCHANGE_REQUEST_RECEIPT_OUTBOX' USING ERRCODE='23514';END IF;
 IF c.state='REQUESTED' AND EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=target) THEN RAISE EXCEPTION 'UNAPPROVED_EXCHANGE_RESERVATION' USING ERRCODE='23514';END IF;
 IF c.state='APPROVED' THEN
  IF n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=target AND sequence=c.current_sequence AND status='RESERVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_history WHERE exchange_id=target AND version=1 AND action='APPROVED') OR NOT EXISTS(SELECT 1 FROM claim_exchange_command WHERE exchange_id=target AND version=1 AND action='APPROVED')
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-approved:'||target::text AND j.cause_key=target::text AND j.payload=target::text)
  OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id JOIN order_physical_unit p ON p.id=u.physical_id WHERE r.exchange_id=target AND r.sequence=c.current_sequence AND (r.order_id<>a.order_id OR r.seller_order_id<>a.seller_order_id OR r.checkout_id<>p.checkout_id OR r.item_id<>p.item_id OR r.ordinal<>p.ordinal OR r.source_reservation_id<>p.reservation_id OR r.source_sku_id<>p.sku_id OR r.target_sku_id<>u.target_sku_id OR r.approved_at<>c.approved_at OR COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=r.id),r.expires_at)<>c.expires_at OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=c.approved_at)))
  THEN RAISE EXCEPTION 'EXCHANGE_APPROVAL_RESERVATION_GRAPH' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;

CREATE FUNCTION verify_exchange_refund_effect(p_attempt uuid,p_version bigint) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE x claim_exchange_refund_effect;r claim_exchange_refund_result;c claim_exchange_request;
BEGIN
 SELECT * INTO x FROM claim_exchange_refund_effect WHERE attempt_id=p_attempt AND result_version=p_version;
 IF x.attempt_id IS NULL THEN RAISE EXCEPTION 'EXCHANGE_REFUND_EFFECT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO r FROM claim_exchange_refund_result WHERE attempt_id=p_attempt AND result_version=p_version;
 SELECT * INTO c FROM claim_exchange_request WHERE id=x.exchange_id;
 IF (r.intent_id,r.assessment_id,r.result_state) IS DISTINCT FROM(x.exchange_id,x.assessment_id,x.result_state)
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=x.job_id AND j.type='EXCHANGE_REFUND_RESUME.v1'
  AND j.dedupe_key='exchange-refund-result:'||p_attempt::text||':'||p_version::text AND j.cause_key=x.exchange_id::text AND j.payload=x.exchange_id::text||'|'||p_attempt::text||'|'||p_version::text
  AND(x.created_xid<>pg_current_xact_id() OR(j.status='RUNNING' AND j.owner_token=x.owner_token AND j.lease_until>clock_timestamp())))
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-refund-result-'||p_attempt::text||'-'||p_version::text||':'||x.exchange_id::text AND j.cause_key=x.exchange_id::text AND j.payload=x.exchange_id::text)
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_REFUND_EFFECT_DELIVERY_REQUIRED' USING ERRCODE='23514';END IF;
 IF x.mode='STALE' THEN
  IF NOT(c.state='REFUNDED' OR EXISTS(SELECT 1 FROM payment_refund_attempt p WHERE p.id=p_attempt AND p.version>p_version)
   OR EXISTS(SELECT 1 FROM claim_fee_binding b WHERE b.assessment_id=x.assessment_id AND b.attempt_id<>p_attempt))
  THEN RAISE EXCEPTION 'EXCHANGE_REFUND_STALE_RESULT_PROOF_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE
  IF c.version<x.workflow_revision OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history h ON h.exchange_id=command.exchange_id AND h.version=command.version
   WHERE command.exchange_id=x.exchange_id AND command.command_key='refund-result:'||p_attempt::text||':'||p_version::text AND command.action=x.mode AND command.version=x.workflow_revision AND command.fingerprint=x.fingerprint AND command.receipt=x.receipt AND h.action=x.mode AND h.actor='RECORDED_WORKER' AND h.occurred_at=x.recorded_at)
   OR(x.mode='REVIEW_REQUIRED' AND r.result_state NOT IN('FAILED','UNKNOWN'))
   OR(x.mode IN('ITEM_READY','SETTLEMENT_PENDING') AND(r.result_state<>'SUCCEEDED' OR NOT EXISTS(SELECT 1 FROM claim_fee_application app JOIN claim_fee_assessment a ON a.id=app.assessment_id
    WHERE app.assessment_id=x.assessment_id AND app.attempt_id=p_attempt AND app.result_version=p_version AND a.state='APPLIED' AND ((x.mode='ITEM_READY' AND a.additional_due=0) OR(x.mode='SETTLEMENT_PENDING' AND a.additional_due>0)))))
  THEN RAISE EXCEPTION 'EXACT_EXCHANGE_REFUND_EFFECT_WORKFLOW_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
END $$;
CREATE FUNCTION verify_exchange_refund_ready(p_exchange uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE r claim_exchange_refund_item_ready;n integer;
BEGIN
 SELECT * INTO r FROM claim_exchange_refund_item_ready WHERE exchange_id=p_exchange;
 IF r.id IS NULL THEN RETURN;END IF;
 PERFORM verify_exchange_refund_consent(p_exchange);PERFORM verify_exchange_refund_effect(r.attempt_id,r.result_version);
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=p_exchange;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_refund_consent c JOIN claim_exchange_refund_effect e ON e.ready_id=r.id
  JOIN claim_fee_assessment a ON a.id=r.assessment_id JOIN claim_fee_application applied ON applied.assessment_id=a.id
  JOIN payment_refund_attempt p ON p.id=r.attempt_id
  WHERE c.id=r.conversion_id AND c.exchange_id=p_exchange AND c.assessment_id=a.id AND e.mode='ITEM_READY' AND e.workflow_revision=r.workflow_revision AND e.job_id=r.job_id AND e.owner_token=r.owner_token AND e.recorded_at=r.occurred_at
   AND a.intent_id=p_exchange AND a.intent_kind='EXCHANGE_REFUND' AND a.state='APPLIED' AND a.additional_due=0
   AND applied.attempt_id=p.id AND applied.result_version=r.result_version AND applied.result_reference=r.reference
   AND p.claim_id=p_exchange AND p.payment_id=r.payment_id AND p.order_id=r.order_id AND p.seller_order_id=r.seller_order_id AND p.purpose='ITEM' AND p.state='SUCCEEDED' AND p.version=r.result_version AND p.amount=r.amount AND p.result_reference=r.reference)
 OR n=0 OR n<>(SELECT count(*) FROM claim_exchange_refund_ready_unit WHERE exchange_id=p_exchange) OR n<>jsonb_array_length(r.physical_snapshot)
 OR n<>(SELECT count(DISTINCT(x->>'itemId',x->>'ordinal')) FROM jsonb_array_elements(r.physical_snapshot) x)
 OR EXISTS(SELECT 1 FROM claim_exchange_refund_ready_unit u
  LEFT JOIN claim_exchange_inspection_unit i ON i.exchange_id=u.exchange_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal
  LEFT JOIN claim_exchange_collection collected ON collected.id=i.collection_id
  LEFT JOIN order_exchange_physical_unit physical ON physical.exchange_id=u.exchange_id AND physical.item_id=u.item_id AND physical.ordinal=u.ordinal
  WHERE u.exchange_id=p_exchange AND(i.inspection_id IS NULL OR collected.id IS NULL OR physical.physical_id IS NULL OR collected.status<>'RECEIVED'
   OR(u.physical_id,u.inspection_id,u.collection_id,u.condition,u.responsibility,u.restitution_id) IS DISTINCT FROM(i.physical_id,i.inspection_id,i.collection_id,i.condition,i.responsibility,i.restitution_id)
   OR u.physical_id<>physical.physical_id OR(u.condition='SALEABLE' AND u.restitution_id IS NULL) OR(u.condition='DEFECTIVE' AND u.restitution_id IS NOT NULL)
   OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.physical_snapshot) x WHERE x->>'itemId'=u.item_id::text AND x->>'ordinal'=u.ordinal::text AND x->>'physicalId'=u.physical_id::text AND x->>'collectionId'=u.collection_id::text AND x->>'inspectionId'=u.inspection_id::text AND x->>'inspection'=u.condition AND x->>'responsibility'=u.responsibility AND x->>'restitutionId' IS NOT DISTINCT FROM u.restitution_id::text)))
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=r.seller_order_id::text AND j.payload='EXCHANGE_REFUND_READY|'||p_exchange::text AND j.dedupe_key='seller-aftercare:'||r.seller_order_id::text||':exchange-refund-ready:'||p_exchange::text)
 THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_REFUND_ITEM_READY_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION verify_exchange_refund_completion(p_exchange uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE r claim_exchange_request;c claim_exchange_refund_completion;ready claim_exchange_refund_item_ready;n integer;
BEGIN
 SELECT * INTO r FROM claim_exchange_request WHERE id=p_exchange;SELECT * INTO c FROM claim_exchange_refund_completion WHERE exchange_id=p_exchange;
 IF c.exchange_id IS NULL THEN
  IF r.state='REFUNDED' OR EXISTS(SELECT 1 FROM order_exchange_refund_completion WHERE exchange_id=p_exchange)
   OR EXISTS(SELECT 1 FROM order_unit_hold WHERE id=r.hold_id AND state='REFUNDED') THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_REFUND_COMPLETION_REQUIRED' USING ERRCODE='23514';END IF;RETURN;
 END IF;
 PERFORM verify_exchange_refund_ready(p_exchange);SELECT * INTO ready FROM claim_exchange_refund_item_ready WHERE exchange_id=p_exchange;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=p_exchange;
 IF r.state<>'REFUNDED' OR r.version<>c.workflow_revision OR n=0 OR n<>(SELECT count(*) FROM order_exchange_refund_completion_unit WHERE exchange_id=p_exchange)
 OR NOT EXISTS(SELECT 1 FROM order_exchange_refund_completion o JOIN order_unit_hold h ON h.id=o.hold_id AND h.order_id=o.order_id AND h.cause_id=o.cause_id
  JOIN claim_exchange_history hist ON hist.exchange_id=r.id AND hist.version=c.workflow_revision
  JOIN claim_exchange_command cmd ON cmd.exchange_id=r.id AND cmd.version=c.workflow_revision
  JOIN claim_seller_shipping_decision d ON d.id=c.decision_id
  JOIN job j ON j.id=c.job_id
  WHERE o.exchange_id=r.id AND o.conversion_id=ready.conversion_id AND o.ready_id=ready.id AND o.intent_id=r.id AND o.shipping_decision_id=c.decision_id AND o.order_id=r.order_id AND o.seller_order_id=r.seller_order_id
   AND o.hold_id=r.hold_id AND o.cause_id=r.cause_id AND o.payment_id=ready.payment_id AND o.item_attempt_id=ready.attempt_id AND o.result_version=ready.result_version AND o.reference=ready.reference
   AND c.conversion_id=ready.conversion_id AND c.ready_id=ready.id AND h.kind='EXCHANGE' AND h.state='REFUNDED' AND h.outcome=o.reference
   AND hist.action='REFUNDED' AND hist.reason='EXCHANGE_REFUND_COMPLETED' AND hist.actor='RECORDED_WORKER' AND hist.occurred_at=c.occurred_at
   AND cmd.action='REFUNDED' AND cmd.fingerprint=c.fingerprint AND cmd.receipt=c.receipt
   AND d.order_id=r.order_id AND d.seller_order_id=r.seller_order_id
   AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=r.seller_order_id::text AND return_seller_delivery_identity(j.id,r.seller_order_id,r.order_id)
   AND(c.created_xid<>pg_current_xact_id() OR(j.status='RUNNING' AND j.owner_token=c.owner_token AND j.lease_until>clock_timestamp()))
   AND(d.state<>'REFUND_REQUIRED' OR EXISTS(SELECT 1 FROM order_aftercare_shipping settled JOIN payment_refund_attempt p ON p.id=settled.refund_attempt_id
    WHERE settled.order_id=r.order_id AND settled.seller_order_id=r.seller_order_id AND p.state='SUCCEEDED' AND p.purpose='SHIPPING' AND p.order_id=settled.order_id AND p.seller_order_id=settled.seller_order_id AND p.claim_id=settled.cause_id AND p.payment_id=settled.payment_id AND p.amount=settled.amount AND p.result_reference=settled.reference)))
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u LEFT JOIN order_exchange_refund_completion_unit o ON o.exchange_id=u.exchange_id AND o.item_id=u.item_id AND o.ordinal=u.ordinal LEFT JOIN order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal
  WHERE u.exchange_id=p_exchange AND(o.exchange_id IS NULL OR o.order_id<>r.order_id OR o.seller_order_id<>r.seller_order_id OR l.state<>'REFUNDED' OR l.refund_cause_id IS DISTINCT FROM r.cause_id))
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-refund-completed:'||p_exchange::text AND j.cause_key=p_exchange::text AND j.payload=p_exchange::text)
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_REFUND_COMPLETION_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION exchange_refund_business_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;
BEGIN
 IF TG_TABLE_NAME='order_unit_hold' THEN SELECT id INTO target FROM claim_exchange_request WHERE hold_id=NEW.id;
 ELSIF TG_TABLE_NAME='claim_exchange_request' THEN target=NEW.id;ELSE target=NEW.exchange_id;END IF;
 IF target IS NULL THEN RETURN NULL;END IF;
 IF TG_TABLE_NAME='claim_exchange_refund_effect' THEN PERFORM verify_exchange_refund_effect(NEW.attempt_id,NEW.result_version);END IF;
 IF EXISTS(SELECT 1 FROM claim_exchange_refund_consent WHERE exchange_id=target) THEN
  PERFORM verify_exchange_refund_consent(target);PERFORM verify_exchange_refund_ready(target);PERFORM verify_exchange_refund_completion(target);
  IF NOT EXISTS(SELECT 1 FROM order_unit_hold h JOIN claim_exchange_request c ON c.hold_id=h.id WHERE c.id=target AND(h.state='ACTIVE' OR h.state='REFUNDED' AND EXISTS(SELECT 1 FROM claim_exchange_refund_completion done WHERE done.exchange_id=target))) THEN RAISE EXCEPTION 'EXCHANGE_REFUND_EXACT_HOLD_STATE_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;
DO $$ DECLARE tbl text;BEGIN
 FOREACH tbl IN ARRAY ARRAY['claim_exchange_refund_effect','claim_exchange_refund_item_ready','claim_exchange_refund_ready_unit','order_exchange_refund_completion','order_exchange_refund_completion_unit','claim_exchange_refund_completion','claim_exchange_request','order_unit_hold'] LOOP
  EXECUTE format('CREATE CONSTRAINT TRIGGER exchange_refund_business_graph AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_business_graph()',tbl);
 END LOOP;
END $$;
