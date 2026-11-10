-- Preserve the applied V86 graph and finish its successful deferred branch.
CREATE OR REPLACE FUNCTION actual_fee_assessment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path TO marketplace,public AS $$
DECLARE a claim_fee_assessment;q claim_fee_quote;v_id uuid;u record;
BEGIN
 IF TG_TABLE_NAME='claim_fee_assessment' THEN v_id=NEW.id;ELSE v_id=NEW.assessment_id;END IF;
 SELECT * INTO a FROM claim_fee_assessment WHERE id=v_id;SELECT * INTO q FROM claim_fee_quote WHERE id=a.quote_id;
 IF a.id IS NULL OR q.id IS NULL OR a.source_schema_version<>q.source_schema_version  THEN RAISE EXCEPTION 'EXACT_ACTUAL_FEE_ASSESSMENT_SCHEMA_REQUIRED' USING ERRCODE='23514';END IF;
 IF a.source_schema_version=1 THEN IF EXISTS(SELECT 1 FROM claim_exchange_fee_assessment_bucket WHERE assessment_id=v_id) OR EXISTS(SELECT 1 FROM claim_exchange_fee_assessment_unit WHERE assessment_id=v_id) THEN RAISE EXCEPTION 'LEGACY_FEE_ASSESSMENT_MEANING_IMMUTABLE' USING ERRCODE='23514';END IF;RETURN NULL;END IF;
 PERFORM verify_actual_fee_quote(q.id);PERFORM check_fee_assessment(a.id);
 FOR u IN SELECT units.*,bucket.source_kind,bucket.delivery_bucket_id FROM claim_all_fee_assessment_unit units JOIN claim_all_fee_assessment_bucket bucket ON bucket.assessment_id=units.assessment_id AND bucket.bucket_id=units.bucket_id WHERE units.assessment_id=v_id LOOP
  IF NOT EXISTS(SELECT 1 FROM claim_fee_quote_physical_unit source JOIN claim_delivery_fee_bucket root ON root.delivery_bucket_id=source.delivery_bucket_id AND root.source_kind=source.source_kind AND root.order_id=a.order_id AND root.seller_order_id=a.seller_order_id WHERE source.quote_id=q.id AND source.item_id=u.item_id AND source.ordinal=u.ordinal AND source.source_kind=u.source_kind AND source.delivery_bucket_id=u.delivery_bucket_id AND root.id=u.bucket_id) THEN RAISE EXCEPTION 'EXACT_ASSESSED_ACTUAL_DELIVERY_REQUIRED' USING ERRCODE='23514';END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM claim_all_fee_assessment_bucket b WHERE b.assessment_id=v_id AND(b.order_id,b.seller_order_id) IS DISTINCT FROM(a.order_id,a.seller_order_id)) THEN RAISE EXCEPTION 'EXACT_ACTUAL_FEE_ORDER_REQUIRED' USING ERRCODE='23514';END IF;
 RETURN NULL;
END $$;
