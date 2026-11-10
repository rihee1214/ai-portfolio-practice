ALTER TABLE claim_fee_quote ADD COLUMN purpose varchar(20) NOT NULL DEFAULT 'OPEN' CHECK(purpose IN ('OPEN','INSPECTED_RETURN'));
ALTER TABLE claim_fee_quote ADD CONSTRAINT fee_quote_purpose_identity UNIQUE(id,purpose);
CREATE TABLE claim_return_fee_quote_context (
 quote_id uuid PRIMARY KEY,return_id uuid NOT NULL REFERENCES claim_return_request(id),
 purpose varchar(20) NOT NULL DEFAULT 'INSPECTED_RETURN' CHECK(purpose='INSPECTED_RETURN'),
 return_revision bigint NOT NULL CHECK(return_revision>=0),inspection_fingerprint varchar(64) NOT NULL CHECK(inspection_fingerprint ~ '^[0-9a-f]{64}$'),
 inspection_snapshot jsonb NOT NULL CHECK(jsonb_typeof(inspection_snapshot)='array'),
 FOREIGN KEY(quote_id,purpose) REFERENCES claim_fee_quote(id,purpose)
);
CREATE TRIGGER return_fee_quote_context_immutable BEFORE UPDATE OR DELETE ON claim_return_fee_quote_context FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
CREATE FUNCTION inspected_return_quote_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_id uuid;v_quote marketplace.claim_fee_quote%ROWTYPE;v_context marketplace.claim_return_fee_quote_context%ROWTYPE;
 v_request marketplace.claim_return_request%ROWTYPE;v_count bigint;
BEGIN
 IF TG_TABLE_NAME='claim_fee_quote' THEN v_id:=NEW.id;ELSE v_id:=NEW.quote_id;END IF;
 SELECT * INTO STRICT v_quote FROM marketplace.claim_fee_quote WHERE id=v_id;
 SELECT * INTO v_context FROM marketplace.claim_return_fee_quote_context WHERE quote_id=v_id;
 IF v_quote.purpose='OPEN' THEN
  IF FOUND THEN RAISE EXCEPTION 'OPEN_QUOTE_CANNOT_CLAIM_INSPECTION' USING ERRCODE='23514';END IF;
  RETURN NULL;
 END IF;
 IF NOT FOUND THEN RAISE EXCEPTION 'INSPECTED_RETURN_QUOTE_CONTEXT_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT * INTO v_request FROM marketplace.claim_return_request WHERE id=v_context.return_id;
 IF NOT FOUND OR v_request.order_id<>v_quote.order_id OR v_request.seller_order_id<>v_quote.seller_order_id
  OR v_request.version<v_context.return_revision OR NOT EXISTS(SELECT 1 FROM marketplace.claim_return_history h WHERE h.return_id=v_request.id AND h.version=v_context.return_revision AND h.action='AWAITING_CONSENT')
  OR v_quote.body->>'sellerOrderId' IS DISTINCT FROM v_quote.seller_order_id::text
  OR (v_quote.body->>'orderRevision')::bigint IS DISTINCT FROM v_quote.order_revision THEN
  RAISE EXCEPTION 'INSPECTED_RETURN_QUOTE_IDENTITY_REQUIRED' USING ERRCODE='23514';END IF;
 SELECT count(*) INTO v_count FROM marketplace.claim_return_unit WHERE return_id=v_request.id;
 IF v_count=0 OR jsonb_array_length(v_context.inspection_snapshot)<>v_count OR jsonb_typeof(v_quote.body->'units') IS DISTINCT FROM 'array'
  OR jsonb_array_length(v_quote.body->'units')<>v_count
  OR (SELECT count(DISTINCT(j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_context.inspection_snapshot)j(value))<>v_count
  OR (SELECT count(DISTINCT(j.value->>'itemId',j.value->>'ordinal')) FROM jsonb_array_elements(v_quote.body->'units')j(value))<>v_count THEN
  RAISE EXCEPTION 'EXACT_INSPECTED_RETURN_QUOTE_UNITS_REQUIRED' USING ERRCODE='23514';END IF;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(v_context.inspection_snapshot)j(value)
  LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=v_request.id AND i.item_id::text=j.value->>'itemId' AND i.ordinal::text=j.value->>'ordinal'
  LEFT JOIN marketplace.claim_return_inspection h ON h.id=i.inspection_id
  WHERE i.inspection_id IS NULL OR h.version>v_context.return_revision
   OR j.value->>'collection' IS DISTINCT FROM 'RECEIVED' OR j.value->>'collectionId' IS DISTINCT FROM i.collection_id::text
   OR j.value->>'inspection' IS DISTINCT FROM i.condition OR j.value->>'inspectionId' IS DISTINCT FROM i.inspection_id::text
   OR j.value->>'responsibility' IS DISTINCT FROM i.responsibility OR j.value->>'restitutionId' IS DISTINCT FROM i.restitution_id::text)
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(v_quote.body->'units')j(value)
   LEFT JOIN marketplace.claim_return_inspection_unit i ON i.return_id=v_request.id AND i.item_id::text=j.value->>'itemId' AND i.ordinal::text=j.value->>'ordinal'
   LEFT JOIN marketplace.order_unit n ON n.item_id=i.item_id AND n.ordinal=i.ordinal
   LEFT JOIN marketplace.order_item_return_policy p ON p.item_id=i.item_id
   LEFT JOIN marketplace.order_initial_delivery_unit du ON du.item_id=i.item_id AND du.ordinal=i.ordinal
   LEFT JOIN marketplace.order_initial_delivery d ON d.event_id=du.event_id
   WHERE i.inspection_id IS NULL OR n.item_id IS NULL OR p.item_id IS NULL OR d.event_id IS NULL OR j.value->>'responsibility' IS DISTINCT FROM i.responsibility
    OR j.value->>'originalShipmentId' IS DISTINCT FROM d.shipment_id::text
    OR j.value->>'net' IS DISTINCT FROM n.net::text OR j.value->>'policyFee' IS DISTINCT FROM COALESCE(p.policy_snapshot->>'amount','3000')
    OR j.value->>'policySource' IS DISTINCT FROM COALESCE(p.policy_snapshot->>'source','LEGACY_UNSPECIFIED')
    OR j.value->>'policyRevision' IS DISTINCT FROM COALESCE(p.policy_snapshot->>'revision','0')
    OR j.value->>'policyVersion' IS DISTINCT FROM 'P09-RETURN-PRODUCT-MAX-1') THEN
  RAISE EXCEPTION 'ACTUAL_RETURN_INSPECTION_QUOTE_SNAPSHOT_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN
 RAISE EXCEPTION 'INSPECTED_RETURN_QUOTE_FORMAT' USING ERRCODE='23514';
END $$;
CREATE CONSTRAINT TRIGGER inspected_return_quote AFTER INSERT ON claim_fee_quote DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inspected_return_quote_guard();
CREATE CONSTRAINT TRIGGER inspected_return_quote_context AFTER INSERT ON claim_return_fee_quote_context DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION inspected_return_quote_guard();
