-- EXCHANGE_REFUND is a financial conversion of a real EXCHANGE, not a synthetic RETURN or CANCEL.
-- The original hold kind, accepted physical source, purchase reservation and inspection are unchanged.
ALTER TABLE claim_financial_intent DROP CONSTRAINT claim_financial_intent_kind_check;
ALTER TABLE claim_financial_intent DROP CONSTRAINT claim_financial_intent_hold_id_cause_id_order_id_kind_fkey;
ALTER TABLE claim_financial_intent ALTER COLUMN kind TYPE varchar(32);
ALTER TABLE claim_financial_intent ADD CONSTRAINT claim_financial_intent_kind_check CHECK(kind IN('CANCEL','RETURN','EXCHANGE_REFUND'));
ALTER TABLE claim_financial_intent ADD COLUMN hold_kind varchar(10)
 GENERATED ALWAYS AS ((CASE WHEN kind='EXCHANGE_REFUND' THEN 'EXCHANGE' ELSE kind END)::varchar(10)) STORED;
ALTER TABLE claim_financial_intent ADD FOREIGN KEY(hold_id,cause_id,order_id,hold_kind) REFERENCES order_unit_hold(id,cause_id,order_id,kind);
ALTER TABLE claim_financial_refund_binding ALTER COLUMN intent_kind TYPE varchar(32);
ALTER TABLE claim_financial_refund_binding DROP CONSTRAINT financial_binding_kind_purpose;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_binding_kind_purpose CHECK(intent_kind IN('CANCEL','RETURN','EXCHANGE_REFUND'));
ALTER TABLE claim_financial_refund_binding DROP CONSTRAINT financial_return_fee_required;
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_return_fee_required CHECK(
 (intent_kind='CANCEL' AND fee_assessment_id IS NULL)
 OR(intent_kind IN('RETURN','EXCHANGE_REFUND') AND purpose='ITEM' AND fee_assessment_id IS NOT NULL)
 OR(intent_kind='RETURN' AND purpose='SHIPPING' AND fee_assessment_id IS NULL));
ALTER TABLE claim_fee_assessment ALTER COLUMN intent_kind TYPE varchar(32);
ALTER TABLE claim_fee_assessment DROP CONSTRAINT claim_fee_assessment_intent_kind_check;
ALTER TABLE claim_fee_assessment ADD CONSTRAINT claim_fee_assessment_intent_kind_check CHECK(intent_kind IN('RETURN','EXCHANGE_REFUND'));
ALTER TABLE claim_fee_quote ALTER COLUMN purpose TYPE varchar(32);
ALTER TABLE claim_fee_quote DROP CONSTRAINT claim_fee_quote_purpose_check;
ALTER TABLE claim_fee_quote ADD CONSTRAINT claim_fee_quote_purpose_check CHECK(purpose IN('OPEN','INSPECTED_RETURN','INSPECTED_EXCHANGE_REFUND'));
ALTER TABLE claim_exchange_request ADD CONSTRAINT exchange_refund_source_identity UNIQUE(id,order_id,seller_order_id,hold_id,cause_id);

CREATE TABLE claim_exchange_refund_fee_quote_context (
 quote_id uuid PRIMARY KEY,exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),
 purpose varchar(32) NOT NULL DEFAULT 'INSPECTED_EXCHANGE_REFUND' CHECK(purpose='INSPECTED_EXCHANGE_REFUND'),
 exchange_revision bigint NOT NULL CHECK(exchange_revision>=0),inspection_fingerprint varchar(64) NOT NULL CHECK(inspection_fingerprint ~ '^[0-9a-f]{64}$'),
 inspection_snapshot jsonb NOT NULL CHECK(jsonb_typeof(inspection_snapshot)='array'),
 FOREIGN KEY(quote_id,purpose) REFERENCES claim_fee_quote(id,purpose),
 FOREIGN KEY(exchange_id,exchange_revision) REFERENCES claim_exchange_history(exchange_id,version),
 UNIQUE(quote_id,exchange_id,exchange_revision)
);
CREATE TRIGGER exchange_refund_quote_immutable BEFORE UPDATE OR DELETE ON claim_exchange_refund_fee_quote_context FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();

