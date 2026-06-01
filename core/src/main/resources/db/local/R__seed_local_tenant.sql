-- ============================================================
-- R__seed_local_tenant.sql — local 프로필 전용 테넌트 시드 (repeatable)
--
-- ⚠️ local 전용. rpId=localhost 라 완전 로컬(http://localhost:9090)에서
--    패스키 등록/인증 ceremony 가 동작한다. dev/qa/prod 는 이 디렉터리 미포함.
--
-- 테넌트 demo-rp (tenantId 0000...C0DE — 테스트 픽스처와 동일 값).
-- API key: pk_devlocal + plaintext "dev_local_secret_known_plaintext_for_test_only".
--   전체 X-API-Key: pk_devlocaldev_local_secret_known_plaintext_for_test_only
--
-- aaguid_policy(ANY)·snapshot 도 직접 시드 — V26/V27 백필은 이미 적용 완료라
-- 새 tenant 를 따라오지 않으므로.
--
-- VPD: api_key INSERT 전후로 CTX_PKG.set_tenant/clear_tenant 필요.
-- Idempotent: 모든 INSERT NOT EXISTS 가드.
-- ============================================================

-- 1. tenant: demo-rp
INSERT INTO tenant (
  id, slug, display_name, rp_id, rp_name, status,
  require_user_verification, mds_required, created_at, updated_at
)
SELECT
  HEXTORAW('0000000000000000000000000000C0DE'),
  'demo-rp', 'Demo RP', 'localhost', 'Demo RP',
  'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM tenant
   WHERE id = HEXTORAW('0000000000000000000000000000C0DE') OR slug = 'demo-rp'
);

-- 2. allowed_origin
INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
SELECT SYS_GUID(), (SELECT id FROM tenant WHERE slug = 'demo-rp'),
       'http://localhost:9090', 0
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
       AND origin = 'http://localhost:9090'
  );

-- 3. accepted_format: none + packed
INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(), (SELECT id FROM tenant WHERE slug = 'demo-rp'), 'none'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp') AND format = 'none'
  );

INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(), (SELECT id FROM tenant WHERE slug = 'demo-rp'), 'packed'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp') AND format = 'packed'
  );

-- 4. aaguid_policy (ANY) — V26 백필 대체
INSERT INTO tenant_aaguid_policy (tenant_id, policy_mode, mds_strict, created_at, updated_at, updated_by)
SELECT (SELECT id FROM tenant WHERE slug = 'demo-rp'), 'ANY', 'N',
       SYSTIMESTAMP, SYSTIMESTAMP, 'seed:local'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_aaguid_policy
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
  );

-- 5. webauthn_snapshot — V27 백필 대체 (컬럼명/구조 V27 동일)
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
  t.require_user_verification, t.mds_required, SYSTIMESTAMP, 'seed:local'
FROM tenant t
WHERE t.slug = 'demo-rp'
  AND NOT EXISTS (
    SELECT 1 FROM tenant_webauthn_snapshot s WHERE s.tenant_id = t.id
  );

-- 6. api_key (VPD 컨텍스트 필요)
BEGIN
  APP_OWNER.CTX_PKG.set_tenant('0000000000000000000000000000C0DE');
END;
/

INSERT INTO api_key (id, tenant_id, key_prefix, key_hash, name, created_at)
SELECT
  HEXTORAW('0000000000000000000000000000CAFE'),
  HEXTORAW('0000000000000000000000000000C0DE'),
  'pk_devlocal',
  '$2a$12$KZVGGNjMziJkHl3yF7P1GuVEv9.1wF.WznMfgP3oeAeL5ktuHB8WK',
  'local seed — demo-rp',
  SYSTIMESTAMP
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE id = HEXTORAW('0000000000000000000000000000C0DE'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key WHERE key_prefix = 'pk_devlocal'
        OR id = HEXTORAW('0000000000000000000000000000CAFE')
  );

BEGIN
  APP_OWNER.CTX_PKG.clear_tenant;
END;
/

-- 7. api_key_scope: registration + authentication
INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(), HEXTORAW('0000000000000000000000000000CAFE'), 'registration'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('0000000000000000000000000000CAFE'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('0000000000000000000000000000CAFE') AND scope = 'registration'
  );

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(), HEXTORAW('0000000000000000000000000000CAFE'), 'authentication'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('0000000000000000000000000000CAFE'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('0000000000000000000000000000CAFE') AND scope = 'authentication'
  );

COMMIT;
