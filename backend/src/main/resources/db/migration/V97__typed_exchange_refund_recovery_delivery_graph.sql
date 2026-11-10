-- EXCHANGE_REFUND recovery has its own exact manifests; RETURN tables, keys and guards retain their meaning.
ALTER TABLE claim_exchange_request ADD CONSTRAINT exchange_recovery_parent_identity UNIQUE(id,order_id,seller_order_id);
-- Recovery is a new delivery of an existing fact, never a new financial authority.
CREATE TABLE claim_exchange_refund_recovery_run (
 id uuid PRIMARY KEY,scan_version varchar(40) NOT NULL CHECK(scan_version='P09-E-REFUND-RECOVERY-1'),started_at timestamptz NOT NULL,
 stages jsonb NOT NULL CHECK(jsonb_typeof(stages)='array' AND jsonb_array_length(stages) BETWEEN 1 AND 3)
);
CREATE TABLE claim_exchange_refund_recovery_stage (
 run_id uuid NOT NULL REFERENCES claim_exchange_refund_recovery_run(id),stage varchar(24) NOT NULL CHECK(stage IN ('ITEM_EFFECT','SHIPPING_RESULT','SELLER_REEVALUATE')),
 upper_id uuid,cursor_id uuid,completed boolean NOT NULL DEFAULT false,first_job_id uuid NOT NULL UNIQUE REFERENCES job(id) DEFERRABLE INITIALLY DEFERRED,
 cursor_page_id uuid,PRIMARY KEY(run_id,stage),CHECK(upper_id IS NOT NULL OR cursor_id IS NULL)
);
CREATE TABLE claim_exchange_refund_recovery_page (
 id uuid PRIMARY KEY,run_id uuid NOT NULL,stage varchar(24) NOT NULL,cursor_key varchar(36) NOT NULL,
 locator_ids jsonb NOT NULL CHECK(jsonb_typeof(locator_ids)='array' AND jsonb_array_length(locator_ids)<=50),
 scan_job_id uuid NOT NULL UNIQUE REFERENCES job(id),plan_owner_token uuid NOT NULL,planned_at timestamptz NOT NULL,
 UNIQUE(run_id,stage,cursor_key),UNIQUE(id,run_id,stage),
 FOREIGN KEY(run_id,stage) REFERENCES claim_exchange_refund_recovery_stage(run_id,stage)
);
CREATE TABLE claim_exchange_refund_recovery_delivery (
 id uuid PRIMARY KEY,run_id uuid NOT NULL REFERENCES claim_exchange_refund_recovery_run(id),stage varchar(24) NOT NULL CHECK(stage IN ('ITEM_EFFECT','SHIPPING_RESULT','SELLER_REEVALUATE')),
 page_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,exchange_id uuid NOT NULL,
 attempt_id uuid,result_version bigint,source_fact_id uuid NOT NULL,source_fingerprint varchar(64) NOT NULL CHECK(source_fingerprint ~ '^[0-9a-f]{64}$'),
 payment_id uuid,purpose varchar(10),amount bigint,original_reference varchar(200),result_state varchar(16),result_reference varchar(200),result_at timestamptz,
 job_id uuid NOT NULL UNIQUE REFERENCES job(id) DEFERRABLE INITIALLY DEFERRED,
 type varchar(100) NOT NULL,dedupe_key varchar(200) NOT NULL UNIQUE,cause_key varchar(200) NOT NULL,payload varchar(1000) NOT NULL,scheduled_at timestamptz NOT NULL,
 scan_job_id uuid NOT NULL REFERENCES job(id),scan_owner_token uuid NOT NULL,issued_at timestamptz NOT NULL,
 FOREIGN KEY(page_id,run_id,stage) REFERENCES claim_exchange_refund_recovery_page(id,run_id,stage),
 FOREIGN KEY(exchange_id,order_id,seller_order_id) REFERENCES claim_exchange_request(id,order_id,seller_order_id),
 FOREIGN KEY(attempt_id,exchange_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference)
  REFERENCES payment_refund_attempt(id,claim_id,order_id,seller_order_id,payment_id,purpose,amount,original_reference) DEFERRABLE INITIALLY DEFERRED,
 UNIQUE NULLS NOT DISTINCT(run_id,stage,source_fact_id,attempt_id,result_version,source_fingerprint),
 CHECK(dedupe_key='e-refund-recovery:'||id::text),
 CHECK((stage='ITEM_EFFECT' AND type='EXCHANGE_REFUND_RESUME.v1' AND purpose='ITEM'
    AND cause_key=exchange_id::text AND payload=exchange_id::text||'|'||attempt_id::text||'|'||result_version::text)
  OR(stage='SHIPPING_RESULT' AND type='REFUND_RESULT_RECORDED.v1' AND purpose='SHIPPING'
    AND source_fact_id=exchange_id AND cause_key=attempt_id::text AND payload=attempt_id::text||'|'||result_version::text)
  OR(stage='SELLER_REEVALUATE' AND type='SELLER_AFTERCARE_REEVALUATE.v1' AND source_fact_id=exchange_id
    AND cause_key=seller_order_id::text AND payload='EXCHANGE_REFUND_READY|'||exchange_id::text)),
 CHECK((stage IN ('ITEM_EFFECT','SHIPPING_RESULT') AND attempt_id IS NOT NULL AND payment_id IS NOT NULL AND result_version IS NOT NULL AND result_version>0
    AND amount IS NOT NULL AND amount>=0 AND purpose IS NOT NULL AND original_reference IS NOT NULL AND result_state IS NOT NULL AND result_state IN ('SUCCEEDED','FAILED','UNKNOWN'))
  OR(stage='SELLER_REEVALUATE' AND attempt_id IS NULL AND payment_id IS NULL AND result_version IS NULL AND purpose IS NULL
    AND amount IS NULL AND original_reference IS NULL AND result_state IS NULL AND result_reference IS NULL AND result_at IS NULL))
);
CREATE TABLE claim_exchange_refund_recovery_candidate (
 page_id uuid NOT NULL,run_id uuid NOT NULL,stage varchar(24) NOT NULL,exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),
 outcome varchar(20) NOT NULL CHECK(outcome IN ('DELIVERED','SKIP','INTEGRITY_ERROR')),reason varchar(100) NOT NULL,
 delivery_id uuid REFERENCES claim_exchange_refund_recovery_delivery(id) DEFERRABLE INITIALLY DEFERRED,
 scan_job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,recorded_at timestamptz NOT NULL,
 PRIMARY KEY(page_id,exchange_id),UNIQUE(run_id,stage,exchange_id),
 FOREIGN KEY(page_id,run_id,stage) REFERENCES claim_exchange_refund_recovery_page(id,run_id,stage),
 CHECK((outcome='DELIVERED' AND delivery_id IS NOT NULL) OR(outcome IN ('SKIP','INTEGRITY_ERROR') AND delivery_id IS NULL))
);
CREATE TABLE claim_exchange_refund_recovery_page_complete (
 page_id uuid PRIMARY KEY REFERENCES claim_exchange_refund_recovery_page(id),next_cursor uuid,finished boolean NOT NULL,next_job_id uuid REFERENCES job(id) DEFERRABLE INITIALLY DEFERRED,
 scan_job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,recorded_at timestamptz NOT NULL,
 CHECK((finished AND next_job_id IS NULL) OR(NOT finished AND next_job_id IS NOT NULL))
);
ALTER TABLE claim_exchange_refund_recovery_stage ADD CONSTRAINT recovery_stage_cursor_page FOREIGN KEY(cursor_page_id) REFERENCES claim_exchange_refund_recovery_page_complete(page_id) DEFERRABLE INITIALLY DEFERRED;
DO $$ DECLARE tbl text;BEGIN
 FOREACH tbl IN ARRAY ARRAY['claim_exchange_refund_recovery_run','claim_exchange_refund_recovery_page','claim_exchange_refund_recovery_delivery','claim_exchange_refund_recovery_candidate','claim_exchange_refund_recovery_page_complete'] LOOP
  EXECUTE format('CREATE TRIGGER e_immutable_recovery_evidence BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable()',tbl);
 END LOOP;
