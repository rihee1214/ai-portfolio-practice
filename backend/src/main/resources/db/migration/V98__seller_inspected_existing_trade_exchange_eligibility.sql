-- Only new approvals and new physical dispatches need current target eligibility.
-- Historical generation/dispatch receipts keep their original facts after later store/catalog changes.
CREATE FUNCTION verify_exchange_current_trade_targets(p_exchange uuid,p_phase text) RETURNS void LANGUAGE plpgsql
SET search_path TO marketplace,public AS $$
DECLARE claim claim_exchange_request;trade seller_store;original_store uuid;total integer;
BEGIN
 IF p_phase NOT IN('APPROVAL','DISPATCH') THEN RAISE EXCEPTION 'EXACT_EXCHANGE_TARGET_PHASE_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO STRICT claim FROM claim_exchange_request WHERE id=p_exchange;original_store:=(claim.source_snapshot->>'storeId')::uuid;
 SELECT * INTO trade FROM seller_store WHERE id=original_store;
 IF trade.id IS NULL OR NOT EXISTS(SELECT 1 FROM identity_user u JOIN role_assignment r ON r.user_id=u.id AND r.role='SELLER' WHERE u.id=trade.owner_id AND u.state='ACTIVE')
 THEN RAISE EXCEPTION 'CURRENT_EXISTING_TRADE_SELLER_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO total FROM claim_exchange_unit WHERE exchange_id=p_exchange;
 IF total=0 OR total<>(SELECT count(*) FROM claim_exchange_unit unit JOIN catalog_sku sku ON sku.id=unit.target_sku_id JOIN catalog_product product ON product.id=sku.product_id JOIN catalog_category category ON category.id=product.category_id WHERE unit.exchange_id=p_exchange AND product.store_id=original_store AND product.state='ACTIVE' AND NOT product.is_hidden_by_admin AND category.active AND sku.active
  AND(p_phase<>'APPROVAL' OR EXISTS(SELECT 1 FROM jsonb_array_elements(claim.source_snapshot->'units') source WHERE source->>'itemId'=unit.item_id::text AND(source->>'ordinal')::integer=unit.ordinal AND(source->>'originalUnitPrice')::bigint=sku.price)))
 THEN RAISE EXCEPTION 'CURRENT_PUBLIC_ACTIVE_SAME_STORE_EXCHANGE_TARGET_REQUIRED' USING ERRCODE='23514';END IF;
 IF trade.state='OPEN' AND trade.visible THEN RETURN;END IF;
 IF trade.state NOT IN('SUSPENDED','CLOSED') OR total<>(SELECT count(*) FROM claim_exchange_unit unit JOIN claim_exchange_inspection_unit inspected ON inspected.exchange_id=unit.exchange_id AND inspected.item_id=unit.item_id AND inspected.ordinal=unit.ordinal AND inspected.physical_id=unit.physical_id JOIN claim_exchange_collection received ON received.id=inspected.collection_id AND received.exchange_id=unit.exchange_id AND received.item_id=unit.item_id AND received.ordinal=unit.ordinal AND received.physical_id=unit.physical_id AND received.status='RECEIVED' WHERE unit.exchange_id=p_exchange AND inspected.responsibility='SELLER' AND inspected.condition IN('SALEABLE','DEFECTIVE'))
 THEN RAISE EXCEPTION 'ACTUAL_INSPECTED_SELLER_RESPONSIBILITY_FOR_STORE_EXCEPTION_REQUIRED' USING ERRCODE='23514';END IF;
END $$;
CREATE FUNCTION exchange_current_trade_target_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM marketplace.verify_exchange_current_trade_targets(NEW.exchange_id,CASE WHEN TG_TABLE_NAME='claim_exchange_approval_generation' THEN 'APPROVAL' ELSE 'DISPATCH' END);RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exchange_new_approval_target_eligibility AFTER INSERT ON claim_exchange_approval_generation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_current_trade_target_guard();
CREATE CONSTRAINT TRIGGER exchange_new_dispatch_target_eligibility AFTER INSERT ON claim_exchange_dispatch_effect DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exchange_current_trade_target_guard();
