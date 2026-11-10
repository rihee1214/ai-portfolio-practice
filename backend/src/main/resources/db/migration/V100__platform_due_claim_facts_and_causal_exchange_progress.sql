-- Scheduler decisions are generated only by the actual job transition, in its transaction.
-- Event observations may move backwards; neither this fact nor history replaces final fencing.
SET search_path TO marketplace,public;
ALTER TABLE job ADD COLUMN claim_checked_at timestamptz;
CREATE TABLE job_claim_fact (
 job_id uuid NOT NULL REFERENCES job(id),attempt integer NOT NULL CHECK(attempt BETWEEN 1 AND 5),owner_token uuid NOT NULL,
 claimed_at timestamptz NOT NULL,scheduled_at_snapshot timestamptz NOT NULL,required_at timestamptz NOT NULL,
 eligibility_kind varchar(8) NOT NULL CHECK(eligibility_kind IN('READY','TAKEOVER')),
 initial_lease_until timestamptz NOT NULL,previous_attempt integer NOT NULL,previous_owner uuid,
 created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
 PRIMARY KEY(job_id,attempt),UNIQUE(job_id,owner_token),UNIQUE(job_id,attempt,owner_token),
 CHECK(attempt=previous_attempt+1 AND claimed_at>=required_at AND initial_lease_until=claimed_at+interval '30 seconds')
);
CREATE FUNCTION immutable_platform_claim_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_PLATFORM_CLAIM_FACT' USING ERRCODE='23514';END $$;
CREATE TRIGGER platform_claim_fact_immutable BEFORE UPDATE OR DELETE ON job_claim_fact FOR EACH ROW EXECUTE FUNCTION immutable_platform_claim_fact();
CREATE FUNCTION platform_claim_transition_check() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NEW.attempts IS DISTINCT FROM OLD.attempts THEN
  IF NEW.attempts<>OLD.attempts+1 OR OLD.attempts>=5 OR OLD.status NOT IN('READY','RUNNING')
   OR NEW.status<>'RUNNING' OR NEW.owner_token IS NULL OR NEW.owner_token IS NOT DISTINCT FROM OLD.owner_token
   OR NEW.claim_checked_at IS NULL OR NEW.lease_until IS DISTINCT FROM NEW.claim_checked_at+interval '30 seconds'
   OR OLD.status='READY' AND(NEW.claim_checked_at<OLD.next_run_at OR NEW.claim_checked_at<OLD.scheduled_at)
   OR OLD.status='RUNNING' AND(NEW.claim_checked_at<OLD.lease_until OR NEW.claim_checked_at<OLD.scheduled_at AND NOT EXISTS(
    SELECT 1 FROM job_claim_fact predecessor WHERE predecessor.job_id=OLD.id AND predecessor.attempt=OLD.attempts AND predecessor.owner_token=OLD.owner_token AND predecessor.scheduled_at_snapshot=OLD.scheduled_at))
  THEN RAISE EXCEPTION 'ACTUAL_DUE_JOB_CLAIM_TRANSITION_REQUIRED' USING ERRCODE='23514';END IF;
 ELSIF NEW.claim_checked_at IS DISTINCT FROM OLD.claim_checked_at THEN
  RAISE EXCEPTION 'CLAIM_DECISION_REQUIRES_NEW_ATTEMPT' USING ERRCODE='23514';
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER platform_claim_transition_before BEFORE UPDATE ON job FOR EACH ROW EXECUTE FUNCTION platform_claim_transition_check();
CREATE FUNCTION platform_claim_transition_record() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NEW.attempts=OLD.attempts+1 THEN
  INSERT INTO job_claim_fact(job_id,attempt,owner_token,claimed_at,scheduled_at_snapshot,required_at,eligibility_kind,initial_lease_until,previous_attempt,previous_owner)
  VALUES(NEW.id,NEW.attempts,NEW.owner_token,NEW.claim_checked_at,OLD.scheduled_at,CASE OLD.status WHEN 'READY' THEN OLD.next_run_at ELSE OLD.lease_until END,CASE OLD.status WHEN 'READY' THEN 'READY' ELSE 'TAKEOVER' END,NEW.lease_until,OLD.attempts,OLD.owner_token);
 END IF;RETURN NULL;