END $$;
CREATE FUNCTION e_recovery_stage_bounds_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.run_id,NEW.stage,NEW.upper_id,NEW.first_job_id) IS DISTINCT FROM(OLD.run_id,OLD.stage,OLD.upper_id,OLD.first_job_id)
 THEN RAISE EXCEPTION 'IMMUTABLE_RECOVERY_SCAN_BOUNDS' USING ERRCODE='23514';END IF;
 IF OLD.completed OR NEW.cursor_page_id IS NULL THEN RAISE EXCEPTION 'RECOVERY_CURSOR_PAGE_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER e_recovery_stage_bounds BEFORE UPDATE ON claim_exchange_refund_recovery_stage FOR EACH ROW EXECUTE FUNCTION e_recovery_stage_bounds_guard();

-- This identity proof stays valid after the issuing scan ACK; execution still needs its own live job fence.
CREATE FUNCTION exact_exchange_refund_recovery_delivery(v_job uuid,v_stage text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_delivery d
  JOIN marketplace.claim_exchange_refund_recovery_run r ON r.id=d.run_id AND r.scan_version='P09-E-REFUND-RECOVERY-1'
  JOIN marketplace.claim_exchange_refund_recovery_page p ON p.id=d.page_id AND p.run_id=d.run_id AND p.stage=d.stage
  JOIN marketplace.job j ON j.id=d.job_id
  JOIN marketplace.outbox_event e ON e.id=d.id AND e.job_id=j.id
  WHERE d.job_id=v_job AND d.stage=v_stage AND j.type=d.type AND j.dedupe_key=d.dedupe_key AND j.cause_key=d.cause_key AND j.payload=d.payload
   AND j.scheduled_at=d.scheduled_at AND d.dedupe_key='e-refund-recovery:'||d.id::text)
