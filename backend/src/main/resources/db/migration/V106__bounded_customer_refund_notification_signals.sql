SET search_path TO marketplace,public;

-- A signal is neither a cancellation attribution nor a new GET budget. Its original slots stay fixed.
CREATE TABLE payment_refund_signal (
 payment_id uuid NOT NULL REFERENCES payment_attempt(id),payload_sha256 char(64) NOT NULL CHECK(payload_sha256~'^[0-9a-f]{64}$'),
 provider_status varchar(20) NOT NULL CHECK(provider_status IN('DONE','PARTIAL_CANCELED','CANCELED')),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(payment_id,payload_sha256)
);
CREATE TRIGGER refund_signal_immutable BEFORE UPDATE OR DELETE ON payment_refund_signal
 FOR EACH ROW EXECUTE FUNCTION immutable_provider_refund_evidence();
CREATE FUNCTION refund_signal_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM payment_attempt p JOIN payment_toss_binding b ON b.attempt_id=p.id
  WHERE p.id=NEW.payment_id AND p.mode='SANDBOX' AND p.state='SUCCEEDED' AND p.compensation='NONE' AND p.amount=b.amount)
 OR NOT EXISTS(SELECT 1 FROM payment_refund_attempt a JOIN payment_refund_query_policy q ON q.attempt_id=a.id
  WHERE a.payment_id=NEW.payment_id AND a.amount>0 AND a.state IN('PROCESSING','UNKNOWN') AND q.prepared_at=a.started_at)
 THEN RAISE EXCEPTION 'ORIGINAL_PENDING_CUSTOMER_REFUND_SIGNAL_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_signal_exact AFTER INSERT ON payment_refund_signal DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION refund_signal_graph();
