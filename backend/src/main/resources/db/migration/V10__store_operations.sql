ALTER TABLE seller_store ADD COLUMN state varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','SUSPENDED','CLOSED'));
ALTER TABLE seller_store ADD CONSTRAINT store_restricted_not_public CHECK(state='OPEN' OR visible=false);
CREATE TABLE store_operation_event (
 id uuid PRIMARY KEY,
 store_id uuid NOT NULL REFERENCES seller_store(id),
 actor_id uuid NOT NULL REFERENCES identity_user(id),
 action varchar(16) NOT NULL CHECK(action IN ('suspend','restore','close')),
 before_state varchar(16) NOT NULL CHECK(before_state IN ('OPEN','SUSPENDED')),
 after_state varchar(16) NOT NULL CHECK(after_state IN ('OPEN','SUSPENDED','CLOSED')),
 reason varchar(300) NOT NULL CHECK(length(btrim(reason))>0),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX store_operation_history ON store_operation_event(store_id,occurred_at DESC,id);
