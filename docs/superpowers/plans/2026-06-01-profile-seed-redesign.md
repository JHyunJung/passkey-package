# 프로필별 초기 시드 재정의 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dev/qa/prod/local 프로필별로 초기 시드 데이터(운영자 계정·테넌트·API키)를 완전히 분리하고, 로그인 화면의 데모 role 카드를 dev/local에서만 노출한다.

**Architecture:** `db/migration`은 스키마+인프라 싱글톤만 두고, 데모 데이터는 프로필 전용 Flyway location(`db/seed-common`, `db/local`, `db/dev`, `db/qa`, `db/prod`)으로 분리한다. 기존 V11/V23의 시드 INSERT는 들어내고(스키마 DDL은 유지), 데이터는 repeatable(`R__`) 시드로 이관한다. checksum 충돌은 `flyway clean` 재마이그레이션으로 흡수한다(DB 초기화 전제). IT 테스트(test 프로필)는 `db/testfix`로 alice/bob/계정을 복원해 호환을 유지한다.

**Tech Stack:** Oracle 21 XE, Flyway(versioned + repeatable), Spring Boot 3.5 프로필, Oracle VPD(CTX_PKG), React/Vite(admin-ui).

**Spec:** `docs/superpowers/specs/2026-06-01-profile-seed-redesign-design.md`

**Worktree:** `.claude/worktrees/profile-seed-redesign`, 브랜치 `worktree-profile-seed-redesign`

---

## 중요 사전 지식 (구현자 필독)

1. **VPD 컨텍스트**: `api_key` 테이블은 VPD 정책(V8/V20)이 걸려 있어, INSERT 전에 `APP_OWNER.CTX_PKG.set_tenant('<RAW16 hex 대시없음>')`를 호출하고 INSERT 후 `CTX_PKG.clear_tenant`를 호출해야 한다. `tenant`/`tenant_allowed_origin`/`tenant_accepted_format`/`api_key_scope`는 VPD 없음(컨텍스트 불필요). PL/SQL BEGIN/END 블록은 Flyway가 `/` 구분자로 파싱한다.

2. **tenant_aaguid_policy / tenant_webauthn_snapshot 백필**: V26/V27은 "기존 모든 tenant에 default policy/snapshot 자동 생성"하는 **마이그레이션 백필**이다(시드 아님). 새 R__ 시드가 tenant를 만들면 V26/V27은 이미 적용 완료라 따라오지 않으므로, **R__ 시드에서 aaguid_policy와 snapshot도 직접 INSERT**해야 한다.

3. **role 모델**: `admin_user.role`은 `PLATFORM_OPERATOR`/`RP_ADMIN` 2종(V23). alice는 PLATFORM_OPERATOR(tenant_id NULL). RP_ADMIN은 tenant_id 필수(CHECK 제약).

4. **bcrypt 해시 재사용**: alice 비번 `alice-temp-pw`의 해시는 기존 V11에 있는 `$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS`를 그대로 쓴다. API키 해시도 기존 R__dev_seed.sql 값을 재사용한다(plaintext 동일).

5. **테스트 호환**: IT는 `test` 프로필 → `db/migration,db/testfix`만 읽음. `db/seed-common`을 안 읽으므로, alice/bob 계정을 `db/testfix`에 복원해야 IT가 안 깨진다. demo-rp는 `AdminFlowIT.resetState()`가 이미 인라인 재시드하므로 추가 작업 불필요.

6. **결정적 tenantId** (RAW16 hex, 대시 없음):
   - local `demo-rp`: `0000000000000000000000000000C0DE` (기존 V23 값 재사용 — 테스트와 일치)
   - dev `dev-passkey`: `7F00DEAD000000000000000DE7000001` (신규, 충돌 없는 값)
   - dev API key id: `7F00DEAD000000000000000DE700AA01`

---

## File Structure

**신규 파일:**
- `core/src/main/resources/db/seed-common/R__seed_operators.sql` — alice 계정
- `core/src/main/resources/db/local/R__seed_local_tenant.sql` — demo-rp(localhost) + API키 + policy + snapshot
- `core/src/main/resources/db/dev/R__seed_dev_tenant.sql` — dev-passkey(도메인) + API키 + policy + snapshot
- `core/src/main/resources/db/qa/R__seed_qa_placeholder.sql` — 주석만(빈 location 방지)
- `core/src/main/resources/db/prod/R__seed_prod_placeholder.sql` — 주석만
- `admin-app/src/main/resources/application-local.yml` — local 프로필
- `admin-app/src/test/resources/db/testfix/V9001__test_seed_operators.sql` — 테스트용 alice/bob 복원

