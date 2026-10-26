ALTER TABLE guest_attempt ADD COLUMN prepared_password_hash text;
CREATE TABLE guest_buyer (
 id uuid PRIMARY KEY,
 buyer_name varchar(80) NOT NULL,
 verified_phone varchar(16) NOT NULL,
 password_hash text NOT NULL CHECK (password_hash LIKE '{argon2id}%'),
 password_version bigint NOT NULL DEFAULT 1 CHECK(password_version > 0),
 created_at timestamptz NOT NULL
);
-- This is an ownership reference, not a real order/payment/shipping record.
CREATE TABLE guest_order_reference (
 order_number varchar(64) PRIMARY KEY,
 guest_buyer_id uuid NOT NULL REFERENCES guest_buyer(id)
);
CREATE INDEX guest_order_reference_buyer ON guest_order_reference(guest_buyer_id);
CREATE TABLE guest_access_session (
 token_hash varchar(64) PRIMARY KEY,
 guest_buyer_id uuid NOT NULL REFERENCES guest_buyer(id),
 password_version bigint NOT NULL,
 idle_expires_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 CHECK(idle_expires_at <= expires_at)
);
CREATE TABLE guest_login_limit (
 key_hash varchar(64) PRIMARY KEY,
 window_start timestamptz NOT NULL,
 requests integer NOT NULL CHECK(requests >= 0)
);