END $$;
CREATE TRIGGER platform_claim_transition_recorded AFTER UPDATE ON job FOR EACH ROW EXECUTE FUNCTION platform_claim_transition_record();
CREATE FUNCTION platform_claim_fact_current_transition() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF pg_trigger_depth()<2 OR NEW.created_xid<>pg_current_xact_id() OR NOT EXISTS(
  SELECT 1 FROM job j WHERE j.id=NEW.job_id AND j.status='RUNNING' AND j.attempts=NEW.attempt AND j.owner_token=NEW.owner_token
   AND j.claim_checked_at=NEW.claimed_at AND j.scheduled_at=NEW.scheduled_at_snapshot AND j.lease_until=NEW.initial_lease_until)
 THEN RAISE EXCEPTION 'CLAIM_FACT_ONLY_FROM_ACTUAL_JOB_TRANSITION' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER platform_claim_fact_origin BEFORE INSERT ON job_claim_fact FOR EACH ROW EXECUTE FUNCTION platform_claim_fact_current_transition();
CREATE FUNCTION platform_claim_transition_bilateral() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NEW.attempts=OLD.attempts+1 AND NOT EXISTS(
  SELECT 1 FROM job_claim_fact f WHERE f.job_id=NEW.id AND f.attempt=NEW.attempts AND f.owner_token=NEW.owner_token
   AND f.claimed_at=NEW.claim_checked_at AND f.scheduled_at_snapshot=OLD.scheduled_at AND f.previous_attempt=OLD.attempts
   AND f.previous_owner IS NOT DISTINCT FROM OLD.owner_token AND f.initial_lease_until=NEW.lease_until
   AND f.required_at=CASE OLD.status WHEN 'READY' THEN OLD.next_run_at ELSE OLD.lease_until END
   AND f.eligibility_kind=CASE OLD.status WHEN 'READY' THEN 'READY' ELSE 'TAKEOVER' END AND f.created_xid=pg_current_xact_id())
 THEN RAISE EXCEPTION 'JOB_CLAIM_AND_IMMUTABLE_FACT_SAME_TRANSACTION_REQUIRED' USING ERRCODE='23514';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER platform_claim_transition_exact AFTER UPDATE ON job DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform_claim_transition_bilateral();

-- Exact pre-migration rows, not a caller-selectable schema/version flag. No claim-time backfill.
CREATE TABLE platform_legacy_exchange_progress (
 shipment_id uuid NOT NULL,version bigint NOT NULL,event_id uuid NOT NULL,job_id uuid NOT NULL,owner_token uuid NOT NULL,
 PRIMARY KEY(shipment_id,version)
);
INSERT INTO platform_legacy_exchange_progress SELECT shipment_id,version,event_id,job_id,owner_token FROM fulfillment_exchange_progress_effect;
CREATE TRIGGER legacy_exchange_progress_immutable BEFORE INSERT OR UPDATE OR DELETE ON platform_legacy_exchange_progress FOR EACH ROW EXECUTE FUNCTION immutable_platform_claim_fact();
ALTER TABLE fulfillment_exchange_progress_effect ADD COLUMN claim_attempt integer;
ALTER TABLE fulfillment_exchange_progress_effect ADD CONSTRAINT exchange_progress_actual_claim_fact FOREIGN KEY(job_id,claim_attempt,owner_token) REFERENCES job_claim_fact(job_id,attempt,owner_token);
CREATE FUNCTION exchange_progress_exact_due_claim() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN
 IF NEW.claim_attempt IS NULL OR NOT EXISTS(
  SELECT 1 FROM job_claim_fact f JOIN job j ON j.id=f.job_id JOIN fulfillment_exchange_progress_schedule s ON s.job_id=f.job_id
  WHERE f.job_id=NEW.job_id AND f.attempt=NEW.claim_attempt AND f.owner_token=NEW.owner_token
   AND j.status='RUNNING' AND j.attempts=f.attempt AND j.owner_token=f.owner_token AND j.lease_until>clock_timestamp()
   AND s.shipment_id=NEW.shipment_id AND s.expected_version=NEW.version AND f.scheduled_at_snapshot=s.scheduled_at)
 THEN RAISE EXCEPTION 'EXCHANGE_PROGRESS_ACTUAL_DUE_CLAIM_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;
