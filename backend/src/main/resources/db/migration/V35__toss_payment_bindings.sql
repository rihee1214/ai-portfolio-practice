-- Raw provider payment references are server-only durable recovery data. Never put them in audit/log payloads.
CREATE TABLE payment_toss_binding (
 attempt_id uuid PRIMARY KEY REFERENCES payment_attempt(id),
 provider_payment_key varchar(200) NOT NULL UNIQUE,
 provider_order_id varchar(64) NOT NULL UNIQUE,
 amount bigint NOT NULL CHECK(amount>0),
 CHECK(length(provider_payment_key)>0)
);
CREATE TABLE payment_webhook_receipt (
 attempt_id uuid NOT NULL REFERENCES payment_attempt(id),
 payload_sha256 char(64) NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(attempt_id,payload_sha256)
);
