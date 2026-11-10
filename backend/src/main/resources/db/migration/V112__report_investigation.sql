ALTER TABLE operations_report
 ADD COLUMN parent_id uuid,
 ADD COLUMN private_target boolean NOT NULL DEFAULT false,
 ADD COLUMN assignee uuid REFERENCES identity_user(id),
 ADD COLUMN version bigint NOT NULL DEFAULT 0,
 ADD COLUMN decision text CHECK(decision IN ('VIOLATION','NO_VIOLATION','INCONCLUSIVE')),
 ADD CONSTRAINT report_privacy_parent CHECK(NOT private_target OR (parent_id IS NOT NULL AND target_kind IN ('QUESTION','ANSWER')));
CREATE TABLE operations_report_event (
 report_id uuid NOT NULL REFERENCES operations_report(id),
 version bigint NOT NULL,
 actor uuid NOT NULL REFERENCES identity_user(id),
 request_key uuid NOT NULL,
 command text NOT NULL,
 previous_state text NOT NULL,
 next_state text NOT NULL,
 previous_assignee uuid,
 next_assignee uuid,
 decision text,
 reason text NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(report_id,version),
 UNIQUE(report_id,actor,request_key)
);
