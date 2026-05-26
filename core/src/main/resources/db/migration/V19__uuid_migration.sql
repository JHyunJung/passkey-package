-- ============================================================
-- V19: UUID Migration — RAW(16) PK + FK for all entity tables
-- ============================================================
--
-- Rationale: Phase 6 migrates all surrogate keys from Oracle SEQUENCE
-- + NUMBER(19,0) to application-generated UUIDs stored as RAW(16).
-- Hibernate @UuidGenerator assigns IDs before INSERT, so sequences
-- are no longer needed. Operational data is assumed absent (spec §0).
--
-- Drop order: children with FKs first, then parents.
-- Recreate order: parents first, children after.
--
-- VPD policy reattachment is intentionally deferred to V20 (T2).
-- This migration is schema-only.
--
-- New columns in this migration (not present in V1-V18):
--   tenant.slug        VARCHAR2(64) UNIQUE NOT NULL
--   scheduler_lease.id RAW(16) PRIMARY KEY  (name demoted to UNIQUE)
-- ============================================================

-- ------------------------------------------------------------
-- STEP 1: Drop packages that reference tables being dropped
-- (must drop before the tables they reference)
-- ------------------------------------------------------------

-- api_key_lookup_pkg references api_key table (V8)
BEGIN
  EXECUTE IMMEDIATE 'DROP PACKAGE APP_OWNER.api_key_lookup_pkg';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -4043 THEN RAISE; END IF;
END;
/

-- signing_key_bootstrap_pkg references signing_key table (V18)
BEGIN
  EXECUTE IMMEDIATE 'DROP PACKAGE APP_OWNER.signing_key_bootstrap_pkg';
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -4043 THEN RAISE; END IF;
END;
/

-- ------------------------------------------------------------
-- STEP 2: Drop VPD policies on tables being dropped
-- (required before DROP TABLE succeeds)
-- ------------------------------------------------------------

BEGIN
  DBMS_RLS.DROP_POLICY(
    object_schema => 'APP_OWNER',
    object_name   => 'CREDENTIAL',
    policy_name   => 'CREDENTIAL_TENANT_ISOLATION'
  );
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -28101 THEN RAISE; END IF;
END;
/

BEGIN
  DBMS_RLS.DROP_POLICY(
    object_schema => 'APP_OWNER',
    object_name   => 'API_KEY',
    policy_name   => 'API_KEY_TENANT_ISOLATION'
  );
EXCEPTION WHEN OTHERS THEN
  IF SQLCODE != -28101 THEN RAISE; END IF;
END;
/

-- ------------------------------------------------------------
-- STEP 3: Drop tables (children first, then parents)
-- PURGE bypasses the Oracle recycle bin.
-- ------------------------------------------------------------

-- Children with FK to admin_user
DROP TABLE audit_log     PURGE;

-- Children with FK to tenant
DROP TABLE api_key       PURGE;
DROP TABLE credential    PURGE;

-- Parents / platform-scoped tables (no FK dependencies)
DROP TABLE admin_user      PURGE;
DROP TABLE signing_key     PURGE;
DROP TABLE mds_blob_cache  PURGE;
DROP TABLE scheduler_lease PURGE;
DROP TABLE tenant          PURGE;

-- ------------------------------------------------------------
-- STEP 4: Drop all sequences (UUIDs are app-generated)
-- ------------------------------------------------------------

DROP SEQUENCE admin_user_seq;
DROP SEQUENCE api_key_seq;
DROP SEQUENCE audit_log_seq;
DROP SEQUENCE credential_seq;
DROP SEQUENCE mds_blob_cache_seq;
DROP SEQUENCE signing_key_seq;

-- ------------------------------------------------------------
-- STEP 5: Recreate tables in dependency order
-- PK and FK columns that were NUMBER(19,0) or VARCHAR2(64)
-- surrogate keys are now RAW(16) (UUID binary form).
-- All other columns are reproduced verbatim from V1-V18.
-- ------------------------------------------------------------