CREATE TABLE claim_exchange_refund_consent (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL UNIQUE,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL,cause_id uuid NOT NULL,
 intent_kind varchar(32) NOT NULL DEFAULT 'EXCHANGE_REFUND' CHECK(intent_kind='EXCHANGE_REFUND'),
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 quote_id uuid NOT NULL UNIQUE,assessment_id uuid NOT NULL UNIQUE REFERENCES claim_fee_assessment(id),attempt_id uuid NOT NULL UNIQUE REFERENCES payment_refund_attempt(id),
 original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),
 sequence integer NOT NULL CHECK(sequence>=0),release_id uuid UNIQUE REFERENCES inventory_exchange_release(id) DEFERRABLE INITIALLY DEFERRED,
 inspection_fingerprint varchar(64) NOT NULL CHECK(inspection_fingerprint ~ '^[0-9a-f]{64}$'),
 explicit_consent boolean NOT NULL CHECK(explicit_consent),legacy_consent boolean NOT NULL,authority_reference varchar(64) NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),receipt jsonb NOT NULL CHECK(jsonb_typeof(receipt)='object'),accepted_at timestamptz NOT NULL,
 created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(exchange_id,order_id,seller_order_id,hold_id,cause_id) REFERENCES claim_exchange_request(id,order_id,seller_order_id,hold_id,cause_id),
 FOREIGN KEY(exchange_id,seller_order_id,order_id,intent_kind) REFERENCES claim_financial_intent(id,seller_order_id,order_id,kind),
 FOREIGN KEY(quote_id,exchange_id,original_revision) REFERENCES claim_exchange_refund_fee_quote_context(quote_id,exchange_id,exchange_revision),
 FOREIGN KEY(exchange_id,request_key) REFERENCES claim_exchange_command(exchange_id,command_key) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(exchange_id,revision) REFERENCES claim_exchange_history(exchange_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER exchange_refund_consent_immutable BEFORE UPDATE OR DELETE ON claim_exchange_refund_consent FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();

-- Separate typed owner evidence. It is never inserted into the RETURN result/event tables.
CREATE TABLE claim_exchange_refund_result (
 attempt_id uuid NOT NULL,result_version bigint NOT NULL CHECK(result_version>0),intent_id uuid NOT NULL,assessment_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 intent_kind varchar(32) NOT NULL DEFAULT 'EXCHANGE_REFUND' CHECK(intent_kind='EXCHANGE_REFUND'),
 amount bigint NOT NULL CHECK(amount>=0),result_state varchar(16) NOT NULL CHECK(result_state IN('SUCCEEDED','FAILED','UNKNOWN')),
 result_reference varchar(200),result_at timestamptz,fingerprint varchar(64) NOT NULL,occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(attempt_id,result_version),FOREIGN KEY(intent_id,seller_order_id,order_id,intent_kind) REFERENCES claim_financial_intent(id,seller_order_id,order_id,kind),
 FOREIGN KEY(assessment_id,attempt_id) REFERENCES claim_fee_binding_history(assessment_id,attempt_id),
 CHECK((result_state='SUCCEEDED' AND result_reference IS NOT NULL AND result_at IS NOT NULL) OR(result_state<>'SUCCEEDED' AND result_reference IS NULL AND result_at IS NULL)),
 UNIQUE(assessment_id,attempt_id,result_version,result_state,amount,result_reference)
);
CREATE INDEX exchange_refund_result_intent ON claim_exchange_refund_result(intent_id,occurred_at);
CREATE TRIGGER exchange_refund_result_immutable BEFORE UPDATE OR DELETE ON claim_exchange_refund_result FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TABLE claim_exchange_refund_result_event (
 attempt_id uuid NOT NULL,result_version bigint NOT NULL,action varchar(30) NOT NULL CHECK(action='RESULT_RETAINED'),occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(attempt_id,result_version),FOREIGN KEY(attempt_id,result_version) REFERENCES claim_exchange_refund_result(attempt_id,result_version)
);
CREATE TRIGGER exchange_refund_result_event_immutable BEFORE UPDATE OR DELETE ON claim_exchange_refund_result_event FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE FUNCTION validate_exchange_financial_result() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_refund_attempt p JOIN claim_financial_refund_binding b ON b.attempt_id=p.id
  WHERE p.id=NEW.attempt_id AND p.version=NEW.result_version AND p.state=NEW.result_state AND p.amount=NEW.amount AND p.claim_id=NEW.intent_id AND p.order_id=NEW.order_id AND p.seller_order_id=NEW.seller_order_id AND p.purpose='ITEM'
   AND p.result_reference IS NOT DISTINCT FROM NEW.result_reference AND p.result_at IS NOT DISTINCT FROM NEW.result_at AND b.intent_kind='EXCHANGE_REFUND' AND b.fee_assessment_id=NEW.assessment_id)
  OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=NEW.job_id AND j.type='REFUND_RESULT_RECORDED.v1' AND j.dedupe_key='refund-result:'||NEW.attempt_id::text||':'||NEW.result_version::text
   AND j.cause_key=NEW.attempt_id::text AND j.payload=NEW.attempt_id::text||'|'||NEW.result_version::text AND j.status='RUNNING' AND j.owner_token=NEW.owner_token AND j.lease_until>clock_timestamp())
 THEN RAISE EXCEPTION 'ACTUAL_OWNED_EXCHANGE_FINANCIAL_RESULT_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER exchange_financial_result_exact BEFORE INSERT ON claim_exchange_refund_result FOR EACH ROW EXECUTE FUNCTION validate_exchange_financial_result();
CREATE FUNCTION verify_exchange_financial_result(p_attempt uuid,p_version bigint) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE r claim_exchange_refund_result;
BEGIN
 SELECT * INTO r FROM claim_exchange_refund_result WHERE attempt_id=p_attempt AND result_version=p_version;
 IF r.attempt_id IS NULL OR NOT EXISTS(SELECT 1 FROM claim_exchange_refund_result_event e WHERE e.attempt_id=p_attempt AND e.result_version=p_version AND e.action='RESULT_RETAINED')
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_REFUND_RESUME.v1'
   AND j.dedupe_key='exchange-refund-result:'||p_attempt::text||':'||p_version::text AND j.cause_key=r.intent_id::text AND j.payload=r.intent_id::text||'|'||p_attempt::text||'|'||p_version::text AND j.scheduled_at=r.occurred_at)
  OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=r.job_id AND j.type='REFUND_RESULT_RECORDED.v1' AND j.dedupe_key='refund-result:'||p_attempt::text||':'||p_version::text AND j.cause_key=p_attempt::text AND j.payload=p_attempt::text||'|'||p_version::text)
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_FINANCIAL_RESULT_EVENT_DELIVERY_REQUIRED' USING ERRCODE='23514';END IF;
 IF r.created_xid=pg_current_xact_id() AND NOT EXISTS(SELECT 1 FROM job j WHERE j.id=r.job_id AND j.status='RUNNING' AND j.owner_token=r.owner_token AND j.lease_until>clock_timestamp()) THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_RESULT_FINAL_LEASE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION exchange_financial_result_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN PERFORM verify_exchange_financial_result(NEW.attempt_id,NEW.result_version);RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER exchange_financial_result_graph_required AFTER INSERT ON claim_exchange_refund_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_financial_result_graph();
CREATE CONSTRAINT TRIGGER exchange_financial_result_event_graph AFTER INSERT ON claim_exchange_refund_result_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_financial_result_graph();

