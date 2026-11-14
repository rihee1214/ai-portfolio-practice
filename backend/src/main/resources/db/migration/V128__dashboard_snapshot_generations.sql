SET search_path TO marketplace,public;

CREATE TABLE dashboard_run (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 job_id uuid NOT NULL UNIQUE REFERENCES job(id),
 scheduled_at timestamptz NOT NULL,
 state text NOT NULL CHECK(state IN ('PENDING','PUBLISHED','SUPERSEDED')),
 completed_at timestamptz
);
CREATE TABLE dashboard_request (
 request_key uuid PRIMARY KEY, actor_id uuid NOT NULL, reason text NOT NULL CHECK(length(trim(reason)) BETWEEN 1 AND 300),
 run_id bigint NOT NULL REFERENCES dashboard_run(id), requested_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE dashboard_snapshot (
 id uuid PRIMARY KEY, run_id bigint NOT NULL REFERENCES dashboard_run(id), job_id uuid NOT NULL,
 attempt integer NOT NULL, token uuid NOT NULL, definition_version integer NOT NULL CHECK(definition_version=1),
 source_as_of timestamptz NOT NULL, source_snapshot pg_snapshot NOT NULL,
 created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 UNIQUE(run_id,attempt), UNIQUE(run_id,id),
 FOREIGN KEY(job_id,attempt,token) REFERENCES job_claim_fact(job_id,attempt,owner_token)
);
ALTER TABLE dashboard_run ADD UNIQUE(id,job_id);
ALTER TABLE dashboard_snapshot ADD FOREIGN KEY(run_id,job_id) REFERENCES dashboard_run(id,job_id);
CREATE TABLE dashboard_metric (
 snapshot_id uuid NOT NULL REFERENCES dashboard_snapshot(id), scope text NOT NULL CHECK(scope IN ('GLOBAL','STORE','ASSIGNEE')),
 scope_id uuid, day date, metric text NOT NULL, value numeric NOT NULL CHECK(value=trunc(value)),
 CHECK((scope='GLOBAL')=(scope_id IS NULL)),
 UNIQUE NULLS NOT DISTINCT(snapshot_id,scope,scope_id,metric,day)
);
CREATE TABLE dashboard_head (
 singleton boolean PRIMARY KEY CHECK(singleton), run_id bigint, snapshot_id uuid,
 last_scheduled timestamptz, CHECK((run_id IS NULL)=(snapshot_id IS NULL)),
 FOREIGN KEY(run_id,snapshot_id) REFERENCES dashboard_snapshot(run_id,id)
);
INSERT INTO dashboard_head(singleton) VALUES(true);

CREATE FUNCTION dashboard_preserve_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'DASHBOARD_SNAPSHOT_IMMUTABLE' USING ERRCODE='23514';
END $$;
CREATE TRIGGER dashboard_snapshot_immutable BEFORE UPDATE OR DELETE ON dashboard_snapshot
FOR EACH ROW EXECUTE FUNCTION dashboard_preserve_snapshot();
CREATE TRIGGER dashboard_metric_immutable BEFORE UPDATE OR DELETE ON dashboard_metric
FOR EACH ROW EXECUTE FUNCTION dashboard_preserve_snapshot();
CREATE TRIGGER dashboard_request_immutable BEFORE UPDATE OR DELETE ON dashboard_request
FOR EACH ROW EXECUTE FUNCTION dashboard_preserve_snapshot();
CREATE FUNCTION dashboard_metric_same_snapshot_transaction() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM dashboard_snapshot s WHERE s.id=NEW.snapshot_id AND s.created_xid=pg_current_xact_id()) THEN
  RAISE EXCEPTION 'DASHBOARD_SNAPSHOT_SEALED' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER dashboard_metric_sealed BEFORE INSERT ON dashboard_metric
FOR EACH ROW EXECUTE FUNCTION dashboard_metric_same_snapshot_transaction();
