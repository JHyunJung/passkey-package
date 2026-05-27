-- ============================================================
-- R__dev_seed.sql — dev profile 데이터 시드 (repeatable)
--
-- ⚠️ DEV / LOCAL 전용 — 절대 PROD 활성화 금지 ⚠️
--    아래 BCrypt hash 가 잠그는 secret 은 git 에 commit 된 알려진 plaintext.
--    activation 은 admin-app application-dev.yml 의 flyway.locations 가
--    classpath:db/dev 를 추가했을 때만. prod profile 은 이 디렉터리를 안 본다.
--
-- 목적: 앱 개발자가 RP 측 register / authenticate 테스트를 즉시 시작할 수
-- 있도록 tenant 3개 + 각 tenant 의 API key 1개를 미리 INSERT.
-- credential 은 시드하지 않음 — 실제 WebAuthn ceremony 로 채워야 의미 있음.
--
-- 활성화 조건: spring.flyway.locations 가 classpath:db/dev 를 포함해야 적용.
-- application-dev.yml 이 그 설정을 추가. local profile 만으로는 적용 안 됨.
--
-- Idempotency: 모든 INSERT 가 NOT EXISTS / EXISTS 가드. 재실행 시 silent skip.
-- BCrypt hash 는 strength 12 (CoreSecurityConfig 와 동일).
--
-- prefix 형식: pk_ + 8자 (총 11자, ApiKeyAuthFilter PREFIX_LEN 일치)
-- 시드 secret (앱 개발자에게 공개해도 되는 알려진 plaintext):
--   acme-corp:     dev_acme_secret_known_plaintext_for_local_test_only
--   foo-corp:      dev_foo_secret_known_plaintext_for_local_test_only_x
--   bar-corp:      dev_bar_secret_known_plaintext_for_local_test_only_xy
--
-- prefix: pk_devacme0 / pk_devfoo01 / pk_devbar02
--
-- 전체 X-API-Key 헤더값 (prefix + secret 를 콜론 없이 이어붙임):
--   acme: X-API-Key: pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only
--   foo:  X-API-Key: pk_devfoo01dev_foo_secret_known_plaintext_for_local_test_only_x
--   bar:  X-API-Key: pk_devbar02dev_bar_secret_known_plaintext_for_local_test_only_xy
-- ============================================================

-- 결정적 UUID (RAW(16)) — Top 5 / Activity 페이지의 tenant_id 일관 표시용
-- 7f...로 시작해 V11 seed (00...c0de) 와 충돌 없음.
--
-- acme-corp:  7F00DEAD00000000000000000ACE0001
-- foo-corp:   7F00DEAD0000000000000000F00C0001
-- bar-corp:   7F00DEAD0000000000000000BA1C0001

-- ─── 1. tenant: acme-corp ───────────────────────────────────────
INSERT INTO tenant (
  id, slug, display_name, rp_id, rp_name, status,
  require_user_verification, mds_required,
  created_at, updated_at
)
SELECT
  HEXTORAW('7F00DEAD00000000000000000ACE0001'),
  'acme-corp', 'Acme Corp', 'localhost', 'Acme Corp',
  'active', 'Y', 'N',
  SYSTIMESTAMP, SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM tenant
   WHERE id = HEXTORAW('7F00DEAD00000000000000000ACE0001')
      OR slug = 'acme-corp'
);

-- acme-corp 의 allowed_origin
INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'acme-corp'),
       'http://localhost:9090', 0
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'acme-corp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'acme-corp')
       AND origin = 'http://localhost:9090'
  );

-- acme-corp 의 accepted_format: none + packed
INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'acme-corp'),
       'none'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'acme-corp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'acme-corp')
       AND format = 'none'
  );

INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'acme-corp'),
       'packed'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'acme-corp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'acme-corp')
       AND format = 'packed'
  );