-- A common fee application consumes an exact typed result identity. Each registry row must have its
-- real immutable RETURN or EXCHANGE_REFUND owner row; registry-only commits are forbidden below.
ALTER TABLE claim_financial_refund_binding ADD CONSTRAINT financial_result_binding_identity UNIQUE(attempt_id,intent_id,order_id,seller_order_id,fee_assessment_id);
CREATE TABLE claim_financial_result (
 attempt_id uuid NOT NULL,result_version bigint NOT NULL,intent_id uuid NOT NULL,assessment_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 intent_kind varchar(32) NOT NULL CHECK(intent_kind IN('RETURN','EXCHANGE_REFUND')),amount bigint NOT NULL,result_state varchar(16) NOT NULL,result_reference varchar(200),result_at timestamptz,fingerprint varchar(64) NOT NULL,
 PRIMARY KEY(attempt_id,result_version),UNIQUE(assessment_id,attempt_id,result_version,result_state,amount,result_reference),
 FOREIGN KEY(attempt_id,intent_id,order_id,seller_order_id,assessment_id) REFERENCES claim_financial_refund_binding(attempt_id,intent_id,order_id,seller_order_id,fee_assessment_id)
);
INSERT INTO claim_financial_result SELECT attempt_id,result_version,intent_id,assessment_id,order_id,seller_order_id,'RETURN',amount,result_state,result_reference,result_at,fingerprint FROM claim_return_refund_result;
-- These selectors are generated from a closed type enum, not nullable IDs supplied by a caller.
-- Every root has exactly one concrete child; the child's reverse FK also requires its root.
ALTER TABLE claim_financial_result ADD COLUMN return_attempt_id uuid GENERATED ALWAYS AS (CASE WHEN intent_kind='RETURN' THEN attempt_id END) STORED;
ALTER TABLE claim_financial_result ADD COLUMN return_result_version bigint GENERATED ALWAYS AS (CASE WHEN intent_kind='RETURN' THEN result_version END) STORED;
ALTER TABLE claim_financial_result ADD COLUMN exchange_attempt_id uuid GENERATED ALWAYS AS (CASE WHEN intent_kind='EXCHANGE_REFUND' THEN attempt_id END) STORED;
ALTER TABLE claim_financial_result ADD COLUMN exchange_result_version bigint GENERATED ALWAYS AS (CASE WHEN intent_kind='EXCHANGE_REFUND' THEN result_version END) STORED;
ALTER TABLE claim_financial_result ADD FOREIGN KEY(return_attempt_id,return_result_version) REFERENCES claim_return_refund_result(attempt_id,result_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_financial_result ADD FOREIGN KEY(exchange_attempt_id,exchange_result_version) REFERENCES claim_exchange_refund_result(attempt_id,result_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_financial_result ADD FOREIGN KEY(assessment_id,return_attempt_id,return_result_version,result_state,amount,result_reference) REFERENCES claim_return_refund_result(assessment_id,attempt_id,result_version,result_state,amount,result_reference) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_financial_result ADD FOREIGN KEY(assessment_id,exchange_attempt_id,exchange_result_version,result_state,amount,result_reference) REFERENCES claim_exchange_refund_result(assessment_id,attempt_id,result_version,result_state,amount,result_reference) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_return_refund_result ADD FOREIGN KEY(attempt_id,result_version) REFERENCES claim_financial_result(attempt_id,result_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE claim_exchange_refund_result ADD FOREIGN KEY(attempt_id,result_version) REFERENCES claim_financial_result(attempt_id,result_version) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER financial_result_immutable BEFORE UPDATE OR DELETE ON claim_financial_result FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE FUNCTION register_typed_financial_result() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 INSERT INTO claim_financial_result(attempt_id,result_version,intent_id,assessment_id,order_id,seller_order_id,intent_kind,amount,result_state,result_reference,result_at,fingerprint)
 VALUES(NEW.attempt_id,NEW.result_version,NEW.intent_id,NEW.assessment_id,NEW.order_id,NEW.seller_order_id,CASE WHEN TG_TABLE_NAME='claim_return_refund_result' THEN 'RETURN' ELSE 'EXCHANGE_REFUND' END,NEW.amount,NEW.result_state,NEW.result_reference,NEW.result_at,NEW.fingerprint);
 RETURN NULL;
END $$;

CREATE FUNCTION verify_exchange_refund_quote(p_quote uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE q claim_fee_quote;c claim_exchange_refund_fee_quote_context;e claim_exchange_request;n integer;
BEGIN
 SELECT * INTO q FROM claim_fee_quote WHERE id=p_quote;SELECT * INTO c FROM claim_exchange_refund_fee_quote_context WHERE quote_id=p_quote;
 IF q.id IS NULL OR c.quote_id IS NULL OR q.purpose<>'INSPECTED_EXCHANGE_REFUND' OR q.source_schema_version<>2 THEN RAISE EXCEPTION 'TYPED_EXCHANGE_REFUND_QUOTE_CONTEXT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO e FROM claim_exchange_request WHERE id=c.exchange_id;SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=c.exchange_id;
 IF e.id IS NULL OR e.order_id<>q.order_id OR e.seller_order_id<>q.seller_order_id OR e.version<c.exchange_revision OR n=0
  OR q.body->>'sellerOrderId' IS DISTINCT FROM q.seller_order_id::text OR(q.body->>'orderRevision')::bigint IS DISTINCT FROM q.order_revision
  OR jsonb_typeof(q.body->'units') IS DISTINCT FROM 'array' OR jsonb_array_length(q.body->'units')<>n OR jsonb_array_length(c.inspection_snapshot)<>n
  OR n<>(SELECT count(DISTINCT(x->>'itemId',x->>'ordinal')) FROM jsonb_array_elements(q.body->'units') x)
  OR n<>(SELECT count(DISTINCT(x->>'itemId',x->>'ordinal')) FROM jsonb_array_elements(c.inspection_snapshot) x)
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_REFUND_QUOTE_UNITS_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(c.inspection_snapshot) x
  LEFT JOIN claim_exchange_inspection_unit i ON i.exchange_id=e.id AND i.item_id::text=x->>'itemId' AND i.ordinal::text=x->>'ordinal'
  LEFT JOIN claim_exchange_inspection h ON h.id=i.inspection_id
  LEFT JOIN claim_exchange_collection received ON received.id=i.collection_id
  LEFT JOIN order_exchange_physical_unit fixed ON fixed.exchange_id=e.id AND fixed.item_id=i.item_id AND fixed.ordinal=i.ordinal
  WHERE i.inspection_id IS NULL OR h.version>c.exchange_revision OR received.version>c.exchange_revision OR received.status IS DISTINCT FROM 'RECEIVED'
   OR i.physical_id IS DISTINCT FROM fixed.physical_id OR x->>'physicalId' IS DISTINCT FROM fixed.physical_id::text
   OR x->>'actualSkuId' IS DISTINCT FROM fixed.source_snapshot->'stock'->>'skuId'
   OR x->>'collection' IS DISTINCT FROM 'RECEIVED' OR x->>'collectionId' IS DISTINCT FROM i.collection_id::text
   OR x->>'inspection' IS DISTINCT FROM i.condition OR x->>'inspectionId' IS DISTINCT FROM i.inspection_id::text
   OR x->>'responsibility' IS DISTINCT FROM i.responsibility OR x->>'restitutionId' IS DISTINCT FROM i.restitution_id::text)
 OR EXISTS(SELECT 1 FROM jsonb_array_elements(q.body->'units') x
  LEFT JOIN claim_exchange_inspection_unit i ON i.exchange_id=e.id AND i.item_id::text=x->>'itemId' AND i.ordinal::text=x->>'ordinal'
  LEFT JOIN order_exchange_physical_unit fixed ON fixed.exchange_id=e.id AND fixed.item_id=i.item_id AND fixed.ordinal=i.ordinal
  LEFT JOIN claim_fee_quote_physical_unit quoted ON quoted.quote_id=q.id AND quoted.item_id=i.item_id AND quoted.ordinal=i.ordinal
  LEFT JOIN order_unit original ON original.item_id=i.item_id AND original.ordinal=i.ordinal
  LEFT JOIN order_item_return_policy policy ON policy.item_id=i.item_id
  LEFT JOIN order_initial_delivery_unit du ON du.item_id=i.item_id AND du.ordinal=i.ordinal
  LEFT JOIN order_initial_delivery delivery ON delivery.event_id=du.event_id
  WHERE i.inspection_id IS NULL OR original.item_id IS NULL OR policy.item_id IS NULL OR delivery.event_id IS NULL
   OR quoted.physical_id IS DISTINCT FROM fixed.physical_id OR quoted.source_snapshot IS DISTINCT FROM fixed.source_snapshot
   OR x->>'physicalId' IS DISTINCT FROM fixed.physical_id::text OR x->>'responsibility' IS DISTINCT FROM i.responsibility
   OR x->>'originalShipmentId' IS DISTINCT FROM delivery.shipment_id::text OR x->>'net' IS DISTINCT FROM original.net::text
   OR x->>'policyFee' IS DISTINCT FROM COALESCE(policy.policy_snapshot->>'amount','3000')
   OR x->>'policySource' IS DISTINCT FROM COALESCE(policy.policy_snapshot->>'source','LEGACY_UNSPECIFIED')
   OR x->>'policyRevision' IS DISTINCT FROM COALESCE(policy.policy_snapshot->>'revision','0')
   OR x->>'policyVersion' IS DISTINCT FROM 'P09-RETURN-PRODUCT-MAX-1')
 THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_INSPECTION_REFUND_QUOTE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION exchange_refund_quote_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN PERFORM verify_exchange_refund_quote(NEW.quote_id);RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER exchange_refund_quote_exact AFTER INSERT ON claim_exchange_refund_fee_quote_context DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_quote_guard();

CREATE FUNCTION verify_exchange_refund_consent(p_exchange uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c claim_exchange_refund_consent;e claim_exchange_request;n integer;
BEGIN
 SELECT * INTO c FROM claim_exchange_refund_consent WHERE exchange_id=p_exchange;SELECT * INTO e FROM claim_exchange_request WHERE id=p_exchange;
 IF c.id IS NULL OR e.id IS NULL OR e.version<c.revision OR e.current_sequence<>c.sequence OR e.state NOT IN('REFUND_PENDING','REVIEW_REQUIRED','SETTLEMENT_PENDING','REFUNDED')
  OR(e.order_id,e.seller_order_id,e.hold_id,e.cause_id) IS DISTINCT FROM(c.order_id,c.seller_order_id,c.hold_id,c.cause_id)
  OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version
   WHERE command.exchange_id=e.id AND command.command_key=c.request_key AND command.fingerprint=c.fingerprint AND command.action='REFUND_PENDING' AND command.version=c.revision
    AND history.action='REFUND_PENDING' AND history.reason=c.reason AND history.occurred_at=c.accepted_at AND c.receipt->'progress'=command.receipt)
  OR NOT EXISTS(SELECT 1 FROM claim_exchange_refund_fee_quote_context context JOIN claim_fee_quote quote ON quote.id=context.quote_id
   JOIN claim_fee_assessment a ON a.quote_id=quote.id JOIN claim_fee_consent consent ON consent.assessment_id=a.id
   JOIN claim_fee_binding_history binding ON binding.assessment_id=a.id AND binding.attempt_id=c.attempt_id
   JOIN payment_refund_attempt attempt ON attempt.id=binding.attempt_id
   WHERE context.quote_id=c.quote_id AND context.exchange_id=e.id AND context.exchange_revision=c.original_revision AND context.inspection_fingerprint=c.inspection_fingerprint
    AND quote.order_id=e.order_id AND quote.seller_order_id=e.seller_order_id AND a.id=c.assessment_id AND a.intent_id=e.id AND a.intent_kind='EXCHANGE_REFUND'
    AND a.order_id=e.order_id AND a.seller_order_id=e.seller_order_id AND a.created_xid=c.created_xid AND a.state IN('BOUND','APPLIED')
    AND consent.legacy_consent=c.legacy_consent AND consent.legacy_consent=quote.legacy_consent_required
    AND attempt.claim_id=e.id AND attempt.order_id=e.order_id AND attempt.seller_order_id=e.seller_order_id AND attempt.purpose='ITEM' AND attempt.sequence=1
    AND attempt.amount=a.refund AND attempt.original_reference=quote.fingerprint AND attempt.cause_id=c.id
    AND c.receipt->>'assessmentId'=a.id::text AND c.receipt->>'refundAttemptId'=attempt.id::text)
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-refund-consent:'||e.id::text AND j.cause_key=e.id::text AND j.payload=e.id::text)
  OR EXISTS(SELECT 1 FROM claim_exchange_dispatch_effect WHERE exchange_id=e.id)
  OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=e.id AND status='COMMITTED')
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_REFUND_CONSENT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM verify_exchange_refund_quote(c.quote_id);
 SELECT count(*) INTO n FROM inventory_exchange_reservation WHERE exchange_id=e.id AND sequence=c.sequence;
 IF c.release_id IS NOT NULL THEN
  IF c.release_id<>c.id OR n=0 OR NOT EXISTS(SELECT 1 FROM inventory_exchange_release released WHERE released.id=c.release_id AND released.exchange_id=e.id AND released.sequence=c.sequence AND released.reason='REFUND_CONVERSION' AND released.released_at=c.accepted_at)
   OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=e.id AND sequence=c.sequence AND status<>'RELEASED')
  THEN RAISE EXCEPTION 'EXACT_CONVERTED_EXCHANGE_RESERVATION_RELEASE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSIF NOT(c.sequence=0 AND n=0) AND NOT(c.sequence>0 AND n>0
  AND NOT EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=e.id AND sequence=c.sequence AND status<>'EXPIRED')
  AND EXISTS(SELECT 1 FROM inventory_exchange_release released JOIN claim_exchange_expiry_delivery effect ON effect.release_id=released.id WHERE released.exchange_id=e.id AND released.sequence=c.sequence AND released.reason='EXPIRED' AND effect.mode='EXPIRED' AND released.released_at<=c.accepted_at))
 THEN RAISE EXCEPTION 'CONVERSION_WITHOUT_RELEASE_REQUIRES_ACTUAL_EXPIRED_OR_NO_ALLOCATION' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION exchange_refund_consent_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE target uuid;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_request' THEN target=NEW.id;
 ELSIF TG_TABLE_NAME='claim_financial_intent' THEN IF NEW.kind<>'EXCHANGE_REFUND' THEN RETURN NULL;END IF;target=NEW.id;
 ELSIF TG_TABLE_NAME='claim_fee_assessment' THEN IF NEW.intent_kind<>'EXCHANGE_REFUND' THEN RETURN NULL;END IF;target=NEW.intent_id;
 ELSIF TG_TABLE_NAME='payment_refund_attempt' THEN SELECT id INTO target FROM claim_financial_intent WHERE id=NEW.claim_id AND kind='EXCHANGE_REFUND';IF target IS NULL THEN RETURN NULL;END IF;
 ELSE target=NEW.exchange_id;END IF;
 IF EXISTS(SELECT 1 FROM claim_exchange_refund_consent WHERE exchange_id=target) THEN PERFORM verify_exchange_refund_consent(target);
 ELSIF EXISTS(SELECT 1 FROM claim_exchange_request WHERE id=target AND state IN('REFUND_PENDING','SETTLEMENT_PENDING','REFUNDED'))
  OR EXISTS(SELECT 1 FROM claim_financial_intent WHERE id=target AND kind='EXCHANGE_REFUND')
 THEN RAISE EXCEPTION 'EXPLICIT_EXCHANGE_REFUND_CONSENT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_refund_consent_graph AFTER INSERT ON claim_exchange_refund_consent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_consent_guard();
