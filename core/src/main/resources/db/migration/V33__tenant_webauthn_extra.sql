-- ============================================================
-- V33 — tenant.attestation_conveyance + tenant.webauthn_timeout_ms
--
-- 목적: TenantOverview WebAuthn 요약 카드 + WebauthnConfigTab attestation/timeout
--       의 fixture/하드코딩 (Gap #4) 제거.
--
-- attestation_conveyance: WebAuthn AttestationConveyancePreference enum string.
--   Valid: 'NONE' | 'INDIRECT' | 'DIRECT' | 'ENTERPRISE'. Default 'NONE'.
-- webauthn_timeout_ms: ceremony timeout in ms. Default 60000.
--
-- Idempotent: ALTER wrapped in EXCEPTION (ORA-01430 = column exists).
-- ============================================================

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD (attestation_conveyance VARCHAR2(16) DEFAULT ''NONE'' NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD (webauthn_timeout_ms NUMBER(10,0) DEFAULT 60000 NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_check_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check_exists, -2264);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD CONSTRAINT ck_tenant_attestation CHECK (attestation_conveyance IN (''NONE'',''INDIRECT'',''DIRECT'',''ENTERPRISE''))';
EXCEPTION
  WHEN e_check_exists THEN NULL;
END;
/

DECLARE
  e_check_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_check_exists, -2264);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE tenant ADD CONSTRAINT ck_tenant_timeout_range CHECK (webauthn_timeout_ms BETWEEN 1000 AND 600000)';
EXCEPTION
  WHEN e_check_exists THEN NULL;
END;
/

COMMIT;
