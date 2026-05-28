-- ============================================================
-- V26 — Tenant AAGUID Policy — V21 패턴(세미콜론 포함) 따름
-- ============================================================

-- 1. TENANT_AAGUID_POLICY
CREATE TABLE tenant_aaguid_policy (
  tenant_id    RAW(16)        DEFAULT SYS_GUID() NOT NULL,
  policy_mode         VARCHAR2(16)                      NOT NULL,
  mds_strict   CHAR(1)        DEFAULT 'N'        NOT NULL,
  created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_by   VARCHAR2(255),
  CONSTRAINT pk_tenant_aaguid_policy PRIMARY KEY (tenant_id),
  CONSTRAINT fk_tenant_aaguid_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
  CONSTRAINT ck_tenant_aaguid_policy_mode CHECK (policy_mode IN ('ANY', 'ALLOWLIST', 'DENYLIST')),
  CONSTRAINT ck_tenant_aaguid_mds_strict CHECK (mds_strict IN ('Y', 'N'))
);

-- 2. TENANT_AAGUID_POLICY_ENTRY
CREATE TABLE tenant_aaguid_policy_entry (
  tenant_id    RAW(16)        DEFAULT SYS_GUID() NOT NULL,
  aaguid       RAW(16)        DEFAULT SYS_GUID() NOT NULL,
  note         VARCHAR2(256),
  CONSTRAINT pk_tenant_aaguid_policy_entry PRIMARY KEY (tenant_id, aaguid),
  CONSTRAINT fk_tenant_aaguid_policy_entry_policy FOREIGN KEY (tenant_id) REFERENCES tenant_aaguid_policy(tenant_id) ON DELETE CASCADE
);

-- 3. 기존 tenant 에 대해 default policy 자동 생성 (policy_mode=ANY, mds_strict=N)
INSERT INTO tenant_aaguid_policy (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
SELECT t.id, 'ANY', 'N', SYSTIMESTAMP, SYSTIMESTAMP, 'migration:v26'
FROM tenant t
WHERE NOT EXISTS (
  SELECT 1 FROM tenant_aaguid_policy p WHERE p.tenant_id = t.id
);

-- 4. 권한
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_aaguid_policy TO APP_ADMIN;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_aaguid_policy_entry TO APP_ADMIN;
