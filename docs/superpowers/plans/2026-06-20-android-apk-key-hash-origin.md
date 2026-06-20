# Android 앱 origin(apk-key-hash) 등록·검증 지원 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 테넌트가 Android 앱 서명키 해시를 `android:apk-key-hash:<해시>` origin으로 등록하고, 기존 WebAuthn origin 검증이 그대로 통과시키도록 한다.

**Architecture:** 방식 A(명시적 등록). web origin과 android key-hash를 같은 `tenant_allowed_origin` 테이블·`origin` 컬럼에 저장한다. DB CHECK 제약을 두 패턴 모두 허용하도록 완화하고, 백엔드에 가벼운 형식 검증 유틸을 추가하며, admin-ui에서 SHA-256 지문(hex)을 `android:apk-key-hash:<base64url>`로 변환해 입력받는다. verifier 코어(`ClientDataValidator`)와 런타임(`RegistrationFinishService`)은 변경하지 않는다.

**Tech Stack:** Java 17 / Spring Boot (core, admin-app, webauthn 모듈), Oracle + Flyway, React + TypeScript + Vitest (admin-ui), JUnit 5 + Testcontainers.

## Global Constraints

- apk-key-hash origin 형식: `android:apk-key-hash:<B>`, `<B>` = base64url(no padding)로 인코딩한 SHA-256(32바이트) = **정확히 43자**, 문자셋 `[A-Za-z0-9_-]`.
- DB CHECK 정규식(android): `^android:apk-key-hash:[A-Za-z0-9_-]{43}$`.
- DB CHECK 정규식(web, 기존 유지): `^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$`.
- 제약 이름은 기존과 동일하게 `ck_tao_origin_format` 유지.
- verifier 코어(`ClientDataValidator`)·런타임(`RegistrationFinishService`)은 **변경 금지**.
- 새 Flyway 버전은 `V48` (현재 최신 `V47__drop_unused_columns.sql`).
- Oracle DDL/CHECK 변경은 정적 inspection이 못 잡으므로 Testcontainers 실제 실행으로 검증.
- 커밋 메시지 끝에: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

## File Structure

| 파일 | 책임 | 생성/수정 |
| --- | --- | --- |
| `core/src/main/resources/db/migration/V48__tenant_allowed_origin_android.sql` | CHECK 제약 교체 (web OR android key-hash) | 생성 |
| `core/src/test/java/com/crosscert/passkey/core/db/TenantAllowedOriginAndroidCheckIT.java` | DB CHECK Testcontainers 검증 | 생성 |
| `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormat.java` | origin 형식 검증 유틸 (web/android 두 패턴) | 생성 |
| `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormatTest.java` | 유틸 단위 테스트 | 생성 |
| `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java` | create/update 시 origin 형식 검증 호출 | 수정 |
| `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java` | 잘못된 origin 거부 테스트 | 수정 |
| `admin-ui/src/lib/apkKeyHash.ts` | SHA-256 지문(hex) → apk-key-hash 변환 순수 함수 | 생성 |
| `admin-ui/src/lib/apkKeyHash.test.ts` | 변환 함수 단위 테스트 | 생성 |
| `admin-ui/src/pages/tenant/WebauthnConfigTab.tsx` | `validateOrigin` android 분기 + Android 키 입력 UX + 칩 라벨링 | 수정 |
| `admin-ui/src/pages/tenant/WebauthnConfigTab.test.ts` | `validateOrigin` android 분기 테스트 | 수정 |
| `webauthn/src/test/java/com/crosscert/passkey/webauthn/clientdata/ClientDataValidatorTest.java` | android origin 통과 회귀 테스트 | 수정 |

---

## Task 1: DB CHECK 제약 완화 (V48 마이그레이션)

**Files:**
- Create: `core/src/main/resources/db/migration/V48__tenant_allowed_origin_android.sql`
- Test: `core/src/test/java/com/crosscert/passkey/core/db/TenantAllowedOriginAndroidCheckIT.java`

**Interfaces:**
- Consumes: 기존 테이블 `tenant_allowed_origin`, 제약 `ck_tao_origin_format` (V21에서 생성).
- Produces: `tenant_allowed_origin.origin`이 `android:apk-key-hash:<43자>` 행을 받아들임. 잘못된 형식(43자 아님, http/android 외 scheme)은 거부.

- [ ] **Step 1: 마이그레이션 SQL 작성**

