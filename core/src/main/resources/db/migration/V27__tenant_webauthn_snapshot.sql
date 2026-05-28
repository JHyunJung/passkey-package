-- ============================================================
-- V27 — TENANT_WEBAUTHN_SNAPSHOT (append-only 히스토리)
--
-- Note: V21 패턴 따름 — CREATE TABLE 끝에 ; 필수 (Flyway Oracle 파서 요건).
--       첫 번째 RAW 컬럼에 DEFAULT SYS_GUID() 배치.
-- ============================================================

CREATE SEQUENCE tenant_webauthn_snapshot_seq
  START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE tenant_webauthn_snapshot (
  id                        NUMBER(19,0)  NOT NULL,
  tenant_id                 RAW(16)       DEFAULT SYS_GUID() NOT NULL,
  rp_id                     VARCHAR2(256)                    NOT NULL,
  rp_name                   VARCHAR2(256)                    NOT NULL,
  allowed_origins           CLOB                             NOT NULL,
  accepted_formats          CLOB                             NOT NULL,
  require_user_verification CHAR(1)       DEFAULT 'N'        NOT NULL,
  mds_required              CHAR(1)       DEFAULT 'N'        NOT NULL,
  taken_at                  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  taken_by                  VARCHAR2(255),
  CONSTRAINT pk_tenant_webauthn_snapshot PRIMARY KEY (id),
  CONSTRAINT fk_tenant_webauthn_snapshot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
  CONSTRAINT ck_tenant_snapshot_uv CHECK (require_user_verification IN ('Y', 'N')),
  CONSTRAINT ck_tenant_snapshot_mds CHECK (mds_required IN ('Y', 'N'))
);

CREATE INDEX tenant_webauthn_snapshot_tenant_taken_ix
  ON tenant_webauthn_snapshot (tenant_id, taken_at);

INSERT INTO tenant_webauthn_snapshot (
  id, tenant_id, rp_id, rp_name, allowed_origins, accepted_formats,
  require_user_verification, mds_required, taken_at, taken_by
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
  SYSTIMESTAMP,
  'migration:v27'
FROM tenant t
WHERE NOT EXISTS (
  SELECT 1 FROM tenant_webauthn_snapshot s WHERE s.tenant_id = t.id
);

GRANT SELECT, INSERT ON tenant_webauthn_snapshot TO APP_ADMIN;
GRANT SELECT ON tenant_webauthn_snapshot_seq TO APP_ADMIN;