**수정 파일:**
- `core/src/main/resources/db/migration/V11__seed_admin_user.sql` — 시드 INSERT 제거, 주석만 남김
- `core/src/main/resources/db/migration/V23__admin_role_separation.sql` — demo-rp 시드 + bob UPDATE 제거(DDL/role UPDATE 유지)
- `admin-app/src/main/resources/application-dev.yml` — flyway locations에 seed-common 추가, dev 테넌트는 새 파일
- `admin-app/src/main/resources/application-qa.yml` — flyway locations 정의(seed-common+qa)
- `admin-app/src/main/resources/application-prod.yml` — flyway locations에 db/prod 추가
- `admin-ui/src/pages/LoginPage.tsx` — 데모 카드 dev/local 게이팅 + RP_ADMIN 카드 제거

**삭제 파일:**
- `core/src/main/resources/db/dev/R__dev_seed.sql` — 새 R__seed_dev_tenant.sql로 교체

---

## Task 1: seed-common — alice 운영자 계정 repeatable 시드

**Files:**
- Create: `core/src/main/resources/db/seed-common/R__seed_operators.sql`

- [ ] **Step 1: 시드 파일 작성**

`core/src/main/resources/db/seed-common/R__seed_operators.sql`:

```sql
-- ============================================================
-- R__seed_operators.sql — local·dev·qa 공통 운영자 계정 시드 (repeatable)
--
-- ⚠️ prod 는 이 디렉터리를 flyway.locations 에 포함하지 않는다.
--    임시 비번(alice-temp-pw)은 git 공개 plaintext — 비-운영 전용.
--
-- alice 만 시드. PLATFORM_OPERATOR(tenant_id NULL). bob 은 시드하지 않음
-- (RP_ADMIN 테스트는 db/testfix 가 별도 복원).
--
-- Idempotent: email UNIQUE 기준 NOT EXISTS 가드.
-- bcrypt strength 12, plaintext "alice-temp-pw" (기존 V11 해시 재사용).
-- ============================================================

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
SELECT admin_user_seq.NEXTVAL, 'alice@crosscert.com',
       '$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS',
       'PLATFORM_OPERATOR', 'Y', SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM admin_user WHERE email = 'alice@crosscert.com'
);

COMMIT;
```

- [ ] **Step 2: 커밋**

```bash
git add core/src/main/resources/db/seed-common/R__seed_operators.sql
git commit -m "feat(seed): seed-common 에 alice 운영자 계정 repeatable 시드"
```

---

## Task 2: local — demo-rp 테넌트 repeatable 시드

**Files:**
- Create: `core/src/main/resources/db/local/R__seed_local_tenant.sql`

- [ ] **Step 1: 시드 파일 작성**

`core/src/main/resources/db/local/R__seed_local_tenant.sql`:

```sql
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

-- 5. webauthn_snapshot — V27 백필 대체
INSERT INTO tenant_webauthn_snapshot (
  id, tenant_id, rp_id, rp_name, allowed_origins_json, accepted_formats_json,
  require_user_verification, mds_required, taken_at
)
SELECT
  tenant_webauthn_snapshot_seq.NEXTVAL,
  t.id, t.rp_id, t.rp_name,
  (SELECT '[' || LISTAGG('"' || o.origin || '"', ',') WITHIN GROUP (ORDER BY o.sort_order) || ']'
     FROM tenant_allowed_origin o WHERE o.tenant_id = t.id),
  (SELECT '[' || LISTAGG('"' || f.format || '"', ',') WITHIN GROUP (ORDER BY f.format) || ']'
     FROM tenant_accepted_format f WHERE f.tenant_id = t.id),
  t.require_user_verification, t.mds_required, SYSTIMESTAMP
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
  '$2a$12$placeholderlocalplaceholderlocalplaceholderlocalplaceholderl',
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
```

- [ ] **Step 1.5 (필수): 실제 bcrypt 해시 생성 + 교체**

위 `key_hash`의 `$2a$12$placeholder...`는 자리표시자다. plaintext `dev_local_secret_known_plaintext_for_test_only`의 실제 bcrypt(strength 12, `CoreSecurityConfig`의 `new BCryptPasswordEncoder(12)`와 동일) 해시로 교체한다.