-- --------------------------------------------------------
-- 5a. tenant (no FK deps; platform-scoped, no VPD)
--     Columns from V1 + V6 + NEW: slug (Phase 6)
-- --------------------------------------------------------
CREATE TABLE tenant (
  id                 RAW(16)                  NOT NULL,
  display_name       VARCHAR2(256)            NOT NULL,
  status             VARCHAR2(16)             DEFAULT 'active' NOT NULL,
  slug               VARCHAR2(64)             NOT NULL,
  rp_id              VARCHAR2(256)            NOT NULL,
  rp_name            VARCHAR2(256)            NOT NULL,
  allowed_origins    CLOB                     NOT NULL,
  attestation_policy CLOB,
  created_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_tenant         PRIMARY KEY (id),
  CONSTRAINT uq_tenant_slug    UNIQUE (slug),
  CONSTRAINT ck_tenant_status  CHECK (status IN ('active','suspended')),
  CONSTRAINT ck_tenant_origins_json
    CHECK (allowed_origins IS NULL OR allowed_origins IS JSON),
  CONSTRAINT ck_tenant_attest_policy_json
    CHECK (attestation_policy IS NULL OR attestation_policy IS JSON)
);

GRANT SELECT ON tenant TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant TO APP_ADMIN;

-- --------------------------------------------------------
-- 5b. scheduler_lease
--     Original V1: name VARCHAR2(64) was PK.
--     V19: add id RAW(16) as PK; name becomes UNIQUE NOT NULL.
--     V14 AUDIT_CHAIN_LOCK sentinel: re-inserted below (Step 6).
-- --------------------------------------------------------
CREATE TABLE scheduler_lease (
  id          RAW(16)                  DEFAULT SYS_GUID() NOT NULL,
  name        VARCHAR2(64)             NOT NULL,
  holder      VARCHAR2(256)            NOT NULL,
  expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_scheduler_lease   PRIMARY KEY (id),
  CONSTRAINT uq_scheduler_lease_name UNIQUE (name)
);

GRANT SELECT ON scheduler_lease TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler_lease TO APP_ADMIN;

-- --------------------------------------------------------
-- 5c. mds_blob_cache (platform-scoped, no VPD)
--     V1 columns; id now RAW(16) (singleton seed in Step 6).
-- --------------------------------------------------------
CREATE TABLE mds_blob_cache (
  id           RAW(16)                  NOT NULL,
  version      NUMBER(19,0)             NOT NULL,
  next_update  DATE                     NOT NULL,
  fetched_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  blob_jwt     CLOB                     NOT NULL,
  CONSTRAINT pk_mds_blob_cache PRIMARY KEY (id)
);

GRANT SELECT ON mds_blob_cache TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON mds_blob_cache TO APP_ADMIN;

-- --------------------------------------------------------
-- 5d. admin_user (platform-scoped, no VPD)
--     V9 columns; id now RAW(16).
-- --------------------------------------------------------
CREATE TABLE admin_user (
  id            RAW(16)                  NOT NULL,
  email         VARCHAR2(255)            NOT NULL,
  bcrypt_hash   VARCHAR2(72)             NOT NULL,
  role          VARCHAR2(16)             NOT NULL,
  enabled       CHAR(1)                  DEFAULT 'Y' NOT NULL,
  created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_login_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_admin_user       PRIMARY KEY (id),
  CONSTRAINT uq_admin_user_email UNIQUE (email),
  CONSTRAINT ck_admin_user_role  CHECK (role IN ('ADMIN','VIEWER')),
  CONSTRAINT ck_admin_user_enabled CHECK (enabled IN ('Y','N'))
);

GRANT SELECT, INSERT ON admin_user TO APP_ADMIN;
GRANT UPDATE(last_login_at, bcrypt_hash, role, enabled) ON admin_user TO APP_ADMIN;
-- APP_RUNTIME needs SELECT for Hibernate ddl-validate (V12 invariant)
GRANT SELECT ON admin_user TO APP_RUNTIME;

