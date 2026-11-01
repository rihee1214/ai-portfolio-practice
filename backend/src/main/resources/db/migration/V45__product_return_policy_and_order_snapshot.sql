-- Existing product defaults apply to future purchases, never to historical orders.
ALTER TABLE catalog_product ADD COLUMN return_fee bigint NOT NULL DEFAULT 3000 CHECK(return_fee>=0);
ALTER TABLE catalog_product ADD COLUMN return_policy_revision bigint NOT NULL DEFAULT 0 CHECK(return_policy_revision>=0);
ALTER TABLE catalog_product ADD COLUMN return_policy_source varchar(30) NOT NULL DEFAULT 'MIGRATED_DEFAULT_3000' CHECK(return_policy_source IN ('MIGRATED_DEFAULT_3000','PRODUCT_DEFAULT_3000','SELLER_PRODUCT'));
ALTER TABLE catalog_product ALTER COLUMN return_policy_source SET DEFAULT 'PRODUCT_DEFAULT_3000';
CREATE TABLE order_item_return_policy (
 item_id uuid PRIMARY KEY REFERENCES order_item(id),status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','UNRESOLVED')),
 source varchar(40) NOT NULL,policy_snapshot jsonb,
 CHECK((status='UNRESOLVED' AND source='LEGACY_UNSPECIFIED' AND policy_snapshot IS NULL) OR(status='ACTIVE' AND source IN ('MIGRATED_DEFAULT_3000','PRODUCT_DEFAULT_3000','SELLER_PRODUCT') AND policy_snapshot IS NOT NULL))
);
INSERT INTO order_item_return_policy(item_id,status,source) SELECT id,'UNRESOLVED','LEGACY_UNSPECIFIED' FROM order_item;
ALTER TABLE order_item ADD CONSTRAINT order_item_return_policy_required FOREIGN KEY(id) REFERENCES order_item_return_policy(item_id) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION check_order_item_return_policy() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE original jsonb;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'IMMUTABLE_ORDER_RETURN_POLICY' USING ERRCODE='23514'; END IF;
 SELECT line_snapshot->'returnPolicy' INTO original FROM order_item WHERE id=NEW.item_id;
 IF NEW.status<>'ACTIVE' OR original IS NULL OR original='null'::jsonb OR original IS DISTINCT FROM NEW.policy_snapshot
 OR NEW.source IS DISTINCT FROM original->>'source' OR original->>'version'<>'P09-RETURN-PRODUCT-MAX-1'
 OR original->>'basis'<>'ORIGINAL_SHIPMENT' OR original->>'aggregation'<>'RETURNED_TARGET_CUMULATIVE_MAX' OR original->>'repeat'<>'DELTA_ONLY'
 OR (original->>'amount') !~ '^(0|[1-9][0-9]*)$' OR (original->>'amount')::numeric>9223372036854775807
 THEN RAISE EXCEPTION 'ORDER_RETURN_POLICY_SNAPSHOT_MISMATCH' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER order_item_return_policy_valid BEFORE INSERT OR UPDATE OR DELETE ON order_item_return_policy FOR EACH ROW EXECUTE FUNCTION check_order_item_return_policy();
