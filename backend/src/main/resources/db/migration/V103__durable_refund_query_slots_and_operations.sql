SET search_path TO marketplace,public;
CREATE TABLE payment_refund_query_policy (
 attempt_id uuid PRIMARY KEY REFERENCES payment_refund_attempt(id),prepared_at timestamptz NOT NULL,
 deadline timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 CHECK(deadline=prepared_at+interval '24 hours')
);
CREATE TABLE payment_refund_query_slot (
 attempt_id uuid NOT NULL REFERENCES payment_refund_query_policy(attempt_id),slot int NOT NULL CHECK(slot BETWEEN 1 AND 6),
 due_at timestamptz NOT NULL,job_id uuid NOT NULL UNIQUE REFERENCES job(id),created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(attempt_id,slot),UNIQUE(attempt_id,slot,job_id)
);
CREATE TABLE payment_refund_query_consumption (
 attempt_id uuid NOT NULL,slot int NOT NULL,job_id uuid NOT NULL,owner_token uuid NOT NULL,
 decision varchar(20) NOT NULL CHECK(decision IN('CALL','SKIP_TERMINAL','SKIP_DELAYED','REVIEW')),
 checked_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(attempt_id,slot),FOREIGN KEY(attempt_id,slot,job_id) REFERENCES payment_refund_query_slot(attempt_id,slot,job_id),
 FOREIGN KEY(job_id,owner_token) REFERENCES job_claim_fact(job_id,owner_token),CHECK(slot<=5 OR decision<>'CALL')
);
CREATE TABLE payment_refund_execution (
 attempt_id uuid PRIMARY KEY REFERENCES payment_refund_attempt(id),job_id uuid NOT NULL REFERENCES job(id),owner_token uuid NOT NULL,
 FOREIGN KEY(job_id,owner_token) REFERENCES job_claim_fact(job_id,owner_token)
);
CREATE TABLE payment_refund_operation (
 id uuid PRIMARY KEY,attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),actor uuid NOT NULL,
 session_id varchar(100) NOT NULL,auth_version bigint NOT NULL,command_key varchar(100) NOT NULL,
 original_version bigint NOT NULL CHECK(original_version>=0),action varchar(12) NOT NULL CHECK(action IN('QUERY','RECONCILE')),
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),evidence_reference varchar(2000),requested_transaction varchar(200),
 fingerprint char(64) NOT NULL,job_id uuid NOT NULL UNIQUE REFERENCES job(id),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),UNIQUE(actor,command_key),
 CHECK((action='QUERY' AND evidence_reference IS NULL AND requested_transaction IS NULL) OR
 (action='RECONCILE' AND evidence_reference IS NOT NULL AND requested_transaction IS NOT NULL AND length(trim(evidence_reference))>0 AND length(trim(requested_transaction))>0))
);
CREATE TRIGGER refund_query_policy_immutable BEFORE UPDATE OR DELETE ON payment_refund_query_policy FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE TRIGGER refund_query_slot_immutable BEFORE UPDATE OR DELETE ON payment_refund_query_slot FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE TRIGGER refund_query_consumption_immutable BEFORE UPDATE OR DELETE ON payment_refund_query_consumption FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE TRIGGER refund_operation_immutable BEFORE UPDATE OR DELETE ON payment_refund_operation FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();

CREATE FUNCTION refund_query_policy_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE identity uuid;p payment_refund_query_policy;
BEGIN
 identity=NEW.attempt_id;SELECT * INTO p FROM payment_refund_query_policy WHERE attempt_id=identity;
 IF p.attempt_id IS NULL OR NOT EXISTS(SELECT 1 FROM payment_refund_attempt a WHERE a.id=identity AND a.amount>0 AND a.started_at=p.prepared_at)
 OR 6<>(SELECT count(*) FROM payment_refund_query_slot WHERE attempt_id=identity)
 OR EXISTS(SELECT 1 FROM payment_refund_query_slot s LEFT JOIN job j ON j.id=s.job_id WHERE s.attempt_id=identity AND
 (s.due_at IS DISTINCT FROM p.prepared_at+make_interval(secs=>CASE s.slot WHEN 1 THEN 60 WHEN 2 THEN 300 WHEN 3 THEN 1800 WHEN 4 THEN 7200 WHEN 5 THEN 43200 ELSE 86400 END)
 OR j.type IS DISTINCT FROM 'REFUND_QUERY_DUE.v1' OR j.dedupe_key IS DISTINCT FROM 'refund-auto:'||identity::text||':'||s.slot::text
 OR j.cause_key IS DISTINCT FROM identity::text OR j.payload IS DISTINCT FROM identity::text||'|'||s.slot::text
 OR j.scheduled_at IS DISTINCT FROM s.due_at OR s.created_xid<>p.created_xid
 OR NOT EXISTS(SELECT 1 FROM outbox_event e WHERE e.job_id=j.id)))
 THEN RAISE EXCEPTION 'EXACT_SIX_REFUND_QUERY_SLOTS_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_query_policy_exact AFTER INSERT ON payment_refund_query_policy DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION refund_query_policy_graph();
CREATE CONSTRAINT TRIGGER refund_query_slot_exact AFTER INSERT ON payment_refund_query_slot DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION refund_query_policy_graph();