-- --------------------------------------------------------
-- 5e. signing_key (platform-scoped, no VPD)
--     V15 columns; id now RAW(16).
--     Function-based unique index preserved from V15.
-- --------------------------------------------------------
CREATE TABLE signing_key (
  id              RAW(16)                  NOT NULL,
  kid             VARCHAR2(64)             NOT NULL,
  alg             VARCHAR2(16)             NOT NULL,
  status          VARCHAR2(16)             NOT NULL,
  public_jwk      CLOB                     NOT NULL,
  private_pkcs8   BLOB                     NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  rotated_at      TIMESTAMP WITH TIME ZONE,
  revoked_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_signing_key        PRIMARY KEY (id),
  CONSTRAINT uq_signing_key_kid    UNIQUE (kid),
  CONSTRAINT ck_signing_key_status CHECK (status IN ('ACTIVE','ROTATED','REVOKED')),
  CONSTRAINT ck_signing_key_alg    CHECK (alg IN ('RS256')),
  CONSTRAINT ck_signing_key_public_jwk_json CHECK (public_jwk IS JSON)
);

CREATE INDEX signing_key_status_ix ON signing_key(status);

-- Enforce at most one ACTIVE key at any time (V15 invariant).
-- Only rows where status='ACTIVE' project the literal 1; all others
-- project NULL, which is excluded from B-tree unique-index coverage.
CREATE UNIQUE INDEX signing_key_one_active_uix
  ON signing_key (CASE WHEN status = 'ACTIVE' THEN 1 END);

GRANT SELECT, INSERT ON signing_key TO APP_ADMIN;
GRANT UPDATE(status, rotated_at, revoked_at) ON signing_key TO APP_ADMIN;
-- APP_RUNTIME SELECT for ddl-validate + JWKS reads (V16 invariant)
GRANT SELECT ON signing_key TO APP_RUNTIME;

-- --------------------------------------------------------
-- 5f. api_key (tenant-scoped; VPD reattached in V20)
--     V7 columns; id and tenant_id now RAW(16).
-- --------------------------------------------------------
CREATE TABLE api_key (
  id              RAW(16)                  NOT NULL,
  tenant_id       RAW(16)                  NOT NULL,
  key_prefix      VARCHAR2(16)             NOT NULL,
  key_hash        VARCHAR2(255)            NOT NULL,
  name            VARCHAR2(256)            NOT NULL,
  scopes          CLOB                     NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_used_at    TIMESTAMP WITH TIME ZONE,
  expires_at      TIMESTAMP WITH TIME ZONE,
  revoked_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_api_key        PRIMARY KEY (id),
  CONSTRAINT uq_api_key_prefix UNIQUE (key_prefix),
  CONSTRAINT fk_api_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT ck_api_key_scopes_json CHECK (scopes IS JSON)
);

-- Grants from V7 (column-scoped UPDATE on last_used_at only for APP_RUNTIME)
GRANT SELECT ON api_key TO APP_RUNTIME;
GRANT UPDATE(last_used_at) ON api_key TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_key TO APP_ADMIN;

-- --------------------------------------------------------
-- 5g. credential (tenant-scoped; VPD reattached in V20)
--     V2 columns; id and tenant_id now RAW(16).
-- --------------------------------------------------------
CREATE TABLE credential (
  id               RAW(16)                  NOT NULL,
  tenant_id        RAW(16)                  NOT NULL,
  user_handle      RAW(64)                  NOT NULL,
  credential_id    RAW(1023)                NOT NULL,
  public_key       BLOB                     NOT NULL,
  sign_count       NUMBER(19,0)             DEFAULT 0 NOT NULL,
  aaguid           RAW(16),
  transports       VARCHAR2(128),
  attestation_fmt  VARCHAR2(64),
  backup_state     CLOB,
  created_at       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_used_at     TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_credential        PRIMARY KEY (id),
  CONSTRAINT uq_credential_id     UNIQUE (tenant_id, credential_id),
  CONSTRAINT fk_credential_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT ck_credential_backup_state CHECK (backup_state IS NULL OR backup_state IS JSON)
);

CREATE INDEX ix_credential_tenant_user ON credential(tenant_id, user_handle);

GRANT SELECT, INSERT, UPDATE, DELETE ON credential TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON credential TO APP_ADMIN;

