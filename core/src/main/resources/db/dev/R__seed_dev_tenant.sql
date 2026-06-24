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
-- RP_ADMIN(bob) 도 이 테넌트에 바인딩해 함께 시드 — dev 어드민 콘솔을
--   PLATFORM_OPERATOR(alice) 처럼 별도 수동 INSERT 없이 바로 로그인 가능.
--   bob@crosscert.com / "bob-temp-pw", mfa_enabled='N'(편의상 TOTP 생략).
--   ⚠️ RP_ADMIN 은 V23 CHECK 제약상 tenant_id NOT NULL 필수 → 테넌트 INSERT
--      뒤에 와야 하므로 seed-common 이 아니라 이 파일(같은 트랜잭션 순서)에 둔다.
--
-- aaguid_policy(ANY)·snapshot 직접 시드. (VPD 제거됨 — tenant_id 명시 INSERT, 컨텍스트 불필요.)
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

-- 6. api_key — tenant_id 를 명시 INSERT 한다. (VPD 제거됨: 과거엔 update_check 통과를
--    위해 CTX_PKG.set_tenant 로 컨텍스트를 설정했으나, 이제 불필요.)
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

-- 8. admin_user: bob (RP_ADMIN, dev-passkey 테넌트 바인딩)
--    alice(PLATFORM_OPERATOR)와 동급으로 dev 어드민에 바로 로그인.
--    고정 RAW id 0x...0011 (testfix V9001 의 bob 과 동일), bcrypt "bob-temp-pw".
--    mfa_enabled='N' — 비밀번호만으로 admin/api 접근(MfaPendingFilter 우회).
INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, tenant_id, mfa_enabled, created_at)
SELECT HEXTORAW('00000000000000000000000000000011'), 'bob@crosscert.com',
       '$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G',
       'RP_ADMIN', 'Y',
       (SELECT id FROM tenant WHERE slug = 'dev-passkey'),
       'N', SYSTIMESTAMP
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'dev-passkey')
  AND NOT EXISTS (
    SELECT 1 FROM admin_user WHERE email = 'bob@crosscert.com'
  );

COMMIT;
