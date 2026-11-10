-- Observed wall-clock values are immutable evidence, not causal ordering.
-- Exact request/generation/expiry/consent revisions and bilateral operation graphs remain mandatory.
-- Upper deadlines, seven-day durations, current authority and final job leases are unchanged.
SET search_path TO marketplace,public;

CREATE OR REPLACE FUNCTION verify_exchange_approval_generation(p_id uuid,p_sequence integer) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE g claim_exchange_approval_generation;a inventory_exchange_approval;c claim_exchange_request;n integer;effective timestamptz;
BEGIN
 SELECT * INTO c FROM claim_exchange_request WHERE id=p_id;SELECT * INTO g FROM claim_exchange_approval_generation WHERE exchange_id=p_id AND sequence=p_sequence;SELECT * INTO a FROM inventory_exchange_approval WHERE exchange_id=p_id AND sequence=p_sequence;SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=p_id;
 IF g.exchange_id IS NULL OR a.exchange_id IS NULL OR c.id IS NULL OR n=0 OR c.current_sequence<p_sequence OR c.current_sequence<>(SELECT max(sequence) FROM claim_exchange_approval_generation WHERE exchange_id=p_id) OR c.current_sequence<>(SELECT count(*) FROM claim_exchange_approval_generation WHERE exchange_id=p_id)
 OR(a.order_id,a.seller_order_id,a.approved_at,a.expires_at) IS DISTINCT FROM(c.order_id,c.seller_order_id,g.approved_at,g.expires_at)
 OR n<>(SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=p_id AND sequence=p_sequence)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version WHERE command.exchange_id=p_id AND command.command_key=g.command_key AND command.action='APPROVED' AND command.version=g.revision AND command.receipt=g.receipt AND history.action='APPROVED' AND history.occurred_at=g.approved_at)
 OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key=(CASE WHEN p_sequence=1 THEN 'exchange-approved:' ELSE 'exchange-approved-'||p_sequence::text||':' END)||p_id::text AND j.cause_key=p_id::text AND j.payload=p_id::text)
 OR g.revision<>g.original_revision+1 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history predecessor WHERE predecessor.exchange_id=p_id AND predecessor.version=g.original_revision)
 OR(g.receipt->>'version')::bigint IS DISTINCT FROM g.revision OR g.receipt->'accepted' IS DISTINCT FROM c.receipt
 OR n<>(SELECT count(*) FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE(saved->>'sequence')::integer=p_sequence)
 OR EXISTS(SELECT 1 FROM inventory_exchange_reservation r JOIN claim_exchange_unit u ON u.exchange_id=r.exchange_id AND u.physical_id=r.physical_id WHERE r.exchange_id=p_id AND r.sequence=p_sequence AND((r.order_id,r.seller_order_id,r.checkout_id,r.item_id,r.ordinal,r.target_sku_id,r.approved_at,r.expires_at) IS DISTINCT FROM(a.order_id,a.seller_order_id,a.checkout_id,u.item_id,u.ordinal,u.target_sku_id,a.approved_at,a.expires_at)
  OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(g.receipt->'reservations') saved WHERE saved->>'id'=r.id::text AND saved->>'exchangeId'=p_id::text AND saved->>'physicalId'=r.physical_id::text AND saved->>'targetSkuId'=r.target_sku_id::text AND(saved->>'sequence')::integer=r.sequence AND(saved->>'approvedAt')::timestamptz=r.approved_at AND(saved->>'expiresAt')::timestamptz=r.expires_at AND saved->>'status'='RESERVED')
  OR NOT EXISTS(SELECT 1 FROM inventory_exchange_event e WHERE e.reservation_id=r.id AND e.cause='RESERVE' AND e.occurred_at=r.approved_at)))
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_APPROVAL_GENERATION_REQUIRED' USING ERRCODE='23514';END IF;
 IF p_sequence>1 AND NOT EXISTS(SELECT 1 FROM inventory_exchange_release released JOIN claim_exchange_expiry_delivery expiry ON expiry.release_id=released.id WHERE released.exchange_id=p_id AND released.sequence=p_sequence-1 AND released.reason='EXPIRED' AND expiry.mode='EXPIRED' AND expiry.exchange_id=p_id AND expiry.sequence=p_sequence-1 AND expiry.workflow_revision<=g.original_revision AND NOT EXISTS(SELECT 1 FROM inventory_exchange_reservation old WHERE old.exchange_id=p_id AND old.sequence=p_sequence-1 AND old.status<>'EXPIRED')) THEN RAISE EXCEPTION 'EXPLICIT_REAPPROVAL_REQUIRES_PREVIOUS_EXPIRY_EFFECT' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=p_id AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(g.receipt->'approvedOffers') offer JOIN jsonb_array_elements(c.source_snapshot->'units') original ON(original->>'itemId')::uuid=u.item_id AND(original->>'ordinal')::integer=u.ordinal WHERE offer->>'skuId'=u.target_sku_id::text AND offer->>'storeId'=c.source_snapshot->>'storeId' AND offer->>'price'=original->>'originalUnitPrice')) THEN RAISE EXCEPTION 'GENERATION_STORED_GROSS_PRICE_AND_OWN_STORE_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM verify_exchange_reservation_schedule(p_id,p_sequence,1);
 SELECT COALESCE((SELECT extended_expires_at FROM claim_exchange_extension WHERE exchange_id=p_id AND sequence=p_sequence),g.expires_at) INTO effective;
 IF p_sequence=c.current_sequence AND(c.approved_at,c.expires_at,c.approved_offers) IS DISTINCT FROM(g.approved_at,effective,g.receipt->'approvedOffers') THEN RAISE EXCEPTION 'CURRENT_EXCHANGE_GENERATION_PROJECTION_REQUIRED' USING ERRCODE='23514';END IF;
