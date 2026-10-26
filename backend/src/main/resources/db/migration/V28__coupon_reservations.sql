CREATE TABLE coupon_reservation (
 id uuid PRIMARY KEY, checkout_id uuid NOT NULL, seller_order_ref uuid NOT NULL,
 member_id uuid NOT NULL REFERENCES identity_user(id), store_id uuid NOT NULL REFERENCES seller_store(id), coupon_id uuid NOT NULL REFERENCES issued_coupon(id),
 request_hash varchar(64) NOT NULL, quote_snapshot jsonb NOT NULL, expires_at timestamptz NOT NULL,
 state varchar(9) NOT NULL CHECK(state IN ('RESERVED','COMMITTED','RELEASED','EXPIRED','RESTORED')), version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(checkout_id,store_id), UNIQUE(seller_order_ref)
);
CREATE UNIQUE INDEX coupon_one_active_use ON coupon_reservation(coupon_id) WHERE state IN ('RESERVED','COMMITTED');
CREATE TABLE coupon_reservation_event (
 id uuid PRIMARY KEY, reservation_id uuid NOT NULL REFERENCES coupon_reservation(id), action varchar(9) NOT NULL,
 version bigint NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(reservation_id,action)
);
CREATE TABLE coupon_restoration (
 cause_id uuid PRIMARY KEY, reservation_id uuid NOT NULL REFERENCES coupon_reservation(id), seller_order_ref uuid NOT NULL,
 request_hash varchar(64) NOT NULL, restored boolean NOT NULL, reusable boolean NOT NULL, reason varchar(30) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(), CHECK(NOT reusable OR restored)
);
CREATE UNIQUE INDEX coupon_one_restoration_per_use ON coupon_restoration(reservation_id) WHERE restored;
