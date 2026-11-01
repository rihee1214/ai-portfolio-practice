CREATE TABLE claim (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,order_number varchar(64) NOT NULL,seller_order_id uuid NOT NULL,checkout_id uuid NOT NULL,hold_id uuid NOT NULL UNIQUE,
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,quote_fingerprint varchar(64) NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),policy_version varchar(100) NOT NULL CHECK(policy_version='P09-C-CANCEL-1'),
 original_revision bigint NOT NULL CHECK(original_revision>=0),item_amount bigint NOT NULL CHECK(item_amount>=0),original_shipping bigint NOT NULL CHECK(original_shipping>=0),
 refund_attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id) DEFERRABLE INITIALLY DEFERRED,
 state varchar(24) NOT NULL CHECK(state IN ('APPROVED','PROCESSING','REVIEW_REQUIRED','SETTLEMENT_PENDING','COMPLETED','WITHDRAWN')),
 receipt jsonb NOT NULL,version bigint NOT NULL DEFAULT 0 CHECK(version>=0),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),FOREIGN KEY(hold_id,order_id) REFERENCES order_unit_hold(id,order_id),
 UNIQUE(order_id,request_key),UNIQUE(id,seller_order_id,order_id)
);
CREATE TABLE claim_unit (
 claim_id uuid NOT NULL,seller_order_id uuid NOT NULL,order_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,sku_id uuid NOT NULL,net bigint NOT NULL CHECK(net>=0),
 PRIMARY KEY(claim_id,item_id,ordinal),FOREIGN KEY(claim_id,seller_order_id,order_id) REFERENCES claim(id,seller_order_id,order_id),
 FOREIGN KEY(item_id,seller_order_id,order_id) REFERENCES order_item(id,seller_order_id,order_id),FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal)
);
CREATE TABLE claim_event (
 id uuid PRIMARY KEY,claim_id uuid NOT NULL REFERENCES claim(id),version bigint NOT NULL,action varchar(40) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(claim_id,version,action)
);
CREATE TABLE claim_refund_effect (
 claim_id uuid NOT NULL REFERENCES claim(id),attempt_id uuid NOT NULL UNIQUE REFERENCES payment_refund_attempt(id),purpose varchar(10) NOT NULL CHECK(purpose IN ('ITEM','SHIPPING')),
 result_version bigint NOT NULL CHECK(result_version>0),reference varchar(200) NOT NULL,occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(claim_id,purpose)
);