END $$;

CREATE OR REPLACE FUNCTION immutable_exchange_approval_prices() RETURNS trigger LANGUAGE plpgsql
SET search_path TO marketplace,public AS $$
BEGIN
 IF OLD.approved_at IS NOT NULL THEN
  IF NEW.current_sequence IS DISTINCT FROM OLD.current_sequence THEN
   IF OLD.current_sequence<1 OR NEW.current_sequence<>OLD.current_sequence+1
    OR OLD.state NOT IN('EXPIRED','REVIEW_REQUIRED')
    OR NEW.state NOT IN('APPROVED','COLLECTING','READY','REVIEW_REQUIRED')
    OR NEW.version<>OLD.version+1
    OR NOT EXISTS(
     SELECT 1 FROM inventory_exchange_release released
      JOIN claim_exchange_expiry_delivery effect ON effect.release_id=released.id
     WHERE released.exchange_id=OLD.id AND released.sequence=OLD.current_sequence
      AND released.reason='EXPIRED' AND effect.mode='EXPIRED'
      AND effect.exchange_id=OLD.id AND effect.sequence=OLD.current_sequence
      AND effect.workflow_revision<=OLD.version)
    OR EXISTS(SELECT 1 FROM inventory_exchange_reservation
     WHERE exchange_id=OLD.id AND sequence=OLD.current_sequence AND status<>'EXPIRED')
    OR NOT EXISTS(
     SELECT 1 FROM inventory_exchange_approval next
     WHERE next.exchange_id=OLD.id AND next.sequence=NEW.current_sequence
      AND next.order_id=OLD.order_id AND next.seller_order_id=OLD.seller_order_id
      AND next.checkout_id=(OLD.source_snapshot->'units'->0->>'checkoutId')::uuid
      AND next.approved_at=NEW.approved_at AND next.expires_at=NEW.expires_at
      AND next.created_xid=pg_current_xact_id())
   THEN RAISE EXCEPTION 'EXACT_EXPIRED_GENERATION_REAPPROVAL_PROJECTION_REQUIRED' USING ERRCODE='23514';END IF;
   -- Read the old projection while this BEFORE trigger still sees it. This verifies its immutable
   -- generation, original price, exact expiry/release and inventory receipts without rewriting them.
   PERFORM verify_exchange_approval_generation(OLD.id,OLD.current_sequence);
   -- V89's deferred graph verifies the new immutable command/generation/price/source/reservation,
   -- schedule and outbox in both directions after the complete new operation is written.
  ELSIF (OLD.approved_at,OLD.approved_offers) IS DISTINCT FROM (NEW.approved_at,NEW.approved_offers) THEN
   RAISE EXCEPTION 'IMMUTABLE_EXCHANGE_APPROVAL_PRICE_EVIDENCE' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION exchange_extension_unit_current_reservation() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
BEGIN IF NOT EXISTS(SELECT 1 FROM inventory_exchange_extension e JOIN inventory_exchange_reservation r ON r.exchange_id=e.exchange_id WHERE e.id=NEW.extension_id AND e.created_xid=pg_current_xact_id() AND r.id=NEW.reservation_id AND r.status='RESERVED' AND e.previous_expires_at=r.expires_at) THEN RAISE EXCEPTION 'EXCHANGE_EXTENSION_CURRENT_RESERVED_SOURCE_REQUIRED' USING ERRCODE='23514';END IF;RETURN NEW;END $$;

