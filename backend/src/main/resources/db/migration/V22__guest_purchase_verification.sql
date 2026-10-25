CREATE TABLE guest_checkout (
 id uuid PRIMARY KEY,
 token_hash varchar(64) NOT NULL UNIQUE,
 browser_hash varchar(64) NOT NULL,
 purpose varchar(16) NOT NULL CHECK (purpose = 'PURCHASE'),
 expires_at timestamptz NOT NULL,
 state varchar(16) NOT NULL CHECK (state IN ('OPEN','VERIFIED','CONSUMED','REVOKED')),
 current_attempt uuid,
 consumed_at timestamptz,
 created_at timestamptz NOT NULL
);
CREATE INDEX guest_checkout_browser ON guest_checkout(browser_hash);
CREATE TABLE guest_attempt (
 id uuid PRIMARY KEY,
 checkout_id uuid NOT NULL REFERENCES guest_checkout(id),
 buyer_name varchar(80) NOT NULL,
 phone varchar(16) NOT NULL,
 purpose varchar(16) NOT NULL CHECK (purpose = 'PURCHASE'),
 provider varchar(32) NOT NULL DEFAULT 'FIREBASE_EMULATOR' CHECK (provider = 'FIREBASE_EMULATOR'),
 mode varchar(16) NOT NULL DEFAULT 'local-test' CHECK (mode = 'local-test'),
 state varchar(16) NOT NULL CHECK (state IN ('ISSUING','SENT','EXCHANGING','EXCHANGED','CONFIRMING','VERIFIED','REJECTED')),
 provider_id varchar(64),
 issued_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 authenticated_at timestamptz,
 consumed_at timestamptz,
 failures integer NOT NULL DEFAULT 0 CHECK(failures BETWEEN 0 AND 5)
);
ALTER TABLE guest_checkout ADD CONSTRAINT guest_checkout_attempt_fk FOREIGN KEY(current_attempt) REFERENCES guest_attempt(id);
CREATE TABLE guest_phone_limit (
 key_hash varchar(64) PRIMARY KEY,
 window_start timestamptz NOT NULL,
 last_sent timestamptz,
 sends integer NOT NULL DEFAULT 0,
 failures integer NOT NULL DEFAULT 0
);