Create `core/src/main/resources/db/migration/V48__tenant_allowed_origin_android.sql`:

```sql
-- ============================================================
-- tenant_allowed_origin.origin 에 Android 네이티브 앱 origin 형식 허용.
-- WebAuthn Android 클라이언트는 clientData.origin 을
--   android:apk-key-hash:<base64url(SHA-256(서명인증서))>  (padding 없는 43자)
-- 로 보낸다. 기존 web(https?://) origin 검증은 그대로 두고, OR 로 android
-- key-hash 패턴을 추가한다. 제약 이름은 V21 과 동일하게 유지(재현성).
-- ============================================================

ALTER TABLE tenant_allowed_origin DROP CONSTRAINT ck_tao_origin_format;

ALTER TABLE tenant_allowed_origin ADD CONSTRAINT ck_tao_origin_format CHECK (
  REGEXP_LIKE(origin, '^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$')
  OR REGEXP_LIKE(origin, '^android:apk-key-hash:[A-Za-z0-9_-]{43}$')
);
```

- [ ] **Step 2: DB CHECK 검증 IT 작성 (실패 확인용)**

Create `core/src/test/java/com/crosscert/passkey/core/db/TenantAllowedOriginAndroidCheckIT.java`.

먼저 같은 디렉터리/패키지에 기존 Testcontainers IT가 있는지 확인하고 그 베이스 클래스/애너테이션 패턴을 그대로 따른다:

Run: `find core/src/test -name '*IT.java' | head` 로 기존 IT 1개를 열어 `@SpringBootTest`/`@Testcontainers`/`JdbcTemplate` 또는 `DataSource` 주입 패턴을 확인한 뒤 동일하게 작성한다.

테스트 본문 (JdbcTemplate 기준 — 기존 IT가 다른 주입을 쓰면 거기에 맞춰 INSERT 실행 방식만 교체):

```java
package com.crosscert.passkey.core.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TenantAllowedOriginAndroidCheckIT {

    @Autowired
    JdbcTemplate jdbc;

    // 43자 base64url 더미 해시 (A 43개)
    private static final String VALID_HASH =
            "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private byte[] seedTenant() {
        // 테스트 스키마에 tenant 1개 시드 후 그 id 반환.
        // 기존 IT 의 tenant 시드 헬퍼가 있으면 재사용. 없으면 raw insert.
        byte[] id = new byte[16];
        UUID u = UUID.randomUUID();
        // (실제 구현 시: 기존 IT 의 tenant 생성 유틸/시드를 호출)
        return id;
    }

    @Test
    void acceptsAndroidApkKeyHashOrigin() {
        byte[] tenantId = seedTenant();
        int rows = jdbc.update(
                "INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order) " +
                "VALUES (SYS_GUID(), ?, ?, 0)",
                tenantId, VALID_HASH);
        assertEquals(1, rows);
    }

    @Test
    void rejectsWrongLengthApkKeyHash() {
        byte[] tenantId = seedTenant();
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update(
                    "INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order) " +
                    "VALUES (SYS_GUID(), ?, ?, 0)",
                    tenantId, "android:apk-key-hash:TOOSHORT"));
    }

    @Test
    void stillRejectsNonUrlNonAndroidOrigin() {
        byte[] tenantId = seedTenant();
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update(
                    "INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order) " +
                    "VALUES (SYS_GUID(), ?, ?, 0)",
                    tenantId, "ftp://example.com"));
    }
}
```

> 주의: `seedTenant()`는 placeholder가 아니라 "기존 IT의 tenant 시드 헬퍼를 재사용하라"는 지시다. 구현 시 같은 패키지 IT(예: `TenantKpiIT`)에서 tenant를 만드는 방식을 그대로 복사한다. tenant 시드 없이 FK 위반이 나면 안 된다.

- [ ] **Step 3: 마이그레이션 전(V48 없이)으로 IT를 돌려 android origin이 거부되는지 확인**

V48 파일을 잠시 다른 이름으로 옮기거나, 우선 `acceptsAndroidApkKeyHashOrigin`만 실행:

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.db.TenantAllowedOriginAndroidCheckIT.acceptsAndroidApkKeyHashOrigin'`
Expected: FAIL — V21 제약이 android origin INSERT를 거부(DataIntegrityViolationException). 이로써 테스트가 실제 제약을 검증함을 확인.

