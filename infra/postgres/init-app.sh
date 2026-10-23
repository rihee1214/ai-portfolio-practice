#!/bin/sh
set -eu
# Only executed by the official image when the data volume is empty.
# psql quoted variables escape values and identifiers; never concatenate SQL passwords.
psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 <<'SQL'
\getenv app_user APP_USER
\getenv app_password APP_PASSWORD
\getenv app_db APP_DB
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
CREATE DATABASE :"app_db" OWNER :"app_user";
REVOKE CONNECT ON DATABASE :"app_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_db" TO :"app_user";
SQL
