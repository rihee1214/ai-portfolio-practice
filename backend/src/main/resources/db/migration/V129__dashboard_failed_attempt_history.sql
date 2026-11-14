SET search_path TO marketplace,public;
CREATE TABLE dashboard_failed_attempt (
    job_id uuid NOT NULL REFERENCES dashboard_run(job_id),
    attempt integer NOT NULL,
    owner_token uuid NOT NULL,
    failed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (job_id,attempt),
    FOREIGN KEY (job_id,attempt,owner_token) REFERENCES job_claim_fact(job_id,attempt,owner_token)
);
CREATE TRIGGER dashboard_failed_attempt_immutable BEFORE UPDATE OR DELETE ON dashboard_failed_attempt
FOR EACH ROW EXECUTE FUNCTION dashboard_preserve_snapshot();
