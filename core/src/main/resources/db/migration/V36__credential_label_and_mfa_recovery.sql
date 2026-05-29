-- ============================================================
-- V36 — credential.label + admin MFA recovery code 테이블
--
-- 목적:
--   P0-4: end-user 가 자신의 passkey 를 식별할 수 있도록 CREDENTIAL.LABEL 추가.
--   P0-5: admin MFA recovery code. 1회용 코드의 sha-256 hash 만 저장.
--
-- Idempotent: 모든 DDL 을 EXCEPTION 으로 감싼다 (V32/V34 패턴).
-- ============================================================

-- ------------------------------------------------------------
-- P0-4: CREDENTIAL.LABEL (nullable — 기존 credential 은 label 없이 동작)
--
-- GRANT 불필요: CREDENTIAL 은 V2/V19 에서 이미 APP_RUNTIME / APP_ADMIN 에
--   table-level SELECT,INSERT,UPDATE,DELETE 를 보유. Oracle 의 table-level
--   DML grant 는 신규 컬럼을 자동 포함하므로 컬럼 추가용 별도 GRANT 가 없다.
-- ------------------------------------------------------------
DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);  -- ORA-01430: column already exists
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE credential ADD (label VARCHAR2(128))';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

-- ------------------------------------------------------------
-- P0-5: ADMIN_USER_RECOVERY_CODE (platform 자원 — NO VPD)
--
--   admin_user 와 1:N. tenant 무관이므로 VPD 없음.
--   id RAW(16) DEFAULT SYS_GUID(): BaseEntity 규약 (V19 admin_user, SchedulerLease 패턴).
--   FK admin_user_id RAW(16) → admin_user(id) ON DELETE CASCADE (V29 invitation 패턴).
--   code_hash VARCHAR2(64): sha-256 hex = 64 chars.
--   created_at TIMESTAMP WITH TIME ZONE: 코드베이스 timestamp 규약 (BaseEntity).
-- ------------------------------------------------------------
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);  -- ORA-00955: name already used
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE admin_user_recovery_code (
    id            RAW(16)                  DEFAULT SYS_GUID() NOT NULL,
    admin_user_id RAW(16)                  NOT NULL,
    code_hash     VARCHAR2(64)             NOT NULL,
    used_at       TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_admin_user_recovery_code PRIMARY KEY (id),
    CONSTRAINT fk_recovery_admin_user FOREIGN KEY (admin_user_id)
      REFERENCES admin_user (id) ON DELETE CASCADE
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- CREATE INDEX idempotency: re-run 시 ORA-00955 (동일 index 명) 또는
--   ORA-01408 (동일 column list 가 이미 indexed) 발생 가능. 둘 다 무시 (V34 패턴).
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_recovery_admin_user ON admin_user_recovery_code (admin_user_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 OR SQLCODE = -1408 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- ------------------------------------------------------------
-- 권한
--   APP_ADMIN: recovery code 는 admin-app 이 발급/검증/사용처리 → SELECT,INSERT,UPDATE,DELETE.
--     (UPDATE: used_at 마킹, DELETE: 재발급 시 기존 코드 폐기)
--   APP_RUNTIME: passkey-app 이 @EntityScan("...core.entity") 로 전 엔티티를 스캔,
--     Hibernate ddl-validate 통과를 위해 SELECT 필요 (V30/V31 invariant). DML 은 없음.
-- ------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON admin_user_recovery_code TO APP_ADMIN;
GRANT SELECT ON admin_user_recovery_code TO APP_RUNTIME;
