CREATE SCHEMA c3_fixture;

CREATE TABLE c3_fixture.account (
    id bigint PRIMARY KEY,
    label text NOT NULL UNIQUE,
    balance numeric(14, 2) NOT NULL CHECK (balance >= 0),
    note text
);

CREATE TABLE c3_fixture.transfer_entry (
    reference text PRIMARY KEY,
    from_id bigint NOT NULL REFERENCES c3_fixture.account(id),
    to_id bigint NOT NULL REFERENCES c3_fixture.account(id),
    amount numeric(14, 2) NOT NULL CHECK (amount > 0),
    CHECK (from_id <> to_id)
);
