CREATE TABLE content_product_question (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    author_id uuid NOT NULL REFERENCES identity_user(id),
    visibility varchar(8) NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE')),
    body varchar(2000) NOT NULL CHECK (length(trim(body)) > 0),
    request_key uuid NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE(author_id,request_key)
);
CREATE INDEX content_product_question_list ON content_product_question(product_id,id);
CREATE TABLE operations_support_assignment (
    target_id uuid PRIMARY KEY,
    kind varchar(20) NOT NULL,
    operator_id uuid REFERENCES identity_user(id),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0)
);
CREATE TABLE operations_assignment_event (
    target_id uuid NOT NULL REFERENCES operations_support_assignment(target_id),
    version bigint NOT NULL,
    actor_id uuid NOT NULL REFERENCES identity_user(id),
    previous_operator uuid REFERENCES identity_user(id),
    next_operator uuid NOT NULL REFERENCES identity_user(id),
    request_key uuid NOT NULL,
    PRIMARY KEY(target_id,version),
    UNIQUE(target_id,actor_id,request_key)
);
CREATE TABLE content_product_answer (
    id uuid PRIMARY KEY,
    question_id uuid NOT NULL UNIQUE REFERENCES content_product_question(id),
    author_id uuid NOT NULL REFERENCES identity_user(id),
    body varchar(2000) NOT NULL CHECK (length(trim(body)) > 0),
    request_key uuid NOT NULL,
    expected_version bigint NOT NULL
);
CREATE FUNCTION protect_question_visibility() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.visibility IS DISTINCT FROM OLD.visibility THEN
        RAISE EXCEPTION 'QUESTION_VISIBILITY_FIXED' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER content_product_question_fixed_visibility BEFORE UPDATE ON content_product_question
FOR EACH ROW EXECUTE FUNCTION protect_question_visibility();