생성 방법 — 일회성 JUnit 테스트 `admin-app/src/test/java/com/crosscert/passkey/admin/GenHashTmp.java` (생성 후 삭제):

```java
package com.crosscert.passkey.admin;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class GenHashTmp {
    @Test
    void gen() {
        var enc = new BCryptPasswordEncoder(12);
        System.out.println("LOCAL_HASH=" + enc.encode("dev_local_secret_known_plaintext_for_test_only"));
        System.out.println("DEV_HASH="   + enc.encode("dev_server_secret_known_plaintext_for_test_only"));
    }
}
```

실행:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/profile-seed-redesign
./gradlew :admin-app:test --tests "*GenHashTmp" -q 2>&1 | grep -E "LOCAL_HASH=|DEV_HASH="
```
Expected: `LOCAL_HASH=$2a$12$...` / `DEV_HASH=$2a$12$...` 출력.

- `LOCAL_HASH` 값으로 이 파일(Task 2)의 `pk_devlocal` key_hash 교체.
- `DEV_HASH` 값은 Task 3의 `pk_devsrv01` key_hash 교체에 사용(미리 받아둠).
- 교체 후 임시 파일 삭제: `rm admin-app/src/test/java/com/crosscert/passkey/admin/GenHashTmp.java`

> 자리표시자를 그대로 두면 sample-rp의 API키 인증(BCrypt 검증)이 실패하므로 반드시 교체.

- [ ] **Step 2: 커밋**

```bash
git add core/src/main/resources/db/local/R__seed_local_tenant.sql
git commit -m "feat(seed): local 프로필 demo-rp(localhost) 테넌트 시드"
```

---

## Task 3: dev — dev-passkey 테넌트 repeatable 시드 (기존 R__dev_seed 교체)

**Files:**
- Create: `core/src/main/resources/db/dev/R__seed_dev_tenant.sql`
- Delete: `core/src/main/resources/db/dev/R__dev_seed.sql`

- [ ] **Step 1: 기존 dev 시드 삭제**

```bash
git rm core/src/main/resources/db/dev/R__dev_seed.sql
```

- [ ] **Step 2: 새 dev 시드 작성**

`core/src/main/resources/db/dev/R__seed_dev_tenant.sql`:

```sql
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

