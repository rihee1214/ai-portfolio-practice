-- Approval headers are a current projection. Every generation's original receipt, price and seven-day
-- allocation remain immutable; only a genuine expired generation may advance to a fresh allocation.
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
      AND released.released_at<=NEW.approved_at)
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
