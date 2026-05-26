-- Signing key store with envelope-encrypted PKCS8 private keys.
-- Phase 3 (T1): introduces persistent ID Token signing keys so server
-- restarts no longer invalidate previously-issued JWTs.
--
-- Key lifecycle:
--   ACTIVE  → ROTATED (admin manual rotate; new ACTIVE inserted)
--   ROTATED → REVOKED (KeyExpirationJob after grace period, default 30min)
--
-- JWKS exposes ACTIVE + ROTATED so RPs holding JWTs signed by the old
-- key can still verify during grace. REVOKED is hidden.

CREATE SEQUENCE signing_key_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE signing_key (
  id              NUMBER(19,0)             NOT NULL,
  kid             VARCHAR2(64)             NOT NULL,
  alg             VARCHAR2(16)             NOT NULL,
  status          VARCHAR2(16)             NOT NULL,
  public_jwk      CLOB                     NOT NULL,
  private_pkcs8   BLOB                     NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  rotated_at      TIMESTAMP WITH TIME ZONE,
  revoked_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_signing_key PRIMARY KEY (id),
  CONSTRAINT uq_signing_key_kid UNIQUE (kid),
  CONSTRAINT ck_signing_key_status CHECK (status IN ('ACTIVE','ROTATED','REVOKED')),
  CONSTRAINT ck_signing_key_alg CHECK (alg IN ('RS256')),
  CONSTRAINT ck_signing_key_public_jwk_json CHECK (public_jwk IS JSON)
);

CREATE INDEX signing_key_status_ix ON signing_key(status);

-- Enforce at most one ACTIVE key at any time.
-- Oracle function-based unique index: only rows where status='ACTIVE'
-- project the literal 1; all other statuses project NULL, which is
-- excluded from unique-index coverage (NULL != NULL in B-tree indexes).
-- Rotation flow: UPDATE old row status→ROTATED, then INSERT new ACTIVE;
-- the uniqueness window is within a single transaction.
CREATE UNIQUE INDEX signing_key_one_active_uix
  ON signing_key (CASE WHEN status = 'ACTIVE' THEN 1 END);

GRANT SELECT, INSERT ON signing_key TO APP_ADMIN;
GRANT UPDATE(status, rotated_at, revoked_at) ON signing_key TO APP_ADMIN;
GRANT SELECT ON signing_key_seq TO APP_ADMIN;
