-- V45: explicit grants unblocked by removing GRANT ALL from APP_ADMIN_USER
-- (finding #3 / tasks B2+B3).
--
-- 1. admin_user full UPDATE to APP_ADMIN:
--    V9/V22 used column-level UPDATE on admin_user. Subsequent migrations
--    (V23 tenant_id, V37 failed_login_count/locked_until/status/suspended_at/
--    suspended_by, V32 mfa_enabled/mfa_secret) added new columns without
--    adding column-level grants. Hibernate writes the full row on every save,
--    so APP_ADMIN needs UPDATE on all columns. Granting table-level UPDATE
--    (no column list) supersedes the prior column-level grants cleanly and
--    is safe because admin_user is entirely admin-app–managed (no runtime
--    user should ever update it — APP_RUNTIME has SELECT-only on admin_user).
--
-- 2. signing_key_bootstrap_pkg EXECUTE to APP_ADMIN:
--    SigningKeyProvider.createInitialKey() (shared :core component) calls
--    APP_OWNER.signing_key_bootstrap_pkg.bootstrap_active via the admin-app
--    datasource (APP_ADMIN_USER). V18 granted EXECUTE to APP_RUNTIME only;
--    APP_ADMIN also needs it so admin-app can bootstrap the first ACTIVE
--    signing key on a fresh installation.

GRANT UPDATE ON admin_user TO APP_ADMIN;

GRANT EXECUTE ON APP_OWNER.signing_key_bootstrap_pkg TO APP_ADMIN;
