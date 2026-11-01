CREATE TABLE claim_shipping_obligation (
 seller_order_id uuid PRIMARY KEY,order_id uuid NOT NULL,claim_id uuid NOT NULL,kind varchar(30) NOT NULL DEFAULT 'ORIGINAL_SHIPPING_REFUND' CHECK(kind='ORIGINAL_SHIPPING_REFUND'),
 amount bigint NOT NULL CHECK(amount>=0),refund_attempt_id uuid NOT NULL UNIQUE REFERENCES payment_refund_attempt(id) DEFERRABLE INITIALLY DEFERRED,
 state varchar(12) NOT NULL CHECK(state IN ('RESERVED','SUCCEEDED')),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(claim_id,seller_order_id,order_id) REFERENCES claim(id,seller_order_id,order_id)
);
CREATE TABLE order_cancellation_settlement (
 cause_id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL UNIQUE,payment_id uuid NOT NULL,refund_attempt_id uuid NOT NULL UNIQUE,reference varchar(200) NOT NULL,fingerprint varchar(64) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(cause_id,order_id),FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),FOREIGN KEY(hold_id,order_id) REFERENCES order_unit_hold(id,order_id),FOREIGN KEY(payment_id,order_id) REFERENCES payment_attempt(id,order_id)
);
CREATE TABLE order_cancellation_settlement_unit (
 item_id uuid NOT NULL,ordinal integer NOT NULL,order_id uuid NOT NULL,cause_id uuid NOT NULL,PRIMARY KEY(item_id,ordinal),
 FOREIGN KEY(item_id,ordinal,order_id) REFERENCES order_unit_lifecycle(item_id,ordinal,order_id),FOREIGN KEY(cause_id,order_id) REFERENCES order_cancellation_settlement(cause_id,order_id)
);
CREATE TABLE order_cancellation_shipping (
 seller_order_id uuid PRIMARY KEY,order_id uuid NOT NULL,cause_id uuid NOT NULL UNIQUE,payment_id uuid NOT NULL,refund_attempt_id uuid NOT NULL UNIQUE,amount bigint NOT NULL CHECK(amount>=0),reference varchar(200) NOT NULL,fingerprint varchar(64) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),FOREIGN KEY(payment_id,order_id) REFERENCES payment_attempt(id,order_id)
);
CREATE TABLE order_aftercare_closure (
 order_id uuid PRIMARY KEY REFERENCES purchase_order(id),target varchar(10) NOT NULL CHECK(target IN ('CLOSED','CANCELED')),reason varchar(30) NOT NULL CHECK(reason IN ('ALL_UNSHIPPED_CANCEL','ALL_CONFIRMED','MIXED_CONFIRMED_CANCEL')),payment_id uuid,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),CHECK((reason='ALL_CONFIRMED' AND payment_id IS NULL AND target='CLOSED') OR(reason='ALL_UNSHIPPED_CANCEL' AND payment_id IS NOT NULL AND target='CANCELED') OR(reason='MIXED_CONFIRMED_CANCEL' AND payment_id IS NOT NULL AND target='CLOSED'))
);
