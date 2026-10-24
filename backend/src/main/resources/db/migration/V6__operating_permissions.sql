CREATE TABLE admin_permission (
 user_id uuid NOT NULL REFERENCES identity_user(id),
 permission varchar(24) NOT NULL CHECK (permission IN ('ACCOUNTS','CONTENT','SUPPORT','SETTLEMENTS','SYSTEM')),
 PRIMARY KEY(user_id,permission)
);
CREATE TABLE admin_bootstrap (
 id integer PRIMARY KEY CHECK (id=1),
 user_id uuid REFERENCES identity_user(id),
 completed_at timestamptz,
 CHECK ((user_id IS NULL) = (completed_at IS NULL))
);
INSERT INTO admin_bootstrap(id) VALUES(1);
CREATE TABLE authorization_audit (
 id uuid PRIMARY KEY,
 target_id uuid NOT NULL REFERENCES identity_user(id),
 action varchar(32) NOT NULL CHECK (action='INITIAL_ADMIN_GRANTED'),
 source varchar(32) NOT NULL CHECK (source='LOCAL_BOOTSTRAP'),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 detail varchar(200) NOT NULL
);
