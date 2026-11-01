ALTER TABLE payment_attempt ADD CONSTRAINT payment_refund_identity UNIQUE(id,order_id);
CREATE TABLE payment_refund_budget (
 payment_id uuid PRIMARY KEY,order_id uuid NOT NULL,paid bigint NOT NULL CHECK(paid>=0),
 succeeded bigint NOT NULL DEFAULT 0 CHECK(succeeded>=0),reserved bigint NOT NULL DEFAULT 0 CHECK(reserved>=0),
 FOREIGN KEY(payment_id,order_id) REFERENCES payment_attempt(id,order_id),UNIQUE(payment_id,order_id),CHECK(reserved<=paid AND succeeded<=paid-reserved)
);
CREATE TABLE payment_refund_attempt (
 id uuid PRIMARY KEY,cause_id uuid NOT NULL UNIQUE,payment_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,claim_id uuid NOT NULL,
 purpose varchar(10) NOT NULL CHECK(purpose IN ('ITEM','SHIPPING')),sequence integer NOT NULL CHECK(sequence>0),amount bigint NOT NULL CHECK(amount>=0),
 original_reference varchar(200) NOT NULL,fingerprint varchar(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('RESERVED','PROCESSING','UNKNOWN','FAILED','SUCCEEDED','WITHDRAWN')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),submit_key varchar(100) NOT NULL UNIQUE,started_at timestamptz,fence_token uuid,
 result_reference varchar(200),result_at timestamptz,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(payment_id,order_id) REFERENCES payment_refund_budget(payment_id,order_id),FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 UNIQUE(payment_id,claim_id,purpose,sequence),
 CHECK((state='SUCCEEDED' AND result_reference IS NOT NULL AND length(result_reference)>0 AND result_at IS NOT NULL) OR(state<>'SUCCEEDED' AND result_reference IS NULL AND result_at IS NULL)),
 CHECK((state IN ('RESERVED','WITHDRAWN') AND started_at IS NULL AND fence_token IS NULL) OR(state NOT IN ('RESERVED','WITHDRAWN') AND started_at IS NOT NULL AND fence_token IS NOT NULL))
);
CREATE TABLE payment_refund_event (
 id uuid PRIMARY KEY,attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),version bigint NOT NULL,action varchar(30) NOT NULL,
 reason varchar(500),occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(attempt_id,version,action)
);
CREATE TABLE payment_refund_mock_setting (claim_id uuid NOT NULL,purpose varchar(10) NOT NULL,behavior varchar(24) NOT NULL CHECK(behavior IN ('SUCCESS','FAILED','UNKNOWN','LOST_RESPONSE')),PRIMARY KEY(claim_id,purpose));
CREATE TABLE payment_refund_mock (
 attempt_id uuid PRIMARY KEY REFERENCES payment_refund_attempt(id),amount bigint NOT NULL CHECK(amount>=0),idempotency_key varchar(100) NOT NULL UNIQUE,
 behavior varchar(24) NOT NULL,state varchar(16) NOT NULL CHECK(state IN ('SUCCEEDED','FAILED','UNKNOWN')),reference varchar(200),
 submit_calls integer NOT NULL DEFAULT 0,query_calls integer NOT NULL DEFAULT 0,effects integer NOT NULL CHECK(effects BETWEEN 0 AND 1)
);
CREATE FUNCTION check_refund_budget(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b payment_refund_budget%ROWTYPE;
BEGIN
 SELECT * INTO b FROM payment_refund_budget WHERE payment_id=target;
 IF NOT FOUND THEN RETURN; END IF;
 IF NOT EXISTS(SELECT 1 FROM payment_attempt p WHERE p.id=target AND p.amount=b.paid AND p.state='SUCCEEDED' AND p.compensation='NONE')
 OR b.succeeded IS DISTINCT FROM (SELECT COALESCE(sum(amount),0) FROM payment_refund_attempt WHERE payment_id=target AND state='SUCCEEDED')
 OR b.reserved IS DISTINCT FROM (SELECT COALESCE(sum(amount),0) FROM payment_refund_attempt WHERE payment_id=target AND state IN ('RESERVED','PROCESSING','UNKNOWN'))
 THEN RAISE EXCEPTION 'CUSTOMER_REFUND_BUDGET_MISMATCH' USING ERRCODE='23514'; END IF;
END $$;
CREATE FUNCTION check_refund_budget_row() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN PERFORM check_refund_budget(COALESCE(NEW.payment_id,OLD.payment_id));RETURN NULL;END $$;
CREATE CONSTRAINT TRIGGER refund_budget_valid AFTER INSERT OR UPDATE OR DELETE ON payment_refund_budget DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_refund_budget_row();
CREATE CONSTRAINT TRIGGER refund_attempt_budget_valid AFTER INSERT OR UPDATE OR DELETE ON payment_refund_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_refund_budget_row();
CREATE FUNCTION protect_refund_attempt() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.state IN ('SUCCEEDED','FAILED','WITHDRAWN') OR (NEW.id,NEW.cause_id,NEW.payment_id,NEW.order_id,NEW.seller_order_id,NEW.claim_id,NEW.purpose,NEW.sequence,NEW.amount,NEW.original_reference,NEW.fingerprint,NEW.submit_key) IS DISTINCT FROM (OLD.id,OLD.cause_id,OLD.payment_id,OLD.order_id,OLD.seller_order_id,OLD.claim_id,OLD.purpose,OLD.sequence,OLD.amount,OLD.original_reference,OLD.fingerprint,OLD.submit_key)
 THEN RAISE EXCEPTION 'IMMUTABLE_REFUND_ATTEMPT' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER refund_attempt_immutable BEFORE UPDATE OR DELETE ON payment_refund_attempt FOR EACH ROW EXECUTE FUNCTION protect_refund_attempt();
