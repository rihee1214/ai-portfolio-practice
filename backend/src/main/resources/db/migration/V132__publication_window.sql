SET search_path TO marketplace,public;
CREATE FUNCTION site_post_visible(state text, starts_at timestamptz, ends_at timestamptz, observed_at timestamptz)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT state='PUBLISHED' AND starts_at IS NOT NULL AND starts_at<=observed_at
        AND (ends_at IS NULL OR observed_at<ends_at)
$$;

CREATE TABLE dashboard_failed_followup (
    request_key uuid PRIMARY KEY REFERENCES dashboard_request(request_key),
    failed_job uuid NOT NULL REFERENCES dashboard_run(job_id),
    next_job uuid NOT NULL REFERENCES dashboard_run(job_id),
    CHECK(failed_job<>next_job)
);
CREATE TRIGGER dashboard_followup_immutable BEFORE UPDATE OR DELETE ON dashboard_failed_followup
FOR EACH ROW EXECUTE FUNCTION site_keep_history();
