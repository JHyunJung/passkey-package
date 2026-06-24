-- Phase 0 bootstrap. Runs once as SYS(SYSDBA) after `docker compose up -d`.
-- 스키마 소유자(APP_OWNER) + 런타임/어드민 유저·권한 부트스트랩.
-- VPD 는 제거됨 — 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect)가
-- 전담한다. DBMS_RLS GRANT·CTX_PKG·APP_CTX·EXEMPT ACCESS POLICY 는 더 이상 만들지
-- 않는다(과거 잔재는 V52__drop_vpd.sql 이 제거). 유저 3분할(APP_OWNER/APP_RUNTIME_
-- USER/APP_ADMIN_USER)은 최소권한(테이블별 GRANT·DDL 격리)을 위해 유지한다.
-- The APP_OWNER user is created by docker-compose's APP_USER env var.
--
-- Idempotency: every statement is safe to re-run. Roles/users use PL/SQL
-- EXCEPTION blocks to swallow ORA-01921 / ORA-01920 (object exists).
-- GRANTs are split one-privilege-per-statement so a single typo does not
-- atomically roll back the whole bundle (Oracle's compound-grant gotcha).
--
-- Privilege names verified against system_privilege_map.
-- DDL targeting APP_OWNER objects is schema-qualified so we never need
-- to switch sessions (no CONNECT, no committed app-owner password).

WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE

ALTER SESSION SET CONTAINER = XEPDB1;

-- ============================================================
-- Roles
-- ============================================================

-- APP_RUNTIME: runtime sessions (passkey-app, admin-app normal transactions).
-- Tenant isolation is enforced at the app level (Hibernate @Filter), not via
-- any DB-kernel policy on this role.
BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL; -- ORA-01921: role already exists
  ELSE RAISE;
  END IF;
END;
/
GRANT CREATE SESSION TO APP_RUNTIME;

-- APP_ADMIN: admin-app runtime + scheduler. Cross-tenant admin queries are
-- handled at the app layer (explicit tenant_id checks); Flyway runs as APP_OWNER.
BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE APP_ADMIN';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL;
  ELSE RAISE;
  END IF;
END;
/
GRANT CREATE SESSION TO APP_ADMIN;

-- ============================================================
-- APP_OWNER privileges (schema owner)
-- One GRANT per privilege so a bad name does not nuke the bundle.
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
-- Users
-- ============================================================

BEGIN
  EXECUTE IMMEDIATE 'CREATE USER APP_RUNTIME_USER IDENTIFIED BY runtime_pw';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL; -- ORA-01920: user already exists
  ELSE RAISE;
  END IF;
END;
/
GRANT APP_RUNTIME TO APP_RUNTIME_USER;

BEGIN
  EXECUTE IMMEDIATE 'CREATE USER APP_ADMIN_USER IDENTIFIED BY admin_pw';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1920 THEN NULL;
  ELSE RAISE;
  END IF;
END;
/
GRANT APP_ADMIN TO APP_ADMIN_USER;

-- (VPD 제거됨) — 과거엔 여기서 CTX_PKG 패키지와 APP_CTX 컨텍스트를 생성해 VPD
-- predicate 가 SYS_CONTEXT('APP_CTX','TENANT_ID') 로 테넌트를 읽게 했습니다. VPD
-- 가 제거되어 더 이상 만들지 않습니다. 테넌트 격리는 앱 레벨 Hibernate @Filter.

EXIT;