- [ ] **Step 4: V48 적용 후 전체 IT 통과 확인**

V48을 제자리에 두고:

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.db.TenantAllowedOriginAndroidCheckIT'`
Expected: PASS (3 테스트 모두). android origin INSERT 성공, 잘못된 길이·비-URL origin 거부.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/db/migration/V48__tenant_allowed_origin_android.sql \
        core/src/test/java/com/crosscert/passkey/core/db/TenantAllowedOriginAndroidCheckIT.java
git commit -m "feat(db): tenant_allowed_origin 에 android:apk-key-hash origin 허용 (V48)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 백엔드 origin 형식 검증 유틸

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormat.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormatTest.java`

**Interfaces:**
- Produces: `AllowedOriginFormat.isValid(String origin): boolean` — DB CHECK와 1:1 대응. web 패턴 또는 android key-hash 패턴이면 true, 아니면 false. null/blank는 false.

- [ ] **Step 1: 유틸 단위 테스트 작성**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormatTest.java`:

```java
package com.crosscert.passkey.admin.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AllowedOriginFormatTest {

    private static final String HASH43 =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 chars

    @Test
    void acceptsHttpsOrigin() {
        assertTrue(AllowedOriginFormat.isValid("https://example.com"));
        assertTrue(AllowedOriginFormat.isValid("https://sub.example.com:8443"));
        assertTrue(AllowedOriginFormat.isValid("http://localhost:9090"));
    }

    @Test
    void acceptsAndroidApkKeyHash() {
        assertTrue(AllowedOriginFormat.isValid("android:apk-key-hash:" + HASH43));
    }

    @Test
    void rejectsWrongLengthApkKeyHash() {
        assertFalse(AllowedOriginFormat.isValid("android:apk-key-hash:TOOSHORT"));
        assertFalse(AllowedOriginFormat.isValid("android:apk-key-hash:" + HASH43 + "X")); // 44
    }

    @Test
    void rejectsApkKeyHashWithBadChars() {
        // '+' '/' '=' 는 base64url 이 아님
        String bad = "android:apk-key-hash:" + "+/=".concat("A".repeat(40));
        assertFalse(AllowedOriginFormat.isValid(bad));
    }

    @Test
    void rejectsOtherSchemes() {
        assertFalse(AllowedOriginFormat.isValid("ftp://example.com"));
        assertFalse(AllowedOriginFormat.isValid("ios:something"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertFalse(AllowedOriginFormat.isValid(null));
        assertFalse(AllowedOriginFormat.isValid("   "));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.AllowedOriginFormatTest'`
Expected: FAIL — `AllowedOriginFormat` 클래스 없음(컴파일 에러).

- [ ] **Step 3: 유틸 구현**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormat.java`:

```java
package com.crosscert.passkey.admin.tenant;

import java.util.regex.Pattern;

/**
 * tenant_allowed_origin.origin 의 허용 형식. DB CHECK(ck_tao_origin_format)와
 * 1:1 대응한다.
 *  - web:     https?://host[:port]
 *  - android: android:apk-key-hash:<43자 base64url(no padding)>
 * 형식 위반을 DB ORA 에러 전에 앱 레벨에서 잡아 명확한 400 을 준다.
 */
public final class AllowedOriginFormat {

    private static final Pattern WEB =
            Pattern.compile("^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$");

    private static final Pattern ANDROID =
            Pattern.compile("^android:apk-key-hash:[A-Za-z0-9_-]{43}$");

    private AllowedOriginFormat() {}

