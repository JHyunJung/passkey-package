-- ============================================================
-- bootstrap-external-body.sql — 실제 부트스트랩 body.
-- 직접 실행하지 말고:
--   - sqlplus / init-db-external.sh 는 이 파일을 직접 호출(env DEFINE 주입 후).
--   - DBeaver 등 수동 실행 사용자는 bootstrap-external.sql(wrapper)을 실행할 것.
-- ============================================================

WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE

-- 서비스/PDB 명: 호출측(init-db-external.sh 또는 bootstrap-external.sql wrapper)에서
-- DEFINE ora_service=... 로 주입 필수. SQL 파일에 in-file DEFINE 이 없으므로 주입이 유일 정의.
-- non-CDB 인스턴스에서는 이 줄에서 ORA-65040 이 날 수 있다 — 그 경우 이 줄을 주석 처리하라.
ALTER SESSION SET CONTAINER = &ora_service;

-- 빈 값 가드: 하나라도 비면 즉시 중단 (Oracle '' IS NULL → fail-closed).
-- WHENEVER SQLERROR EXIT(상단)이 이 RAISE 를 비0 종료로 만든다.
BEGIN
  IF '&&app_owner_pw' IS NULL OR '&&runtime_pw' IS NULL OR '&&admin_pw' IS NULL THEN
    RAISE_APPLICATION_ERROR(-20001,
      'bootstrap 중단: app_owner_pw/runtime_pw/admin_pw 중 미정의. DEFINE 또는 env(APP_OWNER_PW/RUNTIME_PW/ADMIN_PW) 주입 필요.');
  END IF;
END;
/

-- ============================================================
-- APP_OWNER 스키마 유저 (도커 compose 가 하던 것)
-- ============================================================
BEGIN
  EXECUTE IMMEDIATE 'CREATE USER APP_OWNER IDENTIFIED BY "&&app_owner_pw"';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL; -- ORA-01920: user already exists
  ELSE RAISE;
  END IF;
END;
/
GRANT CREATE SESSION TO APP_OWNER;

-- ============================================================
-- Roles
-- ============================================================
BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL; ELSE RAISE; END IF;
END;
/
GRANT CREATE SESSION TO APP_RUNTIME;

BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE APP_ADMIN';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL; ELSE RAISE; END IF;
END;
/
GRANT CREATE SESSION TO APP_ADMIN;

-- ============================================================
-- APP_OWNER privileges (schema owner) — one GRANT per privilege.
-- ⚠️ bootstrap-schema.sql 과 bootstrap-external.sql 의 APP_OWNER 권한 블록은
--    항상 동기 상태를 유지할 것. 한 파일 수정 시 다른 파일도 함께 수정.
-- ============================================================
GRANT CREATE TABLE         TO APP_OWNER;
GRANT CREATE SEQUENCE      TO APP_OWNER;
GRANT CREATE PROCEDURE     TO APP_OWNER;
GRANT CREATE TRIGGER       TO APP_OWNER;
GRANT UNLIMITED TABLESPACE TO APP_OWNER;
GRANT CREATE VIEW          TO APP_OWNER;

-- ============================================================
-- Users (runtime = APP_RUNTIME_USER, admin = APP_ADMIN_USER)
-- ============================================================
BEGIN
  EXECUTE IMMEDIATE 'CREATE USER APP_RUNTIME_USER IDENTIFIED BY "&&runtime_pw"';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL; ELSE RAISE; END IF;
END;
/
GRANT APP_RUNTIME TO APP_RUNTIME_USER;

BEGIN
  EXECUTE IMMEDIATE 'CREATE USER APP_ADMIN_USER IDENTIFIED BY "&&admin_pw"';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL; ELSE RAISE; END IF;
END;
/
GRANT APP_ADMIN TO APP_ADMIN_USER;

-- 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect) 전담 (CTX_PKG/APP_CTX 미생성).

EXIT;
