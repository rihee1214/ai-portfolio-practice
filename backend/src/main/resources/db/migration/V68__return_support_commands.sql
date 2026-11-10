-- Current SUPPORT commands preserve the immutable first consent and chain the actual financial operation.
CREATE TABLE claim_return_support_command (
 id uuid PRIMARY KEY,return_id uuid NOT NULL REFERENCES claim_return_request(id),assessment_id uuid NOT NULL REFERENCES claim_fee_assessment(id),
 previous_attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),
 purpose varchar(10) NOT NULL CHECK(purpose='ITEM'),action varchar(20) NOT NULL CHECK(action IN ('RETRY_FAILED','QUERY_UNKNOWN')),
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,actor_id uuid NOT NULL REFERENCES identity_user(id),actor_version bigint NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),
 original_result_version bigint NOT NULL CHECK(original_result_version>0),fee_command_id uuid UNIQUE REFERENCES claim_fee_support_command(id),query_cause_id uuid,
 receipt jsonb NOT NULL,accepted_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 UNIQUE(return_id,request_key),UNIQUE(return_id,revision),
 FOREIGN KEY(return_id,request_key) REFERENCES claim_return_command(return_id,request_key) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(return_id,revision) REFERENCES claim_return_history(return_id,version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(query_cause_id,attempt_id) REFERENCES payment_refund_query_request(cause_id,attempt_id),
 CHECK((action='RETRY_FAILED' AND fee_command_id=id AND fee_command_id IS NOT NULL AND query_cause_id IS NULL AND previous_attempt_id<>attempt_id)
  OR(action='QUERY_UNKNOWN' AND fee_command_id IS NULL AND query_cause_id=id AND query_cause_id IS NOT NULL AND previous_attempt_id=attempt_id))
);
CREATE TRIGGER return_support_command_immutable BEFORE UPDATE OR DELETE ON claim_return_support_command FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION return_support_effect_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_valid boolean;
BEGIN
 SELECT EXISTS(SELECT 1 FROM marketplace.claim_return_request r
  JOIN marketplace.claim_return_financial_consent consent ON consent.return_id=r.id
  JOIN marketplace.claim_fee_assessment a ON a.id=consent.assessment_id AND a.id=NEW.assessment_id
  JOIN marketplace.claim_fee_binding b ON b.assessment_id=a.id AND b.attempt_id=NEW.attempt_id
  JOIN marketplace.payment_refund_attempt old_attempt ON old_attempt.id=NEW.previous_attempt_id
  JOIN marketplace.payment_refund_attempt next_attempt ON next_attempt.id=NEW.attempt_id
  JOIN marketplace.claim_return_history previous ON previous.return_id=r.id AND previous.version=NEW.original_revision
  JOIN marketplace.claim_return_history h ON h.return_id=r.id AND h.version=NEW.revision
  JOIN marketplace.claim_return_command cmd ON cmd.return_id=r.id AND cmd.request_key=NEW.request_key
  WHERE r.id=NEW.return_id AND r.version>=NEW.revision AND a.intent_id=r.id AND a.order_id=r.order_id AND a.seller_order_id=r.seller_order_id AND a.state='BOUND'
   AND previous.action IN ('REFUND_PENDING','REVIEW_REQUIRED') AND cmd.version=NEW.revision AND cmd.fingerprint=NEW.fingerprint AND cmd.receipt=NEW.receipt->'progress'
   AND h.actor='SUPPORT:'||NEW.actor_id::text AND h.reason=NEW.reason AND h.occurred_at=NEW.accepted_at
   AND h.action=CASE WHEN NEW.action='RETRY_FAILED' THEN 'REFUND_PENDING' ELSE 'REVIEW_REQUIRED' END AND cmd.action=h.action
   AND NEW.receipt->>'commandId'=NEW.id::text AND NEW.receipt->>'returnId'=r.id::text AND NEW.receipt->>'refundAttemptId'=next_attempt.id::text
   AND NEW.receipt->>'purpose'=NEW.purpose AND NEW.receipt->>'action'=NEW.action AND (NEW.receipt->>'revision')::bigint=NEW.revision
   AND old_attempt.claim_id=r.id AND old_attempt.order_id=r.order_id AND old_attempt.seller_order_id=r.seller_order_id AND old_attempt.purpose=NEW.purpose AND old_attempt.version=NEW.original_result_version
   AND next_attempt.claim_id=r.id AND next_attempt.order_id=r.order_id AND next_attempt.seller_order_id=r.seller_order_id AND next_attempt.purpose=NEW.purpose
   AND next_attempt.amount=old_attempt.amount AND next_attempt.amount=a.refund AND next_attempt.payment_id=old_attempt.payment_id
   AND next_attempt.original_reference=old_attempt.original_reference AND next_attempt.original_reference=a.quote_fingerprint
   AND ((NEW.action='RETRY_FAILED' AND old_attempt.state='FAILED' AND next_attempt.state='RESERVED' AND next_attempt.cause_id=NEW.id AND next_attempt.sequence=old_attempt.sequence+1
     AND EXISTS(SELECT 1 FROM marketplace.claim_fee_support_command fee WHERE fee.id=NEW.fee_command_id AND fee.id=NEW.id AND fee.assessment_id=a.id AND fee.previous_attempt_id=old_attempt.id AND fee.attempt_id=next_attempt.id AND fee.actor_id=NEW.actor_id AND fee.reason=NEW.reason AND fee.revision=a.version))
    OR(NEW.action='QUERY_UNKNOWN' AND old_attempt.state IN ('UNKNOWN','PROCESSING') AND old_attempt.id=next_attempt.id
     AND EXISTS(SELECT 1 FROM marketplace.payment_refund_query_request q WHERE q.cause_id=NEW.query_cause_id AND q.cause_id=NEW.id AND q.attempt_id=old_attempt.id AND q.original_version=old_attempt.version AND q.original_state=old_attempt.state AND q.reason=NEW.reason)))
 ) INTO v_valid;
 IF NOT v_valid THEN RAISE EXCEPTION 'RETURN_SUPPORT_CURRENT_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_support_effect_receipt AFTER INSERT ON claim_return_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_support_effect_guard();
