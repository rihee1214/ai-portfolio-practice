SET search_path TO marketplace,public;

-- Previous customer refunds already have a durable T2 preparation. Never restart their 24h clock,
-- change the reserved amount, fabricate provider attribution, or replay their original POST.
DO $$
DECLARE candidate record;target_refund payment_refund_attempt;slot_number integer;
 delay_seconds integer;query_job uuid;query_key text;query_due timestamptz;
BEGIN
 FOR candidate IN SELECT a.id,a.order_id,a.payment_id FROM payment_refund_attempt a
  WHERE a.amount>0 AND a.state IN('PROCESSING','UNKNOWN') AND a.started_at IS NOT NULL
   AND NOT EXISTS(SELECT 1 FROM payment_refund_query_policy p WHERE p.attempt_id=a.id)
  ORDER BY a.order_id,a.payment_id,a.id
 LOOP
  -- Match the runtime parent -> budget -> attempt order and recheck after all waits.
  PERFORM 1 FROM purchase_order o WHERE o.id=candidate.order_id FOR UPDATE;
  PERFORM 1 FROM payment_refund_budget b WHERE b.payment_id=candidate.payment_id FOR UPDATE;
  SELECT * INTO target_refund FROM payment_refund_attempt a WHERE a.id=candidate.id FOR UPDATE;
  IF NOT FOUND OR target_refund.amount<=0 OR target_refund.state NOT IN('PROCESSING','UNKNOWN')
   OR target_refund.started_at IS NULL
   OR EXISTS(SELECT 1 FROM payment_refund_query_policy p WHERE p.attempt_id=target_refund.id)
  THEN CONTINUE;END IF;
  INSERT INTO payment_refund_query_policy(attempt_id,prepared_at,deadline)
   VALUES(target_refund.id,target_refund.started_at,target_refund.started_at+interval '24 hours');
  FOR slot_number IN 1..6 LOOP
   delay_seconds=CASE slot_number WHEN 1 THEN 60 WHEN 2 THEN 300 WHEN 3 THEN 1800 WHEN 4 THEN 7200 WHEN 5 THEN 43200 ELSE 86400 END;
   query_job=gen_random_uuid();query_key='refund-auto:'||target_refund.id::text||':'||slot_number::text;
   query_due=target_refund.started_at+make_interval(secs=>delay_seconds);
   INSERT INTO job(id,type,dedupe_key,cause_key,payload,scheduled_at,next_run_at,status,attempts)
    VALUES(query_job,'REFUND_QUERY_DUE.v1',query_key,target_refund.id::text,
     target_refund.id::text||'|'||slot_number::text,query_due,query_due,'READY',0);
   INSERT INTO outbox_event(id,job_id) VALUES(gen_random_uuid(),query_job);
   INSERT INTO payment_refund_query_slot(attempt_id,slot,due_at,job_id)
    VALUES(target_refund.id,slot_number,query_due,query_job);
  END LOOP;
 END LOOP;
END $$;
