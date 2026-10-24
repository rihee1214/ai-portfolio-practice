ALTER TABLE identity_user ADD COLUMN display_name varchar(80) NOT NULL DEFAULT '';
ALTER TABLE identity_user ADD COLUMN phone varchar(25);
CREATE TABLE password_reset_token (
    user_id uuid PRIMARY KEY REFERENCES identity_user(id),
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE TABLE member_address (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_user(id),
    label varchar(40) NOT NULL CHECK (length(btrim(label)) > 0),
    recipient varchar(80) NOT NULL CHECK (length(btrim(recipient)) > 0),
    phone varchar(25) NOT NULL CHECK (length(phone) >= 7),
    postal_code varchar(5) NOT NULL CHECK (postal_code ~ '^[0-9]{5}$'),
    address_line1 varchar(200) NOT NULL CHECK (length(btrim(address_line1)) > 0),
    address_line2 varchar(200) NOT NULL DEFAULT '',
    is_default boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX member_address_owner_idx ON member_address(user_id, created_at, id);
CREATE UNIQUE INDEX member_address_one_default_idx ON member_address(user_id) WHERE is_default;
