-- New effects require the actual primary route or an exact immutable recovery delivery.
CREATE FUNCTION return_seller_delivery_identity(v_job uuid,v_seller uuid,v_order uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=v_job AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=v_seller::text AND (
  (marketplace.exact_return_recovery_delivery(j.id,'SELLER_REEVALUATE') AND EXISTS(
   SELECT 1 FROM marketplace.claim_return_recovery_delivery d JOIN marketplace.claim_return_item_ready r ON r.return_id=d.return_id
   WHERE d.job_id=j.id AND d.stage='SELLER_REEVALUATE' AND d.order_id=v_order AND d.seller_order_id=v_seller
    AND r.order_id=v_order AND r.seller_order_id=v_seller AND j.payload='RETURN_READY|'||r.return_id::text))
  OR EXISTS(SELECT 1 FROM marketplace.claim_return_item_ready r WHERE r.seller_order_id=v_seller AND r.order_id=v_order
   AND j.payload='RETURN_READY|'||r.return_id::text AND j.dedupe_key='seller-aftercare:'||v_seller::text||':return-ready:'||r.return_id::text)
  OR EXISTS(SELECT 1 FROM marketplace.claim_shipping_result x JOIN marketplace.claim_financial_intent i ON i.id=x.intent_id
   WHERE x.seller_order_id=v_seller AND i.order_id=v_order AND j.payload='SHIPPING_RESULT|'||x.attempt_id::text||'|'||x.result_version::text
    AND j.dedupe_key='seller-aftercare:'||v_seller::text||':shipping-result:'||x.attempt_id::text||':'||x.result_version::text)
  OR EXISTS(SELECT 1 FROM marketplace.claim c JOIN marketplace.claim_refund_effect x ON x.claim_id=c.id AND x.purpose='ITEM'
   WHERE c.seller_order_id=v_seller AND c.order_id=v_order AND j.payload='CANCEL_ITEM|'||c.id::text
    AND j.dedupe_key='seller-aftercare:'||v_seller::text||':cancel-item:'||c.id::text)
  OR EXISTS(SELECT 1 FROM marketplace.order_confirmation c JOIN marketplace.order_confirmation_unit u ON u.confirmation_id=c.id AND u.order_id=c.order_id
   JOIN marketplace.order_unit_lifecycle l ON l.item_id=u.item_id AND l.ordinal=u.ordinal AND l.order_id=c.order_id
   WHERE c.order_id=v_order AND l.seller_order_id=v_seller AND l.confirmation_id=c.id AND l.state='CONFIRMED'
    AND j.payload='CONFIRMATION|'||c.id::text AND j.dedupe_key='seller-aftercare:'||v_seller::text||':confirmation:'||c.id::text)
 ))
$$;
CREATE FUNCTION return_seller_decision_current_job_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT marketplace.return_seller_delivery_identity(NEW.job_id,NEW.seller_order_id,NEW.order_id)
 OR NOT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=NEW.job_id AND j.status='RUNNING' AND j.owner_token=NEW.owner_token)
 THEN RAISE EXCEPTION 'SELLER_DECISION_EXACT_CURRENT_JOB_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER seller_decision_current_job AFTER INSERT ON claim_seller_shipping_decision DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_seller_decision_current_job_guard();

CREATE OR REPLACE FUNCTION shipping_result_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE x marketplace.claim_shipping_result%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='claim_shipping_result' THEN SELECT * INTO STRICT x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.attempt_id AND result_version=NEW.result_version;
 ELSE SELECT * INTO x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.refund_attempt_id AND result_state='SUCCEEDED' ORDER BY result_version DESC LIMIT 1;IF NOT FOUND THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_REQUIRED' USING ERRCODE='23514';END IF;END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_shipping_binding b
  JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=b.seller_order_id
  JOIN marketplace.payment_refund_attempt p ON p.id=b.attempt_id
  JOIN marketplace.job j ON j.id=x.job_id
  WHERE b.attempt_id=x.attempt_id AND b.intent_id=x.intent_id AND b.seller_order_id=x.seller_order_id
   AND o.claim_id=b.intent_id AND o.refund_attempt_id=p.id AND o.amount=p.amount AND p.purpose='SHIPPING' AND x.workflow_revision IS NOT NULL AND o.version=x.workflow_revision AND o.state=x.result_state
   AND p.version=x.result_version AND p.state=x.result_state AND p.result_reference IS NOT DISTINCT FROM x.reference AND p.result_at IS NOT DISTINCT FROM x.result_at
   AND j.type='REFUND_RESULT_RECORDED.v1' AND j.cause_key=p.id::text AND j.status='RUNNING' AND j.owner_token=x.owner_token
   AND j.payload=p.id::text||'|'||p.version::text AND (
    j.dedupe_key='refund-result:'||p.id::text||':'||p.version::text
    OR(marketplace.exact_return_recovery_delivery(j.id,'SHIPPING_RESULT') AND EXISTS(
     SELECT 1 FROM marketplace.claim_return_recovery_delivery d WHERE d.job_id=j.id AND d.stage='SHIPPING_RESULT'
      AND d.return_id=b.intent_id AND d.order_id=b.order_id AND d.seller_order_id=b.seller_order_id AND d.attempt_id=p.id AND d.result_version=p.version
      AND d.payment_id=p.payment_id AND d.amount=p.amount AND d.original_reference=p.original_reference AND d.result_state=p.state
      AND d.result_reference IS NOT DISTINCT FROM p.result_reference AND d.result_at IS NOT DISTINCT FROM p.result_at)))
   AND (x.result_state<>'SUCCEEDED' OR(o.state='SUCCEEDED' AND EXISTS(SELECT 1 FROM marketplace.order_aftercare_shipping s WHERE s.seller_order_id=o.seller_order_id AND s.order_id=o.order_id AND s.refund_attempt_id=p.id AND s.payment_id=p.payment_id AND s.cause_id=o.claim_id AND s.amount=p.amount AND s.reference=p.result_reference)))
 ) THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION return_completion_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;
