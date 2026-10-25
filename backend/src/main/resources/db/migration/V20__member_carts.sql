CREATE TABLE cart (
 user_id uuid PRIMARY KEY REFERENCES identity_user(id),
 version bigint NOT NULL DEFAULT 0 CHECK(version BETWEEN 0 AND 9007199254740991)
);
CREATE TABLE cart_item (
 user_id uuid NOT NULL REFERENCES cart(user_id),
 sku_id uuid NOT NULL,
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),
 PRIMARY KEY(user_id,sku_id)
);
-- Missing/inactive SKU identifiers are retained as unavailable cart items.
CREATE TABLE cart_merge (
 request_id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES cart(user_id),
 payload text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
