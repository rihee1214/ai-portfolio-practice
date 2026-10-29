ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_number_identity UNIQUE(id,order_number);
ALTER TABLE order_item ADD CONSTRAINT order_item_seller_identity UNIQUE(id,seller_order_id);
CREATE TABLE shipment (
 id uuid PRIMARY KEY,
 order_id uuid NOT NULL,
 seller_order_id uuid NOT NULL,
 order_number varchar(64) NOT NULL,
 FOREIGN KEY(order_id,order_number) REFERENCES purchase_order(id,order_number),
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),
 purpose_key varchar(100) NOT NULL,
 state varchar(24) NOT NULL CHECK(state IN ('PREPARING','SHIPPED','IN_TRANSIT','DELIVERED')),
 version bigint NOT NULL CHECK(version>=0),
 created_at timestamptz NOT NULL,
 UNIQUE(id,seller_order_id),
 UNIQUE(seller_order_id,purpose_key)
);
CREATE INDEX shipment_order ON shipment(order_number,created_at,id);
CREATE TABLE shipment_item (
 shipment_id uuid NOT NULL,
 seller_order_id uuid NOT NULL,
 order_item_id uuid NOT NULL,
 FOREIGN KEY(shipment_id,seller_order_id) REFERENCES shipment(id,seller_order_id),
 FOREIGN KEY(order_item_id,seller_order_id) REFERENCES order_item(id,seller_order_id),
 quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),
 PRIMARY KEY(shipment_id,order_item_id)
);
CREATE TABLE shipment_event (
 id uuid PRIMARY KEY,
 event_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 shipment_id uuid NOT NULL REFERENCES shipment(id),
 event_key varchar(200) NOT NULL,
 source varchar(30) NOT NULL CHECK(source IN ('SYSTEM','SELLER','MOCK_PROVIDER')),
 actor varchar(100) NOT NULL,
 content text NOT NULL,
 occurred_at timestamptz NOT NULL,
 processed_at timestamptz NOT NULL,
 previous_state varchar(24) NOT NULL,
 next_state varchar(24) NOT NULL,
 version bigint NOT NULL CHECK(version>=0),
 applied boolean NOT NULL,
 UNIQUE(shipment_id,event_key)
);
CREATE TABLE shipment_audit (
 event_id uuid PRIMARY KEY REFERENCES shipment_event(id),
 shipment_id uuid NOT NULL REFERENCES shipment(id),
 source varchar(30) NOT NULL,
 actor varchar(100) NOT NULL,
 processed_at timestamptz NOT NULL
);