-- --------------------------------------------------------
-- 5h. audit_log (cross-tenant; no VPD; append-only chain)
--     V10 columns; id and actor_id now RAW(16).
--     No FK on actor_id (admin_user rows may be deleted; audit
--     stays; actor_email denormalizes the reference — V10 design).
-- --------------------------------------------------------
CREATE TABLE audit_log (
  id           RAW(16)                  NOT NULL,
  prev_hash    RAW(32),
  hash         RAW(32)                  NOT NULL,
  actor_id     RAW(16)                  NOT NULL,
  actor_email  VARCHAR2(255)            NOT NULL,
  action       VARCHAR2(64)             NOT NULL,
  target_type  VARCHAR2(32),
  target_id    VARCHAR2(64),
  payload      CLOB                     NOT NULL,
  created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_audit_log PRIMARY KEY (id),
  CONSTRAINT ck_audit_log_payload_json CHECK (payload IS JSON)
);

CREATE INDEX audit_log_created_at_ix ON audit_log(created_at);
CREATE INDEX audit_log_actor_ix      ON audit_log(actor_id, created_at);
CREATE INDEX audit_log_target_ix     ON audit_log(target_type, target_id, created_at);

-- Append-only: APP_ADMIN SELECT + INSERT only; no UPDATE, no DELETE (V10/V13)
GRANT SELECT, INSERT ON audit_log TO APP_ADMIN;
-- APP_RUNTIME SELECT for Hibernate ddl-validate (V13 invariant)
GRANT SELECT ON audit_log TO APP_RUNTIME;

-- ------------------------------------------------------------
-- STEP 6: Re-seed sentinel / singleton rows
-- ------------------------------------------------------------

-- 6a. AUDIT_CHAIN_LOCK sentinel in scheduler_lease (V14 invariant).
--     Synthetic UUID: 0x...0000A (arbitrary, memorable).
MERGE INTO scheduler_lease tgt
USING (SELECT 'AUDIT_CHAIN_LOCK' AS name FROM dual) src
ON (tgt.name = src.name)
WHEN NOT MATCHED THEN
  INSERT (id, name, holder, expires_at)
  VALUES (HEXTORAW('0000000000000000000000000000000A'),
          'AUDIT_CHAIN_LOCK', 'audit-system',
          TIMESTAMP '1970-01-01 00:00:00 UTC');

-- 6b. mds_blob_cache singleton row (V17 invariant).
--     Synthetic UUID: 0x...0001 so MdsBlobStore MERGE targets it.
MERGE INTO mds_blob_cache USING dual
ON (id = HEXTORAW('00000000000000000000000000000001'))
WHEN NOT MATCHED THEN
  INSERT (id, version, next_update, fetched_at, blob_jwt)
  VALUES (HEXTORAW('00000000000000000000000000000001'),
          0, DATE '1970-01-01',
          TIMESTAMP '1970-01-01 00:00:00 +00:00', '{}');

