CREATE TABLE payment_attempt (
 id uuid PRIMARY KEY, order_id uuid NOT NULL UNIQUE REFERENCES purchase_order(id), order_number varchar(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('CREATED','PROCESSING','SUCCEEDED','FAILED','UNKNOWN')),
 amount bigint NOT NULL CHECK(amount>=0), mode varchar(16) NOT NULL CHECK(mode IN ('DEMO','SANDBOX')),
 compensation varchar(24) NOT NULL DEFAULT 'NONE' CHECK(compensation IN ('NONE','PENDING','UNKNOWN','CANCELED','FAILED')),
 review_required boolean NOT NULL DEFAULT false, review_reason varchar(64),
 started_at timestamptz NOT NULL, round_started_at timestamptz NOT NULL, query_count integer NOT NULL DEFAULT 0 CHECK(query_count BETWEEN 0 AND 5),
 round integer NOT NULL DEFAULT 0 CHECK(round>=0), next_job uuid, version bigint NOT NULL DEFAULT 0
);
CREATE TABLE payment_event (
 id uuid PRIMARY KEY, attempt_id uuid NOT NULL REFERENCES payment_attempt(id), action varchar(40) NOT NULL,
 actor uuid, reason varchar(300), occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 event_key varchar(160) NOT NULL UNIQUE
);
-- Local DEMO provider state survives process interruption. No production HTTP configuration route exists.
CREATE TABLE payment_mock_setting (order_id uuid PRIMARY KEY REFERENCES purchase_order(id), approval varchar(24) NOT NULL, cancellation varchar(24) NOT NULL DEFAULT 'SUCCESS');
CREATE TABLE payment_mock (
 attempt_id uuid PRIMARY KEY REFERENCES payment_attempt(id), approval varchar(24) NOT NULL, cancellation varchar(24) NOT NULL,
 state varchar(16) NOT NULL, cancel_state varchar(16) NOT NULL DEFAULT 'NONE',
 approval_effects integer NOT NULL DEFAULT 0 CHECK(approval_effects BETWEEN 0 AND 1), cancel_effects integer NOT NULL DEFAULT 0 CHECK(cancel_effects BETWEEN 0 AND 1),
 approval_calls integer NOT NULL DEFAULT 0, query_calls integer NOT NULL DEFAULT 0, cancel_calls integer NOT NULL DEFAULT 0
);