CREATE FUNCTION refund_query_consumption_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE s payment_refund_query_slot;p payment_refund_query_policy;a payment_refund_attempt;
BEGIN
 SELECT * INTO s FROM payment_refund_query_slot WHERE attempt_id=NEW.attempt_id AND slot=NEW.slot;
 SELECT * INTO p FROM payment_refund_query_policy WHERE attempt_id=NEW.attempt_id;SELECT * INTO a FROM payment_refund_attempt WHERE id=NEW.attempt_id;
 IF s.job_id IS DISTINCT FROM NEW.job_id OR NOT EXISTS(SELECT 1 FROM job j JOIN job_claim_fact f ON f.job_id=j.id AND f.owner_token=j.owner_token AND f.attempt=j.attempts WHERE j.id=NEW.job_id AND j.status='RUNNING' AND j.owner_token=NEW.owner_token AND j.lease_until>clock_timestamp() AND f.claimed_at=NEW.checked_at AND f.required_at<=f.claimed_at AND f.scheduled_at_snapshot=s.due_at)
 OR NEW.decision='CALL' AND(a.state IS DISTINCT FROM 'PROCESSING' OR a.fence_token IS DISTINCT FROM NEW.owner_token OR NEW.checked_at>=p.deadline
 OR NOT EXISTS(SELECT 1 FROM payment_refund_execution x WHERE x.attempt_id=a.id AND x.job_id=NEW.job_id AND x.owner_token=NEW.owner_token)
 OR EXISTS(SELECT 1 FROM payment_refund_query_consumption old WHERE old.attempt_id=a.id AND old.slot<>NEW.slot AND old.decision='CALL' AND old.checked_at>=s.due_at))
 OR 5<(SELECT count(*) FROM payment_refund_query_consumption WHERE attempt_id=NEW.attempt_id AND decision='CALL')
 OR NEW.decision='SKIP_TERMINAL' AND a.state NOT IN('SUCCEEDED','FAILED','WITHDRAWN')
 OR NEW.decision='SKIP_DELAYED' AND NOT EXISTS(SELECT 1 FROM payment_refund_query_consumption old WHERE old.attempt_id=NEW.attempt_id AND old.slot<>NEW.slot AND old.decision='CALL' AND old.checked_at>=s.due_at)
 THEN RAISE EXCEPTION 'EXACT_DUE_REFUND_QUERY_CONSUMPTION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_query_consumption_exact AFTER INSERT ON payment_refund_query_consumption DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION refund_query_consumption_graph();

CREATE FUNCTION refund_execution_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_refund_attempt a JOIN job j ON j.id=NEW.job_id WHERE a.id=NEW.attempt_id AND a.state='PROCESSING' AND a.fence_token=NEW.owner_token AND j.status='RUNNING' AND j.owner_token=NEW.owner_token AND j.lease_until>clock_timestamp() AND j.cause_key=a.id::text AND
 (j.type='REFUND_SUBMIT.v1' AND j.payload=a.id::text OR j.type='REFUND_QUERY_DUE.v1' AND EXISTS(SELECT 1 FROM payment_refund_query_consumption c WHERE c.attempt_id=a.id AND c.job_id=j.id AND c.owner_token=NEW.owner_token AND c.decision='CALL')))
 THEN RAISE EXCEPTION 'ACTUAL_REFUND_EXECUTION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_execution_exact AFTER INSERT OR UPDATE ON payment_refund_execution DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION refund_execution_graph();

CREATE FUNCTION refund_operation_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_refund_query_request q JOIN job j ON j.id=q.job_id WHERE q.cause_id=NEW.id AND q.attempt_id=NEW.attempt_id AND q.job_id=NEW.job_id AND q.original_version=NEW.original_version AND q.reason=NEW.reason AND j.type='REFUND_SUBMIT.v1' AND j.dedupe_key='refund-query:'||NEW.attempt_id::text||':'||NEW.id::text AND j.cause_key=NEW.attempt_id::text AND j.payload=NEW.attempt_id::text)
 OR NOT EXISTS(SELECT 1 FROM identity_user u JOIN role_assignment r ON r.user_id=u.id AND r.role='ADMIN' JOIN admin_permission p ON p.user_id=u.id AND p.permission='SYSTEM' WHERE u.id=NEW.actor AND u.state='ACTIVE' AND u.auth_version=NEW.auth_version)
 THEN RAISE EXCEPTION 'ACTUAL_SYSTEM_REFUND_QUERY_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_operation_exact AFTER INSERT ON payment_refund_operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION refund_operation_graph();

-- Preserve all V102 request/binding/fencing checks and add only exact consumed automatic GET sources.
CREATE OR REPLACE FUNCTION verify_provider_refund_call(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c payment_refund_provider_call;r payment_refund_provider_request;a payment_refund_attempt;
BEGIN
 SELECT * INTO c FROM payment_refund_provider_call WHERE id=p_id;SELECT * INTO r FROM payment_refund_provider_request WHERE attempt_id=c.attempt_id;SELECT * INTO a FROM payment_refund_attempt WHERE id=c.attempt_id;
 IF c.id IS NULL OR r.attempt_id IS NULL OR a.id IS NULL OR c.request_fingerprint IS DISTINCT FROM r.request_fingerprint
 OR a.state IS DISTINCT FROM 'PROCESSING' OR a.fence_token IS DISTINCT FROM c.owner_token
 OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=c.job_id AND j.owner_token=c.owner_token AND j.status='RUNNING' AND j.lease_until>clock_timestamp() AND j.cause_key=a.id::text AND
 (j.type='REFUND_SUBMIT.v1' AND j.payload=a.id::text OR c.operation='GET' AND j.type='REFUND_QUERY_DUE.v1' AND EXISTS(SELECT 1 FROM payment_refund_query_consumption x JOIN payment_refund_query_slot s USING(attempt_id,slot,job_id) WHERE x.attempt_id=a.id AND x.job_id=j.id AND x.owner_token=c.owner_token AND x.decision='CALL' AND j.payload=a.id::text||'|'||s.slot::text AND j.scheduled_at=s.due_at)))
 THEN RAISE EXCEPTION 'ACTUAL_PROVIDER_REFUND_CALL_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