    public static boolean isValid(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return WEB.matcher(origin).matches() || ANDROID.matcher(origin).matches();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.AllowedOriginFormatTest'`
Expected: PASS (6 테스트 모두).

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormat.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/AllowedOriginFormatTest.java
git commit -m "feat(admin): origin 형식 검증 유틸 AllowedOriginFormat (web/android)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: TenantAdminService 에 origin 형식 검증 연결

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java:124-145` (create), `:186-247` (update)
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`

**Interfaces:**
- Consumes: `AllowedOriginFormat.isValid(String)` (Task 2), `ErrorCode.INVALID_INPUT`, `BusinessException`.
- Produces: create/update가 형식 위반 origin을 만나면 `BusinessException(ErrorCode.INVALID_INPUT)`을 던진다(DB까지 가기 전).

- [ ] **Step 1: 거부 테스트 작성**

기존 `TenantAdminServiceTest.java`를 열어 create/update 테스트 패턴(mock 주입, 호출 방식)을 확인한 뒤, 다음 테스트를 추가한다. 기존 테스트가 `@ExtendWith(MockitoExtension.class)` + `@Mock` 패턴이면 그에 맞춰 작성:

```java
@Test
void create_rejects_malformed_origin() {
    var req = new TenantAdminDto.TenantCreateRequest(
            "acme", "Acme", "acme.example.com", "Acme RP",
            java.util.List.of("ftp://bad.example.com"),   // 형식 위반
            java.util.Set.of("none"),
            true, false, "NONE", 60000);

    var ex = org.junit.jupiter.api.Assertions.assertThrows(
            com.crosscert.passkey.core.api.BusinessException.class,
            () -> service.create(req, java.util.UUID.randomUUID(), "admin@x.com"));
    org.junit.jupiter.api.Assertions.assertEquals(
            com.crosscert.passkey.core.api.ErrorCode.INVALID_INPUT, ex.errorCode());
}
```

> `ex.errorCode()` 접근자 이름은 기존 `BusinessException` 정의를 확인해 맞춘다(`getErrorCode()`일 수도 있음). 기존 테스트에서 BusinessException을 검증하는 코드가 있으면 그 접근자를 그대로 쓴다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest.create_rejects_malformed_origin'`
Expected: FAIL — 현재는 형식 검증이 없어 예외가 안 나거나 다른 예외가 남.

- [ ] **Step 3: create/update 에 검증 추가**

`TenantAdminService.java`에 private 헬퍼를 추가하고 create/update에서 호출한다.

헬퍼 추가 (클래스 내, 예: `lookup` 메서드 근처):

```java
    private static void validateOriginFormats(List<String> origins) {
        for (String origin : origins) {
            if (!AllowedOriginFormat.isValid(origin)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
    }
```

`create()` 안, allowedOrigins 루프 직전(현재 137행 `int order = 0;` 위)에 추가:

```java
        validateOriginFormats(req.allowedOrigins());
        int order = 0;
        for (String origin : req.allowedOrigins()) {
            t.addAllowedOrigin(origin, order++);
        }
```

`update()` 안, `replaceAllowedOrigins(t, req.allowedOrigins());` 호출 직전(현재 217행)에 추가:

```java
        validateOriginFormats(req.allowedOrigins());
        replaceAllowedOrigins(t, req.allowedOrigins());
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest'`
Expected: PASS (새 테스트 포함, 기존 테스트 회귀 없음).

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java
git commit -m "feat(admin): tenant create/update 에서 origin 형식 검증 (400 fail-fast)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: admin-ui — SHA-256 지문 → apk-key-hash 변환 유틸

**Files:**
- Create: `admin-ui/src/lib/apkKeyHash.ts`
- Test: `admin-ui/src/lib/apkKeyHash.test.ts`

**Interfaces:**
- Produces:
  - `apkKeyHashFromFingerprint(hex: string): { ok: true; value: string } | { ok: false; error: string }` — hex(콜론/공백 허용) → `android:apk-key-hash:<43자>`.
  - `APK_KEY_HASH_PREFIX = 'android:apk-key-hash:'` (export 상수).
  - `isApkKeyHashOrigin(s: string): boolean`.

- [ ] **Step 1: 변환 유틸 단위 테스트 작성**

Create `admin-ui/src/lib/apkKeyHash.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { apkKeyHashFromFingerprint, isApkKeyHashOrigin, APK_KEY_HASH_PREFIX } from './apkKeyHash';

describe('apkKeyHashFromFingerprint', () => {
  // 32바이트 0x00 → SHA-256 자리 더미. base64url(32x 0x00) = "AAAA...A" (43 'A').
  const ALL_ZERO_HEX = '00:'.repeat(31) + '00'; // 32 bytes, colon-separated
  const EXPECTED_ZERO = APK_KEY_HASH_PREFIX + 'A'.repeat(43);

  it('콜론 구분 64 hex 를 apk-key-hash 로 변환한다', () => {
    const r = apkKeyHashFromFingerprint(ALL_ZERO_HEX);
    expect(r).toEqual({ ok: true, value: EXPECTED_ZERO });
  });

  it('콜론 없는 64 hex 도 허용한다', () => {
    const r = apkKeyHashFromFingerprint('00'.repeat(32));
    expect(r).toEqual({ ok: true, value: EXPECTED_ZERO });
  });

  it('대문자/소문자 hex 를 모두 허용한다', () => {
    const upper = apkKeyHashFromFingerprint('AB'.repeat(32));
    const lower = apkKeyHashFromFingerprint('ab'.repeat(32));
    expect(upper.ok).toBe(true);
    expect(upper).toEqual(lower);
  });

  it('변환 결과는 항상 43자 base64url 이다', () => {
    const r = apkKeyHashFromFingerprint('1F'.repeat(32));
    expect(r.ok).toBe(true);
    if (r.ok) {
      const body = r.value.slice(APK_KEY_HASH_PREFIX.length);
      expect(body).toMatch(/^[A-Za-z0-9_-]{43}$/);
    }
  });

  it('hex 길이가 64 가 아니면 거부한다', () => {
    expect(apkKeyHashFromFingerprint('00'.repeat(20)).ok).toBe(false); // 40 hex
    expect(apkKeyHashFromFingerprint('00'.repeat(40)).ok).toBe(false); // 80 hex
  });

  it('hex 가 아닌 문자가 있으면 거부한다', () => {
    expect(apkKeyHashFromFingerprint('ZZ'.repeat(32)).ok).toBe(false);
  });

  it('빈 입력은 거부한다', () => {
    expect(apkKeyHashFromFingerprint('   ').ok).toBe(false);
  });
});

describe('isApkKeyHashOrigin', () => {
  it('android prefix 면 true', () => {
    expect(isApkKeyHashOrigin(APK_KEY_HASH_PREFIX + 'A'.repeat(43))).toBe(true);
  });
  it('https origin 이면 false', () => {
    expect(isApkKeyHashOrigin('https://example.com')).toBe(false);
  });
});
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd admin-ui && npx vitest run src/lib/apkKeyHash.test.ts`
Expected: FAIL — `apkKeyHash` 모듈 없음.

- [ ] **Step 3: 변환 유틸 구현**

Create `admin-ui/src/lib/apkKeyHash.ts`:

```ts
// Android 네이티브 WebAuthn 클라이언트의 clientData.origin 은
//   android:apk-key-hash:<base64url(SHA-256(서명인증서))>  (padding 없는 43자)
// 형태다. 테넌트는 keytool 출력의 SHA-256 지문(hex)을 그대로 붙여넣고,
// 여기서 base64url 로 변환한다.

export const APK_KEY_HASH_PREFIX = 'android:apk-key-hash:';

export function isApkKeyHashOrigin(s: string): boolean {
  return s.startsWith(APK_KEY_HASH_PREFIX);
}

// 바이트 배열 → base64url(no padding)
function toBase64Url(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function apkKeyHashFromFingerprint(
  input: string,
): { ok: true; value: string } | { ok: false; error: string } {
  // 콜론·공백 제거
  const hex = input.replace(/[\s:]/g, '');
  if (hex.length === 0) {
    return { ok: false, error: 'SHA-256 지문을 입력하세요.' };
  }
  if (hex.length !== 64) {
    return {
      ok: false,
      error: `SHA-256 지문은 32바이트(64 hex)여야 합니다. 입력 길이: ${hex.length}`,
    };
  }
  if (!/^[0-9a-fA-F]{64}$/.test(hex)) {
    return { ok: false, error: '지문에 16진수가 아닌 문자가 있습니다.' };
  }
  const bytes = new Uint8Array(32);
  for (let i = 0; i < 32; i++) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return { ok: true, value: APK_KEY_HASH_PREFIX + toBase64Url(bytes) };
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd admin-ui && npx vitest run src/lib/apkKeyHash.test.ts`
Expected: PASS (모든 테스트).

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/lib/apkKeyHash.ts admin-ui/src/lib/apkKeyHash.test.ts
git commit -m "feat(admin-ui): SHA-256 지문→apk-key-hash 변환 유틸

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: validateOrigin 에 android 분기 추가

**Files:**
- Modify: `admin-ui/src/pages/tenant/WebauthnConfigTab.tsx:35-59` (validateOrigin)
- Test: `admin-ui/src/pages/tenant/WebauthnConfigTab.test.ts`

**Interfaces:**
- Consumes: `apkKeyHashFromFingerprint`, `isApkKeyHashOrigin`, `APK_KEY_HASH_PREFIX` (Task 4).
- Produces: `validateOrigin(input, rpId)`가 입력이 `android:apk-key-hash:` prefix면 43자 형식만 검증하고 통과(rpId 범위 검사 건너뜀). 기존 web origin 동작은 불변.

- [ ] **Step 1: android 분기 테스트 추가**

`WebauthnConfigTab.test.ts`에 추가 (기존 파일 끝 `});` 앞):

```ts
  it('이미 완성된 android:apk-key-hash origin 은 rpId 범위와 무관하게 통과한다', () => {
    const apk = 'android:apk-key-hash:' + 'A'.repeat(43);
    const r = validateOrigin(apk, RP);
    expect(r).toEqual({ ok: true, value: apk });
  });

  it('android:apk-key-hash 가 43자가 아니면 거부한다', () => {
    const r = validateOrigin('android:apk-key-hash:TOOSHORT', RP);
    expect(r.ok).toBe(false);
  });
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd admin-ui && npx vitest run src/pages/tenant/WebauthnConfigTab.test.ts`
Expected: FAIL — 현재 `validateOrigin`은 `android:` scheme을 URL 파싱 실패 또는 scheme 거부로 막음.

- [ ] **Step 3: validateOrigin 에 분기 추가**

`WebauthnConfigTab.tsx` 상단 import에 추가:

```ts
import { isApkKeyHashOrigin, APK_KEY_HASH_PREFIX } from '@/lib/apkKeyHash';
```

`validateOrigin` 함수 본문 맨 앞(현재 36행 `const raw = input.trim();` 다음, 빈 입력 체크 뒤)에 android 분기를 넣는다. 수정 후 함수 앞부분:

```ts
export function validateOrigin(input: string, rpId: string): { ok: true; value: string } | { ok: false; error: string } {
  const raw = input.trim();
  if (!raw) return { ok: false, error: 'origin 을 입력하세요.' };

  // Android 네이티브 앱 origin: android:apk-key-hash:<43자 base64url>.
  // 도메인이 아니라 앱 서명키 해시이므로 rpId 서브도메인 범위 검사를 적용하지 않는다.
  if (isApkKeyHashOrigin(raw)) {
    const body = raw.slice(APK_KEY_HASH_PREFIX.length);
    if (/^[A-Za-z0-9_-]{43}$/.test(body)) {
      return { ok: true, value: raw };
    }
    return { ok: false, error: 'android:apk-key-hash 값은 43자 base64url 이어야 합니다.' };
  }

  // 스킴이 없으면 https:// 를 가정해 파싱(사용자가 host만 입력한 경우 허용).
  const withScheme = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
  // ... (이하 기존 코드 그대로)
```

기존 36-39행의 `const raw`/빈 입력 체크는 위에서 흡수되므로 중복 제거(한 번만 선언).

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd admin-ui && npx vitest run src/pages/tenant/WebauthnConfigTab.test.ts`
Expected: PASS (새 2건 + 기존 12건 모두).

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/tenant/WebauthnConfigTab.tsx admin-ui/src/pages/tenant/WebauthnConfigTab.test.ts
git commit -m "feat(admin-ui): validateOrigin 에 android:apk-key-hash 분기 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: admin-ui — Android 키 입력 UX + 칩 라벨링

**Files:**
- Modify: `admin-ui/src/pages/tenant/WebauthnConfigTab.tsx` (origins Field 영역 255-278행, addOrigin 핸들러 146-162행, 컴포넌트 state)

**Interfaces:**
- Consumes: `apkKeyHashFromFingerprint` (Task 4), 기존 `addOrigin`/`draft.origins`.
- Produces: origins 섹션에 "Android 앱 키 추가" 입력 경로. SHA-256 지문 입력 → 변환 → `draft.origins`에 push. 칩은 android origin을 구분 표시.

> 이 Task는 UI 동작이라 단위 테스트보다 수동 확인 위주다. 변환·검증 로직은 Task 4·5에서 이미 테스트됨. 여기서는 배선(wiring)만 한다.

- [ ] **Step 1: Android 지문 입력 state 추가**

`WebauthnConfigTab` 컴포넌트의 originInput state 근처(127행 부근)에 추가:

```ts
  const [apkInput, setApkInput] = useState('');
```

import에 (Task 5에서 이미 apkKeyHash import 추가됨) `apkKeyHashFromFingerprint`를 더한다:

```ts
import { apkKeyHashFromFingerprint, isApkKeyHashOrigin, APK_KEY_HASH_PREFIX } from '@/lib/apkKeyHash';
```

- [ ] **Step 2: addApkKey 핸들러 추가**

`addOrigin` 함수(162행) 다음에 추가:

```ts
  function addApkKey() {
    if (!draft) return;
    const conv = apkKeyHashFromFingerprint(apkInput);
    if (!conv.ok) {
      setOriginError(conv.error);
      return;
    }
    if (draft.origins.includes(conv.value)) {
      setOriginError('이미 추가된 Android 키입니다.');
      return;
    }
    setDraft({ ...draft, origins: [...draft.origins, conv.value] });
    setApkInput('');
    setOriginError(null);
  }
```

- [ ] **Step 3: 칩 라벨링 — android origin 구분 표시**

origins 칩 렌더(258-263행)를 android 여부에 따라 라벨을 다르게 표시하도록 교체:

```tsx
                  {draft.origins.map((o) => (
                    <span key={o} className="chip mono" style={{ fontSize: 11 }}>
                      {isApkKeyHashOrigin(o)
                        ? `🤖 ${o.slice(APK_KEY_HASH_PREFIX.length, APK_KEY_HASH_PREFIX.length + 8)}…`
                        : o}
                      <button className="chip__x" onClick={() => removeOrigin(o)}><Icons.X size={11} /></button>
                    </span>
                  ))}
```

- [ ] **Step 4: Android 키 입력칸 추가**

origins Field(256-278행)의 web origin 입력 div 아래, `{originError && (...)}` 블록 위에 Android 입력 행을 추가:

```tsx
                <div style={{ display: 'flex', gap: 6, marginTop: 8, alignItems: 'center' }}>
                  <input
                    className="input mono"
                    placeholder="Android 서명키 SHA-256 지문 (keytool 출력 붙여넣기) 후 Enter"
                    style={{ flex: 1, fontSize: 12 }}
                    value={apkInput}
                    onChange={(e) => { setApkInput(e.target.value); if (originError) setOriginError(null); }}
                    onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addApkKey())}
                  />
                  <button className="btn btn--sm" onClick={addApkKey}>앱 키 추가</button>
                </div>
