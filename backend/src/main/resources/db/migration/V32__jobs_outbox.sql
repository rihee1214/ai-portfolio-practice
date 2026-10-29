CREATE TABLE job (
    id UUID PRIMARY KEY,
    type VARCHAR(100) NOT NULL CHECK (length(trim(type))>0),
    dedupe_key VARCHAR(200) NOT NULL UNIQUE CHECK (length(trim(dedupe_key))>0),
    cause_key VARCHAR(200) NOT NULL CHECK (length(trim(cause_key))>0),
    payload TEXT NOT NULL CHECK (length(payload)<=65536),
    scheduled_at TIMESTAMPTZ NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY','RUNNING','SUCCEEDED','FAILED')),
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 0 AND 5),
    owner_token UUID,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(64),
    CHECK ((status='RUNNING' AND owner_token IS NOT NULL AND lease_until IS NOT NULL AND attempts>0)
        OR (status<>'RUNNING' AND owner_token IS NULL AND lease_until IS NULL)),
    CHECK (status<>'READY' OR attempts<5)
);
CREATE INDEX job_due ON job(type,next_run_at,id) WHERE status IN ('READY','RUNNING');
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES job(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
