-- Financial result effects are distinct from physical completion and retain their actual job attribution.
ALTER TABLE claim_return_command DROP CONSTRAINT claim_return_command_action_check;
ALTER TABLE claim_return_command ADD CONSTRAINT claim_return_command_action_check CHECK(action IN ('APPROVED','REJECTED','WITHDRAWN','COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT','REFUND_PENDING','DUE_PENDING'));
CREATE TABLE claim_return_financial_result (
 return_id uuid NOT NULL REFERENCES claim_return_request(id),attempt_id uuid NOT NULL,result_version bigint NOT NULL CHECK(result_version>0),
 assessment_id uuid NOT NULL REFERENCES claim_fee_assessment(id),result_state varchar(16) NOT NULL CHECK(result_state IN ('SUCCEEDED','FAILED','UNKNOWN')),
 workflow_revision bigint NOT NULL CHECK(workflow_revision>0),fingerprint varchar(64) NOT NULL,receipt jsonb NOT NULL,
 job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,recorded_at timestamptz NOT NULL,
 PRIMARY KEY(return_id,attempt_id,result_version),UNIQUE(return_id,workflow_revision),
 FOREIGN KEY(attempt_id,result_version) REFERENCES claim_return_refund_result(attempt_id,result_version),
 FOREIGN KEY(return_id,workflow_revision) REFERENCES claim_return_history(return_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER return_financial_result_immutable BEFORE UPDATE OR DELETE ON claim_return_financial_result FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION return_financial_effect_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_request r
  JOIN marketplace.claim_return_financial_consent c ON c.return_id=r.id
  JOIN marketplace.claim_return_refund_result x ON x.intent_id=r.id AND x.attempt_id=NEW.attempt_id AND x.result_version=NEW.result_version
  JOIN marketplace.claim_fee_assessment a ON a.id=c.assessment_id AND a.id=NEW.assessment_id
  JOIN marketplace.claim_fee_binding b ON b.assessment_id=a.id AND b.attempt_id=x.attempt_id
  JOIN marketplace.payment_refund_attempt p ON p.id=x.attempt_id
  JOIN marketplace.claim_return_history h ON h.return_id=r.id AND h.version=NEW.workflow_revision
  JOIN marketplace.claim_return_command cmd ON cmd.return_id=r.id AND cmd.version=h.version
  JOIN marketplace.job j ON j.id=NEW.job_id
  WHERE r.id=NEW.return_id AND r.version=NEW.workflow_revision AND h.action=r.state
   AND x.assessment_id=a.id AND x.result_state=NEW.result_state AND x.amount=a.refund
   AND p.claim_id=r.id AND p.order_id=r.order_id AND p.seller_order_id=r.seller_order_id AND p.purpose='ITEM'
   AND p.version=x.result_version AND p.state=x.result_state AND p.amount=x.amount
   AND p.result_reference IS NOT DISTINCT FROM x.result_reference AND p.result_at IS NOT DISTINCT FROM x.result_at
   AND h.reason='ITEM_FINANCIAL_'||NEW.result_state AND h.occurred_at=NEW.recorded_at
   AND cmd.fingerprint=NEW.fingerprint AND cmd.receipt=NEW.receipt
   AND j.type='RETURN_FINANCE_RESUME.v1' AND j.status='RUNNING' AND j.owner_token=NEW.owner_token
   AND j.cause_key=r.id::text AND j.dedupe_key='return-finance-result:'||p.id::text||':'||p.version::text
   AND j.payload=r.id::text||'|'||p.id::text||'|'||p.version::text
   AND ((NEW.result_state='SUCCEEDED' AND a.state='APPLIED'
      AND r.state=CASE WHEN a.additional_due>0 THEN 'DUE_PENDING' ELSE 'REFUND_PENDING' END
      AND EXISTS(SELECT 1 FROM marketplace.claim_fee_application app WHERE app.assessment_id=a.id AND app.attempt_id=p.id AND app.result_version=p.version AND app.result_reference=p.result_reference))
     OR(NEW.result_state IN ('FAILED','UNKNOWN') AND a.state='BOUND' AND r.state='REVIEW_REQUIRED'
      AND NOT EXISTS(SELECT 1 FROM marketplace.claim_fee_application app WHERE app.assessment_id=a.id)))
 ) THEN RAISE EXCEPTION 'RETURN_FINANCIAL_EFFECT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_financial_effect_receipt AFTER INSERT ON claim_return_financial_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_effect_guard();
CREATE OR REPLACE FUNCTION verify_return_financial_consent(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r marketplace.claim_return_request%ROWTYPE;c marketplace.claim_return_financial_consent%ROWTYPE;
BEGIN
 SELECT * INTO STRICT r FROM marketplace.claim_return_request WHERE id=v_id;
 SELECT * INTO c FROM marketplace.claim_return_financial_consent WHERE return_id=v_id;
 IF NOT FOUND OR r.version<c.revision OR r.state NOT IN ('REFUND_PENDING','REVIEW_REQUIRED','DUE_PENDING') THEN RAISE EXCEPTION 'RETURN_FINANCIAL_CONSENT_REQUIRED' USING ERRCODE='23514';END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_fee_quote_context x
  JOIN marketplace.claim_fee_quote q ON q.id=x.quote_id JOIN marketplace.claim_fee_assessment a ON a.quote_id=q.id
  JOIN marketplace.claim_fee_consent fc ON fc.assessment_id=a.id
  JOIN marketplace.claim_fee_binding_history b ON b.assessment_id=a.id AND b.attempt_id=c.attempt_id
  JOIN marketplace.payment_refund_attempt p ON p.id=b.attempt_id
  JOIN marketplace.order_unit_hold h ON h.id=r.hold_id
  JOIN marketplace.claim_return_command cmd ON cmd.return_id=r.id AND cmd.request_key=c.request_key
  JOIN marketplace.claim_return_history hist ON hist.return_id=r.id AND hist.version=c.revision
  WHERE x.quote_id=c.quote_id AND x.return_id=r.id AND x.return_revision=c.original_revision
   AND q.order_id=r.order_id AND q.seller_order_id=r.seller_order_id
   AND a.id=c.assessment_id AND a.intent_id=r.id AND a.order_id=r.order_id AND a.seller_order_id=r.seller_order_id
   AND a.created_xid=c.created_xid AND a.state IN ('BOUND','APPLIED')
   AND fc.legacy_consent=c.legacy_consent AND fc.legacy_consent=(q.body->>'legacyConsentRequired')::boolean
   AND p.claim_id=r.id AND p.order_id=r.order_id AND p.seller_order_id=r.seller_order_id AND p.purpose='ITEM' AND p.sequence=1
   AND p.amount=a.refund AND p.original_reference=q.fingerprint AND p.cause_id=c.id
   AND h.state='ACTIVE' AND h.kind='RETURN' AND h.cause_id=r.cause_id
   AND cmd.version=c.revision AND cmd.action='REFUND_PENDING' AND cmd.fingerprint=c.fingerprint
   AND hist.action='REFUND_PENDING' AND hist.reason='BUYER_FINAL_FINANCIAL_CONSENT' AND hist.occurred_at=c.accepted_at
   AND c.receipt->'progress'=cmd.receipt AND c.receipt->>'assessmentId'=a.id::text AND c.receipt->>'refundAttemptId'=p.id::text
 ) THEN RAISE EXCEPTION 'RETURN_FINANCIAL_CONSENT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_unit u
  LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=u.return_id AND i.item_id=u.item_id AND i.ordinal=u.ordinal
  LEFT JOIN marketplace.claim_return_collection col ON col.id=i.collection_id
  WHERE u.return_id=r.id AND(i.inspection_id IS NULL OR col.status IS DISTINCT FROM 'RECEIVED')) THEN
  RAISE EXCEPTION 'RETURN_FINANCIAL_CONSENT_REQUIRES_INSPECTION' USING ERRCODE='23514';END IF;
 IF r.state='REVIEW_REQUIRED' AND NOT EXISTS(SELECT 1 FROM marketplace.claim_return_financial_result x WHERE x.return_id=r.id AND x.result_state IN ('FAILED','UNKNOWN') AND x.workflow_revision<=r.version) THEN RAISE EXCEPTION 'RETURN_FINANCIAL_REVIEW_RESULT_REQUIRED' USING ERRCODE='23514';END IF;
 IF r.state='DUE_PENDING' AND NOT EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a JOIN marketplace.claim_fee_application app ON app.assessment_id=a.id WHERE a.id=c.assessment_id AND a.state='APPLIED' AND a.additional_due>0) THEN RAISE EXCEPTION 'RETURN_OUTSTANDING_FEE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
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
CREATE CONSTRAINT TRIGGER return_financial_result_state AFTER INSERT ON claim_return_financial_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
