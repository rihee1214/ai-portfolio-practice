-- V2 is reserved by the existing test-only JDBC fixture; never rewrite its history.
CREATE TABLE identity_user (
    id uuid PRIMARY KEY,
    email varchar(254) COLLATE "C" NOT NULL UNIQUE,
    state varchar(24) NOT NULL CHECK (state IN ('PENDING_EMAIL','ACTIVE','SUSPENDED','WITHDRAWN')),
    auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    created_at timestamptz NOT NULL,
    email_verified_at timestamptz,
    CHECK (email = lower(btrim(email)) AND position('@' in email) > 1),
    CHECK (state <> 'ACTIVE' OR email_verified_at IS NOT NULL)
);
CREATE TABLE user_credential (
    user_id uuid PRIMARY KEY REFERENCES identity_user(id),
    password_hash text NOT NULL CHECK (password_hash LIKE '{argon2id}$argon2id$%')
);
CREATE TABLE role_assignment (
    user_id uuid NOT NULL REFERENCES identity_user(id),
    role varchar(24) NOT NULL CHECK (role IN ('BUYER','SELLER','ADMIN')),
    PRIMARY KEY (user_id,role)
);
CREATE TABLE email_verification_token (
    token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    user_id uuid NOT NULL REFERENCES identity_user(id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX email_verification_user_idx ON email_verification_token(user_id);
