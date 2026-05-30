-- ============================================================
-- V37 — admin 계정 보안: mfa_secret 확장 + lockout 컬럼 + reset 토큰
--
-- 목적 (그룹 A — 스키마 기반):
--   1. admin_user.mfa_secret 를 VARCHAR2(64) → VARCHAR2(255) 확장
--      (V32 에서 평문 길이로 잡았으나, 암호화 후 base64/envelope 가 64자 초과).
--   2. admin_user 계정 잠금 컬럼: failed_login_count, locked_until.
--   3. admin_password_reset_token 테이블 (1회용 reset 토큰의 sha-256 hash 저장).
--
-- Idempotent: 모든 DDL 을 EXCEPTION 으로 감싼다 (V32/V34/V36 패턴).
-- ============================================================

-- ------------------------------------------------------------
-- 1. admin_user.mfa_secret 확장: VARCHAR2(64) → VARCHAR2(255)
--
--   widening 은 자연 멱등 — 재실행 시 동일 길이 지정이라 단순 no-op (에러 없음).
--   benign 한 변형만 삼키고 그 외 진짜 실패(오타 컬럼, 락, ORA-01441 향후
--   width 축소 등)는 RAISE 로 표면화한다 (다른 블록들과 동일한 SQLCODE 가드).
--     ORA-01430(-1430): column being added already exists
--     ORA-01442(-1442): column already NOT NULL (MODIFY 변형)
--     ORA-01451(-1451): column already NULL (MODIFY 변형)
--   컬럼 폭을 줄이지 않고 늘리기만 하므로 기존 데이터 손실 없음.
-- ------------------------------------------------------------
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user MODIFY (mfa_secret VARCHAR2(255))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE IN (-1430, -1442, -1451) THEN NULL;  -- benign no-op variants
    ELSE RAISE;
    END IF;
END;
/

-- ------------------------------------------------------------
-- 2. admin_user 계정 잠금 컬럼
--   failed_login_count: 연속 로그인 실패 횟수 (성공/잠금해제 시 0 리셋).
--   locked_until: 잠금 만료 시각 (NULL = 잠금 아님).
--   ORA-01430 (column already exists) 무시 → 재실행 무해.
-- ------------------------------------------------------------
DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);  -- ORA-01430: column already exists
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (failed_login_count NUMBER DEFAULT 0 NOT NULL)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

DECLARE
  e_column_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_exists, -1430);  -- ORA-01430: column already exists
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE admin_user ADD (locked_until TIMESTAMP WITH TIME ZONE)';
EXCEPTION
  WHEN e_column_exists THEN NULL;
END;
/

-- ------------------------------------------------------------
-- 3. admin_password_reset_token (platform 자원 — NO VPD)
--
--   admin_user 와 1:N. SEQUENCE PK (admin_user_invitation 패턴, V29).
--   token_hash VARCHAR2(64): sha-256 hex = 64 chars, UNIQUE.
--   token_prefix VARCHAR2(8): 8자 식별용 (조회/감사 로그용).
--   FK admin_user_id RAW(16) → admin_user(id) ON DELETE CASCADE (V29 패턴).
-- ------------------------------------------------------------
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);  -- ORA-00955: name already used
BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE admin_password_reset_token_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);  -- ORA-00955: name already used
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE admin_password_reset_token (
    id              NUMBER(19,0)             NOT NULL,
    admin_user_id   RAW(16)                  NOT NULL,
    token_hash      VARCHAR2(64)             NOT NULL,
    token_prefix    VARCHAR2(8)              NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at     TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_admin_password_reset_token PRIMARY KEY (id),
    CONSTRAINT uq_admin_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_pwd_reset_admin_user FOREIGN KEY (admin_user_id)
      REFERENCES admin_user (id) ON DELETE CASCADE
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- CREATE INDEX idempotency: re-run 시 ORA-00955 (동일 index 명) 또는
--   ORA-01408 (동일 column list 가 이미 indexed) 발생 가능. 둘 다 무시 (V34/V36 패턴).
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_pwd_reset_admin_user ON admin_password_reset_token (admin_user_id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 OR SQLCODE = -1408 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- ------------------------------------------------------------
-- 권한
--   APP_ADMIN: reset 토큰은 admin-app 이 발급/검증/소비 → SELECT,INSERT,UPDATE,DELETE.
--     (UPDATE: consumed_at 마킹, DELETE: 재발급 시 기존 토큰 폐기)
--   APP_RUNTIME: passkey-app 이 @EntityScan("...core.entity") 로 전 엔티티를 스캔,
--     Hibernate ddl-validate 통과를 위해 SELECT 필요 (V30/V36 invariant). DML 은 없음.
-- ------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON admin_password_reset_token TO APP_ADMIN;
GRANT SELECT ON admin_password_reset_token TO APP_RUNTIME;
GRANT SELECT ON admin_password_reset_token_seq TO APP_ADMIN;
