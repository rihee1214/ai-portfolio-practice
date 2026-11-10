-- A new accepted physical-return application, never a fabricated CANCEL or financial completion.
CREATE TABLE order_return_application (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL UNIQUE,
 cause_id uuid NOT NULL UNIQUE,hold_kind varchar(10) NOT NULL DEFAULT 'RETURN' CHECK(hold_kind='RETURN'),
 requested_at timestamptz NOT NULL,window_seconds bigint NOT NULL CHECK(window_seconds>0),fingerprint varchar(64) NOT NULL,
 source_snapshot jsonb NOT NULL CHECK(jsonb_typeof(source_snapshot)='object'),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 FOREIGN KEY(hold_id,cause_id,order_id,hold_kind) REFERENCES order_unit_hold(id,cause_id,order_id,kind),
 UNIQUE(id,hold_id,cause_id,seller_order_id,order_id)
);
CREATE TABLE claim_return_request (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,hold_id uuid NOT NULL,cause_id uuid NOT NULL,
 intent_kind varchar(10) NOT NULL DEFAULT 'RETURN' CHECK(intent_kind='RETURN'),
 request_key varchar(100) NOT NULL,request_fingerprint varchar(64) NOT NULL,reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),
 state varchar(32) NOT NULL CHECK(state IN ('REQUESTED','APPROVED','REJECTED','WITHDRAWN','COLLECTING','AWAITING_CONSENT','REVIEW_REQUIRED','REFUND_PENDING','DUE_PENDING','COMPLETED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),requested_at timestamptz NOT NULL,
 receipt jsonb NOT NULL CHECK(jsonb_typeof(receipt)='object'),
 FOREIGN KEY(id,hold_id,cause_id,seller_order_id,order_id) REFERENCES order_return_application(id,hold_id,cause_id,seller_order_id,order_id),
 FOREIGN KEY(id,seller_order_id,order_id,intent_kind) REFERENCES claim_financial_intent(id,seller_order_id,order_id,kind),
 UNIQUE(order_id,request_key),UNIQUE(id,hold_id,seller_order_id,order_id)
);
CREATE TABLE claim_return_unit (
 return_id uuid NOT NULL,hold_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,
 item_id uuid NOT NULL,ordinal integer NOT NULL CHECK(ordinal>=0),
 claimed_responsibility varchar(10) NOT NULL CHECK(claimed_responsibility IN ('BUYER','SELLER')),
 PRIMARY KEY(return_id,item_id,ordinal),
 FOREIGN KEY(return_id,hold_id,seller_order_id,order_id) REFERENCES claim_return_request(id,hold_id,seller_order_id,order_id),
 FOREIGN KEY(return_id,item_id,ordinal) REFERENCES claim_financial_intent_unit(intent_id,item_id,ordinal),
 FOREIGN KEY(hold_id,item_id,ordinal) REFERENCES order_hold_unit(hold_id,item_id,ordinal)
);
CREATE TABLE claim_return_history (
 id uuid PRIMARY KEY,return_id uuid NOT NULL REFERENCES claim_return_request(id),
 action varchar(40) NOT NULL,version bigint NOT NULL CHECK(version>=0),actor varchar(100) NOT NULL,
 reason varchar(500) NOT NULL,occurred_at timestamptz NOT NULL,UNIQUE(return_id,version)
);
CREATE FUNCTION return_application_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_RETURN_APPLICATION_EVIDENCE' USING ERRCODE='23514'; END $$;
CREATE TRIGGER order_return_application_immutable BEFORE UPDATE OR DELETE ON order_return_application FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE TRIGGER claim_return_unit_immutable BEFORE UPDATE OR DELETE ON claim_return_unit FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE TRIGGER claim_return_history_immutable BEFORE UPDATE OR DELETE ON claim_return_history FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION claim_return_receipt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'IMMUTABLE_RETURN_RECEIPT' USING ERRCODE='23514'; END IF;
 IF (OLD.id,OLD.order_id,OLD.seller_order_id,OLD.hold_id,OLD.cause_id,OLD.intent_kind,OLD.request_key,OLD.request_fingerprint,OLD.reason,OLD.requested_at,OLD.receipt)
  IS DISTINCT FROM (NEW.id,NEW.order_id,NEW.seller_order_id,NEW.hold_id,NEW.cause_id,NEW.intent_kind,NEW.request_key,NEW.request_fingerprint,NEW.reason,NEW.requested_at,NEW.receipt)
 THEN RAISE EXCEPTION 'IMMUTABLE_RETURN_RECEIPT' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER claim_return_receipt_immutable BEFORE UPDATE OR DELETE ON claim_return_request FOR EACH ROW EXECUTE FUNCTION claim_return_receipt_immutable();
