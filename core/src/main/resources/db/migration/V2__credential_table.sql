-- Tenant-scoped: tenant_id 컬럼 + VPD policy 대상.
-- 컬럼 정의만. Phase 1에서 도메인 로직 추가.
--
-- Timestamps use TIMESTAMP WITH TIME ZONE (consistent with V1).

CREATE SEQUENCE credential_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE credential (
  -- NUMBER(19,0) sized to match Java long via Hibernate BIGINT mapping.
  id               NUMBER(19,0)             NOT NULL,
  tenant_id        VARCHAR2(64)             NOT NULL,
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
  CONSTRAINT pk_credential PRIMARY KEY (id),
  CONSTRAINT uq_credential_id UNIQUE (tenant_id, credential_id),
  CONSTRAINT fk_credential_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT ck_credential_backup_state CHECK (backup_state IS NULL OR backup_state IS JSON)
);

CREATE INDEX ix_credential_tenant_user ON credential(tenant_id, user_handle);

GRANT SELECT, INSERT, UPDATE, DELETE ON credential TO APP_RUNTIME;
GRANT SELECT ON credential_seq TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON credential TO APP_ADMIN;
GRANT SELECT ON credential_seq TO APP_ADMIN;