-- 5. webauthn_snapshot
INSERT INTO tenant_webauthn_snapshot (
  id, tenant_id, rp_id, rp_name, allowed_origins_json, accepted_formats_json,
  require_user_verification, mds_required, taken_at
)
SELECT
  tenant_webauthn_snapshot_seq.NEXTVAL,
  t.id, t.rp_id, t.rp_name,
  (SELECT '[' || LISTAGG('"' || o.origin || '"', ',') WITHIN GROUP (ORDER BY o.sort_order) || ']'
     FROM tenant_allowed_origin o WHERE o.tenant_id = t.id),
  (SELECT '[' || LISTAGG('"' || f.format || '"', ',') WITHIN GROUP (ORDER BY f.format) || ']'
     FROM tenant_accepted_format f WHERE f.tenant_id = t.id),
  t.require_user_verification, t.mds_required, SYSTIMESTAMP
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
  '$2a$12$placeholderdevplaceholderdevplaceholderdevplaceholderdevplac',
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
```

- [ ] **Step 2.5 (필수): bcrypt 해시 교체**

`$2a$12$placeholderdev...`를 Task 2의 Step 1.5에서 출력된 `DEV_HASH` 값(plaintext `dev_server_secret_known_plaintext_for_test_only`의 bcrypt strength 12)으로 교체한다. Task 2를 먼저 수행했다면 그 값을 그대로 쓰면 되고, 안 했다면 Step 1.5의 GenHashTmp 테스트를 실행해 받는다. **자리표시자 그대로면 sample-rp 인증 실패.**

- [ ] **Step 3: 커밋**

```bash
git add core/src/main/resources/db/dev/
git commit -m "feat(seed): dev 프로필 dev-passkey(도메인) 테넌트 시드, 기존 acme/bar/foo 교체"
```

---

## Task 4: qa/prod placeholder 시드 디렉터리

**Files:**
- Create: `core/src/main/resources/db/qa/R__seed_qa_placeholder.sql`
- Create: `core/src/main/resources/db/prod/R__seed_prod_placeholder.sql`

- [ ] **Step 1: qa placeholder 작성**

`core/src/main/resources/db/qa/R__seed_qa_placeholder.sql`:

```sql
-- ============================================================
-- R__seed_qa_placeholder.sql — qa 프로필 시드 placeholder
--
-- qa 는 운영자 계정만(db/seed-common 의 alice). 테넌트·API키 시드 없음.
-- 이 파일은 db/qa location 이 비어 있지 않도록 두는 no-op placeholder.
-- 실제 qa 전용 시드가 필요해지면 여기에 추가한다.
-- ============================================================
SELECT 1 FROM dual;
```

- [ ] **Step 2: prod placeholder 작성**

`core/src/main/resources/db/prod/R__seed_prod_placeholder.sql`:

```sql
-- ============================================================
-- R__seed_prod_placeholder.sql — prod 프로필 시드 placeholder
--
-- prod 는 어떤 데모 데이터(계정·테넌트·API키)도 자동 시드하지 않는다.
-- 최초 운영자 계정은 별도 부트스트랩/CLI 로 생성한다(본 작업 범위 밖).
-- 이 파일은 db/prod location 이 비어 있지 않도록 두는 no-op placeholder.
-- ============================================================
SELECT 1 FROM dual;
```

- [ ] **Step 3: 커밋**

```bash
git add core/src/main/resources/db/qa/ core/src/main/resources/db/prod/
git commit -m "feat(seed): qa/prod placeholder 시드 디렉터리(no-op)"
```

---

## Task 5: V11/V23 에서 시드 INSERT 제거 (스키마 DDL 유지)

**Files:**
- Modify: `core/src/main/resources/db/migration/V11__seed_admin_user.sql`
- Modify: `core/src/main/resources/db/migration/V23__admin_role_separation.sql`

- [ ] **Step 1: V11 시드 제거**

`V11__seed_admin_user.sql` 전체를 아래로 교체(INSERT 제거, no-op 주석만):

```sql
-- ============================================================
-- V11 — (구) 운영자 계정 시드.
--
-- 시드 데이터는 db/seed-common/R__seed_operators.sql 로 이관됨
-- (프로필별 분리: prod 는 운영자 자동 시드 안 함).
-- 이 마이그레이션은 버전 이력 보존을 위해 남기되 no-op.
-- 테스트(test 프로필)는 db/testfix 가 alice/bob 을 복원한다.
-- ============================================================
SELECT 1 FROM dual;
```

> 주의: 이 파일은 versioned 마이그레이션이므로 내용 변경 시 checksum이 바뀐다. Task 8에서 `flyway clean` 후 재마이그레이션하여 흡수한다. clean 전까지 기존 DB에서는 부팅이 실패할 수 있으나, 본 작업은 DB 초기화 전제이므로 정상.

- [ ] **Step 2: V23 에서 demo-rp 시드 + bob UPDATE 제거**

`V23__admin_role_separation.sql`에서 **스키마 변경(ALTER/CHECK/UPDATE role)은 유지**하고, **demo-rp tenant INSERT 블록(주석 "8. demo tenant 신규"부터)과 그 뒤 allowed_origin/accepted_format INSERT, "9. bob 을 demo tenant 의 RP_ADMIN 으로" UPDATE 블록 전체를 삭제**한다.

삭제 대상 식별: 파일에서 `-- 8. demo tenant 신규` 주석 라인부터 파일의 demo-rp 관련 마지막 INSERT/UPDATE까지. 남겨야 할 것: tenant_id 컬럼 추가, FK, 인덱스, role CHECK 변경, `UPDATE admin_user SET role='PLATFORM_OPERATOR' WHERE role IN ('ADMIN','VIEWER')`, role↔tenant_id invariant 제약.

삭제 후 demo-rp 관련 블록 자리에 주석을 남긴다:

```sql
-- (구) demo-rp 테넌트 시드 + bob RP_ADMIN 지정은 db/local/R__seed_local_tenant.sql
-- 로 이관됨. test 프로필은 AdminFlowIT.resetState() 가 인라인 재시드한다.
```

- [ ] **Step 3: V23 파일 문법 확인 (PL/SQL 블록 균형)**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/profile-seed-redesign
grep -c "INSERT INTO tenant\|demo-rp\|bob@crosscert" core/src/main/resources/db/migration/V23__admin_role_separation.sql
```
Expected: `0` (demo-rp/bob 시드가 모두 제거됨)

- [ ] **Step 4: 커밋**

```bash
git add core/src/main/resources/db/migration/V11__seed_admin_user.sql core/src/main/resources/db/migration/V23__admin_role_separation.sql
git commit -m "refactor(migration): V11/V23 시드 INSERT 제거(스키마 DDL 유지) — 시드는 프로필 경로로 이관"
```

