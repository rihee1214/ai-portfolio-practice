ALTER TABLE order_initial_delivery ADD CONSTRAINT fee_original_shipment_identity UNIQUE(shipment_id,seller_order_id,order_id);
CREATE TABLE claim_fee_bucket (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,original_shipment_id uuid NOT NULL,
 family varchar(100) NOT NULL CHECK(family='P09-RETURN-PRODUCT-MAX-1'),
 assessed_total bigint NOT NULL DEFAULT 0 CHECK(assessed_total>=0),reserved_increment bigint NOT NULL DEFAULT 0 CHECK(reserved_increment>=0),
 deducted_applied bigint NOT NULL DEFAULT 0 CHECK(deducted_applied>=0),additional_collected bigint NOT NULL DEFAULT 0 CHECK(additional_collected>=0),additional_outstanding bigint NOT NULL DEFAULT 0 CHECK(additional_outstanding>=0),
 active_assessment_id uuid,revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 FOREIGN KEY(original_shipment_id,seller_order_id,order_id) REFERENCES order_initial_delivery(shipment_id,seller_order_id,order_id),
 UNIQUE(order_id,seller_order_id,original_shipment_id,family),UNIQUE(id,order_id,seller_order_id,original_shipment_id),
 CHECK(assessed_total<=9223372036854775807-reserved_increment),CHECK(deducted_applied::numeric+additional_collected+additional_outstanding=assessed_total),CHECK(active_assessment_id IS NOT NULL OR reserved_increment=0)
);
CREATE TABLE claim_fee_quote (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,fingerprint varchar(64) NOT NULL,order_revision bigint NOT NULL CHECK(order_revision>=0),
 member_id uuid,guest_buyer_id uuid,quoted_at timestamptz NOT NULL,legacy_consent_required boolean NOT NULL,body jsonb NOT NULL,
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),CHECK((member_id IS NULL)<>(guest_buyer_id IS NULL)),UNIQUE(id,order_id,seller_order_id),
 CHECK(body ?& ARRAY['id','fingerprint','orderNumber','sellerOrderId','orderRevision','units','buckets','net','increment','refund','additionalDue','legacyConsentRequired']),
 CHECK(jsonb_typeof(body->'id')='string' AND (body->>'id')::uuid=id AND jsonb_typeof(body->'fingerprint')='string' AND body->>'fingerprint'=fingerprint),
 CHECK(jsonb_typeof(body->'net')='string' AND jsonb_typeof(body->'increment')='string' AND jsonb_typeof(body->'refund')='string' AND jsonb_typeof(body->'additionalDue')='string')
);
CREATE FUNCTION immutable_fee_quote() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_RETURN_FEE_QUOTE' USING ERRCODE='23514';END $$;
CREATE TRIGGER fee_quote_immutable BEFORE UPDATE OR DELETE ON claim_fee_quote FOR EACH ROW EXECUTE FUNCTION immutable_fee_quote();
