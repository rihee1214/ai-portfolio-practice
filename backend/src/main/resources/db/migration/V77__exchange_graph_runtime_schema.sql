-- V76 has already been applied in a preserved isolated database; keep it immutable.
-- The deferred trigger executes after request transaction statements reset the connection search path.
ALTER FUNCTION marketplace.validate_exchange_graph() SET search_path TO marketplace,public;
