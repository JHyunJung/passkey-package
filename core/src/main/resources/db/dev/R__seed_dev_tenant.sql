-- ============================================================
-- R__seed_dev_tenant.sql — dev 프로필 전용 테넌트 시드 (repeatable)
--
-- ⚠️ dev(서버 배포) 전용. rpId=dev-passkey.crosscert.com.
--    브라우저가 https://dev-passkey.crosscert.com 으로 접속할 때 패스키 동작.
--    완전 로컬(localhost)에서는 rpId 불일치로 ceremony 불가 — 그건 local 프로필.
--
-- 테넌트 dev-passkey (tenantId 7F00DEAD...DE7000001).
-- API key: pk_devsrv01 + plaintext "dev_server_secret_known_plaintext_for_test_only".
--   전체 X-API-Key: pk_devsrv01dev_server_secret_known_plaintext_for_test_only
--
-- aaguid_policy(ANY)·snapshot 직접 시드. VPD 컨텍스트 필요.
-- Idempotent: NOT EXISTS 가드.
-- ============================================================

-- 1. tenant: dev-passkey
INSERT INTO tenant (
  id, slug, display_name, rp_id, rp_name, status,
  require_user_verification, mds_required, created_at, updated_at
)
SELECT
  HEXTORAW('7F00DEAD000000000000000DE7000001'),
  'dev-passkey', 'Dev Passkey', 'dev-passkey.crosscert.com', 'Dev Passkey',
  'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM tenant
   WHERE id = HEXTORAW('7F00DEAD000000000000000DE7000001') OR slug = 'dev-passkey'
);

-- 2. allowed_origin: https://dev-passkey.crosscert.com
INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
SELECT SYS_GUID(), (SELECT id FROM tenant WHERE slug = 'dev-passkey'),
       'https://dev-passkey.crosscert.com', 0
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'dev-passkey')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'dev-passkey')
       AND origin = 'https://dev-passkey.crosscert.com'
  );

-- 3. accepted_format: none + packed
INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(), (SELECT id FROM tenant WHERE slug = 'dev-passkey'), 'none'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'dev-passkey')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'dev-passkey') AND format = 'none'
  );

INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(), (SELECT id FROM tenant WHERE slug = 'dev-passkey'), 'packed'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'dev-passkey')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'dev-passkey') AND format = 'packed'
  );

-- 4. aaguid_policy (ANY)
INSERT INTO tenant_aaguid_policy (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
SELECT (SELECT id FROM tenant WHERE slug = 'dev-passkey'), 'ANY', 'N',
       SYSTIMESTAMP, SYSTIMESTAMP, 'seed:dev'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'dev-passkey')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_aaguid_policy
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'dev-passkey')
  );

-- 5. webauthn_snapshot (컬럼명/구조 V27 동일)
INSERT INTO tenant_webauthn_snapshot (
  id, tenant_id, rp_id, rp_name, allowed_origins, accepted_formats,
  require_user_verification, mds_required, taken_at, taken_by
)
SELECT
  APP_OWNER.tenant_webauthn_snapshot_seq.NEXTVAL,
  t.id, t.rp_id, t.rp_name,
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
  t.require_user_verification, t.mds_required, SYSTIMESTAMP, 'seed:dev'
FROM tenant t
WHERE t.slug = 'dev-passkey'
  AND NOT EXISTS (
    SELECT 1 FROM tenant_webauthn_snapshot s WHERE s.tenant_id = t.id
  );

-- 6. api_key (VPD)
BEGIN
  APP_OWNER.CTX_PKG.set_tenant('7F00DEAD000000000000000DE7000001');
END;
/

INSERT INTO api_key (id, tenant_id, key_prefix, key_hash, name, created_at)
SELECT
  HEXTORAW('7F00DEAD000000000000000DE700AA01'),
  HEXTORAW('7F00DEAD000000000000000DE7000001'),
  'pk_devsrv01',
  '$2a$12$yYvKGSCLJdeRNZzM2/s17uJDEqEqQ4gnMEKBgGTSuY6ulh8jJda/C',
  'dev seed — dev-passkey',
  SYSTIMESTAMP
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE id = HEXTORAW('7F00DEAD000000000000000DE7000001'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key WHERE key_prefix = 'pk_devsrv01'
        OR id = HEXTORAW('7F00DEAD000000000000000DE700AA01')
  );

BEGIN
  APP_OWNER.CTX_PKG.clear_tenant;
END;
/

-- 7. api_key_scope
INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(), HEXTORAW('7F00DEAD000000000000000DE700AA01'), 'registration'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD000000000000000DE700AA01'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD000000000000000DE700AA01') AND scope = 'registration'
  );

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(), HEXTORAW('7F00DEAD000000000000000DE700AA01'), 'authentication'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD000000000000000DE700AA01'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD000000000000000DE700AA01') AND scope = 'authentication'
  );

COMMIT;