-- acme-corp 의 API key
-- prefix: pk_devacme0 / hash of "dev_acme_secret_known_plaintext_for_local_test_only"
-- scopes 는 V21 에서 별도 테이블 (api_key_scope) 로 normalize. valid: 'registration', 'authentication', 'admin'
--
-- VPD: api_key 에 API_KEY_TENANT_ISOLATION 정책 (V8 + V20) — APP_CTX 미설정
-- 시 predicate '1=0' 가 INSERT 차단. 각 INSERT 전에 CTX_PKG.set_tenant 로
-- target tenant_id 컨텍스트 set 필요. tenant_id 는 RAW(16) hex (대시 없음).
BEGIN
  APP_OWNER.CTX_PKG.set_tenant('7F00DEAD00000000000000000ACE0001');
END;
/

INSERT INTO api_key (
  id, tenant_id, key_prefix, key_hash, name, created_at
)
SELECT
  HEXTORAW('7F00DEAD00000000000000000ACEAA01'),
  HEXTORAW('7F00DEAD00000000000000000ACE0001'),
  'pk_devacme0',
  '$2a$12$TRfPx.wBWToohvEHbbod4.bASgKzn0zSe4rUzKgH.KseldEPHYIFe',
  'dev seed — acme-corp',
  SYSTIMESTAMP
FROM dual
WHERE EXISTS (
    -- parent tenant 가 정확히 우리가 기대한 id 일 때만 — slug 충돌 (다른 id)
    -- 대비 FK fail 방지
    SELECT 1 FROM tenant WHERE id = HEXTORAW('7F00DEAD00000000000000000ACE0001')
  )
  AND NOT EXISTS (
    SELECT 1 FROM api_key
     WHERE key_prefix = 'pk_devacme0'
        OR id = HEXTORAW('7F00DEAD00000000000000000ACEAA01')
  );

BEGIN
  APP_OWNER.CTX_PKG.clear_tenant;
END;
/

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(),
       HEXTORAW('7F00DEAD00000000000000000ACEAA01'),
       'registration'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD00000000000000000ACEAA01'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD00000000000000000ACEAA01')
       AND scope = 'registration'
  );

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(),
       HEXTORAW('7F00DEAD00000000000000000ACEAA01'),
       'authentication'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD00000000000000000ACEAA01'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD00000000000000000ACEAA01')
       AND scope = 'authentication'
  );


-- ─── 2. tenant: foo-corp ───────────────────────────────────────
INSERT INTO tenant (
  id, slug, display_name, rp_id, rp_name, status,
  require_user_verification, mds_required,
  created_at, updated_at
)
SELECT
  HEXTORAW('7F00DEAD0000000000000000F00C0001'),
  'foo-corp', 'Foo Corp', 'localhost', 'Foo Corp',
  'active', 'Y', 'N',
  SYSTIMESTAMP, SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM tenant
   WHERE id = HEXTORAW('7F00DEAD0000000000000000F00C0001')
      OR slug = 'foo-corp'
);

INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'foo-corp'),
       'http://localhost:9090', 0
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'foo-corp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'foo-corp')
       AND origin = 'http://localhost:9090'
  );

INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'foo-corp'),
       'none'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'foo-corp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'foo-corp')
       AND format = 'none'
  );

-- foo-corp 의 API key
-- prefix: pk_devfoo01 / hash of "dev_foo_secret_known_plaintext_for_local_test_only_x"
BEGIN
  APP_OWNER.CTX_PKG.set_tenant('7F00DEAD0000000000000000F00C0001');
END;
/

INSERT INTO api_key (
  id, tenant_id, key_prefix, key_hash, name, created_at
)
SELECT
  HEXTORAW('7F00DEAD0000000000000000F00CAA02'),
  HEXTORAW('7F00DEAD0000000000000000F00C0001'),
  'pk_devfoo01',
  '$2a$12$nvNgC2nwazai5VMM6WH2qe4pp3wfJDYe8D8sXok6nJQMtS1KTdBb2',
  'dev seed — foo-corp',
  SYSTIMESTAMP
