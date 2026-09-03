-- =====================================================================
-- Forced password change flag
-- =====================================================================
--
-- Gates a user to nothing but reading /auth/me, changing their own password, and
-- logging out until they have changed it. Every other /api/v1 request is refused
-- while this is true.
--
-- Set by the go-live default-password path (reset-for-golive.sh --default-password),
-- which seeds a documented default password so an installer has a known first login —
-- but with this flag on, so the default cannot be used for anything except replacing
-- itself. That is what keeps a convenient default from recreating the committed-
-- credentials problem V9 fixed: the window closes itself on first login rather than
-- depending on anyone remembering to change it.
--
-- Cleared in the same transaction as a successful password change, so there is no
-- moment where the default password is live without the gate.

ALTER TABLE app_user
  ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN app_user.must_change_password IS
  'When true, the user may only read /auth/me, change their password, and log out until they do. Set by the go-live default-password path so a seeded default cannot be used for anything except replacing itself; cleared in the same transaction as a successful password change.';
