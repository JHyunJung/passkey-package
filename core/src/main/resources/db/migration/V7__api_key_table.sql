-- API Key table. Tenant-scoped: each row is owned by exactly one
-- tenant, and VPD enforces that APP_RUNTIME sessions only see their
-- own tenant's keys for normal queries. V8 attaches that policy.
--
-- Storage model (Phase 1 spec §6.2):
--   - key_prefix is the first 11 chars of the full key ("pk_" + 8B
--     base64url), indexed for fast lookup at auth time.
--   - key_hash stores the BCrypt hash of the secret portion.
--   - scopes is a JSON array (Phase 1 stores but does not enforce).
--
-- Uniqueness: GLOBAL on key_prefix (not per-tenant). Reason: the
-- inbound X-API-Key header carries no tenant id, so the auth filter
-- must look up the prefix without yet knowing the tenant. A
-- definer-rights function (V8) handles that lookup; for it to be
-- deterministic, prefixes must be unique across the entire table.
-- Random base64url 8-byte prefixes give a 64^8 = 2.8e14 namespace;
-- the API Key issuer (admin-app, Phase 2) regenerates on collision.

CREATE SEQUENCE api_key_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE api_key (
  id              NUMBER(19,0)             NOT NULL,
  tenant_id       VARCHAR2(64)             NOT NULL,
  key_prefix      VARCHAR2(16)             NOT NULL,
  key_hash        VARCHAR2(255)            NOT NULL,
  name            VARCHAR2(256)            NOT NULL,
  scopes          CLOB                     NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_used_at    TIMESTAMP WITH TIME ZONE,
  expires_at      TIMESTAMP WITH TIME ZONE,
  revoked_at      TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_api_key PRIMARY KEY (id),
  CONSTRAINT uq_api_key_prefix UNIQUE (key_prefix),
  CONSTRAINT fk_api_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT ck_api_key_scopes_json CHECK (scopes IS JSON)
);

-- Grants. APP_RUNTIME has SELECT for normal VPD-filtered queries +
-- column-scoped UPDATE on last_used_at only. No other column is
-- writable from the runtime path — preventing a hijacked runtime
-- from rotating key_hash, flipping scopes, or undoing revocation
-- on a same-tenant row.
GRANT SELECT ON api_key TO APP_RUNTIME;
GRANT UPDATE(last_used_at) ON api_key TO APP_RUNTIME;
GRANT SELECT ON api_key_seq TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_key TO APP_ADMIN;
GRANT SELECT ON api_key_seq TO APP_ADMIN;
