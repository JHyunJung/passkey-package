-- Add per-tenant WebAuthn RP configuration columns to the existing
-- tenant table. Phase 0 created tenant with just id/display_name/
-- status/created_at/updated_at; Phase 1 needs each tenant to declare
-- its WebAuthn RP identity (rp_id, rp_name), its allowed origins
-- whitelist, and an attestation policy JSON.
--
-- Columns are nullable so this migration is safe to apply to a
-- populated tenant table (Phase 0 test tenants). Backfill with
-- safe defaults in the same migration.

ALTER TABLE tenant ADD (
  rp_id              VARCHAR2(256),
  rp_name            VARCHAR2(256),
  allowed_origins    CLOB,
  attestation_policy CLOB
);

ALTER TABLE tenant ADD CONSTRAINT ck_tenant_origins_json
  CHECK (allowed_origins IS NULL OR allowed_origins IS JSON);
ALTER TABLE tenant ADD CONSTRAINT ck_tenant_attest_policy_json
  CHECK (attestation_policy IS NULL OR attestation_policy IS JSON);

-- Backfill: any pre-existing tenant rows get safe local-dev defaults.
-- New tenant inserts (admin-app, Phase 2) are responsible for setting
-- explicit values.
UPDATE tenant
SET rp_id = 'localhost',
    rp_name = display_name,
    allowed_origins = '["http://localhost:8080","http://localhost:8082"]',
    attestation_policy =
      '{"acceptedFormats":["none","packed","android-key","android-safetynet","fido-u2f","apple","tpm"],"requireUserVerification":true,"mdsRequired":false}'
WHERE rp_id IS NULL;

-- After backfill, make rp_id/rp_name/allowed_origins NOT NULL so
-- future inserts must populate them. attestation_policy stays
-- nullable so the application can fall back to a hardcoded default
-- when a tenant has no policy set.
ALTER TABLE tenant MODIFY (
  rp_id              NOT NULL,
  rp_name            NOT NULL,
  allowed_origins    NOT NULL
);
