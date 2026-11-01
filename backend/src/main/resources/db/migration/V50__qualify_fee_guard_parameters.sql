SET search_path TO marketplace;

CREATE OR REPLACE FUNCTION check_fee_assessment(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE a claim_fee_assessment%ROWTYPE;b record;remaining bigint;u record;share bigint;
BEGIN
 SELECT * INTO a FROM claim_fee_assessment WHERE id=check_fee_assessment.target;IF NOT FOUND THEN RETURN;END IF;
 IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)
 OR NOT EXISTS(SELECT 1 FROM claim_fee_quote q WHERE q.id=a.quote_id AND q.body->>'net'=a.net::text AND q.body->>'increment'=a.increment::text AND q.body->>'refund'=a.refund::text AND q.body->>'additionalDue'=a.additional_due::text AND jsonb_array_length(q.body->'units')=(SELECT count(*) FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target))
 OR(SELECT COALESCE(sum(net),0) FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)<>a.net
 OR(SELECT COALESCE(sum(deducted),0) FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)<>a.net-a.refund
 OR(SELECT COALESCE(sum(increment),0) FROM claim_fee_assessment_bucket WHERE assessment_id=check_fee_assessment.target)<>a.increment
 OR(SELECT COALESCE(sum(additional_due),0) FROM claim_fee_assessment_bucket WHERE assessment_id=check_fee_assessment.target)<>a.additional_due
 OR EXISTS(SELECT item_id,ordinal FROM claim_financial_intent_unit WHERE intent_id=a.intent_id EXCEPT SELECT item_id,ordinal FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target)
 OR NOT EXISTS(SELECT 1 FROM claim_fee_consent c JOIN claim_fee_quote q ON q.id=c.quote_id WHERE c.assessment_id=check_fee_assessment.target AND q.id=a.quote_id AND q.fingerprint=a.quote_fingerprint AND c.legacy_consent=q.legacy_consent_required AND c.policy_source=CASE WHEN c.legacy_consent THEN 'LEGACY_QUOTE_EXPLICIT_CONSENT' ELSE 'ORDER_ITEM_SNAPSHOT' END)
 THEN RAISE EXCEPTION 'FEE_ASSESSMENT_CONTENT_MISMATCH' USING ERRCODE='23514';END IF;
 FOR b IN SELECT * FROM claim_fee_assessment_bucket WHERE assessment_id=check_fee_assessment.target LOOP
  IF NOT EXISTS(SELECT 1 FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id)
  OR b.selected_maximum<>(SELECT COALESCE(max(policy_fee) FILTER(WHERE responsibility='BUYER'),0) FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id)
  OR b.deducted<>(SELECT COALESCE(sum(deducted),0) FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id) THEN RAISE EXCEPTION 'FEE_BUCKET_ALLOCATION_MISMATCH' USING ERRCODE='23514';END IF;
  remaining:=b.increment;
  FOR u IN SELECT * FROM claim_fee_assessment_unit WHERE assessment_id=check_fee_assessment.target AND bucket_id=b.bucket_id ORDER BY item_id::text,ordinal LOOP
   share:=CASE WHEN u.responsibility='BUYER' THEN LEAST(remaining,u.net) ELSE 0 END;
   IF u.deducted<>share THEN RAISE EXCEPTION 'FEE_CANONICAL_ALLOCATION_MISMATCH' USING ERRCODE='23514';END IF;remaining:=remaining-share;
  END LOOP;
  IF remaining<>b.additional_due THEN RAISE EXCEPTION 'FEE_ADDITIONAL_DUE_MISMATCH' USING ERRCODE='23514';END IF;
 END LOOP;
END $$;

CREATE OR REPLACE FUNCTION check_fee_bucket(target uuid) RETURNS void LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE b claim_fee_bucket%ROWTYPE;
BEGIN
 SELECT * INTO b FROM claim_fee_bucket WHERE id=check_fee_bucket.target;IF NOT FOUND THEN RETURN;END IF;
 IF b.assessed_total<>(SELECT COALESCE(sum(x.increment),0) FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_fee_bucket.target AND a.state='APPLIED')
 OR b.deducted_applied<>(SELECT COALESCE(sum(x.deducted),0) FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_fee_bucket.target AND a.state='APPLIED')
 OR b.additional_collected<>0
 OR b.additional_outstanding<>(SELECT COALESCE(sum(x.additional_due),0) FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_fee_bucket.target AND a.state='APPLIED')
 OR(b.active_assessment_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.assessment_id=b.active_assessment_id AND x.bucket_id=check_fee_bucket.target AND a.state IN ('RESERVED','BOUND') AND x.baseline=b.assessed_total AND x.increment=b.reserved_increment))
 OR EXISTS(SELECT 1 FROM claim_fee_assessment_bucket x JOIN claim_fee_assessment a ON a.id=x.assessment_id WHERE x.bucket_id=check_fee_bucket.target AND a.state IN ('RESERVED','BOUND') AND x.assessment_id IS DISTINCT FROM b.active_assessment_id)
 THEN RAISE EXCEPTION 'FEE_BUCKET_LEDGER_MISMATCH' USING ERRCODE='23514';END IF;
END $$;