BEGIN
 IF TG_TABLE_NAME='claim_return_completion' THEN
 IF NOT EXISTS(
  SELECT 1 FROM marketplace.job j JOIN marketplace.claim_return_request r ON r.id=NEW.return_id
  WHERE j.id=NEW.job_id AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=r.seller_order_id::text
   AND j.status='RUNNING' AND j.owner_token=NEW.owner_token AND marketplace.return_seller_delivery_identity(j.id,r.seller_order_id,r.order_id)
 ) THEN RAISE EXCEPTION 'RETURN_COMPLETION_CURRENT_JOB_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='order_unit_hold' THEN SELECT id INTO v_id FROM marketplace.claim_return_request WHERE hold_id=NEW.id;
 ELSE v_id:=NEW.return_id;END IF;
 IF v_id IS NOT NULL THEN PERFORM marketplace.verify_return_completion(v_id);END IF;RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION claim_return_recovery_ids(v_stage text) RETURNS TABLE(id uuid) LANGUAGE sql STABLE AS $$
 SELECT r.id FROM marketplace.claim_return_request r
 JOIN marketplace.claim_return_financial_consent c ON c.return_id=r.id
 JOIN marketplace.claim_fee_binding b ON b.assessment_id=c.assessment_id
 JOIN LATERAL(SELECT x.result_version FROM marketplace.claim_return_refund_result x WHERE x.intent_id=r.id AND x.assessment_id=c.assessment_id AND x.attempt_id=b.attempt_id ORDER BY x.result_version DESC LIMIT 1)x ON true
 LEFT JOIN marketplace.claim_return_financial_result effect ON effect.return_id=r.id AND effect.attempt_id=b.attempt_id AND effect.result_version=x.result_version
 WHERE v_stage='ITEM_EFFECT' AND ((effect.return_id IS NULL AND r.state IN ('REFUND_PENDING','REVIEW_REQUIRED','DUE_PENDING'))
  OR(effect.result_state='SUCCEEDED' AND EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.id=c.assessment_id AND a.state='APPLIED' AND a.additional_due=0)
   AND NOT EXISTS(SELECT 1 FROM marketplace.claim_return_item_ready ready WHERE ready.return_id=r.id)))
 UNION ALL
 SELECT r.id FROM marketplace.claim_return_request r JOIN marketplace.claim_return_item_ready ready ON ready.return_id=r.id
 JOIN marketplace.claim_shipping_obligation o ON o.claim_id=r.id AND o.seller_order_id=r.seller_order_id
 JOIN marketplace.claim_return_shipping_binding b ON b.intent_id=r.id AND b.attempt_id=o.refund_attempt_id
 WHERE v_stage='SHIPPING_RESULT' AND o.state<>'SUCCEEDED'
 UNION ALL
 SELECT r.id FROM marketplace.claim_return_request r JOIN marketplace.claim_return_item_ready ready ON ready.return_id=r.id
 LEFT JOIN marketplace.claim_return_completion done ON done.return_id=r.id
 LEFT JOIN marketplace.claim_seller_shipping_current cur ON cur.seller_order_id=r.seller_order_id
 LEFT JOIN marketplace.claim_seller_shipping_decision decision ON decision.id=cur.decision_id
 WHERE v_stage='SELLER_REEVALUATE' AND (done.return_id IS NULL OR decision.id IS NULL OR decision.state='PENDING')
$$;
