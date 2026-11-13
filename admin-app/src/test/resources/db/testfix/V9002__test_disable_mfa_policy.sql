-- ============================================================
-- V9002 — TEST-ONLY: turn the "MFA required" security policy off
--
-- The baseline seeds security_policy.mfa_required='Y' (production default,
-- unchanged). With the policy on, every IT login of an unenrolled admin
-- (alice/bob are mfa_enabled='N' after V9000) is gated with
-- MFA_ENROLL_REQUIRED, and the ITs skip the enrollment step.
--
-- This migration lives ONLY under src/test/resources/db/testfix and is
-- referenced solely by application-test.yml's flyway.locations, so it
-- never reaches a production classpath. MFA-required enforcement is covered
-- by unit/slice tests and the dedicated MfaRequiredPolicyIT, which sets the
-- policy back to 'Y' itself.
--
-- Idempotent UPDATE.
-- ============================================================

UPDATE security_policy SET mfa_required = 'N' WHERE id = 1;

COMMIT;
