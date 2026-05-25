-- V1 only granted SELECT/INSERT/UPDATE/DELETE on scheduler_lease to APP_ADMIN,
-- not to APP_RUNTIME. APP_RUNTIME however inherits the SchedulerLease JPA
-- entity through the shared :core module, and Hibernate's
-- hibernate.ddl-auto=validate issues a metadata probe (effectively
-- SELECT ... FROM scheduler_lease) against every entity table at boot.
-- Without at least SELECT, the probe fails as ORA-00942 and Hibernate
-- raises "Schema-validation: missing table" — passkey-app would not boot.
--
-- Grant read-only access. APP_RUNTIME never writes to scheduler_lease
-- in normal operation; only the admin scheduler (running under APP_ADMIN)
-- needs DML.

GRANT SELECT ON scheduler_lease TO APP_RUNTIME;