CREATE CONSTRAINT TRIGGER exchange_refund_workflow_graph AFTER INSERT OR UPDATE ON claim_exchange_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_consent_guard();
CREATE CONSTRAINT TRIGGER exchange_refund_intent_graph AFTER INSERT ON claim_financial_intent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_consent_guard();
CREATE CONSTRAINT TRIGGER exchange_refund_assessment_graph AFTER INSERT OR UPDATE ON claim_fee_assessment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_consent_guard();
CREATE CONSTRAINT TRIGGER exchange_refund_payment_graph AFTER INSERT OR UPDATE ON payment_refund_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_consent_guard();
ALTER TABLE claim_financial_intent ADD COLUMN exchange_refund_id uuid GENERATED ALWAYS AS (CASE WHEN kind='EXCHANGE_REFUND' THEN id END) STORED;
ALTER TABLE claim_financial_intent ADD FOREIGN KEY(exchange_refund_id) REFERENCES claim_exchange_refund_consent(exchange_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER return_financial_result_registry AFTER INSERT ON claim_return_refund_result FOR EACH ROW EXECUTE FUNCTION register_typed_financial_result();
CREATE TRIGGER exchange_financial_result_registry AFTER INSERT ON claim_exchange_refund_result FOR EACH ROW EXECUTE FUNCTION register_typed_financial_result();
CREATE FUNCTION typed_financial_result_registry_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE actual record;
BEGIN
 IF NEW.intent_kind='RETURN' THEN SELECT * INTO actual FROM claim_return_refund_result WHERE attempt_id=NEW.attempt_id AND result_version=NEW.result_version;
 ELSE SELECT * INTO actual FROM claim_exchange_refund_result WHERE attempt_id=NEW.attempt_id AND result_version=NEW.result_version;END IF;
 IF actual.attempt_id IS NULL OR(actual.intent_id,actual.assessment_id,actual.order_id,actual.seller_order_id,actual.amount,actual.result_state,actual.result_reference,actual.result_at,actual.fingerprint)
  IS DISTINCT FROM(NEW.intent_id,NEW.assessment_id,NEW.order_id,NEW.seller_order_id,NEW.amount,NEW.result_state,NEW.result_reference,NEW.result_at,NEW.fingerprint)
  OR NOT EXISTS(SELECT 1 FROM claim_financial_intent i WHERE i.id=NEW.intent_id AND i.kind=NEW.intent_kind)
 THEN RAISE EXCEPTION 'EXACT_TYPED_FINANCIAL_RESULT_OWNER_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER financial_result_owner_required AFTER INSERT ON claim_financial_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION typed_financial_result_registry_guard();
DO $$ DECLARE previous record;BEGIN
 FOR previous IN SELECT conname FROM pg_constraint WHERE conrelid='claim_fee_application'::regclass AND confrelid='claim_return_refund_result'::regclass LOOP
  EXECUTE format('ALTER TABLE claim_fee_application DROP CONSTRAINT %I',previous.conname);
 END LOOP;
END $$;
ALTER TABLE claim_fee_application ADD CONSTRAINT exact_typed_fee_result_required FOREIGN KEY(assessment_id,attempt_id,result_version,result_state,amount,result_reference) REFERENCES claim_financial_result(assessment_id,attempt_id,result_version,result_state,amount,result_reference);

CREATE OR REPLACE FUNCTION validate_fee_application() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment a JOIN claim_fee_binding b ON b.assessment_id=a.id JOIN payment_refund_attempt p ON p.id=b.attempt_id JOIN claim_financial_intent i ON i.id=a.intent_id JOIN order_unit_hold h ON h.id=i.hold_id
 WHERE a.id=NEW.assessment_id AND a.state='BOUND' AND p.id=NEW.attempt_id AND p.state='SUCCEEDED' AND p.version=NEW.result_version AND p.result_reference=NEW.result_reference AND p.amount=NEW.amount AND a.refund=NEW.amount AND a.increment=NEW.increment AND a.net::numeric-a.refund=NEW.deducted AND a.additional_due=NEW.additional_due AND h.kind=i.hold_kind AND h.state='ACTIVE')
 THEN RAISE EXCEPTION 'FEE_APPLICATION_RESULT_MISMATCH' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION inspected_return_quote_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;v_quote marketplace.claim_fee_quote%ROWTYPE;v_context marketplace.claim_return_fee_quote_context%ROWTYPE;
 v_request marketplace.claim_return_request%ROWTYPE;v_count bigint;