---

## Task 6: V19 alice/bob 시드 제거 + 테스트용 alice/bob 복원 (db/testfix)

> ⚠️ **실행 중 발견(계획 보강):** V11 외에 `V19__uuid_migration.sql`(줄 335~354, 주석 "6c. admin_user seed")도 alice/bob을 `MERGE WHEN NOT MATCHED THEN INSERT`로 시드한다. V11만 비우면 V19가 여전히 prod에 alice/bob을 넣어 격리가 깨진다. 따라서 V19의 그 블록도 제거한다. (V19의 다른 시드 — audit_chain_lock, mds_blob_cache 싱글톤, signing_key bootstrap pkg — 는 인프라 싱글톤이라 유지.)

**Files:**
- Modify: `core/src/main/resources/db/migration/V19__uuid_migration.sql` — alice/bob MERGE 2블록 제거(줄 335~354), COMMIT 유지
- Create: `admin-app/src/test/resources/db/testfix/V9001__test_seed_operators.sql`

- [ ] **Step 1: testfix 시드 작성**

V11에서 alice/bob을 들어냈으므로, test 프로필(db/migration+db/testfix)에서 alice/bob을 복원해야 IT가 안 깨진다. testfix는 versioned(V9000번대)이며 실제 마이그레이션 이후 적용된다.

`admin-app/src/test/resources/db/testfix/V9001__test_seed_operators.sql`:

```sql
-- ============================================================
-- V9001 — test 프로필 전용 운영자 계정 복원.
--
-- 프로덕션 V11 이 시드를 들어냈으므로(프로필 분리), IT 가 의존하는
-- alice(PLATFORM_OPERATOR) / bob(RP_ADMIN 후보) 를 test 에서만 복원.
-- bob 의 tenant 매핑은 AdminFlowIT.resetState() 가 demo-rp 재시드 후 수행.
--
-- plaintext: alice-temp-pw / bob-temp-pw (기존 V11 해시 재사용).
-- Idempotent: email NOT EXISTS 가드.
-- ============================================================

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
SELECT admin_user_seq.NEXTVAL, 'alice@crosscert.com',
       '$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS',
       'PLATFORM_OPERATOR', 'Y', SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE email = 'alice@crosscert.com');

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
SELECT admin_user_seq.NEXTVAL, 'bob@crosscert.com',
       '$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G',
       'PLATFORM_OPERATOR', 'Y', SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE email = 'bob@crosscert.com');

COMMIT;
```

> bob의 role은 PLATFORM_OPERATOR로 시드(tenant_id NULL이라 CHECK 통과). `AdminFlowIT.resetState()`가 이미 bob을 RP_ADMIN+demo-rp로 UPDATE하므로 정합한다.

- [ ] **Step 2: 커밋**

```bash
git add admin-app/src/test/resources/db/testfix/V9001__test_seed_operators.sql
git commit -m "test(fix): test 프로필에 alice/bob 운영자 계정 복원(V11 시드 제거 보상)"
```

---

## Task 7: 프로필 yml — flyway locations 재정의 + application-local.yml 신규

**Files:**
- Create: `admin-app/src/main/resources/application-local.yml`
- Modify: `admin-app/src/main/resources/application-dev.yml`
- Modify: `admin-app/src/main/resources/application-qa.yml`
- Modify: `admin-app/src/main/resources/application-prod.yml`

- [ ] **Step 1: dev yml flyway locations 수정**

`application-dev.yml`의 flyway 블록을 수정 — seed-common 추가:

```yaml
  flyway:
    locations: classpath:db/migration,classpath:db/seed-common,classpath:db/dev
```

(기존 `classpath:db/migration,classpath:db/dev`에서 seed-common 삽입)

- [ ] **Step 2: qa yml flyway locations 정의**

`application-qa.yml`의 flyway 블록 — seed-common + qa:

```yaml
  flyway:
    # qa 는 운영자 계정(seed-common)만. 테넌트 시드는 db/qa(placeholder).
    locations: classpath:db/migration,classpath:db/seed-common,classpath:db/qa
```

(기존 `classpath:db/migration`을 교체. baseline 관련 기존 주석은 유지)

- [ ] **Step 3: prod yml flyway locations 수정**

`application-prod.yml`의 flyway 블록 — db/prod 추가(seed-common 미포함):

