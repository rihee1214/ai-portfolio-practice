CREATE TABLE claim_return_command (
 return_id uuid NOT NULL REFERENCES claim_return_request(id),request_key varchar(100) NOT NULL,
 fingerprint varchar(64) NOT NULL,action varchar(40) NOT NULL CHECK(action IN ('APPROVED','REJECTED','WITHDRAWN')),
 version bigint NOT NULL CHECK(version>0),receipt jsonb NOT NULL CHECK(jsonb_typeof(receipt)='object'),
 PRIMARY KEY(return_id,request_key),UNIQUE(return_id,version),
 FOREIGN KEY(return_id,version) REFERENCES claim_return_history(return_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER claim_return_command_immutable BEFORE UPDATE OR DELETE ON claim_return_command FOR EACH ROW EXECUTE FUNCTION return_application_immutable();
CREATE FUNCTION return_actor_command_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;v_request marketplace.claim_return_request%ROWTYPE;v_command marketplace.claim_return_command%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='claim_return_request' THEN v_id:=NEW.id;ELSE v_id:=NEW.return_id;END IF;
 SELECT * INTO STRICT v_request FROM marketplace.claim_return_request WHERE id=v_id;
 IF v_request.version=0 THEN RETURN NULL;END IF;
 SELECT * INTO v_command FROM marketplace.claim_return_command WHERE return_id=v_id AND version=v_request.version;
 IF NOT FOUND OR v_command.action<>v_request.state THEN RAISE EXCEPTION 'RETURN_CURRENT_COMMAND_MISSING' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM marketplace.claim_return_command c WHERE c.return_id=v_id AND (
  c.version>v_request.version OR c.receipt->>'state' IS DISTINCT FROM c.action
  OR (c.receipt->>'version')::bigint IS DISTINCT FROM c.version
  OR c.receipt->'accepted' IS DISTINCT FROM v_request.receipt
  OR NOT EXISTS(SELECT 1 FROM marketplace.claim_return_history h WHERE h.return_id=v_id AND h.version=c.version
   AND h.action=c.action AND h.reason=c.receipt->'history'->(c.version::integer)->>'reason')
  OR (SELECT count(*) FROM marketplace.job j JOIN marketplace.outbox_event e ON e.job_id=j.id
   WHERE j.type='RETURN_RECORDED.v1' AND j.dedupe_key='return-command:'||v_id::text||':'||c.version::text
   AND j.cause_key=v_id::text AND j.payload=v_id::text)<>1
 )) THEN RAISE EXCEPTION 'RETURN_ACTOR_COMMAND_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 IF v_request.state IN ('REJECTED','WITHDRAWN') AND (
  EXISTS(SELECT 1 FROM marketplace.payment_refund_attempt a WHERE a.claim_id=v_id)
  OR EXISTS(SELECT 1 FROM marketplace.claim_fee_assessment a WHERE a.intent_id=v_id)
 ) THEN RAISE EXCEPTION 'RETURN_RELEASE_MUST_BE_UNFUNDED' USING ERRCODE='23514';END IF;
 RETURN NULL;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
 RAISE EXCEPTION 'RETURN_ACTOR_COMMAND_FORMAT' USING ERRCODE='23514';
END $$;
CREATE CONSTRAINT TRIGGER return_actor_state AFTER INSERT OR UPDATE ON claim_return_request DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_actor_command_guard();
CREATE CONSTRAINT TRIGGER return_actor_receipt AFTER INSERT ON claim_return_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_actor_command_guard();
CREATE CONSTRAINT TRIGGER return_actor_history AFTER INSERT ON claim_return_history DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_actor_command_guard();
