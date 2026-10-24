CREATE TABLE category_event (
    id uuid PRIMARY KEY,
    category_id uuid NOT NULL REFERENCES catalog_category(id),
    actor_id uuid NOT NULL REFERENCES identity_user(id),
    before_name varchar(80),
    before_active boolean,
    after_name varchar(80) NOT NULL,
    after_active boolean NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX category_event_target_idx ON category_event(category_id,occurred_at,id);
