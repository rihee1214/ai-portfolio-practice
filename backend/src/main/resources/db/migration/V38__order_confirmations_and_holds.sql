CREATE TABLE order_policy_migration (
 decision_id varchar(100) PRIMARY KEY, migration_version integer NOT NULL, database_name varchar(100) NOT NULL,
 effective_at timestamptz NOT NULL, policy_version varchar(100) NOT NULL, confirmation_delay_seconds bigint NOT NULL,
 claim_window_seconds bigint NOT NULL, source varchar(40) NOT NULL, approved_date date NOT NULL, approval_reference text NOT NULL
);
INSERT INTO order_policy_migration VALUES('P09-B-H03-20261030-OPTION2',38,current_database(),clock_timestamp(),'P09-B-LEGACY-1',604800,604800,'APPROVED_LEGACY_MIGRATION','2026-10-30','P09-B-design-review.md section10; existing original delivery +7 days; overdue immediately eligible; parent session 01a189ad-17dd-79f3-ad2b-1e6d7af85a3b');
CREATE TABLE order_aftercare_policy (
 order_id uuid PRIMARY KEY REFERENCES purchase_order(id), schema_version integer NOT NULL CHECK(schema_version=1),
 status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','LEGACY_UNSET')), policy_version varchar(100),
 confirmation_delay_seconds bigint, claim_window_seconds bigint, captured_at timestamptz NOT NULL,effective_at timestamptz NOT NULL,
 source varchar(40) NOT NULL CHECK(source IN ('ORDER_CREATION','APPROVED_LEGACY_MIGRATION')), decision_id varchar(100) REFERENCES order_policy_migration(decision_id),
 CHECK((source='ORDER_CREATION' AND decision_id IS NULL) OR(source='APPROVED_LEGACY_MIGRATION' AND decision_id IS NOT NULL)),
 CHECK((status='ACTIVE' AND policy_version IS NOT NULL AND confirmation_delay_seconds IS NOT NULL AND confirmation_delay_seconds>0 AND claim_window_seconds IS NOT NULL AND claim_window_seconds>0)
 OR (status='LEGACY_UNSET' AND policy_version IS NULL AND confirmation_delay_seconds IS NULL AND claim_window_seconds IS NULL))
);
INSERT INTO order_aftercare_policy SELECT o.id,1,'ACTIVE',m.policy_version,m.confirmation_delay_seconds,m.claim_window_seconds,m.effective_at,m.effective_at,m.source,m.decision_id FROM purchase_order o CROSS JOIN order_policy_migration m;
CREATE TABLE order_confirmation_state (order_id uuid PRIMARY KEY REFERENCES purchase_order(id),revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0));
INSERT INTO order_confirmation_state(order_id) SELECT id FROM purchase_order;
CREATE TABLE order_confirmation (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES purchase_order(id), request_key varchar(100) NOT NULL,
 fingerprint varchar(64) NOT NULL, cause varchar(10) NOT NULL CHECK(cause IN ('DIRECT','AUTO')), actor varchar(100) NOT NULL,
 confirmed_at timestamptz NOT NULL, result jsonb NOT NULL, UNIQUE(order_id,request_key), UNIQUE(id,order_id)
);
CREATE TABLE order_unit_hold (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES purchase_order(id), cause_id uuid NOT NULL UNIQUE,
 kind varchar(10) NOT NULL CHECK(kind IN ('CANCEL','RETURN','EXCHANGE')), fingerprint varchar(64) NOT NULL,
 state varchar(10) NOT NULL CHECK(state IN ('ACTIVE','RELEASED','REFUNDED')), acquired_at timestamptz NOT NULL,
 outcome varchar(40), outcome_fingerprint varchar(64), UNIQUE(id,order_id),
 CHECK((state='ACTIVE' AND outcome IS NULL AND outcome_fingerprint IS NULL) OR (state<>'ACTIVE' AND outcome IS NOT NULL AND outcome_fingerprint IS NOT NULL))
);
CREATE TABLE order_unit_lifecycle (
 item_id uuid NOT NULL, ordinal integer NOT NULL, order_id uuid NOT NULL, seller_order_id uuid NOT NULL,
 state varchar(12) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','HELD','CONFIRMED','REFUNDED')), revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 active_hold_id uuid, confirmation_id uuid, refund_cause_id uuid,
 PRIMARY KEY(item_id,ordinal), FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal),
 FOREIGN KEY(item_id,seller_order_id,order_id) REFERENCES order_item(id,seller_order_id,order_id),
 FOREIGN KEY(active_hold_id,order_id) REFERENCES order_unit_hold(id,order_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(confirmation_id,order_id) REFERENCES order_confirmation(id,order_id) DEFERRABLE INITIALLY DEFERRED,
 CHECK((state='OPEN' AND active_hold_id IS NULL AND confirmation_id IS NULL AND refund_cause_id IS NULL)
 OR(state='HELD' AND active_hold_id IS NOT NULL AND confirmation_id IS NULL AND refund_cause_id IS NULL)
 OR(state='CONFIRMED' AND active_hold_id IS NULL AND confirmation_id IS NOT NULL AND refund_cause_id IS NULL)
 OR(state='REFUNDED' AND active_hold_id IS NULL AND confirmation_id IS NULL AND refund_cause_id IS NOT NULL))
);
INSERT INTO order_unit_lifecycle(item_id,ordinal,order_id,seller_order_id) SELECT u.item_id,u.ordinal,i.order_id,i.seller_order_id FROM order_unit u JOIN order_item i ON i.id=u.item_id;
CREATE TABLE order_hold_unit (hold_id uuid NOT NULL REFERENCES order_unit_hold(id),item_id uuid NOT NULL,ordinal integer NOT NULL,PRIMARY KEY(hold_id,item_id,ordinal),FOREIGN KEY(item_id,ordinal) REFERENCES order_unit_lifecycle(item_id,ordinal));
CREATE TABLE order_lifecycle_event (id uuid PRIMARY KEY,order_id uuid NOT NULL REFERENCES purchase_order(id),cause_id uuid NOT NULL,action varchar(30) NOT NULL,occurred_at timestamptz NOT NULL,UNIQUE(cause_id,action));
CREATE TABLE order_dispatch (
 event_id uuid PRIMARY KEY,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,shipment_id uuid NOT NULL,occurred_at timestamptz NOT NULL,fingerprint varchar(64) NOT NULL,
 FOREIGN KEY(seller_order_id,order_id) REFERENCES seller_order(id,order_id),UNIQUE(event_id,seller_order_id,order_id)
);
CREATE TABLE order_dispatch_unit (
 item_id uuid NOT NULL,ordinal integer NOT NULL,event_id uuid NOT NULL,order_id uuid NOT NULL,seller_order_id uuid NOT NULL,PRIMARY KEY(item_id,ordinal),
 FOREIGN KEY(item_id,ordinal) REFERENCES order_unit(item_id,ordinal),FOREIGN KEY(item_id,seller_order_id,order_id) REFERENCES order_item(id,seller_order_id,order_id),
 FOREIGN KEY(event_id,seller_order_id,order_id) REFERENCES order_dispatch(event_id,seller_order_id,order_id)
);
-- Migration alone may consume fulfillment's historical schema. Runtime order never reads it.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM shipment s WHERE s.state<>'PREPARING' AND (SELECT count(*) FROM shipment_event e WHERE e.shipment_id=s.id AND e.applied AND e.previous_state='PREPARING' AND e.next_state='SHIPPED')<>1) THEN RAISE EXCEPTION 'AMBIGUOUS_LEGACY_DISPATCH'; END IF;
END $$;
INSERT INTO order_dispatch SELECT e.id,s.order_id,s.seller_order_id,s.id,e.occurred_at,
 encode(sha256(convert_to('1|'||s.order_id||'|'||s.seller_order_id||'|'||s.id||'|'||e.id||'|'||to_char(e.occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')||'|'||(SELECT string_agg(u.order_item_id||':'||u.ordinal,',' ORDER BY u.order_item_id::text,u.ordinal) FROM shipment_unit u WHERE u.shipment_id=s.id),'UTF8')),'hex')
 FROM shipment s JOIN shipment_event e ON e.shipment_id=s.id AND e.applied AND e.previous_state='PREPARING' AND e.next_state='SHIPPED' WHERE s.state<>'PREPARING';
INSERT INTO order_dispatch_unit SELECT u.order_item_id,u.ordinal,d.event_id,d.order_id,d.seller_order_id FROM order_dispatch d JOIN shipment_unit u ON u.shipment_id=d.shipment_id;
CREATE TABLE order_confirmation_schedule (
 id uuid PRIMARY KEY,order_id uuid NOT NULL REFERENCES purchase_order(id),item_id uuid NOT NULL,ordinal integer NOT NULL,generation bigint NOT NULL CHECK(generation>=0),
 delivery_event_id uuid NOT NULL REFERENCES order_initial_delivery(event_id),policy_version varchar(100) NOT NULL,due_at timestamptz NOT NULL,scheduled_at timestamptz NOT NULL,job_type varchar(40) NOT NULL,
 FOREIGN KEY(item_id,ordinal) REFERENCES order_unit_lifecycle(item_id,ordinal),UNIQUE(item_id,ordinal,generation)
);
CREATE TABLE order_confirmation_scan(run_id varchar(100) PRIMARY KEY,upper_id uuid NOT NULL,started_at timestamptz NOT NULL);
