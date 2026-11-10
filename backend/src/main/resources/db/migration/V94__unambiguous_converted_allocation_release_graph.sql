-- Preserve V91-93 applied evidence; distinguish SQL alias from the historical loop record.
CREATE OR REPLACE FUNCTION verify_exchange_release(p_release uuid) RETURNS void LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE released inventory_exchange_release;r record;expected numeric;n integer;
BEGIN
 SELECT * INTO released FROM inventory_exchange_release WHERE id=p_release;IF released.id IS NULL THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_RECEIPT_REQUIRED' USING ERRCODE='23514';END IF;
 IF released.reason='REFUND_CONVERSION' THEN
  PERFORM verify_exchange_refund_consent(released.exchange_id);
  IF NOT EXISTS(SELECT 1 FROM claim_exchange_refund_consent c WHERE c.id=released.id AND c.exchange_id=released.exchange_id AND c.sequence=released.sequence AND c.release_id=released.id AND c.accepted_at=released.released_at)
   OR NOT EXISTS(SELECT 1 FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence)
   OR (SELECT count(*) FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence)<>(SELECT count(*) FROM inventory_exchange_release_unit WHERE release_id=p_release)
   OR EXISTS(SELECT 1 FROM inventory_exchange_reservation allocated WHERE allocated.exchange_id=released.exchange_id AND allocated.sequence=released.sequence AND(allocated.status<>'RELEASED' OR NOT EXISTS(
    SELECT 1 FROM inventory_exchange_release_unit u JOIN inventory_exchange_event e ON e.id=u.event_id WHERE u.release_id=p_release AND u.reservation_id=allocated.id AND u.exchange_id=allocated.exchange_id AND u.sequence=allocated.sequence AND u.cause='RELEASE' AND e.reservation_id=allocated.id AND e.cause='RELEASE' AND e.occurred_at=released.released_at AND e.after_reserved=e.before_reserved-allocated.quantity AND e.version=e.before_version+1)))
  THEN RAISE EXCEPTION 'EXACT_CONVERTED_ALLOCATION_RELEASE_REQUIRED' USING ERRCODE='23514';END IF;
  FOR r IN SELECT * FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence LOOP
   SELECT COALESCE((SELECT sum(quantity) FROM inventory_reservation WHERE sku_id=r.target_sku_id AND status='RESERVED'),0)+COALESCE((SELECT sum(quantity) FROM inventory_exchange_reservation WHERE target_sku_id=r.target_sku_id AND status='RESERVED'),0) INTO expected;
   IF NOT EXISTS(SELECT 1 FROM inventory_balance WHERE sku_id=r.target_sku_id AND reserved=expected) THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_BALANCE_AND_ACTIVE_RESERVATIONS_REQUIRED' USING ERRCODE='23514';END IF;
  END LOOP;RETURN;
 ELSIF released.reason<>'EXPIRED' THEN RAISE EXCEPTION 'EXCHANGE_FINANCIAL_CONVERSION_NOT_BOUND' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO n FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence;
 IF n=0 OR n<>(SELECT count(*) FROM inventory_exchange_release_unit WHERE release_id=p_release) OR NOT EXISTS(SELECT 1 FROM claim_exchange_expiry_delivery delivery WHERE delivery.release_id=p_release AND delivery.exchange_id=released.exchange_id AND delivery.sequence=released.sequence AND delivery.mode='EXPIRED' AND delivery.recorded_at=released.released_at) THEN RAISE EXCEPTION 'EXACT_EXCHANGE_RELEASE_OPERATION_REQUIRED' USING ERRCODE='23514';END IF;
 FOR r IN SELECT * FROM inventory_exchange_reservation WHERE exchange_id=released.exchange_id AND sequence=released.sequence LOOP
  IF r.status<>'EXPIRED' OR released.released_at<COALESCE((SELECT extended_expires_at FROM inventory_exchange_extension_unit WHERE reservation_id=r.id),r.expires_at) OR NOT EXISTS(SELECT 1 FROM inventory_exchange_release_unit unit JOIN inventory_exchange_event event ON event.id=unit.event_id WHERE unit.release_id=p_release AND unit.exchange_id=released.exchange_id AND unit.sequence=released.sequence AND unit.reservation_id=r.id AND unit.cause='EXPIRE' AND event.reservation_id=r.id AND event.cause=unit.cause AND event.occurred_at=released.released_at AND event.after_reserved=event.before_reserved-r.quantity AND event.version=event.before_version+1) THEN RAISE EXCEPTION 'EXACT_EXPIRED_ALLOCATION_RELEASE_REQUIRED' USING ERRCODE='23514';END IF;
  SELECT COALESCE((SELECT sum(quantity) FROM inventory_reservation WHERE sku_id=r.target_sku_id AND status='RESERVED'),0)+COALESCE((SELECT sum(quantity) FROM inventory_exchange_reservation WHERE target_sku_id=r.target_sku_id AND status='RESERVED'),0) INTO expected;
  IF NOT EXISTS(SELECT 1 FROM inventory_balance WHERE sku_id=r.target_sku_id AND reserved=expected) THEN RAISE EXCEPTION 'EXCHANGE_RELEASE_BALANCE_AND_ACTIVE_RESERVATIONS_REQUIRED' USING ERRCODE='23514';END IF;
 END LOOP;
END $$;