CREATE OR REPLACE FUNCTION verify_exchange_extension(p_id uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c claim_exchange_request;e claim_exchange_extension;n integer;
BEGIN
 SELECT * INTO c FROM claim_exchange_request WHERE id=p_id;SELECT * INTO e FROM claim_exchange_extension WHERE exchange_id=p_id;
 IF e.id IS NULL THEN IF EXISTS(SELECT 1 FROM inventory_exchange_extension WHERE exchange_id=p_id) OR EXISTS(SELECT 1 FROM inventory_exchange_extension_unit WHERE exchange_id=p_id)  THEN RAISE EXCEPTION 'EXCHANGE_EXTENSION_COMMAND_REQUIRED' USING ERRCODE='23514';END IF;RETURN;END IF;
 SELECT count(*) INTO n FROM claim_exchange_unit WHERE exchange_id=p_id;
 IF c.id IS NULL OR c.version<e.revision OR(c.current_sequence=e.sequence AND c.expires_at IS DISTINCT FROM e.extended_expires_at) OR NOT EXISTS(SELECT 1 FROM claim_exchange_approval_generation g WHERE g.exchange_id=p_id AND g.sequence=e.sequence AND g.expires_at=e.previous_expires_at)
 OR n=0 OR n<>(SELECT count(*) FROM inventory_exchange_extension_unit WHERE extension_id=e.id)
 OR NOT EXISTS(SELECT 1 FROM inventory_exchange_extension x WHERE x.id=e.id AND(x.exchange_id,x.sequence,x.previous_expires_at,x.extended_expires_at,x.extended_at)=(e.exchange_id,e.sequence,e.previous_expires_at,e.extended_expires_at,e.extended_at))
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=p_id AND h.version=e.original_revision AND h.action=e.prior_state)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_history h WHERE h.exchange_id=p_id AND h.version=e.revision AND h.action='EXTENDED' AND h.actor='MEMBER:'||e.actor_id::text AND h.reason=e.reason AND h.occurred_at=e.extended_at)
 OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command WHERE command.exchange_id=p_id AND command.command_key=e.command_key AND command.fingerprint=e.fingerprint AND command.action='EXTENDED' AND command.version=e.revision AND command.receipt=e.receipt)
 OR e.receipt->>'state' IS DISTINCT FROM e.prior_state OR(e.receipt->>'version')::bigint IS DISTINCT FROM e.revision OR e.receipt->'accepted'->>'exchangeId' IS DISTINCT FROM p_id::text
 OR NOT EXISTS(SELECT 1 FROM outbox_event outbox JOIN job work ON work.id=outbox.job_id WHERE work.type='EXCHANGE_RECORDED.v1' AND work.dedupe_key='exchange-reservation-extended:'||p_id::text AND work.cause_key=p_id::text AND work.payload=p_id::text)
 OR EXISTS(SELECT 1 FROM claim_exchange_unit u WHERE u.exchange_id=p_id AND NOT EXISTS(SELECT 1 FROM inventory_exchange_extension_unit x JOIN inventory_exchange_reservation r ON r.id=x.reservation_id WHERE x.extension_id=e.id AND r.exchange_id=p_id AND r.sequence=e.sequence AND r.physical_id=u.physical_id AND r.item_id=u.item_id AND r.ordinal=u.ordinal AND r.target_sku_id=u.target_sku_id AND r.expires_at=e.previous_expires_at AND EXISTS(SELECT 1 FROM jsonb_array_elements(e.receipt->'reservations') saved WHERE saved->>'id'=r.id::text AND saved->>'status'='RESERVED' AND(saved->>'expiresAt')::timestamptz=e.extended_expires_at)))
 THEN RAISE EXCEPTION 'EXACT_ONE_REASONED_EXCHANGE_EXTENSION_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM verify_exchange_reservation_schedule(p_id,e.sequence,2);
END $$;