BEGIN
 IF TG_TABLE_NAME='claim_fee_quote' THEN v_id:=NEW.id;ELSE v_id:=NEW.quote_id;END IF;
 SELECT * INTO STRICT v_quote FROM marketplace.claim_fee_quote WHERE id=v_id;
 SELECT * INTO v_context FROM marketplace.claim_return_fee_quote_context WHERE quote_id=v_id;
 IF v_quote.purpose='INSPECTED_EXCHANGE_REFUND' THEN PERFORM marketplace.verify_exchange_refund_quote(v_id);RETURN NULL;END IF;
 IF v_quote.purpose='OPEN' THEN
  IF FOUND THEN RAISE EXCEPTION 'OPEN_QUOTE_CANNOT_CLAIM_INSPECTION' USING ERRCODE='23514';END IF;
  RETURN NULL;
 END IF;
 IF NOT FOUND THEN RAISE EXCEPTION 'INSPECTED_RETURN_QUOTE_CONTEXT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO v_request FROM marketplace.claim_return_request WHERE id=v_context.return_id;
 IF NOT FOUND OR v_request.order_id<>v_quote.order_id OR v_request.seller_order_id<>v_quote.seller_order_id
  OR v_request.version<v_context.return_revision OR NOT EXISTS(SELECT 1 FROM marketplace.claim_return_history h WHERE h.return_id=v_request.id AND h.version=v_context.return_revision AND h.action='AWAITING_CONSENT')
  OR v_quote.body->>'sellerOrderId' IS DISTINCT FROM v_quote.seller_order_id::text
  OR (v_quote.body->>'orderRevision')::bigint IS DISTINCT FROM v_quote.order_revision THEN
  RAISE EXCEPTION 'INSPECTED_RETURN_QUOTE_IDENTITY_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO v_count FROM marketplace.claim_return_unit WHERE return_id=v_request.id;
 IF v_count=0 OR jsonb_array_length(v_context.inspection_snapshot)<>v_count OR jsonb_typeof(v_quote.body->'units') IS DISTINCT FROM 'array'
  OR jsonb_array_length(v_quote.body->'units')<>v_count
  OR (SELECT count(DISTINCT(j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_context.inspection_snapshot)j(value))<>v_count
  OR (SELECT count(DISTINCT(j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_quote.body->'units')j(value))<>v_count THEN
  RAISE EXCEPTION 'EXACT_INSPECTED_RETURN_QUOTE_UNITS_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(v_context.inspection_snapshot)j(value)
  LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=v_request.id AND i.item_id::text=j.value->>'itemId' AND i.ordinal::text=j.value->>'ordinal'
  LEFT JOIN marketplace.claim_return_inspection h ON h.id=i.inspection_id
  WHERE i.inspection_id IS NULL OR h.version>v_context.return_revision
   OR j.value->>'collection' IS DISTINCT FROM 'RECEIVED' OR j.value->>'collectionId' IS DISTINCT FROM i.collection_id::text
   OR j.value->>'inspection' IS DISTINCT FROM i.condition OR j.value->>'inspectionId' IS DISTINCT FROM i.inspection_id::text
   OR j.value->>'responsibility' IS DISTINCT FROM i.responsibility OR j.value->>'restitutionId' IS DISTINCT FROM i.restitution_id::text)
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_quote.body->'units')j(value)
   LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=v_request.id AND i.item_id::text=j.value->>'itemId' AND i.ordinal::text=j.value->>'ordinal'
   LEFT JOIN marketplace.order_unit n ON n.item_id=i.item_id AND n.ordinal=i.ordinal
   LEFT JOIN marketplace.order_item_return_policy p ON p.item_id=i.item_id
   LEFT JOIN marketplace.order_initial_delivery_unit du ON du.item_id=i.item_id AND du.ordinal=i.ordinal
   LEFT JOIN marketplace.order_initial_delivery d ON d.event_id=du.event_id
   WHERE i.inspection_id IS NULL OR n.item_id IS NULL OR p.item_id IS NULL OR d.event_id IS NULL OR j.value->>'responsibility' IS DISTINCT FROM i.responsibility
    OR j.value->>'originalShipmentId' IS DISTINCT FROM d.shipment_id::text
    OR j.value->>'net' IS DISTINCT FROM n.net::text OR j.value->>'policyFee' IS DISTINCT FROM COALESCE(p.policy_snapshot->>'amount','3000')
    OR j.value->>'policySource' IS DISTINCT FROM COALESCE(p.policy_snapshot->>'source','LEGACY_UNSPECIFIED')
    OR j.value->>'policyRevision' IS DISTINCT FROM COALESCE(p.policy_snapshot->>'revision','0')
    OR j.value->>'policyVersion' IS DISTINCT FROM 'P09-RETURN-PRODUCT-MAX-1') THEN
  RAISE EXCEPTION 'ACTUAL_RETURN_INSPECTION_QUOTE_SNAPSHOT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
 RAISE EXCEPTION 'INSPECTED_RETURN_QUOTE_FORMAT' USING ERRCODE='23514';