```yaml
  flyway:
    locations: classpath:db/migration,classpath:db/prod
```

(기존 `classpath:db/migration`에서 db/prod 추가. baseline fail-fast 주석 유지)

- [ ] **Step 4: application-local.yml 신규 생성**

`application-local.yml` — dev yml을 베이스로 하되 flyway locations만 local:

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/XEPDB1
    driver-class-name: oracle.jdbc.OracleDriver
    username: APP_ADMIN_USER
    password: admin_pw
  data:
    redis:
      host: localhost
      port: 6379
  flyway:
    locations: classpath:db/migration,classpath:db/seed-common,classpath:db/local

passkey:
  key-envelope:
    # NON-SECRET, local/dev only — dev 와 동일 키(envelope 복호화 호환).
    master-key: "jDKp21WXeDAwinZI91Hf+8L2zv4xlIQI15YPLhttyYM="
```

> 주의: `application-dev.yml`의 datasource/redis/master-key 설정을 그대로 따른다. dev yml에 추가 설정(logging 등)이 있으면 필요한 만큼 복사. 실제 dev yml을 읽어 누락 설정이 없는지 확인할 것.

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/resources/application-local.yml admin-app/src/main/resources/application-dev.yml admin-app/src/main/resources/application-qa.yml admin-app/src/main/resources/application-prod.yml
git commit -m "feat(config): 프로필별 flyway locations 재정의 + application-local.yml 신규"
```

---

## Task 8: 컴파일 + DB clean 재마이그레이션 검증

**Files:** (없음 — 검증 태스크)

- [ ] **Step 1: 전체 컴파일**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/profile-seed-redesign
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q
```
Expected: BUILD SUCCESSFUL (SQL은 컴파일 대상 아님 — 클래스만 확인)

- [ ] **Step 2: Flyway clean (DB 초기화)**

기존 시드가 있는 DB를 비운다. admin-app의 flyway clean을 실행(또는 Oracle에서 직접 스키마 정리). clean은 기본 비활성이므로 일회성으로 활성화:

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=dev --spring.flyway.clean-disabled=false --spring.flyway.clean-on-validation-error=true' > /tmp/seed-clean.log 2>&1 &
```

> clean이 막혀 있으면 대안: Oracle에서 `flyway_schema_history` + 데이터 테이블을 수동 truncate, 또는 docker 컨테이너 재생성(`docker compose down -v && docker compose up -d` 후 wait-for-oracle). 가장 깨끗한 방법은 컨테이너 재생성.

- [ ] **Step 3: dev 프로필 부팅 + 시드 검증**

dev로 부팅 후 dev-passkey 테넌트와 alice 계정 확인:

```bash
docker exec -i passkey-oracle sqlplus -S APP_ADMIN_USER/admin_pw@localhost:1521/XEPDB1 <<'SQL'
SELECT slug, rp_id FROM app_owner.tenant ORDER BY slug;
SELECT email, role FROM app_owner.admin_user ORDER BY email;
SELECT key_prefix FROM app_owner.api_key ORDER BY key_prefix;
SQL
```
Expected (dev): tenant `dev-passkey`(rp_id=dev-passkey.crosscert.com), admin `alice@crosscert.com`(PLATFORM_OPERATOR), api_key `pk_devsrv01`. demo-rp/acme/bar/foo/bob 없음.

- [ ] **Step 4: local 프로필 부팅 검증**

```bash
# 컨테이너 재생성 또는 clean 후 local 로 부팅
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/seed-local.log 2>&1 &
# 부팅 후:
docker exec -i passkey-oracle sqlplus -S APP_ADMIN_USER/admin_pw@localhost:1521/XEPDB1 <<'SQL'
SELECT slug, rp_id FROM app_owner.tenant ORDER BY slug;
SQL
```
Expected (local): tenant `demo-rp`(rp_id=localhost). alice 계정. api_key `pk_devlocal`.

- [ ] **Step 5: prod 격리 검증**

```bash
# clean 후 prod 로 부팅
./gradlew :admin-app:bootRun --args='--spring.profiles.active=prod ...(prod env)' > /tmp/seed-prod.log 2>&1 &
docker exec -i passkey-oracle sqlplus -S APP_ADMIN_USER/admin_pw@localhost:1521/XEPDB1 <<'SQL'
SELECT COUNT(*) AS tenants FROM app_owner.tenant;
SELECT COUNT(*) AS admins FROM app_owner.admin_user;
SQL
```
Expected (prod): tenants=0, admins=0 (시드 없음).

