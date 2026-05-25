-- Grant APP_RUNTIME SELECT on audit_log + sequence so passkey-app's
-- Hibernate ddl-validate succeeds at boot. passkey-app only reads
-- the audit_log entity scan; admin-app does all writes. APP_RUNTIME
-- cannot INSERT (no INSERT grant from V10), so this stays append-only-
-- from-admin.

GRANT SELECT ON audit_log TO APP_RUNTIME;
GRANT SELECT ON audit_log_seq TO APP_RUNTIME;
