-- ============================================================
-- V32 — admin_user MFA columns
--
-- 목적: AdminUsersTab MFA 컬럼 실데이터화 (Gap #15).
-- Adds mfa_enabled (CHAR Y/N, default 'N') + mfa_secret (nullable VARCHAR2(64)).
-- Updates V11 seed rows: alice → 'Y', bob → 'N'.
-- Idempotent: ALTER wrapped in EXCEPTION (ORA-01430 = column exists).
-- ============================================================

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (mfa_enabled CHAR(1) DEFAULT ''N'' NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (mfa_secret VARCHAR2(64))';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_check_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check_exists, -2264);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_mfa_enabled CHECK (mfa_enabled IN (''Y'',''N''))';
EXCEPTION
  WHEN e_check_exists THEN NULL;
END;
/

-- V11 seed UPDATE (idempotent — overwrites unconditionally)
UPDATE admin_user SET mfa_enabled = 'Y' WHERE email = 'alice@crosscert.com';
UPDATE admin_user SET mfa_enabled = 'N' WHERE email = 'bob@crosscert.com';

COMMIT;