$$;
CREATE FUNCTION verify_exchange_refund_recovery_delivery(v_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE d marketplace.claim_exchange_refund_recovery_delivery%ROWTYPE;expected text;
BEGIN
 SELECT * INTO STRICT d FROM marketplace.claim_exchange_refund_recovery_delivery WHERE id=v_id;
 IF NOT marketplace.exact_exchange_refund_recovery_delivery(d.job_id,d.stage) OR NOT EXISTS(
  SELECT 1 FROM marketplace.job scan JOIN marketplace.claim_exchange_refund_recovery_page p ON p.id=d.page_id AND p.scan_job_id=scan.id
  WHERE scan.id=d.scan_job_id AND scan.type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' AND scan.status='RUNNING' AND scan.owner_token=d.scan_owner_token
   AND scan.cause_key=d.run_id::text AND scan.payload=d.run_id::text||'|'||d.stage||'|'||p.cursor_key
   AND scan.dedupe_key='exchange-refund-recovery-scan:'||d.run_id::text||':'||d.stage||':'||p.cursor_key
 ) THEN RAISE EXCEPTION 'EXACT_RECOVERY_DELIVERY_JOB_OUTBOX_REQUIRED' USING ERRCODE='23514';END IF;
 IF d.stage='ITEM_EFFECT' THEN
  IF NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_consent c
   JOIN marketplace.claim_fee_assessment a ON a.id=c.assessment_id AND a.intent_id=c.exchange_id
   JOIN marketplace.claim_fee_binding b ON b.assessment_id=a.id AND b.attempt_id=d.attempt_id
   JOIN marketplace.claim_exchange_refund_result x ON x.intent_id=c.exchange_id AND x.assessment_id=a.id AND x.attempt_id=b.attempt_id AND x.result_version=d.result_version
   JOIN marketplace.claim_financial_refund_binding fb ON fb.attempt_id=x.attempt_id
   WHERE c.id=d.source_fact_id AND c.exchange_id=d.exchange_id AND a.order_id=d.order_id AND a.seller_order_id=d.seller_order_id
    AND x.amount=d.amount AND x.result_state=d.result_state AND x.result_reference IS NOT DISTINCT FROM d.result_reference AND x.result_at IS NOT DISTINCT FROM d.result_at
    AND fb.payment_id=d.payment_id AND fb.purpose='ITEM' AND fb.original_reference=d.original_reference AND a.refund=d.amount
    AND d.source_fingerprint=encode(sha256(convert_to('ITEM_EFFECT|'||c.exchange_id::text||'|'||c.id::text||'|'||a.id::text||'|'||x.attempt_id::text||'|'||x.result_version::text||'|'||x.fingerprint,'UTF8')),'hex')
  ) THEN RAISE EXCEPTION 'ACTUAL_RETAINED_ITEM_RECOVERY_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSIF d.stage='SHIPPING_RESULT' THEN
  IF NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_shipping_binding b
   JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=b.seller_order_id AND o.refund_attempt_id=b.attempt_id AND o.claim_id=b.intent_id
   JOIN marketplace.payment_refund_attempt p ON p.id=b.attempt_id
   WHERE b.intent_id=d.exchange_id AND b.order_id=d.order_id AND b.seller_order_id=d.seller_order_id AND b.attempt_id=d.attempt_id
    AND b.payment_id=d.payment_id AND b.amount=d.amount AND b.original_reference=d.original_reference
    AND p.version=d.result_version AND p.state=d.result_state AND p.result_reference IS NOT DISTINCT FROM d.result_reference AND p.result_at IS NOT DISTINCT FROM d.result_at
    AND d.source_fingerprint=encode(sha256(convert_to('SHIPPING_RESULT|'||d.exchange_id::text||'|'||d.attempt_id::text||'|'||d.result_version::text||'|'||d.amount::text||'|'||d.original_reference||'|'||d.result_state,'UTF8')),'hex')
  ) THEN RAISE EXCEPTION 'ACTUAL_TYPED_SHIPPING_RECOVERY_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_item_ready ready WHERE ready.exchange_id=d.exchange_id AND ready.order_id=d.order_id AND ready.seller_order_id=d.seller_order_id
   AND d.source_fingerprint=encode(sha256(convert_to('SELLER_REEVALUATE|'||ready.exchange_id::text||'|'||ready.attempt_id::text||'|'||ready.result_version::text||'|'||ready.assessment_id::text,'UTF8')),'hex'))
  THEN RAISE EXCEPTION 'ACTUAL_READY_RECOVERY_ANCHOR_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;
