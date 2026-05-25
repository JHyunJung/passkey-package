-- Phase 0 bootstrap. Runs once as SYS(SYSDBA) after `docker compose up -d`.
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

-- APP_RUNTIME: runtime sessions (passkey-app, admin-app normal transactions)
-- VPD policy applies to this role.
BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL; -- ORA-01921: role already exists
  ELSE RAISE;
  END IF;
END;
/
GRANT CREATE SESSION TO APP_RUNTIME;

-- APP_ADMIN: scheduler + Flyway. EXEMPT ACCESS POLICY bypasses VPD.
BEGIN
  EXECUTE IMMEDIATE 'CREATE ROLE APP_ADMIN';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE = -1921 THEN NULL;
  ELSE RAISE;
  END IF;
END;
/
GRANT CREATE SESSION TO APP_ADMIN;
GRANT EXEMPT ACCESS POLICY TO APP_ADMIN;

-- ============================================================
-- APP_OWNER privileges (schema owner)
-- One GRANT per privilege so a bad name does not nuke the bundle.
-- ============================================================

GRANT CREATE TABLE         TO APP_OWNER;
GRANT CREATE SEQUENCE      TO APP_OWNER;
GRANT CREATE PROCEDURE     TO APP_OWNER;
GRANT CREATE TRIGGER       TO APP_OWNER;
GRANT CREATE ANY CONTEXT   TO APP_OWNER;
GRANT UNLIMITED TABLESPACE TO APP_OWNER;
GRANT EXECUTE ON DBMS_RLS     TO APP_OWNER;
GRANT EXECUTE ON DBMS_SESSION TO APP_OWNER;

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
-- ADMIN needs broad rights for Flyway DDL + DBMS_RLS administration.
-- See docs/superpowers/followups/2026-05-25-prod-hardening-notes.md for
-- a known-issue to tighten this in a later Phase.
GRANT ALL PRIVILEGES TO APP_ADMIN_USER;

-- ============================================================
-- CTX_PKG package + APP_CTX namespace (owned by APP_OWNER)
-- Created from SYS session via schema-qualified DDL; no CONNECT needed.
-- ============================================================

CREATE OR REPLACE PACKAGE APP_OWNER.CTX_PKG AS
  PROCEDURE set_tenant(p_tid IN VARCHAR2);
  PROCEDURE clear_tenant;
END;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.CTX_PKG AS
  PROCEDURE set_tenant(p_tid IN VARCHAR2) IS
  BEGIN
    DBMS_SESSION.SET_CONTEXT('APP_CTX', 'TENANT_ID', p_tid);
  END;
  PROCEDURE clear_tenant IS
  BEGIN
    -- Signature: CLEAR_CONTEXT(namespace, client_id, attribute).
    -- CLIENT_ID must be NULL so we target the session attribute,
    -- not a client-identifier-scoped value. Pass positionally to avoid
    -- name drift across Oracle versions.
    DBMS_SESSION.CLEAR_CONTEXT('APP_CTX', NULL, 'TENANT_ID');
  END;
END;
/

-- Context namespace must reference APP_OWNER.CTX_PKG (the trusted package
-- that sets/clears it). Created with CREATE ANY CONTEXT from SYS.
CREATE OR REPLACE CONTEXT APP_CTX USING APP_OWNER.CTX_PKG;

GRANT EXECUTE ON APP_OWNER.CTX_PKG TO APP_RUNTIME;
GRANT EXECUTE ON APP_OWNER.CTX_PKG TO APP_ADMIN;

EXIT;
