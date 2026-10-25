CREATE TABLE inventory_balance (
 sku_id uuid PRIMARY KEY REFERENCES catalog_sku(id),
 on_hand bigint NOT NULL DEFAULT 0 CHECK(on_hand BETWEEN 0 AND 999999999),
 reserved bigint NOT NULL DEFAULT 0 CHECK(reserved BETWEEN 0 AND on_hand),
 version bigint NOT NULL DEFAULT 0 CHECK(version BETWEEN 0 AND 9007199254740991)
);
INSERT INTO inventory_balance(sku_id) SELECT id FROM catalog_sku;
CREATE TABLE stock_movement (
 request_id uuid PRIMARY KEY,
 sku_id uuid NOT NULL REFERENCES inventory_balance(sku_id),
 actor_id uuid NOT NULL REFERENCES identity_user(id),
 cause varchar(30) NOT NULL CHECK(cause='MANUAL_ADJUST'),
 reason varchar(200) NOT NULL CHECK(length(trim(reason)) BETWEEN 1 AND 200),
 before_on_hand bigint NOT NULL CHECK(before_on_hand BETWEEN 0 AND 999999999),
 after_on_hand bigint NOT NULL CHECK(after_on_hand BETWEEN 0 AND 999999999),
 reserved bigint NOT NULL CHECK(reserved BETWEEN 0 AND LEAST(before_on_hand,after_on_hand)),
 before_version bigint NOT NULL CHECK(before_version>=0),
 version bigint NOT NULL CHECK(version=before_version+1 AND version<=9007199254740991),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(sku_id,version), CHECK(before_on_hand<>after_on_hand)
);
