-- ============================================================
-- V27 — TENANT_WEBAUTHN_SNAPSHOT (append-only 히스토리)
--
-- 목적: WebAuthn config 변경 직전 스냅샷 저장.
--   diff 미리보기 + 운영 사고 추적.
--
-- 신규 tenant 의 초기 snapshot 은 TenantAdminService.create 가 INSERT.
-- 기존 tenant 는 아래 NOT EXISTS 절로 migration 시 자동 초기 snapshot 생성.
--
-- Idempotency:
--   CREATE SEQUENCE/TABLE → ORA-00955 를 EXCEPTION 으로 swallow.
--   CREATE INDEX → ORA-00955 를 EXCEPTION 으로 swallow.
--   INSERT → NOT EXISTS 절로 멱등.
-- ============================================================

-- 1. SEQUENCE
BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE tenant_webauthn_snapshot_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;  -- ORA-00955: sequence already exists
    ELSE RAISE;
    END IF;
END;
/

-- 2. TABLE
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE tenant_webauthn_snapshot (
      id                        NUMBER(19,0)             NOT NULL,
      tenant_id                 RAW(16)                  NOT NULL,
      rp_id                     VARCHAR2(256)            NOT NULL,
      rp_name                   VARCHAR2(256)            NOT NULL,
      allowed_origins           CLOB                     NOT NULL,
      accepted_formats          CLOB                     NOT NULL,
      require_user_verification CHAR(1)                  NOT NULL,
      mds_required              CHAR(1)                  NOT NULL,
      taken_at                  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
      taken_by                  VARCHAR2(255),
      CONSTRAINT pk_tenant_webauthn_snapshot PRIMARY KEY (id),
      CONSTRAINT fk_tenant_webauthn_snapshot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
      CONSTRAINT ck_tenant_snapshot_origins_json CHECK (allowed_origins IS JSON),
      CONSTRAINT ck_tenant_snapshot_formats_json CHECK (accepted_formats IS JSON),
      CONSTRAINT ck_tenant_snapshot_uv CHECK (require_user_verification IN ('Y', 'N')),
      CONSTRAINT ck_tenant_snapshot_mds CHECK (mds_required IN ('Y', 'N'))
    )
  ]';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;  -- ORA-00955: table already exists
    ELSE RAISE;
    END IF;
END;
/

-- 3. INDEX: tenant_id + taken_at DESC — per-tenant 최신 snapshot 조회 최적화
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX tenant_webauthn_snapshot_tenant_taken_ix ON tenant_webauthn_snapshot (tenant_id, taken_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;  -- ORA-00955: index already exists
    ELSE RAISE;
    END IF;
END;
/

-- 4. 기존 tenant 의 초기 snapshot 자동 INSERT (멱등: NOT EXISTS)
INSERT INTO tenant_webauthn_snapshot (
  id, tenant_id, rp_id, rp_name, allowed_origins, accepted_formats,
  require_user_verification, mds_required, taken_by
)
SELECT
  tenant_webauthn_snapshot_seq.NEXTVAL,
  t.id,
  t.rp_id,
  t.rp_name,
  NVL(
    (SELECT '[' || LISTAGG('"' || o.origin || '"', ',') WITHIN GROUP (ORDER BY o.sort_order) || ']'
       FROM tenant_allowed_origin o WHERE o.tenant_id = t.id),
    '[]'
  ),
  NVL(
    (SELECT '[' || LISTAGG('"' || f.format || '"', ',') WITHIN GROUP (ORDER BY f.format) || ']'
       FROM tenant_accepted_format f WHERE f.tenant_id = t.id),
    '[]'
  ),
  t.require_user_verification,
  t.mds_required,
  'migration:v27'
FROM tenant t
WHERE NOT EXISTS (
  SELECT 1 FROM tenant_webauthn_snapshot s WHERE s.tenant_id = t.id
);

-- 5. 권한 — APP_ADMIN (admin-app CRUD, diff endpoint)
--    APP_RUNTIME SELECT 는 V28 (runtime_grants 패턴, 기존 V12/V13/V16 과 일관)
GRANT SELECT, INSERT ON tenant_webauthn_snapshot TO APP_ADMIN;
GRANT SELECT ON tenant_webauthn_snapshot_seq TO APP_ADMIN;
