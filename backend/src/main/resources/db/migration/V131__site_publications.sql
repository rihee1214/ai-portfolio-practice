SET search_path TO marketplace,public;
CREATE TABLE media_site_image (
    asset_id uuid PRIMARY KEY REFERENCES media_asset(id)
);
CREATE TABLE site_post_revision (
    post_id uuid NOT NULL,
    version bigint NOT NULL CHECK(version>0),
    kind text NOT NULL CHECK(kind IN ('NOTICE','FAQ','BANNER')),
    state text NOT NULL CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED')),
    title text NOT NULL CHECK(char_length(title) BETWEEN 1 AND 120),
    body text NOT NULL CHECK(char_length(body)<=10000),
    alt text NOT NULL CHECK(char_length(alt)<=300),
    destination text,
    priority integer NOT NULL,
    starts_at timestamptz,
    ends_at timestamptz,
    asset_id uuid REFERENCES media_site_image(asset_id),
    actor_id uuid NOT NULL,
    request_key uuid NOT NULL,
    fingerprint char(64) NOT NULL,
    reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 500),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(post_id,version),
    UNIQUE(actor_id,request_key),
    CHECK(ends_at IS NULL OR starts_at IS NULL OR starts_at<ends_at),
    CHECK(state<>'PUBLISHED' OR starts_at IS NOT NULL),
    CHECK(kind='BANNER' OR (asset_id IS NULL AND destination IS NULL AND alt='')),
    CHECK(kind<>'BANNER' OR state<>'PUBLISHED' OR (asset_id IS NOT NULL AND length(trim(alt))>0))
);
CREATE TRIGGER site_post_immutable BEFORE UPDATE OR DELETE ON site_post_revision
FOR EACH ROW EXECUTE FUNCTION site_keep_history();
CREATE TABLE site_post (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    FOREIGN KEY(id,version) REFERENCES site_post_revision(post_id,version)
);
CREATE TABLE site_info_revision (
    version bigint PRIMARY KEY CHECK(version>0),
    site_name text NOT NULL CHECK(char_length(site_name) BETWEEN 1 AND 80),
    support_guide text NOT NULL CHECK(char_length(support_guide) BETWEEN 1 AND 1000),
    reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 500),
    actor_id uuid,
    request_key uuid,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(actor_id,request_key),
    CHECK((version=1 AND actor_id IS NULL AND request_key IS NULL) OR (version>1 AND actor_id IS NOT NULL AND request_key IS NOT NULL))
);
CREATE TRIGGER site_info_immutable BEFORE UPDATE OR DELETE ON site_info_revision
FOR EACH ROW EXECUTE FUNCTION site_keep_history();
INSERT INTO site_info_revision(version,site_name,support_guide,reason)
VALUES(1,'마켓플레이스','주문과 거래에 관한 문의는 고객지원에서 접수해 주세요.','초기 사이트 안내');