END $$;
CREATE TRIGGER exchange_progress_due_claim_before BEFORE INSERT ON fulfillment_exchange_progress_effect FOR EACH ROW EXECUTE FUNCTION exchange_progress_exact_due_claim();

CREATE OR REPLACE FUNCTION verify_exchange_shipping(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE h fulfillment_exchange_shipment;e fulfillment_exchange_event;z fulfillment_exchange_progress_effect;s fulfillment_exchange_progress_schedule;n integer;last_version bigint;expected_state text;
BEGIN
 SELECT * INTO h FROM fulfillment_exchange_shipment WHERE id=p_id;IF h.id IS NULL THEN RAISE EXCEPTION 'ACTUAL_EXCHANGE_SHIPMENT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM fulfillment_exchange_allocation WHERE shipment_id=p_id;
 IF n=0 OR n<>(SELECT count(*) FROM claim_exchange_unit WHERE exchange_id=h.exchange_id) OR 2<>(SELECT count(*) FROM fulfillment_exchange_progress_schedule WHERE shipment_id=p_id)
 OR EXISTS(SELECT 1 FROM fulfillment_exchange_progress_schedule sch LEFT JOIN job j ON j.id=sch.job_id LEFT JOIN outbox_event o ON o.job_id=j.id WHERE sch.shipment_id=p_id AND(j.id IS NULL OR o.id IS NULL OR j.type<>'EXCHANGE_SHIPMENT_PROGRESS.v1' OR j.dedupe_key<>'exchange-progress:'||p_id::text||':'||sch.expected_version::text OR j.cause_key<>p_id::text OR j.payload<>p_id::text||'|'||sch.expected_version::text OR j.scheduled_at<>sch.scheduled_at OR sch.scheduled_at<>h.shipped_at))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_SCHEDULE_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT max(version) INTO last_version FROM fulfillment_exchange_event WHERE shipment_id=p_id;
 IF last_version IS NULL OR last_version NOT IN(1,2,3) OR h.version<>last_version OR last_version<>(SELECT count(*) FROM fulfillment_exchange_event WHERE shipment_id=p_id)
 OR h.state<>(CASE last_version WHEN 1 THEN 'SHIPPED' WHEN 2 THEN 'IN_TRANSIT' ELSE 'DELIVERED' END)
 OR NOT EXISTS(SELECT 1 FROM fulfillment_exchange_event f WHERE f.shipment_id=p_id AND f.version=last_version AND f.state=h.state AND (last_version<3 AND h.delivered_at IS NULL OR last_version=3 AND h.delivered_at=f.occurred_at))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_SHIPPING_HEADER_EVENT_REQUIRED' USING ERRCODE='23514';END IF;
 FOR e IN SELECT * FROM fulfillment_exchange_event WHERE shipment_id=p_id ORDER BY version LOOP
  expected_state=CASE e.version WHEN 1 THEN 'SHIPPED' WHEN 2 THEN 'IN_TRANSIT' WHEN 3 THEN 'DELIVERED' ELSE 'INVALID' END;
  IF e.state<>expected_state OR e.version=1 AND e.occurred_at<>h.shipped_at OR e.version>1 AND NOT EXISTS(SELECT 1 FROM fulfillment_exchange_event p WHERE p.shipment_id=p_id AND p.version=e.version-1) THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_STATE_TIME_REQUIRED' USING ERRCODE='23514';END IF;
  IF e.version=1 THEN
   IF EXISTS(SELECT 1 FROM fulfillment_exchange_progress_effect WHERE event_id=e.id) OR NOT EXISTS(SELECT 1 FROM order_exchange_dispatch d JOIN claim_exchange_dispatch_effect c ON c.exchange_id=d.exchange_id WHERE d.exchange_id=h.exchange_id AND d.shipment_id=h.id AND d.event_id=e.id AND c.shipment_id=h.id AND c.dispatch_id=h.dispatch_id)
   THEN RAISE EXCEPTION 'EXACT_EXCHANGE_DISPATCH_EVENT_REQUIRED' USING ERRCODE='23514';END IF;
  ELSE
   SELECT * INTO z FROM fulfillment_exchange_progress_effect WHERE shipment_id=p_id AND version=e.version;SELECT * INTO s FROM fulfillment_exchange_progress_schedule WHERE shipment_id=p_id AND expected_version=e.version;
   IF z.shipment_id IS NULL OR s.shipment_id IS NULL OR z.event_id<>e.id OR z.job_id<>s.job_id OR NOT(EXISTS(SELECT 1 FROM platform_legacy_exchange_progress legacy WHERE (legacy.shipment_id,legacy.version,legacy.event_id,legacy.job_id,legacy.owner_token)=(z.shipment_id,z.version,z.event_id,z.job_id,z.owner_token)) OR EXISTS(SELECT 1 FROM job_claim_fact due WHERE due.job_id=z.job_id AND due.attempt=z.claim_attempt AND due.owner_token=z.owner_token AND due.scheduled_at_snapshot=s.scheduled_at)) OR NOT EXISTS(SELECT 1 FROM job j WHERE j.id=z.job_id AND j.type='EXCHANGE_SHIPMENT_PROGRESS.v1' AND j.dedupe_key='exchange-progress:'||p_id::text||':'||e.version::text AND j.cause_key=p_id::text AND j.payload=p_id::text||'|'||e.version::text AND j.scheduled_at=s.scheduled_at)
   THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_JOB_EFFECT_REQUIRED' USING ERRCODE='23514';END IF;
   IF e.version=3 THEN
    IF n<>(SELECT count(*) FROM fulfillment_exchange_allocation a JOIN order_physical_delivery d ON d.physical_id=a.physical_id AND d.bucket_id=p_id AND d.event_id=e.id AND d.delivered_at=e.occurred_at WHERE a.shipment_id=p_id)
    OR NOT EXISTS(SELECT 1 FROM order_exchange_delivery_bucket b WHERE b.bucket_id=p_id AND b.shipment_id=p_id)
    OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_DELIVERED.v1' AND j.dedupe_key='exchange-delivered:'||h.exchange_id::text||':'||e.id::text AND j.cause_key=h.exchange_id::text AND j.payload=h.exchange_id::text||'|'||e.id::text AND j.scheduled_at=e.occurred_at)
    THEN RAISE EXCEPTION 'EXACT_EXCHANGE_DELIVERY_AND_NOTIFICATION_REQUIRED' USING ERRCODE='23514';END IF;
   END IF;
  END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM fulfillment_exchange_progress_effect effect_row LEFT JOIN fulfillment_exchange_event event_row ON event_row.id=effect_row.event_id WHERE effect_row.shipment_id=p_id AND(event_row.id IS NULL OR event_row.shipment_id<>p_id OR event_row.version<>effect_row.version OR event_row.version NOT IN(2,3)))
 OR EXISTS(SELECT 1 FROM order_physical_delivery d JOIN fulfillment_exchange_allocation a ON a.physical_id=d.physical_id LEFT JOIN fulfillment_exchange_event actual_event ON actual_event.id=d.event_id WHERE a.shipment_id=p_id AND(d.bucket_id<>p_id OR actual_event.id IS NULL OR actual_event.shipment_id<>p_id OR actual_event.version<>3 OR actual_event.state<>'DELIVERED' OR d.delivered_at<>actual_event.occurred_at))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_PROGRESS_REVERSE_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