END $$;

CREATE OR REPLACE FUNCTION verify_exchange_release(p_release uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE released inventory_exchange_release;r record;expected numeric;n integer;
BEGIN
 SELECT * INTO released FROM inventory_exchange_release WHERE id=p_release;IF released.id IS NULL THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 IF released.reason='REFUND_CONVERSION' THEN
  PERFORM verify_exchange_refund_consent(released.exchange_id);
  IF NOT EXISTS(SELECT 1 FROM claim_exchange_refund_consent c WHERE c.id=released.id AND c.exchange_id=released.exchange_id AND c.sequence=released.sequence AND c.release_id=released.id AND c.accepted_at=released.released_at)
   OR NOT EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence)
   OR (SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence)<>(SELECT count(*) FROM inventory_exchange_release_unit WHERE release_id=p_release)
   OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r WHERE r.exchange_id=released.exchange_id AND r.sequence=released.sequence AND(r.status<>'RELEASED' OR NOT EXISTS(
    SELECT 1 FROM inventory_exchange_release_unit u JOIN inventory_exchange_event e ON e.id=u.event_id WHERE u.release_id=p_release AND u.reservation_id=r.id AND u.exchange_id=r.exchange_id AND u.sequence=r.sequence AND u.cause='RELEASE' AND e.reservation_id=r.id AND e.cause='RELEASE' AND e.occurred_at=released.released_at AND e.after_reserved=e.before_reserved-r.quantity AND e.version=e.before_version+1)))
  THEN RAISE EXCEPTION 'EXACT_CONVERTED_ALLOCATION_RELEASE_REQUIRED' USING ERRCODE='23514';END IF;
  FOR r IN SELECT * FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence LOOP
   SELECT COALESCE((SELECT sum(quantity) FROM inventory_reservation WHERE sku_id=r.target_sku_id AND status='RESERVED'),0)+COALESCE((SELECT sum(quantity) FROM inventory_exchange_reservation WHERE target_sku_id=r.target_sku_id AND status='RESERVED'),0) INTO expected;
   IF NOT EXISTS(SELECT 1 FROM inventory_balance WHERE sku_id=r.target_sku_id AND reserved=expected) THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_BALANCE_AND_ACTIVE_RESERVATIONS_REQUIRED' USING ERRCODE='23514';END IF;
  END LOOP;RETURN;
 ELSIF released.reason<>'EXPIRED' THEN RAISE EXCEPTION 'EXCHANGE_FINANCIAL_CONVERSION_NOT_BOUND' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence;
 IF n=0 OR n<>(SELECT count(*) FROM inventory_exchange_release_unit WHERE release_id=p_release) OR NOT EXISTS(SELECT 1 FROM claim_exchange_expiry_delivery delivery WHERE delivery.release_id=p_release AND delivery.exchange_id=released.exchange_id AND delivery.sequence=released.sequence AND delivery.mode='EXPIRED' AND delivery.recorded_at=released.released_at) THEN RAISE EXCEPTION 'EXACT_EXCHANGE_RELEASE_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;
 FOR r IN SELECT * FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence LOOP
  IF r.status<>'EXPIRED' OR released.released_at<COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=r.id),r.expires_at) OR NOT EXISTS(SELECT 1 FROM inventory_exchange_release_unit unit JOIN inventory_exchange_event event ON event.id=unit.event_id WHERE unit.release_id=p_release AND unit.exchange_id=released.exchange_id AND unit.sequence=released.sequence AND unit.reservation_id=r.id AND unit.cause='EXPIRE' AND event.reservation_id=r.id AND event.cause=unit.cause AND event.occurred_at=released.released_at AND event.after_reserved=event.before_reserved-r.quantity AND event.version=event.before_version+1) THEN RAISE EXCEPTION 'EXACT_EXPIRED_ALLOCATION_RELEASE_REQUIRED' USING ERRCODE='23514';END IF;
  SELECT COALESCE((SELECT sum(quantity) FROM inventory_reservation WHERE sku_id=r.target_sku_id AND status='RESERVED'),0)+COALESCE((SELECT sum(quantity) FROM inventory_exchange_reservation WHERE target_sku_id=r.target_sku_id AND status='RESERVED'),0) INTO expected;
  IF NOT EXISTS(SELECT 1 FROM inventory_balance WHERE sku_id=r.target_sku_id AND reserved=expected) THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_BALANCE_AND_ACTIVE_RESERVATIONS_REQUIRED' USING ERRCODE='23514';END IF;
 END LOOP;
