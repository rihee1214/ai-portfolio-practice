-- A durable page plan is separate; each delivery and its candidate receipt commit together.
CREATE FUNCTION return_recovery_delivery_candidate_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM marketplace.claim_return_recovery_candidate c
  WHERE c.delivery_id=NEW.id AND c.outcome='DELIVERED' AND c.run_id=NEW.run_id AND c.stage=NEW.stage
   AND c.page_id=NEW.page_id AND c.return_id=NEW.return_id AND c.scan_job_id=NEW.scan_job_id
   AND c.owner_token=NEW.scan_owner_token)
 THEN RAISE EXCEPTION 'EXACT_RECOVERY_DELIVERY_CANDIDATE_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER recovery_delivery_candidate_proof AFTER INSERT ON claim_return_recovery_delivery
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION return_recovery_delivery_candidate_guard();
