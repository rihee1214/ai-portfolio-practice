CREATE TABLE promotion (
 id uuid PRIMARY KEY, store_id uuid NOT NULL REFERENCES seller_store(id), title varchar(120) NOT NULL,
 scope varchar(8) NOT NULL CHECK(scope IN ('PRODUCT','SHIPPING')), starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL CHECK(ends_at>starts_at),
 kind varchar(5) NOT NULL CHECK(kind IN ('FIXED','RATE','FREE')), value bigint NOT NULL CHECK(value>=0), cap bigint CHECK(cap>=0),
 state varchar(8) NOT NULL CHECK(state IN ('DRAFT','ACTIVE','STOPPED','ARCHIVED')), version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 CHECK((kind='RATE' AND value BETWEEN 1 AND 10000) OR (kind='FIXED' AND value>0) OR (kind='FREE' AND value=0 AND cap IS NULL)),
 CHECK((scope='PRODUCT' AND kind IN ('FIXED','RATE')) OR (scope='SHIPPING' AND kind IN ('FIXED','FREE')))
);
CREATE INDEX promotion_store ON promotion(store_id,id);
CREATE TABLE promotion_target(promotion_id uuid NOT NULL REFERENCES promotion(id),product_id uuid NOT NULL REFERENCES catalog_product(id),PRIMARY KEY(promotion_id,product_id));
CREATE TABLE promotion_event(id uuid PRIMARY KEY,promotion_id uuid NOT NULL REFERENCES promotion(id),actor_id uuid NOT NULL REFERENCES identity_user(id),version bigint NOT NULL,before_state varchar(8),after_state varchar(8) NOT NULL,snapshot text NOT NULL,occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(promotion_id,version));