END $$;

CREATE OR REPLACE FUNCTION exchange_reship_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE v_id uuid;c claim_exchange_request;r claim_exchange_dispatch_request;h fulfillment_exchange_shipment;e claim_exchange_dispatch_effect;n integer;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_request' THEN v_id=NEW.id;
 ELSIF TG_TABLE_NAME='inventory_exchange_commit' THEN SELECT exchange_id INTO v_id FROM inventory_exchange_reservation WHERE id=NEW.reservation_id;
 ELSE v_id=NEW.exchange_id;END IF;
 SELECT * INTO c FROM claim_exchange_request WHERE id=v_id;SELECT * INTO r FROM claim_exchange_dispatch_request WHERE exchange_id=v_id AND sequence=c.current_sequence;
 IF r.id IS NULL THEN
  IF c.state IN('DISPATCH_PENDING','DISPATCHED') THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_DISPATCH_REQUEST_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_exchange_command z JOIN claim_exchange_history t ON t.exchange_id=z.exchange_id AND t.version=z.version WHERE z.exchange_id=v_id AND z.command_key=r.command_key AND z.action='DISPATCH_PENDING' AND z.version=r.version AND t.action=z.action)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=v_id AND NOT EXISTS(SELECT 1 FROM claim_exchange_collection x JOIN claim_exchange_inspection_unit i ON i.collection_id=x.id AND i.exchange_id=x.exchange_id AND i.item_id=x.item_id AND i.ordinal=x.ordinal AND i.physical_id=x.physical_id WHERE x.exchange_id=v_id AND x.item_id=u.item_id AND x.ordinal=u.ordinal AND x.status='RECEIVED'))
 THEN RAISE EXCEPTION 'EXACT_INSPECTED_EXCHANGE_DISPATCH_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO h FROM fulfillment_exchange_shipment WHERE exchange_id=v_id;SELECT * INTO e FROM claim_exchange_dispatch_effect WHERE exchange_id=v_id;
 IF h.id IS NULL THEN
  IF EXISTS(SELECT 1 FROM claim_exchange_refund_consent x WHERE x.exchange_id=v_id AND x.sequence=c.current_sequence) THEN
   PERFORM verify_exchange_refund_consent(v_id);
   IF e.exchange_id IS NOT NULL OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=v_id AND sequence=c.current_sequence AND status NOT IN('EXPIRED','RELEASED')) THEN RAISE EXCEPTION 'CONVERTED_EXCHANGE_CANNOT_DISPATCH' USING ERRCODE='23514';END IF;RETURN NULL;
  END IF;
  IF EXISTS(SELECT 1 FROM inventory_exchange_release released JOIN claim_exchange_expiry_delivery delivery ON delivery.release_id=released.id WHERE released.exchange_id=v_id AND released.sequence=c.current_sequence AND released.reason='EXPIRED' AND delivery.mode='EXPIRED') AND c.state IN('EXPIRED','REVIEW_REQUIRED') THEN RETURN NULL;END IF;
  IF c.state<>'DISPATCH_PENDING' OR e.exchange_id IS NOT NULL OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=v_id AND sequence=c.current_sequence AND status<>'RESERVED') THEN RAISE EXCEPTION 'PENDING_EXCHANGE_CANNOT_COMMIT_STOCK' USING ERRCODE='23514';END IF;RETURN NULL;
 END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=v_id;
 IF e.exchange_id IS NULL OR e.version<>(r.version+1+CASE WHEN EXISTS(SELECT 1 FROM claim_exchange_extension x WHERE x.exchange_id=v_id AND x.prior_state='DISPATCH_PENDING' AND x.original_revision=r.version AND x.revision=r.version+1) THEN 1 ELSE 0 END) OR e.dispatch_id<>r.id OR e.shipment_id<>h.id OR h.dispatch_id<>r.id OR h.order_id<>c.order_id OR h.seller_order_id<>c.seller_order_id
 OR c.state NOT IN('DISPATCHED','COMPLETED') OR n<>(SELECT count(*) FROM fulfillment_exchange_allocation WHERE exchange_id=v_id)
 OR n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=v_id AND sequence=c.current_sequence AND status='COMMITTED')
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=e.job_id AND j.type='EXCHANGE_RESHIP.v1' AND j.dedupe_key='exchange-reship:'||r.id::text AND j.cause_key=v_id::text AND j.payload=v_id::text||'|'||r.id::text)
 OR NOT EXISTS(SELECT 1 FROM order_exchange_dispatch d JOIN fulfillment_exchange_event f ON f.id=d.event_id WHERE d.exchange_id=v_id AND d.shipment_id=h.id AND d.dispatched_at=h.shipped_at AND f.shipment_id=h.id AND f.state='SHIPPED' AND f.occurred_at=h.shipped_at)
 OR EXISTS(SELECT 1 FROM inventory_exchange_reservation x LEFT JOIN inventory_exchange_commit z ON z.reservation_id=x.id LEFT JOIN inventory_exchange_source source ON source.reservation_id=x.id LEFT JOIN fulfillment_exchange_allocation a ON a.reservation_id=x.id WHERE x.exchange_id=v_id AND x.sequence=c.current_sequence AND (z.reservation_id IS NULL OR z.dispatch_id<>r.id OR z.committed_at>=COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=x.id),x.expires_at) OR source.source_id IS NULL OR a.exchange_id IS DISTINCT FROM v_id OR a.source_id IS DISTINCT FROM source.source_id OR a.item_id<>x.item_id OR a.ordinal<>x.ordinal OR NOT EXISTS(SELECT 1 FROM order_physical_source p WHERE p.id=a.physical_id AND p.predecessor_id=a.predecessor_id AND p.committed_source_id=a.source_id)))
 THEN RAISE EXCEPTION 'EXACT_COMMITTED_EXCHANGE_SHIPMENT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;

