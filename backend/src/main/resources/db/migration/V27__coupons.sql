CREATE TABLE coupon_template (
 id uuid PRIMARY KEY, store_id uuid NOT NULL REFERENCES seller_store(id), title varchar(120) NOT NULL,
 kind varchar(5) NOT NULL CHECK(kind IN ('FIXED','RATE')), value bigint NOT NULL CHECK(value>0), cap bigint CHECK(cap>=0), minimum bigint NOT NULL CHECK(minimum>=0),
 issue_start timestamptz NOT NULL, issue_end timestamptz NOT NULL CHECK(issue_end>issue_start), starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL CHECK(ends_at>starts_at AND ends_at>=issue_end),
 total_limit bigint NOT NULL CHECK(total_limit>0), issued_count bigint NOT NULL DEFAULT 0 CHECK(issued_count>=0 AND issued_count<=total_limit),
 state varchar(8) NOT NULL CHECK(state IN ('DRAFT','ACTIVE','STOPPED','ARCHIVED')), version bigint NOT NULL DEFAULT 0 CHECK(version>=0), CHECK(kind<>'RATE' OR value<=10000)
);
CREATE INDEX coupon_template_store ON coupon_template(store_id,id);
CREATE TABLE issued_coupon (
 id uuid PRIMARY KEY, template_id uuid NOT NULL REFERENCES coupon_template(id), member_id uuid NOT NULL REFERENCES identity_user(id), store_id uuid NOT NULL REFERENCES seller_store(id),
 title varchar(120) NOT NULL, kind varchar(5) NOT NULL CHECK(kind IN ('FIXED','RATE')), value bigint NOT NULL CHECK(value>0), cap bigint CHECK(cap>=0), minimum bigint NOT NULL CHECK(minimum>=0), starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL CHECK(ends_at>starts_at),
 template_version bigint NOT NULL CHECK(template_version>=0), state varchar(9) NOT NULL DEFAULT 'AVAILABLE' CHECK(state IN ('AVAILABLE','RESERVED','USED')), version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(member_id,template_id), CHECK(kind<>'RATE' OR value<=10000)
);
CREATE INDEX issued_coupon_member ON issued_coupon(member_id,id);
CREATE TABLE coupon_event(id uuid PRIMARY KEY,template_id uuid NOT NULL REFERENCES coupon_template(id),coupon_id uuid REFERENCES issued_coupon(id),actor_id uuid NOT NULL REFERENCES identity_user(id),action varchar(16) NOT NULL,version bigint NOT NULL,snapshot text NOT NULL,occurred_at timestamptz NOT NULL DEFAULT clock_timestamp());
