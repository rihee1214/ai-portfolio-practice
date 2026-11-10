-- Explicit current-buyer consent is the only transition from inspection to financial reservation.
ALTER TABLE claim_return_command DROP CONSTRAINT claim_return_command_action_check;
ALTER TABLE claim_return_command ADD CONSTRAINT claim_return_command_action_check CHECK(action IN ('APPROVED','REJECTED','WITHDRAWN','COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT','REFUND_PENDING'));
CREATE TABLE claim_return_financial_consent (
 id uuid PRIMARY KEY,return_id uuid NOT NULL UNIQUE REFERENCES claim_return_request(id),request_key varchar(100) NOT NULL,
 fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 quote_id uuid NOT NULL UNIQUE REFERENCES claim_return_fee_quote_context(quote_id),
 assessment_id uuid NOT NULL UNIQUE REFERENCES claim_fee_assessment(id),attempt_id uuid NOT NULL UNIQUE REFERENCES payment_refund_attempt(id),
 original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),
 explicit_consent boolean NOT NULL CHECK(explicit_consent),legacy_consent boolean NOT NULL,authority_reference varchar(64) NOT NULL,
 receipt jsonb NOT NULL CHECK(jsonb_typeof(receipt)='object'),accepted_at timestamptz NOT NULL,
 created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(return_id,request_key) REFERENCES claim_return_command(return_id,request_key) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(return_id,revision) REFERENCES claim_return_history(return_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER return_financial_consent_immutable BEFORE UPDATE OR DELETE ON claim_return_financial_consent FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION verify_return_financial_consent(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r marketplace.claim_return_request%ROWTYPE;c marketplace.claim_return_financial_consent%ROWTYPE;
BEGIN
 SELECT * INTO STRICT r FROM marketplace.claim_return_request WHERE id=v_id;
 SELECT * INTO c FROM marketplace.claim_return_financial_consent WHERE return_id=v_id;
 IF NOT FOUND OR r.version<c.revision OR r.state<>'REFUND_PENDING' THEN RAISE EXCEPTION 'RETURN_FINANCIAL_CONSENT_REQUIRED' USING ERRCODE='23514';END IF;
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
END $$;
CREATE OR REPLACE FUNCTION return_collection_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;v_request marketplace.claim_return_request%ROWTYPE;v_command marketplace.claim_return_command%ROWTYPE;
 v_lost boolean;v_ready boolean;
BEGIN
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;
 ELSIF TG_TABLE_NAME='payment_refund_attempt' THEN v_id:=NEW.claim_id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN v_id:=NEW.intent_id;
 ELSE v_id:=NEW.return_id;END IF;
 SELECT * INTO v_request FROM marketplace.claim_return_request WHERE id=v_id;
 IF NOT FOUND THEN RETURN NULL;END IF;
 IF v_request.state='REFUND_PENDING' THEN PERFORM marketplace.verify_return_financial_consent(v_id);END IF;
 IF v_request.state IN ('REQUESTED','APPROVED','COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT') AND (
  EXISTS(SELECT 1 FROM marketplace.payment_refund_attempt a WHERE a.claim_id=v_id)
  OR EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.intent_id=v_id)
 ) THEN RAISE EXCEPTION 'RETURN_PHYSICAL_FACT_IS_NOT_FINANCIAL_CONSENT' USING ERRCODE='23514';END IF;
 IF v_request.state NOT IN ('COLLECTING','REVIEW_REQUIRED','AWAITING_CONSENT','REFUND_PENDING') THEN
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
  OR (v_request.state<>'REFUND_PENDING' AND v_request.state IS DISTINCT FROM (CASE WHEN v_lost THEN 'REVIEW_REQUIRED' WHEN v_ready THEN 'AWAITING_CONSENT' ELSE 'COLLECTING' END)) THEN
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
CREATE CONSTRAINT TRIGGER return_financial_consent_graph AFTER INSERT ON claim_return_financial_consent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_collection_graph_guard();
