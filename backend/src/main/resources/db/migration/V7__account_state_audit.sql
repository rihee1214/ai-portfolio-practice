CREATE TABLE account_state_audit (
 id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES identity_user(id),
 target_id uuid NOT NULL REFERENCES identity_user(id),
 previous_state varchar(24) NOT NULL,
 next_state varchar(24) NOT NULL,
 reason varchar(300) NOT NULL CHECK (length(btrim(reason)) > 0),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK ((previous_state='ACTIVE' AND next_state IN ('SUSPENDED','WITHDRAWN'))
     OR (previous_state='SUSPENDED' AND next_state='ACTIVE'))
);
CREATE INDEX account_state_audit_target_idx ON account_state_audit(target_id,occurred_at);
