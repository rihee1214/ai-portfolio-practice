CREATE FUNCTION protect_report_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'REPORT_HISTORY_IMMUTABLE' USING ERRCODE='23514';
END $$;
CREATE TRIGGER report_event_immutable BEFORE UPDATE OR DELETE ON operations_report_event
FOR EACH ROW EXECUTE FUNCTION protect_report_history();
CREATE TRIGGER report_evidence_immutable BEFORE UPDATE OR DELETE ON operations_report_evidence
FOR EACH ROW EXECUTE FUNCTION protect_report_history();
