-- Administrative operation for a verified NEW, isolated restore target only.
-- Caller must set p12d.restore_database to the verified physical database name
-- and p12d.restore_run to its ownership UUID on this same connection.
BEGIN;
DO $guard$
BEGIN
 IF current_database() !~ '^d2_it_[0-9a-f]{32}$'
    OR current_setting('p12d.restore_database', true) IS DISTINCT FROM current_database()
    OR coalesce(current_setting('p12d.restore_run', true), '') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
 THEN RAISE EXCEPTION 'Verified isolated restore identity is required'; END IF;
END $guard$;
SET LOCAL search_path TO marketplace, public;
CREATE TEMP TABLE p12d_invalidation_count(kind text PRIMARY KEY, affected bigint NOT NULL) ON COMMIT DROP;
WITH changed AS (DELETE FROM spring_session_attributes RETURNING 1)
 INSERT INTO p12d_invalidation_count SELECT 'sessionAttributes',count(*) FROM changed;
WITH changed AS (DELETE FROM spring_session RETURNING 1)
 INSERT INTO p12d_invalidation_count SELECT 'sessions',count(*) FROM changed;
WITH changed AS (UPDATE guest_access_session SET revoked_at=clock_timestamp() WHERE revoked_at IS NULL RETURNING 1)
 INSERT INTO p12d_invalidation_count SELECT 'guestAccess',count(*) FROM changed;
WITH changed AS (UPDATE email_verification_token SET consumed_at=greatest(clock_timestamp(),created_at) WHERE consumed_at IS NULL RETURNING 1)
 INSERT INTO p12d_invalidation_count SELECT 'emailTokens',count(*) FROM changed;
WITH changed AS (UPDATE password_reset_token SET consumed_at=greatest(clock_timestamp(),created_at) WHERE consumed_at IS NULL RETURNING 1)
 INSERT INTO p12d_invalidation_count SELECT 'passwordTokens',count(*) FROM changed;
WITH changed AS (UPDATE guest_checkout SET state='REVOKED' WHERE state IN ('OPEN','VERIFIED') RETURNING 1)
 INSERT INTO p12d_invalidation_count SELECT 'guestCheckouts',count(*) FROM changed;
WITH changed AS (UPDATE guest_recovery_attempt SET state='REJECTED',grant_hash=NULL,grant_expires_at=NULL
 WHERE state NOT IN ('CONSUMED','REJECTED') OR (state='REJECTED' AND grant_hash IS NOT NULL) RETURNING 1)
 INSERT INTO p12d_invalidation_count SELECT 'guestRecovery',count(*) FROM changed;
SELECT json_build_object('database',current_database(), 'runId',current_setting('p12d.restore_run'),
 'affected',(SELECT json_object_agg(kind,affected) FROM p12d_invalidation_count)) AS invalidation_counts;
COMMIT;
