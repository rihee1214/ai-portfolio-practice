CREATE TABLE seller_application (
 id uuid PRIMARY KEY,
 applicant_id uuid NOT NULL REFERENCES identity_user(id),
 display_name varchar(80) NOT NULL CHECK (length(btrim(display_name)) > 0),
 description varchar(500) NOT NULL CHECK (length(btrim(description)) > 0),
 state varchar(16) NOT NULL CHECK (state IN ('PENDING','APPROVED','REJECTED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX seller_application_one_open ON seller_application(applicant_id) WHERE state IN ('PENDING','APPROVED');
CREATE INDEX seller_application_owner ON seller_application(applicant_id,created_at DESC,id);
CREATE TABLE seller_application_event (
 id uuid PRIMARY KEY,
 application_id uuid NOT NULL REFERENCES seller_application(id),
 actor_id uuid NOT NULL REFERENCES identity_user(id),
 state varchar(16) NOT NULL CHECK (state IN ('PENDING','APPROVED','REJECTED')),
 reason varchar(300) NOT NULL CHECK (length(btrim(reason)) > 0),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(application_id,state)
);