FROM dual
WHERE EXISTS (
    SELECT 1 FROM tenant WHERE id = HEXTORAW('7F00DEAD0000000000000000F00C0001')
  )
  AND NOT EXISTS (
    SELECT 1 FROM api_key
     WHERE key_prefix = 'pk_devfoo01'
        OR id = HEXTORAW('7F00DEAD0000000000000000F00CAA02')
  );

BEGIN
  APP_OWNER.CTX_PKG.clear_tenant;
END;
/

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(),
       HEXTORAW('7F00DEAD0000000000000000F00CAA02'),
       'registration'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD0000000000000000F00CAA02'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD0000000000000000F00CAA02')
       AND scope = 'registration'
  );

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(),
       HEXTORAW('7F00DEAD0000000000000000F00CAA02'),
       'authentication'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD0000000000000000F00CAA02'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD0000000000000000F00CAA02')
       AND scope = 'authentication'
  );


-- ─── 3. tenant: bar-corp ───────────────────────────────────────
INSERT INTO tenant (
  id, slug, display_name, rp_id, rp_name, status,
  require_user_verification, mds_required,
  created_at, updated_at
)
SELECT
  HEXTORAW('7F00DEAD0000000000000000BA1C0001'),
  'bar-corp', 'Bar Corp', 'localhost', 'Bar Corp',
  'active', 'Y', 'N',
  SYSTIMESTAMP, SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM tenant
   WHERE id = HEXTORAW('7F00DEAD0000000000000000BA1C0001')
      OR slug = 'bar-corp'
);

INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'bar-corp'),
       'http://localhost:9090', 0
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'bar-corp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'bar-corp')
       AND origin = 'http://localhost:9090'
  );

INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'bar-corp'),
       'none'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'bar-corp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'bar-corp')
       AND format = 'none'
  );

-- bar-corp 의 API key
-- prefix: pk_devbar02 / hash of "dev_bar_secret_known_plaintext_for_local_test_only_xy"
BEGIN
  APP_OWNER.CTX_PKG.set_tenant('7F00DEAD0000000000000000BA1C0001');
END;
/

INSERT INTO api_key (
  id, tenant_id, key_prefix, key_hash, name, created_at
)
SELECT
  HEXTORAW('7F00DEAD0000000000000000BA1CAA03'),
  HEXTORAW('7F00DEAD0000000000000000BA1C0001'),
  'pk_devbar02',
  '$2a$12$faQxrlOSqHSjy8MdqCwUkO76Sjm8IIS2baVO/L8cxruON4XTXEFTO',
  'dev seed — bar-corp',
  SYSTIMESTAMP
FROM dual
WHERE EXISTS (
    SELECT 1 FROM tenant WHERE id = HEXTORAW('7F00DEAD0000000000000000BA1C0001')
  )
  AND NOT EXISTS (
    SELECT 1 FROM api_key
     WHERE key_prefix = 'pk_devbar02'
        OR id = HEXTORAW('7F00DEAD0000000000000000BA1CAA03')
  );

BEGIN
  APP_OWNER.CTX_PKG.clear_tenant;
END;
/

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(),
       HEXTORAW('7F00DEAD0000000000000000BA1CAA03'),
       'registration'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD0000000000000000BA1CAA03'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD0000000000000000BA1CAA03')
       AND scope = 'registration'
  );

INSERT INTO api_key_scope (id, api_key_id, scope)
SELECT SYS_GUID(),
       HEXTORAW('7F00DEAD0000000000000000BA1CAA03'),
       'authentication'
FROM dual
WHERE EXISTS (SELECT 1 FROM api_key WHERE id = HEXTORAW('7F00DEAD0000000000000000BA1CAA03'))
  AND NOT EXISTS (
    SELECT 1 FROM api_key_scope
     WHERE api_key_id = HEXTORAW('7F00DEAD0000000000000000BA1CAA03')
       AND scope = 'authentication'
  );

COMMIT;