> prod 부팅은 master-key 등 환경변수가 필요하다. 검증이 번거로우면 최소 "Flyway가 db/prod placeholder만 적용하고 tenant/admin_user에 INSERT하지 않음"을 로그로 확인하는 것으로 대체 가능.

- [ ] **Step 6: 커밋 (검증 노트)**

검증 결과를 커밋 메시지에 남길 변경이 없으면 이 단계는 생략. 시드 파일 수정이 필요했다면 그 수정을 커밋.

---

## Task 9: 로그인 데모 카드 dev/local 게이팅 + RP_ADMIN 카드 제거

**Files:**
- Modify: `admin-ui/src/pages/LoginPage.tsx`

- [ ] **Step 1: profile 감지를 dev OR local 로 확장**

`LoginPage.tsx`의 useEffect에서 `devProfile` 상태를 dev 또는 local일 때 true로:

```tsx
  // dev/local profile 감지 — 데모 prefill + 데모 카드 노출 게이팅
  useEffect(() => {
    api.get<{ active: string[]; local?: boolean }>('/admin/api/profile')
      .then((p) => {
        if (p.active?.includes('dev') || p.active?.includes('local') || p.local) {
          setDevProfile(true);
        }
      })
      .catch(() => { /* prod 등 — 무시 */ });
  }, []);
```

- [ ] **Step 2: 데모 카드를 devProfile 일 때만 렌더링 + RP_ADMIN 카드 제거**

기존 `{/* Role switcher — demo prefill */}` 블록을 `devProfile &&` 로 감싸고, 카드 목록에서 RP_ADMIN(bob) 제거. PLATFORM_OPERATOR 카드만 남김:

```tsx
            {/* Role switcher — demo prefill (dev/local 만) */}
            {devProfile && (
              <div>
                <label className="label">데모 계정으로 로그인</label>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 8 }}>
                  {([
                    { v: 'PLATFORM_OPERATOR' as const, t: 'Platform Operator', s: 'alice@crosscert.com' },
                  ] as const).map((opt) => (
                    <button
                      key={opt.v}
                      type="button"
                      onClick={() => selectDemo(opt.v)}
                      style={{
                        padding: '8px 10px',
                        borderRadius: 8,
                        border: `1px solid ${demoRole === opt.v ? 'var(--accent)' : 'var(--border)'}`,
                        background: demoRole === opt.v ? 'var(--accent-soft)' : 'var(--surface)',
                        color: demoRole === opt.v ? 'var(--accent)' : 'var(--text)',
                        cursor: 'pointer',
                        textAlign: 'left',
                      }}
                    >
                      <div style={{ fontSize: 12, fontWeight: 600 }}>{opt.t}</div>
                      <div style={{ fontSize: 11, color: 'var(--text-mute)', marginTop: 2 }}>{opt.s}</div>
                    </button>
                  ))}
                </div>
              </div>
            )}
```

- [ ] **Step 3: selectDemo 에서 RP_ADMIN 분기 제거**

`selectDemo` 함수에서 bob prefill 제거(alice만):

```tsx
  // 데모 role 카드 클릭 → 자동 prefill (dev/local 일 때만)
  function selectDemo(role: 'PLATFORM_OPERATOR' | 'RP_ADMIN') {
    setDemoRole(role);
    if (devProfile && role === 'PLATFORM_OPERATOR') {
      setEmail('alice@crosscert.com');
      setPassword('alice-temp-pw');
    }
  }
```

> `demoRole` 상태 타입에 RP_ADMIN이 남아 있어도 무방(사용 안 됨). TS 에러가 나면 `useState<'PLATFORM_OPERATOR' | 'RP_ADMIN' | null>` 그대로 둔다.

- [ ] **Step 4: 타입 체크 + 빌드**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/profile-seed-redesign/admin-ui
npx tsc --noEmit
npm run build
```
Expected: tsc exit 0, build 성공

- [ ] **Step 5: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/profile-seed-redesign
git add admin-ui/src/pages/LoginPage.tsx
git commit -m "feat(admin-ui): 로그인 데모 카드를 dev/local 에서만 노출 + RP_ADMIN(bob) 카드 제거"
```

---

## Task 10: 단위/슬라이스 테스트 게이트 + 문서 갱신

**Files:**
- Modify: `docs/single-instance-deployment.md` (sample-rp 기동 env 갱신 — 선택)

