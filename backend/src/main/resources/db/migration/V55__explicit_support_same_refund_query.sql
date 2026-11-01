SET search_path TO marketplace;

CREATE TABLE payment_refund_query_request (
 cause_id uuid PRIMARY KEY,attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),original_version bigint NOT NULL CHECK(original_version>0),original_state varchar(16) NOT NULL CHECK(original_state IN ('PROCESSING','UNKNOWN')),
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),fingerprint varchar(64) NOT NULL,job_id uuid NOT NULL UNIQUE REFERENCES job(id),requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(cause_id,attempt_id)
);
CREATE FUNCTION validate_refund_query_request() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_refund_attempt p JOIN job j ON j.id=NEW.job_id WHERE p.id=NEW.attempt_id AND p.version=NEW.original_version AND p.state=NEW.original_state AND j.type='REFUND_SUBMIT.v1' AND j.cause_key=p.id::text AND j.payload=p.id::text AND j.dedupe_key='refund-query:'||p.id||':'||NEW.cause_id AND j.status='READY' AND EXISTS(SELECT 1 FROM outbox_event e WHERE e.job_id=j.id))
 THEN RAISE EXCEPTION 'REFUND_QUERY_CURRENT_ATTEMPT_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER refund_query_request_valid BEFORE INSERT ON payment_refund_query_request FOR EACH ROW EXECUTE FUNCTION validate_refund_query_request();
CREATE FUNCTION immutable_refund_query_request() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_REFUND_QUERY_REQUEST' USING ERRCODE='23514';END $$;
CREATE TRIGGER refund_query_request_immutable BEFORE UPDATE OR DELETE ON payment_refund_query_request FOR EACH ROW EXECUTE FUNCTION immutable_refund_query_request();
CREATE TABLE claim_support_command (
 id uuid PRIMARY KEY,claim_id uuid NOT NULL REFERENCES claim(id),attempt_id uuid NOT NULL REFERENCES payment_refund_attempt(id),purpose varchar(10) NOT NULL CHECK(purpose IN ('ITEM','SHIPPING')),action varchar(20) NOT NULL CHECK(action='QUERY'),
 request_key varchar(100) NOT NULL,fingerprint varchar(64) NOT NULL,actor_id uuid NOT NULL REFERENCES identity_user(id),reason varchar(500) NOT NULL CHECK(length(trim(reason))>0),original_revision bigint NOT NULL CHECK(original_revision>=0),revision bigint NOT NULL CHECK(revision=original_revision+1),receipt jsonb NOT NULL,accepted_at timestamptz NOT NULL,
 UNIQUE(claim_id,request_key),FOREIGN KEY(id,attempt_id) REFERENCES payment_refund_query_request(cause_id,attempt_id)
);
CREATE FUNCTION validate_claim_support_query() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM claim c JOIN payment_refund_attempt p ON p.claim_id=c.id JOIN claim_financial_refund_binding b ON b.attempt_id=p.id JOIN payment_refund_query_request q ON q.cause_id=NEW.id AND q.attempt_id=p.id AND q.reason=NEW.reason
 WHERE c.id=NEW.claim_id AND c.version=NEW.original_revision AND c.state NOT IN ('COMPLETED','WITHDRAWN') AND p.id=NEW.attempt_id AND p.purpose=NEW.purpose AND p.state IN ('PROCESSING','UNKNOWN') AND p.order_id=c.order_id AND p.seller_order_id=c.seller_order_id AND b.intent_kind='CANCEL'
 AND ((NEW.purpose='ITEM' AND c.refund_attempt_id=p.id AND c.item_amount=p.amount) OR(NEW.purpose='SHIPPING' AND c.original_shipping=p.amount AND EXISTS(SELECT 1 FROM claim_shipping_obligation s WHERE s.claim_id=c.id AND s.refund_attempt_id=p.id))))
 THEN RAISE EXCEPTION 'SUPPORT_QUERY_CURRENT_CLAIM_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER claim_support_query_valid BEFORE INSERT ON claim_support_command FOR EACH ROW EXECUTE FUNCTION validate_claim_support_query();
CREATE TRIGGER claim_support_command_immutable BEFORE UPDATE OR DELETE ON claim_support_command FOR EACH ROW EXECUTE FUNCTION immutable_claim_result_receipt();
