CREATE TABLE order_reservation (
 order_id uuid NOT NULL REFERENCES purchase_order(id),
 reservation_id uuid NOT NULL UNIQUE,
 kind varchar(16) NOT NULL CHECK(kind IN ('INVENTORY','COUPON')),
 PRIMARY KEY(order_id,reservation_id)
);
CREATE TABLE order_event (
 id uuid PRIMARY KEY,
 order_id uuid NOT NULL REFERENCES purchase_order(id),
 action varchar(32) NOT NULL,
 occurred_at timestamptz NOT NULL,
 UNIQUE(order_id,action)
);
ALTER TABLE cart_item ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0);
CREATE TABLE cart_order_cleanup (
 order_id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES identity_user(id),
 payload text NOT NULL
);
