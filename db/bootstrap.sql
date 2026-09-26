-- Ordaro database bootstrap — run ONCE per database, as `postgres`, outside Flyway.
--
--   psql "$ADMIN_URL" -v ON_ERROR_STOP=1 \
--        -v owner_password="$TRILLOPOS_OWNER_PASSWORD" -v app_password="$TRILLOPOS_APP_PASSWORD" \
--        -f db/bootstrap.sql
--
-- On Supabase use the session-mode endpoint (port 5432), never the transaction pooler (6543).
-- Supabase's `postgres` is not a superuser but has CREATEROLE, which is all this needs.
--
-- Creates the two roles and the private schema (spec §12 Database hosting, RLS role split):
--   ordaro_owner  runs Flyway and owns every table
--   ordaro_app    what the application connects as: owns nothing, no BYPASSRLS
-- Migration V1 then grants ordaro_app its table privileges and sets default privileges.

create role ordaro_owner login nobypassrls nocreaterole nocreatedb password :'owner_password';
create role ordaro_app   login nobypassrls nocreaterole nocreatedb password :'app_password';

-- `create schema … authorization ordaro_owner` needs the caller to be able to become the owner:
-- membership on PG 15, SET on PG 16+. A non-superuser that creates a role only receives
-- ADMIN OPTION on it by default (PG 16+), so grant membership explicitly. Harmless for a
-- superuser (local, Docker, tests).
grant ordaro_owner to current_user;

create schema ordaro authorization ordaro_owner;
revoke all on schema ordaro from public;
grant usage on schema ordaro to ordaro_app;

alter role ordaro_owner set search_path = ordaro;
alter role ordaro_app   set search_path = ordaro;