END $$;
CREATE FUNCTION exchange_refund_recovery_delivery_graph() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
 IF TG_TABLE_NAME='claim_exchange_refund_recovery_delivery' THEN target:=NEW.id;
 ELSIF TG_TABLE_NAME='job' THEN
  IF NEW.dedupe_key NOT LIKE 'e-refund-recovery:%' THEN RETURN NULL;END IF;
  SELECT id INTO target FROM marketplace.claim_exchange_refund_recovery_delivery WHERE job_id=NEW.id;
 ELSE
  SELECT d.id INTO target FROM marketplace.claim_exchange_refund_recovery_delivery d WHERE d.id=NEW.id AND d.job_id=NEW.job_id;
  IF target IS NULL AND NOT EXISTS(SELECT 1 FROM marketplace.job WHERE id=NEW.job_id AND dedupe_key LIKE 'e-refund-recovery:%') THEN RETURN NULL;END IF;
 END IF;
 IF target IS NULL THEN RAISE EXCEPTION 'RECOVERY_JOB_REQUIRES_EXACT_MANIFEST' USING ERRCODE='23514';END IF;
 PERFORM marketplace.verify_exchange_refund_recovery_delivery(target);RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER e_recovery_delivery_graph AFTER INSERT ON claim_exchange_refund_recovery_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_delivery_graph();