ALTER TABLE claim_exchange_expiry_delivery DROP CONSTRAINT claim_exchange_expiry_delivery_mode_check;
ALTER TABLE claim_exchange_expiry_delivery ADD CHECK(mode IN('EXPIRED','SUPERSEDED','COMMITTED','CONVERTED'));

CREATE OR REPLACE FUNCTION verify_exchange_expiry_delivery(p_job uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE delivery claim_exchange_expiry_delivery;schedule claim_exchange_reservation_schedule;c claim_exchange_request;
BEGIN
 SELECT * INTO delivery FROM claim_exchange_expiry_delivery WHERE job_id=p_job;SELECT * INTO schedule FROM claim_exchange_reservation_schedule WHERE job_id=p_job;SELECT * INTO c FROM claim_exchange_request WHERE id=delivery.exchange_id;PERFORM verify_exchange_reservation_schedule(delivery.exchange_id,delivery.sequence,delivery.epoch);
 IF delivery.job_id IS NULL OR c.id IS NULL THEN RAISE EXCEPTION 'EXACT_EXPIRY_DELIVERY_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 IF delivery.mode='EXPIRED' THEN
  IF delivery.recorded_at<schedule.due_at OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version WHERE command.exchange_id=delivery.exchange_id AND command.command_key='reservation-expired:'||delivery.sequence::text||':'||delivery.epoch::text AND command.action='EXPIRED' AND command.version=delivery.workflow_revision AND command.receipt=delivery.receipt AND history.action='EXPIRED' AND history.actor='RECORDED_WORKER' AND history.occurred_at=delivery.recorded_at)
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-reservation-expired-'||delivery.sequence::text||':'||delivery.exchange_id::text AND j.cause_key=delivery.exchange_id::text AND j.payload=delivery.exchange_id::text)
  THEN RAISE EXCEPTION 'EXACT_EXPIRY_WORKFLOW_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;PERFORM verify_exchange_release(delivery.release_id);
 ELSIF delivery.mode='CONVERTED' THEN
  PERFORM verify_exchange_refund_consent(delivery.exchange_id);
  IF NOT EXISTS(SELECT 1 FROM claim_exchange_refund_consent c WHERE c.exchange_id=delivery.exchange_id AND c.sequence=delivery.sequence)
   OR delivery.release_id IS NOT NULL OR delivery.workflow_revision IS NOT NULL OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r WHERE r.exchange_id=delivery.exchange_id AND r.sequence=delivery.sequence AND r.status NOT IN('RELEASED','EXPIRED'))
  THEN RAISE EXCEPTION 'EXACT_CONVERTED_EXPIRY_RETIREMENT_REQUIRED' USING ERRCODE='23514';END IF;
 ELSIF delivery.mode='SUPERSEDED' THEN
  IF NOT(c.current_sequence>delivery.sequence OR EXISTS(SELECT 1 FROM claim_exchange_reservation_schedule newer WHERE newer.exchange_id=delivery.exchange_id AND newer.sequence=delivery.sequence AND newer.epoch>delivery.epoch)) THEN RAISE EXCEPTION 'EXACT_SUPERSEDED_DEADLINE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM claim_exchange_dispatch_request request JOIN claim_exchange_dispatch_effect effect ON effect.dispatch_id=request.id WHERE request.exchange_id=delivery.exchange_id AND request.sequence=delivery.sequence) OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r WHERE r.exchange_id=delivery.exchange_id AND r.sequence=delivery.sequence AND r.status<>'COMMITTED') THEN RAISE EXCEPTION 'ACTUAL_COMMITTED_GENERATION_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
END $$;
