-- Preserve V103 graph checks; OLD is an implicit trigger record, not a safe SQL alias.
SET search_path TO marketplace,public;
CREATE OR REPLACE FUNCTION refund_query_consumption_graph() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE s payment_refund_query_slot;p payment_refund_query_policy;a payment_refund_attempt;
BEGIN
 SELECT * INTO s FROM payment_refund_query_slot WHERE attempt_id=NEW.attempt_id AND slot=NEW.slot;
 SELECT * INTO p FROM payment_refund_query_policy WHERE attempt_id=NEW.attempt_id;SELECT * INTO a FROM payment_refund_attempt WHERE id=NEW.attempt_id;
 IF s.job_id IS DISTINCT FROM NEW.job_id OR NOT EXISTS(SELECT 1 FROM job j JOIN job_claim_fact f ON f.job_id=j.id AND f.owner_token=j.owner_token AND f.attempt=j.attempts WHERE j.id=NEW.job_id AND j.status='RUNNING' AND j.owner_token=NEW.owner_token AND j.lease_until>clock_timestamp() AND f.claimed_at=NEW.checked_at AND f.required_at<=f.claimed_at AND f.scheduled_at_snapshot=s.due_at)
 OR NEW.decision='CALL' AND(a.state IS DISTINCT FROM 'PROCESSING' OR a.fence_token IS DISTINCT FROM NEW.owner_token OR NEW.checked_at>=p.deadline
 OR NOT EXISTS(SELECT 1 FROM payment_refund_execution x WHERE x.attempt_id=a.id AND x.job_id=NEW.job_id AND x.owner_token=NEW.owner_token)
 OR EXISTS(SELECT 1 FROM payment_refund_query_consumption prior_call WHERE prior_call.attempt_id=a.id AND prior_call.slot<>NEW.slot AND prior_call.decision='CALL' AND prior_call.checked_at>=s.due_at))
 OR 5<(SELECT count(*) FROM payment_refund_query_consumption WHERE attempt_id=NEW.attempt_id AND decision='CALL')
 OR NEW.decision='SKIP_TERMINAL' AND a.state NOT IN('SUCCEEDED','FAILED','WITHDRAWN')
 OR NEW.decision='SKIP_DELAYED' AND NOT EXISTS(SELECT 1 FROM payment_refund_query_consumption prior_call WHERE prior_call.attempt_id=NEW.attempt_id AND prior_call.slot<>NEW.slot AND prior_call.decision='CALL' AND prior_call.checked_at>=s.due_at)
 THEN RAISE EXCEPTION 'EXACT_DUE_REFUND_QUERY_CONSUMPTION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