CREATE CONSTRAINT TRIGGER e_recovery_delivery_job_graph AFTER INSERT ON job DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_delivery_graph();
CREATE CONSTRAINT TRIGGER e_recovery_delivery_outbox_graph AFTER INSERT ON outbox_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_delivery_graph();
CREATE FUNCTION e_immutable_recovery_job_route() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (OLD.dedupe_key LIKE 'e-refund-recovery:%' OR NEW.dedupe_key LIKE 'e-refund-recovery:%' OR OLD.type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' OR NEW.type='EXCHANGE_REFUND_RECOVERY_SCAN.v1')
  AND (OLD.id,OLD.type,OLD.dedupe_key,OLD.cause_key,OLD.payload,OLD.scheduled_at) IS DISTINCT FROM(NEW.id,NEW.type,NEW.dedupe_key,NEW.cause_key,NEW.payload,NEW.scheduled_at)
 THEN RAISE EXCEPTION 'IMMUTABLE_RECOVERY_JOB_IDENTITY' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER e_recovery_job_route BEFORE UPDATE ON job FOR EACH ROW EXECUTE FUNCTION e_immutable_recovery_job_route();
CREATE FUNCTION e_immutable_recovery_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF EXISTS(SELECT 1 FROM marketplace.job WHERE id=OLD.job_id AND dedupe_key LIKE 'e-refund-recovery:%') THEN RAISE EXCEPTION 'IMMUTABLE_RECOVERY_OUTBOX' USING ERRCODE='23514';END IF;RETURN OLD;
 END IF;
 IF EXISTS(SELECT 1 FROM marketplace.job WHERE id IN (OLD.job_id,NEW.job_id) AND dedupe_key LIKE 'e-refund-recovery:%')
  AND (OLD.id,OLD.job_id) IS DISTINCT FROM(NEW.id,NEW.job_id) THEN RAISE EXCEPTION 'IMMUTABLE_RECOVERY_OUTBOX' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER e_recovery_outbox_identity BEFORE UPDATE OR DELETE ON outbox_event FOR EACH ROW EXECUTE FUNCTION e_immutable_recovery_outbox();

-- Stable total locator order. UUID upper bounds are finite bounds, not a point-in-time snapshot.
CREATE FUNCTION claim_exchange_refund_recovery_ids(v_stage text) RETURNS TABLE(id uuid) LANGUAGE sql STABLE AS $$
 SELECT r.id FROM marketplace.claim_exchange_request r
 JOIN marketplace.claim_exchange_refund_consent c ON c.exchange_id=r.id
 JOIN marketplace.claim_fee_binding b ON b.assessment_id=c.assessment_id
 JOIN LATERAL(SELECT x.result_version FROM marketplace.claim_exchange_refund_result x WHERE x.intent_id=r.id AND x.assessment_id=c.assessment_id AND x.attempt_id=b.attempt_id ORDER BY x.result_version DESC LIMIT 1)x ON true
 LEFT JOIN marketplace.claim_exchange_refund_effect effect ON effect.exchange_id=r.id AND effect.attempt_id=b.attempt_id AND effect.result_version=x.result_version
 WHERE v_stage='ITEM_EFFECT' AND effect.exchange_id IS NULL AND r.state IN ('REFUND_PENDING','REVIEW_REQUIRED','SETTLEMENT_PENDING')
 UNION ALL
 SELECT r.id FROM marketplace.claim_exchange_request r JOIN marketplace.claim_exchange_refund_item_ready ready ON ready.exchange_id=r.id
 JOIN marketplace.claim_shipping_obligation o ON o.claim_id=r.id AND o.seller_order_id=r.seller_order_id
 JOIN marketplace.claim_exchange_refund_shipping_binding b ON b.intent_id=r.id AND b.attempt_id=o.refund_attempt_id
 WHERE v_stage='SHIPPING_RESULT' AND o.state<>'SUCCEEDED'
 UNION ALL
 SELECT r.id FROM marketplace.claim_exchange_request r JOIN marketplace.claim_exchange_refund_item_ready ready ON ready.exchange_id=r.id
 LEFT JOIN marketplace.claim_exchange_refund_completion done ON done.exchange_id=r.id
 LEFT JOIN marketplace.claim_seller_shipping_current cur ON cur.seller_order_id=r.seller_order_id
 LEFT JOIN marketplace.claim_seller_shipping_decision decision ON decision.id=cur.decision_id
 WHERE v_stage='SELLER_REEVALUATE' AND (done.exchange_id IS NULL OR decision.id IS NULL OR decision.state='PENDING')
$$;
CREATE FUNCTION claim_exchange_refund_recovery_locators(v_stage text,v_upper uuid,v_cursor uuid) RETURNS jsonb LANGUAGE sql STABLE AS $$
 SELECT COALESCE(jsonb_agg(to_jsonb(x.id) ORDER BY x.id),'[]'::jsonb) FROM(
  SELECT id FROM marketplace.claim_exchange_refund_recovery_ids(v_stage) WHERE v_upper IS NOT NULL AND id<=v_upper AND(v_cursor IS NULL OR id>v_cursor) ORDER BY id LIMIT 50
 )x
$$;
CREATE FUNCTION exchange_refund_recovery_page_plan_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s marketplace.claim_exchange_refund_recovery_stage%ROWTYPE;cursor_value uuid;
BEGIN
 SELECT * INTO STRICT s FROM marketplace.claim_exchange_refund_recovery_stage WHERE run_id=NEW.run_id AND stage=NEW.stage;
 IF NEW.cursor_key='START' THEN cursor_value:=NULL;
 ELSIF NEW.cursor_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN cursor_value:=NEW.cursor_key::uuid;
 ELSE RAISE EXCEPTION 'INVALID_RECOVERY_CURSOR' USING ERRCODE='23514';END IF;
 IF s.completed OR s.cursor_id IS DISTINCT FROM cursor_value OR NEW.locator_ids<>marketplace.claim_exchange_refund_recovery_locators(NEW.stage,s.upper_id,cursor_value)
  OR NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_run r JOIN marketplace.job j ON j.id=NEW.scan_job_id
   WHERE r.id=NEW.run_id AND r.stages ? NEW.stage AND j.type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' AND j.status='RUNNING' AND j.owner_token=NEW.plan_owner_token
    AND j.cause_key=r.id::text AND j.payload=r.id::text||'|'||NEW.stage||'|'||NEW.cursor_key
    AND j.dedupe_key='exchange-refund-recovery-scan:'||r.id::text||':'||NEW.stage||':'||NEW.cursor_key)
 THEN RAISE EXCEPTION 'EXACT_RECOVERY_PAGE_PLAN_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER e_recovery_page_plan AFTER INSERT ON claim_exchange_refund_recovery_page DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_page_plan_guard();
CREATE FUNCTION exchange_refund_recovery_candidate_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_page p JOIN marketplace.job j ON j.id=p.scan_job_id
  WHERE p.id=NEW.page_id AND p.run_id=NEW.run_id AND p.stage=NEW.stage AND p.locator_ids ? NEW.exchange_id::text
   AND j.id=NEW.scan_job_id AND j.status='RUNNING' AND j.owner_token=NEW.owner_token
   AND (NEW.delivery_id IS NULL OR EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_delivery d WHERE d.id=NEW.delivery_id AND d.run_id=p.run_id AND d.stage=p.stage AND d.page_id=p.id AND d.exchange_id=NEW.exchange_id)))
 THEN RAISE EXCEPTION 'EXACT_RECOVERY_CANDIDATE_PROOF_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER e_recovery_candidate_proof AFTER INSERT ON claim_exchange_refund_recovery_candidate DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_candidate_guard();
