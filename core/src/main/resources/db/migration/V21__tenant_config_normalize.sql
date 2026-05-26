-- ============================================================
-- Phase 7 — Tenant config + ApiKey scopes normalization.
-- ALTER tenant: drop allowed_origins + attestation_policy CLOBs,
-- add require_user_verification + mds_required flag columns.
-- ALTER api_key: drop scopes CLOB.
-- CREATE 3 child tables (tenant_allowed_origin, tenant_accepted_format,
-- api_key_scope) with UUID PK + parent FK + CHECK constraint whitelists.
--
-- Assumption: operational data absent (Phase 6 invariant carried forward).
-- ============================================================

-- 1. tenant — drop 2 JSON CLOB columns and their CHECKs
ALTER TABLE tenant DROP CONSTRAINT ck_tenant_origins_json;
ALTER TABLE tenant DROP CONSTRAINT ck_tenant_attest_policy_json;
ALTER TABLE tenant DROP COLUMN allowed_origins;
ALTER TABLE tenant DROP COLUMN attestation_policy;

-- 2. tenant — add boolean flag columns (CHAR(1) with CHECK)
ALTER TABLE tenant ADD (
  require_user_verification CHAR(1) DEFAULT 'Y' NOT NULL,
  mds_required              CHAR(1) DEFAULT 'N' NOT NULL,
  CONSTRAINT ck_tenant_uv  CHECK (require_user_verification IN ('Y','N')),
  CONSTRAINT ck_tenant_mds CHECK (mds_required IN ('Y','N'))
);

-- 3. tenant_allowed_origin (1:N child)
CREATE TABLE tenant_allowed_origin (
  id          RAW(16)        DEFAULT SYS_GUID() NOT NULL,
  tenant_id   RAW(16)                           NOT NULL,
  origin      VARCHAR2(512)                     NOT NULL,
  sort_order  NUMBER(5,0)    DEFAULT 0          NOT NULL,
  CONSTRAINT pk_tenant_allowed_origin PRIMARY KEY (id),
  CONSTRAINT fk_tao_tenant            FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
  CONSTRAINT uq_tao_tenant_origin     UNIQUE (tenant_id, origin),
  CONSTRAINT ck_tao_origin_format
    CHECK (REGEXP_LIKE(origin, '^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$'))
);
CREATE INDEX ix_tao_tenant ON tenant_allowed_origin (tenant_id);

GRANT SELECT ON tenant_allowed_origin TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_allowed_origin TO APP_ADMIN;

-- 4. tenant_accepted_format (1:N child)
CREATE TABLE tenant_accepted_format (
  id          RAW(16)       DEFAULT SYS_GUID() NOT NULL,
  tenant_id   RAW(16)                          NOT NULL,
  format      VARCHAR2(32)                     NOT NULL,
  CONSTRAINT pk_tenant_accepted_format PRIMARY KEY (id),
  CONSTRAINT fk_taf_tenant             FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
  CONSTRAINT uq_taf_tenant_format      UNIQUE (tenant_id, format),
  CONSTRAINT ck_taf_format CHECK (format IN
    ('none','packed','android-key','android-safetynet','fido-u2f','apple','tpm'))
);
CREATE INDEX ix_taf_tenant ON tenant_accepted_format (tenant_id);

GRANT SELECT ON tenant_accepted_format TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_accepted_format TO APP_ADMIN;

-- 5. api_key — drop scopes CLOB
ALTER TABLE api_key DROP CONSTRAINT ck_api_key_scopes_json;
ALTER TABLE api_key DROP COLUMN scopes;

-- 6. api_key_scope (1:N child)
CREATE TABLE api_key_scope (
  id          RAW(16)       DEFAULT SYS_GUID() NOT NULL,
  api_key_id  RAW(16)                          NOT NULL,
  scope       VARCHAR2(32)                     NOT NULL,
  CONSTRAINT pk_api_key_scope     PRIMARY KEY (id),
  CONSTRAINT fk_aks_api_key       FOREIGN KEY (api_key_id) REFERENCES api_key(id) ON DELETE CASCADE,
  CONSTRAINT uq_aks_api_key_scope UNIQUE (api_key_id, scope),
  CONSTRAINT ck_aks_scope CHECK (scope IN ('registration','authentication','admin'))
);
CREATE INDEX ix_aks_api_key ON api_key_scope (api_key_id);

GRANT SELECT ON api_key_scope TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_key_scope TO APP_ADMIN;
