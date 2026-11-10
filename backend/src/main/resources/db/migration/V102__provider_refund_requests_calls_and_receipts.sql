-- Customer provider calls are distinct from late-approval compensation.
SET search_path TO marketplace,public;
ALTER TABLE payment_refund_attempt ADD CONSTRAINT refund_attempt_provider_identity UNIQUE(id,payment_id,order_id,amount);
CREATE TABLE payment_refund_provider_request (
 attempt_id uuid PRIMARY KEY,payment_id uuid NOT NULL,order_id uuid NOT NULL,amount bigint NOT NULL CHECK(amount>0),
 provider varchar(10) NOT NULL CHECK(provider='TOSS'),mode varchar(10) NOT NULL CHECK(mode='SANDBOX'),
 binding_hash char(64) NOT NULL,provider_order_id varchar(100) NOT NULL,merchant_id varchar(200) NOT NULL,
 api_version varchar(10) NOT NULL,currency varchar(3) NOT NULL CHECK(currency='KRW'),original_amount bigint NOT NULL CHECK(original_amount>=amount),
 submit_key varchar(100) NOT NULL,marker varchar(100) NOT NULL UNIQUE,request_fingerprint char(64) NOT NULL UNIQUE,
 request_body jsonb NOT NULL,prepared_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(attempt_id,payment_id,order_id,amount) REFERENCES payment_refund_attempt(id,payment_id,order_id,amount),
 UNIQUE(attempt_id,request_fingerprint),CHECK(marker='F-CUSTOMER-'||attempt_id::text),
 CHECK(submit_key='customer-refund:'||attempt_id::text),CHECK(provider_order_id=order_id::text),
 CHECK(request_body=jsonb_build_object('cancelAmount',amount,'cancelReason',marker))
);
CREATE TABLE payment_refund_provider_call (
 id uuid PRIMARY KEY,attempt_id uuid NOT NULL,request_fingerprint char(64) NOT NULL,
 operation varchar(4) NOT NULL CHECK(operation IN('POST','GET')),job_id uuid NOT NULL,owner_token uuid NOT NULL,
 started_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(attempt_id,request_fingerprint) REFERENCES payment_refund_provider_request(attempt_id,request_fingerprint),
 FOREIGN KEY(job_id,owner_token) REFERENCES job_claim_fact(job_id,owner_token),
 UNIQUE(id,attempt_id),UNIQUE(id,attempt_id,request_fingerprint)
);
CREATE UNIQUE INDEX refund_one_first_provider_post ON payment_refund_provider_call(attempt_id) WHERE operation='POST';
CREATE TABLE payment_refund_provider_observation (
 call_id uuid PRIMARY KEY,attempt_id uuid NOT NULL,request_fingerprint char(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN('SUCCEEDED','UNKNOWN')),transaction_key varchar(200),response_fingerprint char(64),
 evidence jsonb NOT NULL,observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(call_id,attempt_id,request_fingerprint) REFERENCES payment_refund_provider_call(id,attempt_id,request_fingerprint),
 CHECK((state='SUCCEEDED' AND transaction_key IS NOT NULL AND response_fingerprint IS NOT NULL) OR(state='UNKNOWN' AND transaction_key IS NULL)),
 UNIQUE(call_id,attempt_id,transaction_key,request_fingerprint)
);
CREATE TABLE payment_refund_provider_receipt (
 attempt_id uuid PRIMARY KEY,call_id uuid NOT NULL,request_fingerprint char(64) NOT NULL,
 provider varchar(10) NOT NULL CHECK(provider='TOSS'),mode varchar(10) NOT NULL CHECK(mode='SANDBOX'),
 merchant_id varchar(200) NOT NULL,binding_hash char(64) NOT NULL,transaction_key varchar(200) NOT NULL,
 amount bigint NOT NULL CHECK(amount>0),response_fingerprint char(64) NOT NULL,evidence jsonb NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 FOREIGN KEY(call_id,attempt_id,transaction_key,request_fingerprint) REFERENCES payment_refund_provider_observation(call_id,attempt_id,transaction_key,request_fingerprint),
 UNIQUE(mode,merchant_id,binding_hash,transaction_key),UNIQUE(attempt_id,transaction_key)
);
CREATE FUNCTION immutable_provider_refund_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_PROVIDER_REFUND_EVIDENCE' USING ERRCODE='23514';END $$;
CREATE TRIGGER refund_provider_request_immutable BEFORE UPDATE OR DELETE ON payment_refund_provider_request FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE TRIGGER refund_provider_call_immutable BEFORE UPDATE OR DELETE ON payment_refund_provider_call FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE TRIGGER refund_provider_observation_immutable BEFORE UPDATE OR DELETE ON payment_refund_provider_observation FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE TRIGGER refund_provider_receipt_immutable BEFORE UPDATE OR DELETE ON payment_refund_provider_receipt FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE FUNCTION verify_provider_refund_call(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c payment_refund_provider_call;r payment_refund_provider_request;a payment_refund_attempt;
BEGIN
 SELECT * INTO c FROM payment_refund_provider_call WHERE id=p_id;SELECT * INTO r FROM payment_refund_provider_request WHERE attempt_id=c.attempt_id;SELECT * INTO a FROM payment_refund_attempt WHERE id=c.attempt_id;
 IF c.id IS NULL OR r.attempt_id IS NULL OR a.id IS NULL OR c.request_fingerprint<>r.request_fingerprint
 OR a.state<>'PROCESSING' OR a.fence_token<>c.owner_token
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=c.job_id AND j.owner_token=c.owner_token AND j.status='RUNNING' AND j.lease_until>clock_timestamp() AND j.type='REFUND_SUBMIT.v1' AND j.cause_key=a.id::text AND j.payload=a.id::text)
 THEN RAISE EXCEPTION 'ACTUAL_PROVIDER_REFUND_CALL_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION provider_refund_call_origin() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN PERFORM verify_provider_refund_call(NEW.id);RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER refund_provider_call_exact AFTER INSERT ON payment_refund_provider_call DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION provider_refund_call_origin();