CREATE OR REPLACE FUNCTION verify_exchange_refund_consent(p_exchange uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE c claim_exchange_refund_consent;e claim_exchange_request;n integer;
BEGIN
 SELECT * INTO c FROM claim_exchange_refund_consent WHERE exchange_id=p_exchange;SELECT * INTO e FROM claim_exchange_request WHERE id=p_exchange;
 IF c.id IS NULL OR e.id IS NULL OR e.version<c.revision OR e.current_sequence<>c.sequence OR e.state NOT IN('REFUND_PENDING','REVIEW_REQUIRED','SETTLEMENT_PENDING','REFUNDED')
  OR(e.order_id,e.seller_order_id,e.hold_id,e.cause_id) IS DISTINCT FROM(c.order_id,c.seller_order_id,c.hold_id,c.cause_id)
  OR NOT EXISTS(SELECT 1 FROM claim_exchange_command command JOIN claim_exchange_history history ON history.exchange_id=command.exchange_id AND history.version=command.version
   WHERE command.exchange_id=e.id AND command.command_key=c.request_key AND command.fingerprint=c.fingerprint AND command.action='REFUND_PENDING' AND command.version=c.revision
    AND history.action='REFUND_PENDING' AND history.reason=c.reason AND history.occurred_at=c.accepted_at AND c.receipt->'progress'=command.receipt)
  OR NOT EXISTS(SELECT 1 FROM claim_exchange_refund_fee_quote_context context JOIN claim_fee_quote quote ON quote.id=context.quote_id
   JOIN claim_fee_assessment a ON a.quote_id=quote.id JOIN claim_fee_consent consent ON consent.assessment_id=a.id
   JOIN claim_fee_binding_history binding ON binding.assessment_id=a.id AND binding.attempt_id=c.attempt_id
   JOIN payment_refund_attempt attempt ON attempt.id=binding.attempt_id
   WHERE context.quote_id=c.quote_id AND context.exchange_id=e.id AND context.exchange_revision=c.original_revision AND context.inspection_fingerprint=c.inspection_fingerprint
    AND quote.order_id=e.order_id AND quote.seller_order_id=e.seller_order_id AND a.id=c.assessment_id AND a.intent_id=e.id AND a.intent_kind='EXCHANGE_REFUND'
    AND a.order_id=e.order_id AND a.seller_order_id=e.seller_order_id AND a.created_xid=c.created_xid AND a.state IN('BOUND','APPLIED')
    AND consent.legacy_consent=c.legacy_consent AND consent.legacy_consent=quote.legacy_consent_required
    AND attempt.claim_id=e.id AND attempt.order_id=e.order_id AND attempt.seller_order_id=e.seller_order_id AND attempt.purpose='ITEM' AND attempt.sequence=1
    AND attempt.amount=a.refund AND attempt.original_reference=quote.fingerprint AND attempt.cause_id=c.id
    AND c.receipt->>'assessmentId'=a.id::text AND c.receipt->>'refundAttemptId'=attempt.id::text)
  OR NOT EXISTS(SELECT 1 FROM outbox_event o JOIN job j ON j.id=o.job_id WHERE j.type='EXCHANGE_RECORDED.v1' AND j.dedupe_key='exchange-refund-consent:'||e.id::text AND j.cause_key=e.id::text AND j.payload=e.id::text)
  OR EXISTS(SELECT 1 FROM claim_exchange_dispatch_effect WHERE exchange_id=e.id)
  OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=e.id AND status='COMMITTED')
 THEN RAISE EXCEPTION 'EXACT_EXCHANGE_REFUND_CONSENT_GRAPH_REQUIRED' USING ERRCODE='23514';END IF;
 PERFORM verify_exchange_refund_quote(c.quote_id);
 SELECT count(*) INTO n FROM inventory_exchange_reservation WHERE exchange_id=e.id AND sequence=c.sequence;
 IF c.release_id IS NOT NULL THEN
  IF c.release_id<>c.id OR n=0 OR NOT EXISTS(SELECT 1 FROM inventory_exchange_release released WHERE released.id=c.release_id AND released.exchange_id=e.id AND released.sequence=c.sequence AND released.reason='REFUND_CONVERSION' AND released.released_at=c.accepted_at)
   OR EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=e.id AND sequence=c.sequence AND status<>'RELEASED')
  THEN RAISE EXCEPTION 'EXACT_CONVERTED_EXCHANGE_RESERVATION_RELEASE_REQUIRED' USING ERRCODE='23514';END IF;
 ELSIF NOT(c.sequence=0 AND n=0) AND NOT(c.sequence>0 AND n>0
  AND NOT EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=e.id AND sequence=c.sequence AND status<>'EXPIRED')
  AND EXISTS(SELECT 1 FROM inventory_exchange_release released JOIN claim_exchange_expiry_delivery effect ON effect.release_id=released.id WHERE released.exchange_id=e.id AND released.sequence=c.sequence AND released.reason='EXPIRED' AND effect.mode='EXPIRED' AND effect.exchange_id=e.id AND effect.sequence=c.sequence AND effect.workflow_revision<=c.original_revision))
 THEN RAISE EXCEPTION 'CONVERSION_WITHOUT_RELEASE_REQUIRES_ACTUAL_EXPIRED_OR_NO_ALLOCATION' USING ERRCODE='23514';END IF;
END $$;
