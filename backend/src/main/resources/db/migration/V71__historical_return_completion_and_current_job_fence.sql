-- Past completion evidence remains valid after ACK; creating a new receipt still needs the actual current owner.
CREATE OR REPLACE FUNCTION verify_return_completion(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
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
   AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=r.seller_order_id::text
 ) OR EXISTS(SELECT item_id,ordinal FROM marketplace.claim_return_unit WHERE return_id=v_id EXCEPT SELECT item_id,ordinal FROM marketplace.order_return_completion_unit WHERE return_id=v_id)
 OR EXISTS(SELECT item_id,ordinal FROM marketplace.order_return_completion_unit WHERE return_id=v_id EXCEPT SELECT item_id,ordinal FROM marketplace.claim_return_unit WHERE return_id=v_id)
 OR EXISTS(SELECT 1 FROM marketplace.order_return_completion_unit u JOIN marketplace.order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal WHERE u.return_id=v_id AND (l.state<>'REFUNDED' OR l.refund_cause_id IS DISTINCT FROM r.cause_id))
 OR NOT EXISTS(SELECT 1 FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id WHERE j.type='RETURN_RECORDED.v1' AND j.cause_key=v_id::text AND j.payload=v_id::text AND j.dedupe_key='return-command:'||v_id::text||':'||c.workflow_revision::text)
 THEN RAISE EXCEPTION 'ACTUAL_RETURN_COMPLETION_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;

CREATE OR REPLACE FUNCTION return_completion_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;
BEGIN
 IF TG_TABLE_NAME='claim_return_completion' THEN
 IF NOT EXISTS(
  SELECT 1 FROM marketplace.job j JOIN marketplace.claim_return_request r ON r.id=NEW.return_id
  WHERE j.id=NEW.job_id AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=r.seller_order_id::text
   AND j.status='RUNNING' AND j.owner_token=NEW.owner_token
 ) THEN RAISE EXCEPTION 'RETURN_COMPLETION_CURRENT_JOB_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='order_unit_hold' THEN SELECT id INTO v_id FROM marketplace.claim_return_request WHERE hold_id=NEW.id;
 ELSE v_id:=NEW.return_id;END IF;
 IF v_id IS NOT NULL THEN PERFORM marketplace.verify_return_completion(v_id);END IF;RETURN NULL;
END $$;
