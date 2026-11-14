CREATE TABLE notification_retry (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES notification_event(id),
    previous_job uuid NOT NULL UNIQUE REFERENCES job(id),
    job_id uuid NOT NULL UNIQUE REFERENCES job(id),
    operator_id uuid NOT NULL REFERENCES identity_user(id),
    request_key uuid NOT NULL,
    reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 500),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(operator_id,request_key)
);

-- Existing private audit reasons must never become owner-visible by migration.
ALTER TABLE operations_moderation_action ADD COLUMN public_reason varchar(500)
    CHECK (public_reason IS NULL OR length(btrim(public_reason))>0);
