CREATE TABLE inventory_reservation (
 id uuid PRIMARY KEY,
 checkout_id uuid NOT NULL,
 order_item_id uuid NOT NULL UNIQUE,
 sku_id uuid NOT NULL REFERENCES inventory_balance(sku_id),
 quantity bigint NOT NULL CHECK(quantity BETWEEN 1 AND 999999999),
 expires_at timestamptz NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('RESERVED','COMMITTED','RELEASED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 finished_at timestamptz,
 CHECK((status='RESERVED' AND finished_at IS NULL) OR (status<>'RESERVED' AND finished_at IS NOT NULL))
);
CREATE INDEX inventory_reservation_expiry ON inventory_reservation(expires_at,id) WHERE status='RESERVED';
CREATE INDEX inventory_reservation_checkout ON inventory_reservation(checkout_id,id);
CREATE TABLE inventory_reservation_event (
 reservation_id uuid NOT NULL REFERENCES inventory_reservation(id),
 sku_id uuid NOT NULL REFERENCES inventory_balance(sku_id),
 cause varchar(16) NOT NULL CHECK(cause IN ('RESERVE','COMMIT','RELEASE','EXPIRE')),
 quantity bigint NOT NULL CHECK(quantity BETWEEN 1 AND 999999999),
 before_on_hand bigint NOT NULL CHECK(before_on_hand BETWEEN 0 AND 999999999),
 after_on_hand bigint NOT NULL CHECK(after_on_hand BETWEEN 0 AND 999999999),
 before_reserved bigint NOT NULL CHECK(before_reserved BETWEEN 0 AND before_on_hand),
 after_reserved bigint NOT NULL CHECK(after_reserved BETWEEN 0 AND after_on_hand),
 before_version bigint NOT NULL CHECK(before_version>=0),
 version bigint NOT NULL CHECK(version=before_version+1 AND version<=9007199254740991),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(reservation_id,cause), UNIQUE(sku_id,version),
 CHECK((cause='RESERVE' AND after_on_hand=before_on_hand AND after_reserved=before_reserved+quantity)
    OR (cause='COMMIT' AND after_on_hand=before_on_hand-quantity AND after_reserved=before_reserved-quantity)
    OR (cause IN ('RELEASE','EXPIRE') AND after_on_hand=before_on_hand AND after_reserved=before_reserved-quantity))
);
CREATE UNIQUE INDEX inventory_reservation_one_terminal_event ON inventory_reservation_event(reservation_id) WHERE cause<>'RESERVE';
