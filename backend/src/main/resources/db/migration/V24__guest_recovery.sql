ALTER TABLE guest_buyer ADD COLUMN recovery_code_hash varchar(64);
CREATE TABLE guest_recovery_attempt (
 id uuid PRIMARY KEY,
 buyer_id uuid NOT NULL REFERENCES guest_buyer(id),
 browser_hash varchar(64) NOT NULL,
 phone varchar(16) NOT NULL,
 purpose varchar(16) NOT NULL DEFAULT 'RECOVERY' CHECK(purpose='RECOVERY'),
 password_version bigint NOT NULL,
 recovery_hash varchar(64) NOT NULL,
 provider_id text,
 issued_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('ISSUING','SENT','EXCHANGING','EXCHANGED','CONFIRMING','READY','CONSUMED','REJECTED')),
 failures integer NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 5),
 grant_hash varchar(64) UNIQUE,
 grant_expires_at timestamptz,
 CHECK ((grant_hash IS NULL) = (grant_expires_at IS NULL))
);
CREATE INDEX guest_recovery_buyer ON guest_recovery_attempt(buyer_id);
CREATE TABLE guest_recovery_limit (
 key_hash varchar(64) PRIMARY KEY,
 window_start timestamptz NOT NULL,
 requests integer NOT NULL CHECK(requests >= 0),
 last_sent timestamptz
);