-- 6c. admin_user seed (V11 invariant): same bcrypt hashes preserved.
--     Synthetic UUIDs: alice = 0x...0010, bob = 0x...0011.
MERGE INTO admin_user tgt
USING (SELECT 'alice@crosscert.com' AS email FROM dual) src
ON (tgt.email = src.email)
WHEN NOT MATCHED THEN
  INSERT (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (HEXTORAW('00000000000000000000000000000010'),
          'alice@crosscert.com',
          '$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS',
          'ADMIN', 'Y', SYSTIMESTAMP);

MERGE INTO admin_user tgt
USING (SELECT 'bob@crosscert.com' AS email FROM dual) src
ON (tgt.email = src.email)
WHEN NOT MATCHED THEN
  INSERT (id, email, bcrypt_hash, role, enabled, created_at)
  VALUES (HEXTORAW('00000000000000000000000000000011'),
          'bob@crosscert.com',
          '$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G',
          'VIEWER', 'Y', SYSTIMESTAMP);

COMMIT;

-- ------------------------------------------------------------
-- STEP 7: Recreate packages that were dropped in Step 1
-- (Updated to use RAW(16) id types where applicable)
-- ------------------------------------------------------------

-- 7a. api_key_lookup_pkg (V8 invariant; id type updated to RAW(16))
CREATE OR REPLACE PACKAGE APP_OWNER.api_key_lookup_pkg AUTHID DEFINER AS
  TYPE api_key_row IS RECORD (
    id            RAW(16),
    tenant_id     RAW(16),
    key_hash      VARCHAR2(255),
    expires_at    TIMESTAMP WITH TIME ZONE,
    revoked_at    TIMESTAMP WITH TIME ZONE
  );

  PROCEDURE find_by_prefix(
    p_prefix       IN  VARCHAR2,
    p_found        OUT NUMBER,
    p_id           OUT RAW,
    p_tenant_id    OUT RAW,
    p_key_hash     OUT VARCHAR2,
    p_expires_at   OUT TIMESTAMP WITH TIME ZONE,
    p_revoked_at   OUT TIMESTAMP WITH TIME ZONE);

  PROCEDURE touch_last_used(p_id IN RAW, p_now IN TIMESTAMP WITH TIME ZONE);
END api_key_lookup_pkg;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.api_key_lookup_pkg AS

  PROCEDURE find_by_prefix(
    p_prefix       IN  VARCHAR2,
    p_found        OUT NUMBER,
    p_id           OUT RAW,
    p_tenant_id    OUT RAW,
    p_key_hash     OUT VARCHAR2,
    p_expires_at   OUT TIMESTAMP WITH TIME ZONE,
    p_revoked_at   OUT TIMESTAMP WITH TIME ZONE) IS
  BEGIN
    SELECT id, tenant_id, key_hash, expires_at, revoked_at
      INTO p_id, p_tenant_id, p_key_hash, p_expires_at, p_revoked_at
      FROM api_key
     WHERE key_prefix = p_prefix;
    p_found := 1;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      p_found := 0;
      p_id := NULL;
      p_tenant_id := NULL;
      p_key_hash := NULL;
      p_expires_at := NULL;
      p_revoked_at := NULL;
  END find_by_prefix;

  PROCEDURE touch_last_used(p_id IN RAW, p_now IN TIMESTAMP WITH TIME ZONE) IS
  BEGIN
    UPDATE api_key
       SET last_used_at = p_now
     WHERE id = p_id
       AND tenant_id = HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID'));
  END touch_last_used;

END api_key_lookup_pkg;
/

GRANT EXECUTE ON APP_OWNER.api_key_lookup_pkg TO APP_RUNTIME;
GRANT EXECUTE ON APP_OWNER.api_key_lookup_pkg TO APP_ADMIN;

-- 7b. signing_key_bootstrap_pkg (V18 invariant; id type updated to RAW(16))
CREATE OR REPLACE PACKAGE APP_OWNER.signing_key_bootstrap_pkg
  AUTHID DEFINER
AS
  PROCEDURE bootstrap_active(
    p_kid           IN  VARCHAR2,
    p_alg           IN  VARCHAR2,
    p_public_jwk    IN  CLOB,
    p_private_pkcs8 IN  BLOB,
    p_inserted      OUT NUMBER);
END signing_key_bootstrap_pkg;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.signing_key_bootstrap_pkg AS

  PROCEDURE bootstrap_active(
    p_kid           IN  VARCHAR2,
    p_alg           IN  VARCHAR2,
    p_public_jwk    IN  CLOB,
    p_private_pkcs8 IN  BLOB,
    p_inserted      OUT NUMBER) IS
    v_count NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_count FROM signing_key WHERE status = 'ACTIVE';
    IF v_count > 0 THEN
      p_inserted := 0;
      RETURN;
    END IF;
    -- UUID generated by SYS_GUID() for bootstrap row (app not involved here)
    INSERT INTO signing_key (id, kid, alg, status, public_jwk, private_pkcs8, created_at)
      VALUES (SYS_GUID(), p_kid, p_alg, 'ACTIVE',
              p_public_jwk, p_private_pkcs8, SYSTIMESTAMP);
    p_inserted := 1;
  EXCEPTION
    WHEN DUP_VAL_ON_INDEX THEN
      p_inserted := 0;
  END bootstrap_active;

END signing_key_bootstrap_pkg;
/

GRANT EXECUTE ON APP_OWNER.signing_key_bootstrap_pkg TO APP_RUNTIME;

-- ============================================================
-- V19 complete. VPD reattachment deferred to V20.
-- ============================================================
