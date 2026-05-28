-- ============================================================
-- V31 — security_policy (platform-wide singleton row, id = 1)
--
-- 목적: SecurityPolicyTab 저장 영속화 (Gap #20).
-- Single row, id=1 always. Idempotent — re-running V31 must succeed.
--
-- Note on numbering: spec §F1.1 references "V30" but V30 was already
-- claimed by admin_user_invitation_runtime_grants. Per spec §1 note
-- ("번호는 phase 가 실제로 마이그레이션을 만들 때 그 시점에 결정"),
-- this migration shifts to V31 (next free slot).
--
-- Patterns:
--   - CREATE wrapped in EXCEPTION (ORA-00955 = table exists)
--   - GRANT separated, one per statement (V29 pattern, line 44-45)
--   - Seed row INSERT skipped if id=1 already present
-- ============================================================

-- 1. Table
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE security_policy (
    id                              NUMBER(1,0)              NOT NULL,
    session_idle_timeout_minutes    NUMBER(5,0)              NOT NULL,
    password_min_length             NUMBER(3,0)              NOT NULL,
    mfa_required                    CHAR(1)                  NOT NULL,
    cors_allowlist                  CLOB                     NOT NULL,
    updated_at                      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_by                      VARCHAR2(255),
    CONSTRAINT pk_security_policy PRIMARY KEY (id),
    CONSTRAINT ck_security_policy_singleton CHECK (id = 1),
    CONSTRAINT ck_security_policy_mfa CHECK (mfa_required IN (''Y'',''N''))
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- 2. Seed singleton row (only if not present)
MERGE INTO security_policy t
USING (SELECT 1 AS id FROM dual) s
ON (t.id = s.id)
WHEN NOT MATCHED THEN INSERT (id, session_idle_timeout_minutes, password_min_length, mfa_required, cors_allowlist, updated_at, updated_by)
VALUES (1, 30, 12, 'Y', '[]', SYSTIMESTAMP, 'system');

COMMIT;

-- 3. Grants to APP_ADMIN (split per V29 pattern)
GRANT SELECT ON security_policy TO APP_ADMIN;
GRANT UPDATE ON security_policy TO APP_ADMIN;

-- 4. Runtime grant — passkey-app (APP_RUNTIME) 도 @EntityScan("core.entity")
--    으로 SecurityPolicy 를 스캔하므로 ddl-auto:validate 통과를 위해
--    SELECT 권한 필요. V30 runtime_grants 패턴 일관.
GRANT SELECT ON security_policy TO APP_RUNTIME;
