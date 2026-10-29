CREATE TABLE order_checkout_attempt (
 id uuid PRIMARY KEY,
 purchase_session_id uuid NOT NULL,
 request_key varchar(128) NOT NULL CHECK(length(request_key)>0),
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 guest_attempt_id uuid UNIQUE REFERENCES guest_attempt(id),
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 UNIQUE(purchase_session_id,request_key),
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '15 minutes')
);
CREATE TABLE purchase_order (
 id uuid PRIMARY KEY,
 checkout_id uuid NOT NULL UNIQUE REFERENCES order_checkout_attempt(id),
 order_number varchar(64) NOT NULL UNIQUE,
 member_id uuid REFERENCES identity_user(id),
 guest_buyer_id uuid REFERENCES guest_buyer(id),
 mode varchar(16) NOT NULL CHECK(mode IN ('DEMO','SANDBOX')),
 state varchar(24) NOT NULL CHECK(state IN ('PENDING_PAYMENT','PAID','CLOSED','CANCELED','EXPIRED')),
 amount bigint NOT NULL CHECK(amount>=0),
 policy_version varchar(100) NOT NULL,
 quote_snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 CHECK((member_id IS NULL) <> (guest_buyer_id IS NULL)),
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '15 minutes')
);
CREATE INDEX purchase_order_member_history ON purchase_order(member_id,created_at DESC,id DESC);
CREATE INDEX purchase_order_guest_history ON purchase_order(guest_buyer_id,created_at DESC,id DESC);
CREATE INDEX purchase_order_expiry ON purchase_order(expires_at) WHERE state='PENDING_PAYMENT';
CREATE TABLE seller_order (
 id uuid PRIMARY KEY,
 order_id uuid NOT NULL REFERENCES purchase_order(id),
 store_id uuid NOT NULL REFERENCES seller_store(id),
 subtotal bigint NOT NULL CHECK(subtotal>=0),
 product_discount bigint NOT NULL CHECK(product_discount>=0 AND product_discount<=subtotal),
 coupon_discount bigint NOT NULL CHECK(coupon_discount>=0 AND coupon_discount<=subtotal-product_discount),
 shipping_base bigint NOT NULL CHECK(shipping_base>=0),
 shipping_discount bigint NOT NULL CHECK(shipping_discount>=0 AND shipping_discount<=shipping_base),
 total bigint NOT NULL CHECK(total>=0),
 UNIQUE(order_id,store_id), UNIQUE(id,order_id),
 CHECK(total::numeric=subtotal::numeric-product_discount-coupon_discount+shipping_base-shipping_discount)
);
CREATE TABLE order_item (
 id uuid PRIMARY KEY,
 order_id uuid NOT NULL REFERENCES purchase_order(id),
 seller_order_id uuid NOT NULL,
 sku_id uuid NOT NULL,
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),
 unit_price bigint NOT NULL CHECK(unit_price>=0),
 gross bigint NOT NULL CHECK(gross>=0),
 promotion_discount bigint NOT NULL CHECK(promotion_discount>=0 AND promotion_discount<=gross),
 coupon_discount bigint NOT NULL CHECK(coupon_discount>=0 AND coupon_discount<=gross-promotion_discount),
 net bigint NOT NULL CHECK(net>=0 AND net=gross-promotion_discount-coupon_discount),
 line_snapshot jsonb NOT NULL,
 UNIQUE(order_id,sku_id),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 CHECK(gross::numeric=unit_price::numeric*quantity)
);
CREATE TABLE order_unit (
 item_id uuid NOT NULL REFERENCES order_item(id),
 ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 98),
 gross bigint NOT NULL CHECK(gross>=0),
 promotion_discount bigint NOT NULL CHECK(promotion_discount>=0 AND promotion_discount<=gross),
 coupon_discount bigint NOT NULL CHECK(coupon_discount>=0 AND coupon_discount<=gross-promotion_discount),
 net bigint NOT NULL CHECK(net>=0 AND net=gross-promotion_discount-coupon_discount),
 PRIMARY KEY(item_id,ordinal)
);
CREATE TABLE order_contact (
 order_id uuid PRIMARY KEY REFERENCES purchase_order(id),
 buyer_name varchar(80) NOT NULL CHECK(length(buyer_name)>0),
 buyer_phone varchar(32) NOT NULL CHECK(length(buyer_phone)>0),
 recipient_name varchar(80) NOT NULL CHECK(length(recipient_name)>0),
 recipient_phone varchar(32) NOT NULL CHECK(length(recipient_phone)>0),
 postal_code varchar(20) NOT NULL CHECK(length(postal_code)>0),
 address_line1 varchar(300) NOT NULL CHECK(length(address_line1)>0),
 address_line2 varchar(300) NOT NULL
);
