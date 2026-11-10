CREATE TABLE operations_report (
    id uuid PRIMARY KEY,
    reporter_id uuid NOT NULL REFERENCES identity_user(id),
    target_kind text NOT NULL,
    target_id uuid NOT NULL,
    edition bigint NOT NULL CHECK (edition >= 0),
    state text NOT NULL CHECK (state IN ('RECEIVED','INVESTIGATING','CLOSED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (reporter_id,target_kind,target_id,edition),
    UNIQUE (id,reporter_id)
);
CREATE INDEX operations_report_quota ON operations_report(reporter_id,created_at);
CREATE TABLE operations_report_evidence (
    reporter_id uuid NOT NULL,
    request_key uuid NOT NULL,
    report_id uuid NOT NULL,
    submitted_target text NOT NULL,
    asset_id uuid,
    reason text NOT NULL CHECK (length(trim(reason)) > 0),
    outcome text NOT NULL CHECK (outcome IN ('RECEIVED','EVIDENCE_ADDED','REOPEN_REQUESTED')),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (reporter_id,request_key),
    FOREIGN KEY (report_id,reporter_id) REFERENCES operations_report(id,reporter_id)
);
