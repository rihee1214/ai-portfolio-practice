-- Foundation only: business tables are introduced by later feature migrations.
-- Flyway creates and owns the configured application schema and history table.
COMMENT ON SCHEMA "${flyway:defaultSchema}" IS 'Marketplace application schema managed by Flyway';