```

- [ ] **Step 5: 빌드/타입체크 + 기존 테스트 회귀 확인**

Run: `cd admin-ui && npx tsc --noEmit && npx vitest run src/pages/tenant/WebauthnConfigTab.test.ts src/lib/apkKeyHash.test.ts`
Expected: 타입 에러 0, 테스트 PASS.

- [ ] **Step 6: Commit**

```bash
git add admin-ui/src/pages/tenant/WebauthnConfigTab.tsx
git commit -m "feat(admin-ui): Android 서명키 지문 입력 UX + android origin 칩 라벨링

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: verifier 회귀 테스트 (android origin 통과 보장)

**Files:**
- Modify: `webauthn/src/test/java/com/crosscert/passkey/webauthn/clientdata/ClientDataValidatorTest.java`

**Interfaces:**
- Consumes: 기존 `ClientDataValidator.validate(...)` (변경 없음).
- Produces: 저장된 `android:apk-key-hash:` origin이 허용 목록에 있으면 검증 통과함을 명시하는 회귀 테스트.

> verifier 코어는 이미 `allowedOrigins.contains(origin)`로 동작하므로 코드 변경은 없다. 이 Task는 "android origin도 그냥 문자열로 통과한다"는 계약을 테스트로 못 박는다(미래 회귀 방지).