- [ ] **Step 1: bob 의존 IT 식별**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/profile-seed-redesign
grep -rln "bob@crosscert\|RP_ADMIN" admin-app/src/test/java | head
```
각 파일이 bob을 (a) testfix 시드(V9001)에서 받는지, (b) 직접 생성하는지 확인. V9001이 bob을 PLATFORM_OPERATOR로 복원하고 AdminFlowIT가 RP_ADMIN으로 UPDATE하므로, 대부분 그대로 통과해야 한다. 통과 안 하는 테스트가 있으면 그 테스트가 기대하는 초기 role을 확인해 V9001 또는 테스트를 조정.

- [ ] **Step 2: 컴파일 + 단위/슬라이스 테스트 (Oracle IT 제외)**

```bash
./gradlew :core:test :admin-app:test --tests "*Test" -q 2>&1 | tail -20
```
Expected: 단위/슬라이스 테스트 통과. (Oracle `*IT`는 이 환경에서 flaky하므로 게이트에서 제외 — 부팅 검증은 Task 8이 담당)

- [ ] **Step 3: 배포 문서의 sample-rp 기동 env 갱신**

`docs/single-instance-deployment.md`에서 dev 서버 sample-rp 기동 예시의 `PASSKEY_TENANT_ID`/`PASSKEY_API_KEY`를 새 dev 시드 값으로 갱신:
- `PASSKEY_TENANT_ID`: dev-passkey tenantId — RAW hex `7F00DEAD000000000000000DE7000001` 또는 UUID 형식 `7f00dead-0000-0000-0000-000de7000001` (sample-rp `normalizeTenantId`가 둘 다 처리)
- `PASSKEY_API_KEY`: `pk_devsrv01dev_server_secret_known_plaintext_for_test_only`

해당 문구가 있으면 교체, 없으면 이 단계 생략.

- [ ] **Step 4: 커밋**

```bash
git add docs/single-instance-deployment.md
git commit -m "docs: sample-rp 기동 env 를 새 dev-passkey 시드 값으로 갱신"
```

---

## 실행 중 발견·수정 (Task 8 검증)

부팅 검증에서 계획에 없던 3개 버그를 발견·수정했다(커밋 3645b86):
1. **snapshot 컬럼명**: 계획이 `allowed_origins_json`/`accepted_formats_json`로 썼으나 V27 실제 컬럼은 `allowed_origins`/`accepted_formats`(+ `taken_by`). → 수정.
2. **snapshot 시퀀스 접두사**: Flyway 세션(APP_ADMIN_USER)에서 `tenant_webauthn_snapshot_seq.NEXTVAL`이 접두사 없이는 ORA-02289. → `APP_OWNER.` 접두사 추가.
3. **admin_user.id 타입**: V19 이후 RAW(16)이고 `admin_user_seq`는 죽은 시퀀스(GRANT 없음). → seed-common/testfix에서 `admin_user_seq.NEXTVAL` 대신 고정 RAW(alice 0x...0010, bob 0x...0011) 사용.

**검증 운영 노트:** 같은 Oracle 인스턴스를 여러 프로필로 번갈아 부팅하면 repeatable 시드 "not resolved locally" validate 충돌이 난다(dev 시드 history가 남은 DB를 local로 부팅 시). 따라서 각 프로필 검증은 컨테이너 재생성(깨끗한 DB)에서 수행했다. **prod 실부팅은 "non-empty schema + no history → baseline 거부"(의도된 fail-fast)로 단독 검증 불가** — prod 격리는 코드 레벨(flyway locations에 seed 미포함 + db/migration 시드 INSERT 0 + db/prod no-op)로 보장 확인.

## 최종 검증 체크리스트

- [ ] dev 부팅 → tenant `dev-passkey`(rp_id 도메인), alice, `pk_devsrv01`. acme/bar/foo/demo-rp/bob 없음
- [ ] local 부팅 → tenant `demo-rp`(rp_id localhost), alice, `pk_devlocal`
- [ ] qa 부팅 → alice 계정만, 테넌트 0
- [ ] prod 부팅 → 시드 0 (tenant/admin_user 비어 있음)
- [ ] test 프로필 단위/슬라이스 테스트 통과 (alice/bob testfix 복원 동작)
- [ ] admin-ui: dev/local에서 데모 카드 노출, prod/qa에서 미노출, RP_ADMIN 카드 없음
- [ ] sample-rp가 dev 시드 API키(`pk_devsrv01...`)로 기동 가능 (수동)
