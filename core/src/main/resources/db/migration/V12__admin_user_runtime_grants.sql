-- V9 granted admin_user and admin_user_seq only to APP_ADMIN. APP_RUNTIME
-- inherits the AdminUser JPA entity through the shared :core module, and
-- Hibernate's hibernate.ddl-auto=validate probes every entity table at boot.
-- Without at least SELECT, the probe fails as ORA-00942 and Hibernate raises
-- "Schema-validation: missing table" — passkey-app would not boot.
--
-- Grant read-only access. APP_RUNTIME never reads or writes admin_user
-- in normal operation; only admin-app (running under APP_ADMIN) needs DML.

GRANT SELECT ON admin_user TO APP_RUNTIME;
GRANT SELECT ON admin_user_seq TO APP_RUNTIME;