CREATE FUNCTION exchange_refund_recovery_page_complete_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p marketplace.claim_exchange_refund_recovery_page%ROWTYPE;last_id uuid;expected_cursor uuid;total integer;
BEGIN
 SELECT * INTO STRICT p FROM marketplace.claim_exchange_refund_recovery_page WHERE id=NEW.page_id;total:=jsonb_array_length(p.locator_ids);
 IF total>0 THEN last_id:=(p.locator_ids->>(total-1))::uuid;ELSE last_id:=CASE WHEN p.cursor_key='START' THEN NULL ELSE p.cursor_key::uuid END;END IF;
 expected_cursor:=CASE WHEN p.cursor_key='START' THEN NULL ELSE p.cursor_key::uuid END;
 IF NEW.scan_job_id<>p.scan_job_id OR NEW.next_cursor IS DISTINCT FROM last_id OR NEW.finished IS DISTINCT FROM(total<50)
 OR (SELECT count(*) FROM marketplace.claim_exchange_refund_recovery_candidate WHERE page_id=p.id)<>total
 OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(p.locator_ids) v(id) WHERE NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_candidate c WHERE c.page_id=p.id AND c.exchange_id::text=v.id))
 OR NOT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=NEW.scan_job_id AND j.status='RUNNING' AND j.owner_token=NEW.owner_token)
 OR NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_stage s WHERE s.run_id=p.run_id AND s.stage=p.stage AND s.cursor_page_id=p.id AND s.cursor_id IS NOT DISTINCT FROM last_id AND s.completed=NEW.finished)
 OR (NOT NEW.finished AND NOT EXISTS(SELECT 1 FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id
  WHERE j.id=NEW.next_job_id AND j.type='EXCHANGE_REFUND_RECOVERY_SCAN.v1' AND j.cause_key=p.run_id::text
   AND j.payload=p.run_id::text||'|'||p.stage||'|'||last_id::text AND j.dedupe_key='exchange-refund-recovery-scan:'||p.run_id::text||':'||p.stage||':'||last_id::text))
 THEN RAISE EXCEPTION 'COMPLETE_RECOVERY_PAGE_AND_CURSOR_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER e_recovery_page_complete_proof AFTER INSERT ON claim_exchange_refund_recovery_page_complete DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_page_complete_guard();
CREATE FUNCTION exchange_refund_recovery_stage_cursor_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_page_complete c JOIN marketplace.claim_exchange_refund_recovery_page p ON p.id=c.page_id
  WHERE c.page_id=NEW.cursor_page_id AND p.run_id=NEW.run_id AND p.stage=NEW.stage
   AND p.cursor_key=COALESCE(OLD.cursor_id::text,'START') AND c.next_cursor IS NOT DISTINCT FROM NEW.cursor_id AND c.finished=NEW.completed)
 THEN RAISE EXCEPTION 'RECOVERY_CURSOR_REQUIRES_PAGE_RECEIPT' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER e_recovery_cursor_proof AFTER UPDATE ON claim_exchange_refund_recovery_stage DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_stage_cursor_guard();


CREATE OR REPLACE FUNCTION claim_exchange_refund_recovery_ids(v_stage text) RETURNS TABLE(id uuid) LANGUAGE sql STABLE AS $$
 SELECT r.id FROM marketplace.claim_exchange_request r
 JOIN marketplace.claim_exchange_refund_consent c ON c.exchange_id=r.id
 JOIN marketplace.claim_fee_binding b ON b.assessment_id=c.assessment_id
 JOIN LATERAL(SELECT x.result_version FROM marketplace.claim_exchange_refund_result x WHERE x.intent_id=r.id AND x.assessment_id=c.assessment_id AND x.attempt_id=b.attempt_id ORDER BY x.result_version DESC LIMIT 1)x ON true
 LEFT JOIN marketplace.claim_exchange_refund_effect effect ON effect.exchange_id=r.id AND effect.attempt_id=b.attempt_id AND effect.result_version=x.result_version
 WHERE v_stage='ITEM_EFFECT' AND ((effect.exchange_id IS NULL AND r.state IN ('REFUND_PENDING','REVIEW_REQUIRED','SETTLEMENT_PENDING'))
  OR(effect.result_state='SUCCEEDED' AND EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.id=c.assessment_id AND a.state='APPLIED' AND a.additional_due=0)
   AND NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_item_ready ready WHERE ready.exchange_id=r.id)))
 UNION ALL
 SELECT r.id FROM marketplace.claim_exchange_request r JOIN marketplace.claim_exchange_refund_item_ready ready ON ready.exchange_id=r.id
 JOIN marketplace.claim_shipping_obligation o ON o.claim_id=r.id AND o.seller_order_id=r.seller_order_id
 JOIN marketplace.claim_exchange_refund_shipping_binding b ON b.intent_id=r.id AND b.attempt_id=o.refund_attempt_id
 WHERE v_stage='SHIPPING_RESULT' AND o.state<>'SUCCEEDED'
 UNION ALL
 SELECT r.id FROM marketplace.claim_exchange_request r JOIN marketplace.claim_exchange_refund_item_ready ready ON ready.exchange_id=r.id
 LEFT JOIN marketplace.claim_exchange_refund_completion done ON done.exchange_id=r.id
 LEFT JOIN marketplace.claim_seller_shipping_current cur ON cur.seller_order_id=r.seller_order_id
 LEFT JOIN marketplace.claim_seller_shipping_decision decision ON decision.id=cur.decision_id
 WHERE v_stage='SELLER_REEVALUATE'
