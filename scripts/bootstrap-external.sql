-- ============================================================
-- bootstrap-external.sql — 도커 없이 이미 떠 있는 Oracle 에 직접 적용하는 부트스트랩.
--
-- scripts/bootstrap-vpd.sql 은 docker compose 가 APP_OWNER 유저를 먼저 만든다고
-- 가정한다(APP_USER env). 외부 Oracle 에는 그 단계가 없으므로 이 파일이
-- APP_OWNER 생성까지 포함한다. 그 외(role/VPD/CTX_PKG)는 동일.
--
-- 실행 (SYSDBA 권한 필요):
--   sqlplus -S sys/<pw>@<host>:<port>/<PDB> as sysdba < scripts/bootstrap-external.sql
--
-- ⚠️ PDB 이름이 XEPDB1 이 아니면 아래 ALTER SESSION SET CONTAINER 줄을 바꾸거나,
--    접속 URL 의 service name 을 그 PDB 로 주고 이 ALTER 줄을 주석 처리하라.
--    (이미 해당 PDB 로 접속했다면 ALTER SESSION 은 no-op 이라 둬도 무방.)
--
-- Idempotency: 모든 statement 재실행 안전(존재 시 EXCEPTION 으로 무시).
-- ============================================================

WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE

-- 외부 Oracle 이 단일 인스턴스(non-CDB)면 이 줄에서 ORA-65040 등이 날 수 있다.
-- 그 경우 이 줄을 주석 처리하라(이미 올바른 컨테이너/스키마에 접속한 상태).
ALTER SESSION SET CONTAINER = XEPDB1;

-- ============================================================
-- 계정 비밀번호 — 외부 입력. 미정의/빈 값이면 부트스트랩 중단(fail-fast).
-- ⚠️ DBeaver 등 DEFINE 미지원 클라이언트: 아래 DEFINE 3줄을 강한 실제 비번으로
--    직접 치환한 뒤 실행하라. (sqlplus / init-db-external.sh 경로는 env 주입.)
-- ⚠️ 비번에 & 또는 "(큰따옴표) 포함 시 sqlplus 치환/quoting 충돌. 두 문자 회피 권장.
-- ============================================================
DEFINE app_owner_pw = ""
DEFINE runtime_pw   = ""
DEFINE admin_pw     = ""

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
GRANT EXEMPT ACCESS POLICY TO APP_ADMIN;

-- ============================================================
-- APP_OWNER privileges (schema owner) — one GRANT per privilege.
-- ⚠️ bootstrap-vpd.sql 과 bootstrap-external.sql 의 APP_OWNER 권한 블록은
--    항상 동기 상태를 유지할 것. 한 파일 수정 시 다른 파일도 함께 수정.
-- ============================================================
GRANT CREATE TABLE         TO APP_OWNER;
GRANT CREATE SEQUENCE      TO APP_OWNER;
GRANT CREATE PROCEDURE     TO APP_OWNER;
GRANT CREATE TRIGGER       TO APP_OWNER;
GRANT CREATE ANY CONTEXT   TO APP_OWNER;
GRANT UNLIMITED TABLESPACE TO APP_OWNER;
GRANT EXECUTE ON DBMS_RLS     TO APP_OWNER;
GRANT EXECUTE ON DBMS_SESSION TO APP_OWNER;
GRANT CREATE VIEW          TO APP_OWNER;
GRANT EXEMPT ACCESS POLICY TO APP_OWNER;
GRANT EXECUTE ON DBMS_RLS     TO APP_ADMIN;

-- ============================================================
-- Users (Flyway = APP_ADMIN_USER, runtime = APP_RUNTIME_USER)
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

-- ============================================================
-- CTX_PKG package + APP_CTX namespace (owned by APP_OWNER)
-- ============================================================
CREATE OR REPLACE PACKAGE APP_OWNER.CTX_PKG AS
  PROCEDURE set_tenant(p_tenant_hex IN VARCHAR2);
  PROCEDURE clear_tenant;
END;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.CTX_PKG AS
  PROCEDURE set_tenant(p_tenant_hex IN VARCHAR2) IS
  BEGIN
    DBMS_SESSION.SET_CONTEXT('APP_CTX', 'TENANT_ID', p_tenant_hex);
  END;
  PROCEDURE clear_tenant IS
  BEGIN
    DBMS_SESSION.CLEAR_CONTEXT('APP_CTX', NULL, 'TENANT_ID');
  END;
END;
/

CREATE OR REPLACE CONTEXT APP_CTX USING APP_OWNER.CTX_PKG;

GRANT EXECUTE ON APP_OWNER.CTX_PKG TO APP_RUNTIME;
GRANT EXECUTE ON APP_OWNER.CTX_PKG TO APP_ADMIN;

EXIT;