- [ ] **Step 1: android origin 통과 테스트 추가**

`ClientDataValidatorTest.java`에 추가 (기존 테스트 메서드들 사이, `acceptsValidCreateClientData` 패턴 차용):

```java
    @Test
    void acceptsAndroidApkKeyHashOrigin() {
        byte[] challenge = "the-challenge".getBytes(StandardCharsets.UTF_8);
        String androidOrigin = "android:apk-key-hash:" + "A".repeat(43);
        byte[] cd = clientDataJson("webauthn.get", b64url(challenge), androidOrigin);

        CollectedClientData parsed = validator.validate(
                cd, "webauthn.get", challenge, Set.of(androidOrigin));

        assertEquals(androidOrigin, parsed.origin());
    }

    @Test
    void rejectsAndroidOriginNotInAllowlist() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        String androidOrigin = "android:apk-key-hash:" + "B".repeat(43);
        byte[] cd = clientDataJson("webauthn.get", b64url(challenge), androidOrigin);
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.get", challenge,
                        Set.of("android:apk-key-hash:" + "A".repeat(43))));
        assertEquals(ClientDataException.Reason.ORIGIN_MISMATCH, ex.reason());
    }
```

- [ ] **Step 2: 테스트 통과 확인 (코드 변경 없이 바로 통과해야 함)**

