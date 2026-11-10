-- Preserve V76's original purchase/first-delivery anchors; new typed rows describe current physical objects.
ALTER TABLE order_exchange_application ADD COLUMN source_schema_version integer NOT NULL DEFAULT 1 CHECK(source_schema_version IN (1,2));
CREATE TABLE order_physical_source (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,
 round integer NOT NULL CHECK(round>=0),predecessor_id uuid UNIQUE REFERENCES order_physical_source(id),original_physical_id uuid NOT NULL REFERENCES order_physical_unit(id),
 committed_source_id uuid NOT NULL REFERENCES inventory_committed_unit(id),created_at timestamptz NOT NULL,
 UNIQUE(item_id,ordinal,round),UNIQUE(id,item_id,ordinal),UNIQUE(id,committed_source_id),
 FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal),FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 CHECK((round=0 AND predecessor_id IS NULL AND id=original_physical_id) OR(round>0 AND predecessor_id IS NOT NULL))
);
CREATE TABLE order_physical_current (
 item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 PRIMARY KEY(item_id,ordinal),FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_source(id,item_id,ordinal)
);
CREATE TABLE order_exchange_physical_unit (
 exchange_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,source_schema_version integer NOT NULL CHECK(source_schema_version IN (1,2)),
 source_snapshot jsonb NOT NULL,PRIMARY KEY(exchange_id,item_id,ordinal),UNIQUE(exchange_id,physical_id),
 FOREIGN KEY(exchange_id,item_id,ordinal) REFERENCES order_exchange_unit(exchange_id,item_id,ordinal),
 FOREIGN KEY(physical_id,item_id,ordinal) REFERENCES order_physical_source(id,item_id,ordinal)
);
CREATE TABLE order_delivery_bucket (
 id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,kind varchar(10) NOT NULL CHECK(kind IN ('BASIC','EXCHANGE')),
 UNIQUE(id,kind),UNIQUE(id,order_id,seller_order_id),FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id)
);
CREATE TABLE order_basic_delivery_bucket (
 bucket_id uuid PRIMARY KEY,shipment_id uuid NOT NULL UNIQUE REFERENCES shipment(id),kind varchar(10) NOT NULL DEFAULT 'BASIC' CHECK(kind='BASIC'),
 CHECK(bucket_id=shipment_id),FOREIGN KEY(bucket_id,kind) REFERENCES order_delivery_bucket(id,kind)
);
CREATE TABLE order_physical_delivery (
 physical_id uuid PRIMARY KEY REFERENCES order_physical_source(id),bucket_id uuid NOT NULL REFERENCES order_delivery_bucket(id),event_id uuid NOT NULL,
 delivered_at timestamptz NOT NULL,UNIQUE(physical_id,bucket_id,event_id)
);
-- A concrete EXCHANGE bucket/allocation relation is added with the separate fulfillment store before dispatch is exposed.
CREATE TABLE inventory_exchange_reservation_physical_source (
 reservation_id uuid PRIMARY KEY REFERENCES inventory_exchange_reservation(id),physical_id uuid NOT NULL,source_id uuid NOT NULL,
 FOREIGN KEY(physical_id,source_id) REFERENCES order_physical_source(id,committed_source_id)
);
ALTER TABLE claim_exchange_request DROP CONSTRAINT claim_exchange_request_state_check;
ALTER TABLE claim_exchange_request DROP CONSTRAINT claim_exchange_request_check;
ALTER TABLE claim_exchange_request ADD CONSTRAINT claim_exchange_request_state_check CHECK(state IN ('REQUESTED','RESPONSIBILITY_PENDING','APPROVED','COLLECTING','READY','REVIEW_REQUIRED','DISPATCHED','COMPLETED','REJECTED','WITHDRAWN','EXPIRED','REFUND_PENDING','REFUNDED'));
ALTER TABLE claim_exchange_request ADD CONSTRAINT exchange_approval_snapshot_consistency CHECK((approved_at IS NULL AND expires_at IS NULL AND approved_offers='[]') OR(approved_at IS NOT NULL AND expires_at>approved_at AND jsonb_array_length(approved_offers)>0));
ALTER TABLE claim_exchange_command DROP CONSTRAINT claim_exchange_command_exchange_id_version_key;
ALTER TABLE claim_exchange_command ADD CONSTRAINT exchange_operation_revision_unique UNIQUE(exchange_id,version);
CREATE TABLE claim_exchange_collection (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('RECEIVED','LOST_REPORTED')),version bigint NOT NULL,actor varchar(100) NOT NULL,reason varchar(500) NOT NULL,occurred_at timestamptz NOT NULL,
 UNIQUE(exchange_id,item_id,ordinal,version),UNIQUE(id,exchange_id,item_id,ordinal,status),
 FOREIGN KEY(exchange_id,item_id,ordinal) REFERENCES claim_exchange_unit(exchange_id,item_id,ordinal),
 FOREIGN KEY(exchange_id,physical_id) REFERENCES order_exchange_physical_unit(exchange_id,physical_id),
 FOREIGN KEY(exchange_id,version) REFERENCES claim_exchange_command(exchange_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE claim_exchange_inspection (
 id uuid PRIMARY KEY,exchange_id uuid NOT NULL REFERENCES claim_exchange_request(id),version bigint NOT NULL,actor varchar(100) NOT NULL,reason varchar(500) NOT NULL,occurred_at timestamptz NOT NULL,
 UNIQUE(exchange_id,version),FOREIGN KEY(exchange_id,version) REFERENCES claim_exchange_command(exchange_id,version) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE claim_exchange_inspection_unit (
 inspection_id uuid NOT NULL REFERENCES claim_exchange_inspection(id),exchange_id uuid NOT NULL,item_id uuid NOT NULL,ordinal integer NOT NULL,physical_id uuid NOT NULL,
 collection_id uuid NOT NULL,collection_status varchar(20) NOT NULL DEFAULT 'RECEIVED' CHECK(collection_status='RECEIVED'),
 condition varchar(16) NOT NULL CHECK(condition IN ('SALEABLE','DEFECTIVE')),responsibility varchar(10) NOT NULL CHECK(responsibility IN ('BUYER','SELLER')),
 restitution_id uuid,CHECK((condition='SALEABLE' AND restitution_id=inspection_id AND restitution_id IS NOT NULL) OR(condition='DEFECTIVE' AND restitution_id IS NULL)),
 PRIMARY KEY(inspection_id,item_id,ordinal),UNIQUE(exchange_id,item_id,ordinal),
 FOREIGN KEY(collection_id,exchange_id,item_id,ordinal,collection_status) REFERENCES claim_exchange_collection(id,exchange_id,item_id,ordinal,status),
 FOREIGN KEY(exchange_id,physical_id) REFERENCES order_exchange_physical_unit(exchange_id,physical_id)
);
CREATE TRIGGER physical_source_immutable BEFORE UPDATE OR DELETE ON order_physical_source FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER accepted_physical_source_immutable BEFORE UPDATE OR DELETE ON order_exchange_physical_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER physical_delivery_immutable BEFORE UPDATE OR DELETE ON order_physical_delivery FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER delivery_bucket_immutable BEFORE UPDATE OR DELETE ON order_delivery_bucket FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER basic_bucket_immutable BEFORE UPDATE OR DELETE ON order_basic_delivery_bucket FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER exchange_stock_source_immutable BEFORE UPDATE OR DELETE ON inventory_exchange_reservation_physical_source FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER exchange_collection_immutable BEFORE UPDATE OR DELETE ON claim_exchange_collection FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER exchange_inspection_immutable BEFORE UPDATE OR DELETE ON claim_exchange_inspection FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
CREATE TRIGGER exchange_inspection_unit_immutable BEFORE UPDATE OR DELETE ON claim_exchange_inspection_unit FOR EACH ROW EXECUTE FUNCTION exchange_evidence_immutable();
