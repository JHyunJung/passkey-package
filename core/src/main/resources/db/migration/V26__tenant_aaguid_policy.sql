-- ============================================================
-- V26 — Tenant AAGUID Policy
--
-- 목적: Tenant 별 AAGUID 허용/차단 정책.
--   - mode: ANY | ALLOWLIST | DENYLIST
--   - mds_strict: Y/N — MDS 에 없는 AAGUID 거부 여부
--   - entry 테이블 분리 — 정책 모드/strict 와 list 의 라이프사이클이 다름
--
-- VPD: 두 테이블 모두 tenant_id 컬럼이 있어 V20 VPD policy 대상.
-- Grants:
--   APP_USER (passkey-app) — 등록 ceremony 에서 SELECT 필요.
--   APP_ADMIN (admin-app)  — CRUD 필요.
--   APP_RUNTIME — Hibernate ddl-validate 위해 SELECT (별도 V28 파일로 분리,
--                 기존 V12/V13/V16 runtime_grants 패턴 따름).
--
-- Idempotency:
--   CREATE TABLE → ORA-00955 (name already used) 를 EXCEPTION 으로 swallow.
--   INSERT → NOT EXISTS 절로 멱등.
-- ============================================================

-- 1. TENANT_AAGUID_POLICY 테이블
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE tenant_aaguid_policy (
      tenant_id    RAW(16)                  NOT NULL,
      mode         VARCHAR2(16)             NOT NULL,
      mds_strict   CHAR(1)                  DEFAULT 'N' NOT NULL,
      created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
      updated_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
      updated_by   VARCHAR2(255),
      CONSTRAINT pk_tenant_aaguid_policy PRIMARY KEY (tenant_id),
      CONSTRAINT fk_tenant_aaguid_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
      CONSTRAINT ck_tenant_aaguid_mode CHECK (mode IN ('ANY', 'ALLOWLIST', 'DENYLIST')),
      CONSTRAINT ck_tenant_aaguid_mds_strict CHECK (mds_strict IN ('Y', 'N'))
    )
  ]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;  -- ORA-00955: table already exists
    ELSE RAISE;
    END IF;
END;
/

-- 2. TENANT_AAGUID_POLICY_ENTRY 테이블
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE tenant_aaguid_policy_entry (
      tenant_id    RAW(16)        NOT NULL,
      aaguid       RAW(16)        NOT NULL,
      note         VARCHAR2(256),
      CONSTRAINT pk_tenant_aaguid_policy_entry PRIMARY KEY (tenant_id, aaguid),
      CONSTRAINT fk_tenant_aaguid_policy_entry_policy FOREIGN KEY (tenant_id) REFERENCES tenant_aaguid_policy(tenant_id) ON DELETE CASCADE
    )
  ]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;  -- ORA-00955: table already exists
    ELSE RAISE;
    END IF;
END;
/

-- 3. 기존 tenant 에 대해 default policy 자동 생성 (mode=ANY, mds_strict=N)
--    멱등: NOT EXISTS 절 (Oracle 표준 — ON CONFLICT 없음)
INSERT INTO tenant_aaguid_policy (tenant_id, mode, mds_strict, updated_by)
SELECT t.id, 'ANY', 'N', 'migration:v26'
FROM tenant t
WHERE NOT EXISTS (
  SELECT 1 FROM tenant_aaguid_policy p WHERE p.tenant_id = t.id
);

-- 4. 권한 — APP_USER (passkey-app 등록 ceremony READ) + APP_ADMIN (admin-app CRUD)
--    APP_RUNTIME SELECT 는 V28 (runtime_grants 패턴, 기존 V12/V13/V16 과 일관)
GRANT SELECT ON tenant_aaguid_policy TO APP_USER;
GRANT SELECT ON tenant_aaguid_policy_entry TO APP_USER;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_aaguid_policy TO APP_ADMIN;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_aaguid_policy_entry TO APP_ADMIN;