$$;

-- A durable page plan is separate; each delivery and its candidate receipt commit together.
CREATE FUNCTION exchange_refund_recovery_delivery_candidate_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_candidate c
  WHERE c.delivery_id=NEW.id AND c.outcome='DELIVERED' AND c.run_id=NEW.run_id AND c.stage=NEW.stage
   AND c.page_id=NEW.page_id AND c.exchange_id=NEW.exchange_id AND c.scan_job_id=NEW.scan_job_id
   AND c.owner_token=NEW.scan_owner_token)
 THEN RAISE EXCEPTION 'EXACT_RECOVERY_DELIVERY_CANDIDATE_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER e_recovery_delivery_candidate_proof AFTER INSERT ON claim_exchange_refund_recovery_delivery
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_refund_recovery_delivery_candidate_guard();

CREATE OR REPLACE FUNCTION verify_exchange_refund_effect(p_attempt uuid,p_version bigint) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE x claim_exchange_refund_effect;r claim_exchange_refund_result;c claim_exchange_request;
BEGIN
 SELECT * INTO x FROM claim_exchange_refund_effect WHERE attempt_id=p_attempt AND result_version=p_version;
 IF x.attempt_id IS NULL THEN RAISE EXCEPTION 'EXCHANGE_REFUND_EFFECT_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO r FROM claim_exchange_refund_result WHERE attempt_id=p_attempt AND result_version=p_version;
 SELECT * INTO c FROM claim_exchange_request WHERE id=x.exchange_id;
 IF (r.intent_id,r.assessment_id,r.result_state) IS DISTINCT FROM(x.exchange_id,x.assessment_id,x.result_state)
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=x.job_id AND j.type='EXCHANGE_REFUND_RESUME.v1'
  AND (j.dedupe_key='exchange-refund-result:'||p_attempt::text||':'||p_version::text OR(exact_exchange_refund_recovery_delivery(j.id,'ITEM_EFFECT') AND EXISTS(SELECT 1 FROM claim_exchange_refund_recovery_delivery d WHERE d.job_id=j.id AND d.stage='ITEM_EFFECT' AND d.exchange_id=x.exchange_id AND d.attempt_id=p_attempt AND d.result_version=p_version AND d.order_id=c.order_id AND d.seller_order_id=c.seller_order_id AND d.source_fact_id=(SELECT id FROM claim_exchange_refund_consent WHERE exchange_id=c.id)))) AND j.cause_key=x.exchange_id::text AND j.payload=x.exchange_id::text||'|'||p_attempt::text||'|'||p_version::text
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