CREATE FUNCTION provider_refund_observation_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c payment_refund_provider_call;r payment_refund_provider_request;
BEGIN
 SELECT * INTO c FROM payment_refund_provider_call WHERE id=NEW.call_id;SELECT * INTO r FROM payment_refund_provider_request WHERE attempt_id=NEW.attempt_id;PERFORM verify_provider_refund_call(NEW.call_id);
 IF jsonb_typeof(NEW.evidence) IS DISTINCT FROM 'object' THEN RAISE EXCEPTION 'PROVIDER_REFUND_EVIDENCE_OBJECT_REQUIRED' USING ERRCODE='23514';END IF;
 IF NEW.state='SUCCEEDED' THEN
  IF jsonb_typeof(NEW.evidence->'cancels') IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'PROVIDER_REFUND_CANCEL_ARRAY_REQUIRED' USING ERRCODE='23514';END IF;
  IF jsonb_array_length(NEW.evidence->'cancels')<1 OR EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.evidence->'cancels') x WHERE jsonb_typeof(x) IS DISTINCT FROM 'object' OR jsonb_typeof(x->'transactionKey') IS DISTINCT FROM 'string' OR length(x->>'transactionKey') NOT BETWEEN 1 AND 200 OR jsonb_typeof(x->'amount') IS DISTINCT FROM 'string' OR COALESCE(x->>'amount','')!~'^(0|[1-9][0-9]{0,18})$' OR jsonb_typeof(x->'state') IS DISTINCT FROM 'string' OR jsonb_typeof(x->'matchesServerMarker') IS DISTINCT FROM 'boolean')
  THEN RAISE EXCEPTION 'EXACT_PROVIDER_REFUND_CANCEL_SHAPE_REQUIRED' USING ERRCODE='23514';END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.evidence->'cancels') x WHERE(x->>'amount')::numeric>9223372036854775807) OR (SELECT count(*) FROM jsonb_array_elements(NEW.evidence->'cancels'))<>(SELECT count(DISTINCT x->>'transactionKey') FROM jsonb_array_elements(NEW.evidence->'cancels') x)
  THEN RAISE EXCEPTION 'UNIQUE_BOUNDED_PROVIDER_REFUND_TRANSACTIONS_REQUIRED' USING ERRCODE='23514';END IF;
  IF NEW.evidence->>'provider' IS DISTINCT FROM 'TOSS' OR NEW.evidence->>'mode' IS DISTINCT FROM 'SANDBOX' OR NEW.evidence->>'paymentKeyHash' IS DISTINCT FROM r.binding_hash OR NEW.evidence->>'orderId' IS DISTINCT FROM r.provider_order_id OR NEW.evidence->>'merchant' IS DISTINCT FROM r.merchant_id OR NEW.evidence->>'currency' IS DISTINCT FROM 'KRW' OR (NEW.evidence->>'originalAmount')::bigint IS DISTINCT FROM r.original_amount OR NEW.evidence->>'responseHash' IS DISTINCT FROM NEW.response_fingerprint
  OR 1<>(SELECT count(*) FROM jsonb_array_elements(NEW.evidence->'cancels') x WHERE x->>'transactionKey'=NEW.transaction_key AND(x->>'amount')::bigint=r.amount AND x->>'state'='DONE' AND(x->>'matchesServerMarker')::boolean)
  OR c.operation='POST' AND(NEW.evidence->>'lastTransactionKey' IS DISTINCT FROM NEW.transaction_key OR 1<>(SELECT count(*) FROM jsonb_array_elements(NEW.evidence->'cancels') x WHERE(x->>'matchesServerMarker')::boolean))
  OR c.operation='POST' AND NOT EXISTS(SELECT 1 FROM payment_refund_provider_receipt z WHERE z.call_id=NEW.call_id AND z.attempt_id=NEW.attempt_id AND z.transaction_key=NEW.transaction_key AND z.created_xid=pg_current_xact_id())
  OR c.operation='GET' AND NOT EXISTS(SELECT 1 FROM payment_refund_provider_receipt z WHERE z.attempt_id=NEW.attempt_id AND z.transaction_key=NEW.transaction_key AND z.request_fingerprint=NEW.request_fingerprint)
  THEN RAISE EXCEPTION 'EXACT_PROVIDER_REFUND_OBSERVATION_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_provider_observation_exact AFTER INSERT ON payment_refund_provider_observation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION provider_refund_observation_graph();