CREATE FUNCTION return_support_primitive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_return uuid;v_command uuid;
BEGIN
 IF TG_TABLE_NAME='claim_fee_support_command' THEN
  SELECT c.return_id INTO v_return FROM marketplace.claim_return_financial_consent c WHERE c.assessment_id=NEW.assessment_id;v_command:=NEW.id;
 ELSE
  SELECT c.return_id INTO v_return FROM marketplace.claim_return_financial_consent c JOIN marketplace.claim_fee_binding_history b ON b.assessment_id=c.assessment_id WHERE b.attempt_id=NEW.attempt_id;v_command:=NEW.cause_id;
 END IF;
 IF v_return IS NOT NULL AND NOT EXISTS(SELECT 1 FROM marketplace.claim_return_support_command s WHERE s.id=v_command AND s.return_id=v_return) THEN RAISE EXCEPTION 'RETURN_SUPPORT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER return_support_retry_primitive AFTER INSERT ON claim_fee_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_support_primitive_guard();
CREATE CONSTRAINT TRIGGER return_support_query_primitive AFTER INSERT ON payment_refund_query_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_support_primitive_guard();
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
 IF r.state='REVIEW_REQUIRED' AND NOT EXISTS(SELECT 1 FROM marketplace.claim_return_financial_result x WHERE x.return_id=r.id AND x.result_state IN ('FAILED','UNKNOWN') AND x.workflow_revision<=r.version) AND NOT EXISTS(SELECT 1 FROM marketplace.claim_return_support_command s WHERE s.return_id=r.id AND s.action='QUERY_UNKNOWN' AND s.revision<=r.version) THEN RAISE EXCEPTION 'RETURN_FINANCIAL_REVIEW_RESULT_REQUIRED' USING ERRCODE='23514';END IF;
 IF r.state='DUE_PENDING' AND NOT EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a JOIN marketplace.claim_fee_application app ON app.assessment_id=a.id WHERE a.id=c.assessment_id AND a.state='APPLIED' AND a.additional_due>0) THEN RAISE EXCEPTION 'RETURN_OUTSTANDING_FEE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE OR REPLACE FUNCTION verify_return_financial_operation(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r marketplace.claim_return_request%ROWTYPE;c marketplace.claim_return_financial_consent%ROWTYPE;
 a marketplace.claim_fee_assessment%ROWTYPE;
BEGIN
 SELECT * INTO c FROM marketplace.claim_return_financial_consent WHERE return_id=v_id;
 IF NOT FOUND THEN RETURN;END IF;
 SELECT * INTO STRICT r FROM marketplace.claim_return_request WHERE id=v_id;
 SELECT * INTO STRICT a FROM marketplace.claim_fee_assessment WHERE id=c.assessment_id;
 IF r.version=c.revision AND a.state='BOUND' THEN RETURN;END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_support_command s
  JOIN marketplace.claim_return_command cmd ON cmd.return_id=s.return_id AND cmd.request_key=s.request_key AND cmd.version=s.revision
  JOIN marketplace.claim_fee_binding b ON b.assessment_id=s.assessment_id AND b.attempt_id=s.attempt_id
  WHERE s.return_id=r.id AND s.assessment_id=a.id AND s.revision=r.version AND a.state='BOUND'
   AND cmd.fingerprint=s.fingerprint AND cmd.receipt=s.receipt->'progress' AND cmd.action=r.state
   AND ((s.action='RETRY_FAILED' AND r.state='REFUND_PENDING') OR(s.action='QUERY_UNKNOWN' AND r.state='REVIEW_REQUIRED'))
 ) THEN RETURN;END IF;

 IF r.version<=c.revision OR NOT EXISTS(
  SELECT 1 FROM marketplace.claim_return_financial_result x
  JOIN marketplace.claim_return_command cmd ON cmd.return_id=x.return_id AND cmd.version=x.workflow_revision
  JOIN marketplace.claim_fee_binding b ON b.assessment_id=x.assessment_id AND b.attempt_id=x.attempt_id
  JOIN marketplace.claim_return_refund_result original ON original.intent_id=x.return_id AND original.assessment_id=x.assessment_id AND original.attempt_id=x.attempt_id AND original.result_version=x.result_version AND original.result_state=x.result_state
  WHERE x.return_id=r.id AND x.assessment_id=a.id AND x.workflow_revision=r.version
   AND x.receipt=cmd.receipt AND x.fingerprint=cmd.fingerprint AND cmd.action=r.state
   AND ((x.result_state='SUCCEEDED' AND a.state='APPLIED' AND r.state IN ('REFUND_PENDING','DUE_PENDING'))
     OR(x.result_state IN ('FAILED','UNKNOWN') AND a.state='BOUND' AND r.state='REVIEW_REQUIRED'))
 ) THEN RAISE EXCEPTION 'RETURN_FINANCIAL_OPERATION_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE CONSTRAINT TRIGGER return_support_state_graph AFTER INSERT ON claim_return_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
CREATE CONSTRAINT TRIGGER return_support_operation_graph AFTER INSERT ON claim_return_support_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_financial_operation_guard();