Run: `./gradlew :webauthn:test --tests 'com.crosscert.passkey.webauthn.clientdata.ClientDataValidatorTest'`
Expected: PASS (새 2건 포함). 코드 변경 없이 통과 = verifier가 이미 android origin을 올바르게 처리함을 증명.

- [ ] **Step 3: Commit**

```bash
git add webauthn/src/test/java/com/crosscert/passkey/webauthn/clientdata/ClientDataValidatorTest.java
git commit -m "test(webauthn): android:apk-key-hash origin 통과/거부 회귀 테스트

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 통합 검증 + spec 문서에 iOS 결론 반영

**Files:**
- Modify: `docs/superpowers/specs/2026-06-20-android-apk-key-hash-origin-design.md` (이미 iOS 섹션 있음 — 최종 확인만)

**Interfaces:**
- Consumes: Task 1-7 전체.

- [ ] **Step 1: 모듈별 테스트 일괄 실행**

Run:
```
./gradlew :core:test --tests 'com.crosscert.passkey.core.db.TenantAllowedOriginAndroidCheckIT'
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.AllowedOriginFormatTest' --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest'
./gradlew :webauthn:test --tests 'com.crosscert.passkey.webauthn.clientdata.ClientDataValidatorTest'
```
Expected: 전부 PASS.

Run: `cd admin-ui && npx vitest run src/lib/apkKeyHash.test.ts src/pages/tenant/WebauthnConfigTab.test.ts`
Expected: PASS.

> 주의(메모 `project_full_build_preexisting_traps`): `./gradlew build` 전체는 SliceConfig 충돌·Oracle 컨테이너 경합으로 pre-existing 빨강이 날 수 있다. 머지 게이트로 쓰지 말고 위처럼 대상 테스트만 돌린다. 회귀 판정이 필요하면 base worktree와 대조한다.

- [ ] **Step 2: iOS 결론이 spec 에 반영됐는지 확인**

`docs/superpowers/specs/2026-06-20-android-apk-key-hash-origin-design.md`의 "### 4. iOS" 섹션이 "도메인 등록 + AASA 호스팅으로 커버, 추가 코드 불필요"를 명시하는지 확인. 이미 있으면 변경 없음.

- [ ] **Step 3: 최종 커밋 (필요 시)**

spec 변경이 있었다면:
```bash
git add docs/superpowers/specs/2026-06-20-android-apk-key-hash-origin-design.md
git commit -m "docs(spec): iOS 결론 명시 (도메인 등록으로 커버)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

