-- ============================================================
-- V9000 — TEST-ONLY: disable MFA on the V11/V32 seed admin users
--
-- V32 seeds alice@crosscert.com with mfa_enabled='Y' (production demo
-- of the second factor). Many admin-app ITs log in as alice to exercise
-- role/tenant boundaries and DO NOT perform the TOTP step, so with the
-- new MfaPendingFilter every such login would be gated (403 mfa_required).
--
-- This migration lives ONLY under src/test/resources/db/testfix and is
-- referenced solely by application-test.yml's flyway.locations, so it
-- never reaches a production classpath. The MFA enforcement path itself
-- is covered by MfaPendingFilterTest + TotpServiceTest.
--
-- High version (9000) guarantees it runs AFTER all real migrations.
-- Idempotent UPDATE.
-- ============================================================

UPDATE admin_user
   SET mfa_enabled = 'N'
 WHERE email IN ('alice@crosscert.com', 'bob@crosscert.com');

COMMIT;