CREATE OR REPLACE FUNCTION shipping_result_graph_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE x marketplace.claim_shipping_result%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='claim_shipping_result' THEN SELECT * INTO STRICT x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.attempt_id AND result_version=NEW.result_version;
 ELSE SELECT * INTO x FROM marketplace.claim_shipping_result WHERE attempt_id=NEW.refund_attempt_id AND result_state='SUCCEEDED' ORDER BY result_version DESC LIMIT 1;IF NOT FOUND THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_REQUIRED' USING ERRCODE='23514';END IF;END IF;
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_concrete_shipping_binding b
  JOIN marketplace.claim_shipping_obligation o ON o.seller_order_id=b.seller_order_id
  JOIN marketplace.payment_refund_attempt p ON p.id=b.attempt_id
  JOIN marketplace.job j ON j.id=x.job_id
  WHERE b.attempt_id=x.attempt_id AND b.intent_id=x.intent_id AND b.seller_order_id=x.seller_order_id
   AND o.claim_id=b.intent_id AND o.refund_attempt_id=p.id AND o.amount=p.amount AND p.purpose='SHIPPING' AND x.workflow_revision IS NOT NULL AND o.version=x.workflow_revision AND o.state=x.result_state
   AND p.version=x.result_version AND p.state=x.result_state AND p.result_reference IS NOT DISTINCT FROM x.reference AND p.result_at IS NOT DISTINCT FROM x.result_at
   AND j.type='REFUND_RESULT_RECORDED.v1' AND j.cause_key=p.id::text AND j.status='RUNNING' AND j.owner_token=x.owner_token
   AND j.payload=p.id::text||'|'||p.version::text AND (
    j.dedupe_key='refund-result:'||p.id::text||':'||p.version::text OR(b.kind='EXCHANGE_REFUND' AND marketplace.exact_exchange_refund_recovery_delivery(j.id,'SHIPPING_RESULT') AND EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_delivery d WHERE d.job_id=j.id AND d.stage='SHIPPING_RESULT' AND d.exchange_id=b.intent_id AND d.order_id=b.order_id AND d.seller_order_id=b.seller_order_id AND d.attempt_id=p.id AND d.result_version=p.version AND d.payment_id=p.payment_id AND d.amount=p.amount AND d.original_reference=p.original_reference AND d.result_state=p.state AND d.result_reference IS NOT DISTINCT FROM p.result_reference AND d.result_at IS NOT DISTINCT FROM p.result_at))
    OR(b.kind='RETURN' AND marketplace.exact_return_recovery_delivery(j.id,'SHIPPING_RESULT') AND EXISTS(
     SELECT 1 FROM marketplace.claim_return_recovery_delivery d WHERE d.job_id=j.id AND d.stage='SHIPPING_RESULT'
      AND d.return_id=b.intent_id AND d.order_id=b.order_id AND d.seller_order_id=b.seller_order_id AND d.attempt_id=p.id AND d.result_version=p.version
      AND d.payment_id=p.payment_id AND d.amount=p.amount AND d.original_reference=p.original_reference AND d.result_state=p.state
      AND d.result_reference IS NOT DISTINCT FROM p.result_reference AND d.result_at IS NOT DISTINCT FROM p.result_at)))
   AND (x.result_state<>'SUCCEEDED' OR(o.state='SUCCEEDED' AND EXISTS(SELECT 1 FROM marketplace.order_aftercare_shipping s WHERE s.seller_order_id=o.seller_order_id AND s.order_id=o.order_id AND s.refund_attempt_id=p.id AND s.payment_id=p.payment_id AND s.cause_id=o.claim_id AND s.amount=p.amount AND s.reference=p.result_reference)))
 ) THEN RAISE EXCEPTION 'ACTUAL_SHIPPING_RESULT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION return_seller_delivery_identity(v_job uuid,v_seller uuid,v_order uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM marketplace.job j WHERE j.id=v_job AND j.type='SELLER_AFTERCARE_REEVALUATE.v1' AND j.cause_key=v_seller::text AND (
  (marketplace.exact_return_recovery_delivery(j.id,'SELLER_REEVALUATE') AND EXISTS(
   SELECT 1 FROM marketplace.claim_return_recovery_delivery d JOIN marketplace.claim_return_item_ready r ON r.return_id=d.return_id
   WHERE d.job_id=j.id AND d.stage='SELLER_REEVALUATE' AND d.order_id=v_order AND d.seller_order_id=v_seller
    AND r.order_id=v_order AND r.seller_order_id=v_seller AND j.payload='RETURN_READY|'||r.return_id::text))
  OR(marketplace.exact_exchange_refund_recovery_delivery(j.id,'SELLER_REEVALUATE') AND EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_recovery_delivery d JOIN marketplace.claim_exchange_refund_item_ready r ON r.exchange_id=d.exchange_id WHERE d.job_id=j.id AND d.stage='SELLER_REEVALUATE' AND d.order_id=v_order AND d.seller_order_id=v_seller AND r.order_id=v_order AND r.seller_order_id=v_seller AND j.payload='EXCHANGE_REFUND_READY|'||r.exchange_id::text))
  OR EXISTS(SELECT 1 FROM marketplace.claim_exchange_refund_item_ready r WHERE r.seller_order_id=v_seller AND r.order_id=v_order AND j.payload='EXCHANGE_REFUND_READY|'||r.exchange_id::text AND j.dedupe_key='seller-aftercare:'||v_seller::text||':exchange-refund-ready:'||r.exchange_id::text)
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
