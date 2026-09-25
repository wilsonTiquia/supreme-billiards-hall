-- The read-only login nightwatch connects as. Run ONCE, by hand, as a superuser — not a Flyway
-- migration: Flyway is for schema, and a role's password has no place in a public repo.
--
-- The password comes from the environment, never from this file or the command line:
--
--   NIGHTWATCH_DB_PASSWORD=... psql ... -f create-role.sql
--
-- README.md has the exact command for the VPS and for the Mac.

\set ON_ERROR_STOP on
\getenv pw NIGHTWATCH_DB_PASSWORD
\if :{?pw}
\else
  \echo 'NIGHTWATCH_DB_PASSWORD is not set in the environment; nothing was created.'
  \quit
\endif

CREATE ROLE nightwatch_ro LOGIN PASSWORD :'pw';

-- Belt and braces: every transaction it opens is read-only even if a grant below is ever widened
-- by mistake, and no query it runs can hold the database for long.
ALTER ROLE nightwatch_ro SET default_transaction_read_only = on;
ALTER ROLE nightwatch_ro SET statement_timeout = '10s';

GRANT CONNECT ON DATABASE supreme TO nightwatch_ro;
GRANT USAGE ON SCHEMA public TO nightwatch_ro;
-- Exactly the two tables it reads: branch for the active branches, cash_count for the close.
-- Nothing on payment, bill or app_user. business_date_of() needs no grant: functions are
-- executable by PUBLIC unless revoked.
GRANT SELECT ON cash_count, branch TO nightwatch_ro;
