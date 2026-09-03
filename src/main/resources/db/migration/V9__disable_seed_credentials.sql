-- =====================================================================
-- Disable the seeded login credentials
-- =====================================================================
--
-- V2__seed.sql shipped two users (owner, counter) with bcrypt hashes and a
-- comment claiming "the application sets real bcrypt hashes on first run."
-- That code never existed, so those committed hashes were live, working
-- credentials for anyone with repo access. V2 is frozen (Flyway checksums
-- every applied migration), so the correction is made here rather than by
-- editing it in place.
--
-- The sentinel below is not a valid bcrypt hash. BCryptPasswordEncoder.matches()
-- rejects a stored value that does not look like bcrypt, so NO password can ever
-- authenticate either seeded user after this migration. There is also no hash to
-- crack: the value carries no secret.
--
-- The V2 comment's promise is now finally true, made so by AdminPasswordBootstrap:
-- on boot, if the env var SUPREME_BOOTSTRAP_ADMIN_PASSWORD is set AND owner still
-- carries this exact sentinel, the app encodes it with the real BCryptPasswordEncoder
-- and stores it. It never overwrites a password that has already been set.
--
-- 'counter' stays disabled after this migration by design. The owner restores it
-- through the ADMIN password-reset endpoint (PUT /api/v1/users/{id}/password);
-- it is not meant to be usable until then.
--
-- ---------------------------------------------------------------------
-- LAUNCH-DAY SEQUENCE (also in HELP.md) — someone who is not the author runs this:
--   1. Set SUPREME_BOOTSTRAP_ADMIN_PASSWORD to a strong owner password in the
--      environment (never in a file, never committed).
--   2. Start the app. It initialises the owner password once and logs INFO
--      (the value is never logged).
--   3. Log in as 'owner'.
--   4. IMMEDIATELY change it via PUT /api/v1/auth/password (current + new),
--      then unset the env var before the next restart.
--   5. Set the counter password via PUT /api/v1/users/{counterId}/password.
-- ---------------------------------------------------------------------

UPDATE app_user
   SET password_hash = 'DISABLED-NO-LOGIN',
       updated_at    = now()
 WHERE username IN ('owner', 'counter');