CREATE FUNCTION provider_refund_receipt_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c payment_refund_provider_call;r payment_refund_provider_request;o payment_refund_provider_observation;
BEGIN
 SELECT * INTO c FROM payment_refund_provider_call WHERE id=NEW.call_id;SELECT * INTO r FROM payment_refund_provider_request WHERE attempt_id=NEW.attempt_id;SELECT * INTO o FROM payment_refund_provider_observation WHERE call_id=NEW.call_id;PERFORM verify_provider_refund_call(NEW.call_id);
 IF c.operation<>'POST' OR o.state<>'SUCCEEDED' OR(NEW.attempt_id,NEW.request_fingerprint,NEW.mode,NEW.merchant_id,NEW.binding_hash,NEW.amount) IS DISTINCT FROM(r.attempt_id,r.request_fingerprint,r.mode,r.merchant_id,r.binding_hash,r.amount)
 OR(NEW.transaction_key,NEW.response_fingerprint,NEW.evidence) IS DISTINCT FROM(o.transaction_key,o.response_fingerprint,o.evidence)
 OR NEW.created_xid<>o.created_xid OR NEW.created_xid<>pg_current_xact_id()
 THEN RAISE EXCEPTION 'EXACT_FIRST_POST_PROVIDER_REFUND_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_provider_receipt_exact AFTER INSERT ON payment_refund_provider_receipt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION provider_refund_receipt_graph();
CREATE FUNCTION provider_refund_success_requires_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NEW.state='SUCCEEDED' AND NEW.amount>0 AND EXISTS(SELECT 1 FROM payment_attempt p WHERE p.id=NEW.payment_id AND p.mode='SANDBOX')
 AND NOT EXISTS(SELECT 1 FROM payment_refund_provider_receipt r WHERE r.attempt_id=NEW.id AND r.amount=NEW.amount AND r.transaction_key=NEW.result_reference)
 THEN RAISE EXCEPTION 'SANDBOX_CUSTOMER_REFUND_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER sandbox_refund_success_exact AFTER INSERT OR UPDATE ON payment_refund_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION provider_refund_success_requires_receipt();

CREATE FUNCTION provider_refund_request_origin() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NEW.created_xid<>pg_current_xact_id() OR NOT EXISTS(
  SELECT 1 FROM payment_refund_attempt a JOIN payment_attempt p ON p.id=a.payment_id JOIN payment_toss_binding b ON b.attempt_id=p.id
  WHERE a.id=NEW.attempt_id AND a.state='PROCESSING' AND a.started_at=NEW.prepared_at AND p.mode='SANDBOX' AND p.state='SUCCEEDED' AND p.compensation='NONE'
   AND b.provider_order_id=NEW.provider_order_id AND b.amount=NEW.original_amount
   AND encode(sha256(convert_to(b.provider_payment_key,'UTF8')),'hex')=NEW.binding_hash)
 OR NOT EXISTS(SELECT 1 FROM payment_refund_provider_call c WHERE c.attempt_id=NEW.attempt_id AND c.operation='POST' AND c.request_fingerprint=NEW.request_fingerprint AND c.created_xid=pg_current_xact_id())
 THEN RAISE EXCEPTION 'FIRST_PROVIDER_REQUEST_AND_ACTUAL_POST_CALL_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_provider_request_exact AFTER INSERT ON payment_refund_provider_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION provider_refund_request_origin();