변경이 없으면 이 단계는 건너뛴다.

---

## Self-Review 결과

**1. Spec coverage:**
- DB CHECK 완화 → Task 1 ✓
- 백엔드 형식 검증 유틸 → Task 2 ✓
- create/update 검증 연결 → Task 3 ✓
- admin-ui 변환 유틸 → Task 4 ✓
- validateOrigin android 분기 → Task 5 ✓
- admin-ui 입력 UX + 칩 라벨링 → Task 6 ✓
- verifier 무변경 + 회귀 테스트 → Task 7 ✓
- iOS 점검·문서화 → Task 8 ✓
- 모든 spec 섹션이 task로 커버됨.

**2. Placeholder scan:** Task 1의 `seedTenant()`는 "기존 IT 헬퍼 재사용" 지시로 명시(빈 placeholder 아님). 나머지 코드 블록은 전부 실제 코드.

**3. Type consistency:**
- `apkKeyHashFromFingerprint` 반환 `{ok:true,value}|{ok:false,error}` — Task 4 정의, Task 5·6 사용 일치.
- `APK_KEY_HASH_PREFIX`, `isApkKeyHashOrigin` — Task 4 export, Task 5·6 import 일치.
- `AllowedOriginFormat.isValid` — Task 2 정의, Task 3 사용 일치.
- `validateOrigin` 반환 타입 — 기존 시그니처 유지.
- 43자 base64url 규칙 — DB(Task1)·백엔드(Task2)·UI(Task4,5) 전 계층 동일.
