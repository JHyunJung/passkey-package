# 보안 하드닝 (CRITICAL+HIGH 4건) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-06-10 감사 confirmed 19건 중 최우선 4건(CRITICAL #1 MFA 재등록 우회, HIGH #4 MFA verify 브루트포스, HIGH #3 런타임 GRANT ALL, HIGH #2 외부 부트스트랩 비번)을 TDD로 닫는다.

**Architecture:** 2개 독립 묶음. **A(앱)**: `MfaPendingFilter` 허용 경로 축소 + `MfaController` 핸들러 이중 방어 + verify에 기존 lockout 재사용. **B(인프라)**: Flyway를 `APP_OWNER`로 분리 실행, 런타임 `APP_ADMIN_USER`는 DDL 0·audit_log SELECT+INSERT만, 외부 부트스트랩 비번 DEFINE 외부화.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Security, JUnit5 + Mockito + AssertJ(슬라이스), Testcontainers Oracle XE 21(IT), Oracle VPD/Flyway, sqlplus.

**Spec:** `docs/superpowers/specs/2026-06-10-security-hardening-design.md` (codex 리뷰 반영본, 커밋 432d2e8)

---

## File Structure

**묶음 A (앱):**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaPendingFilter.java` — 허용 경로 prefix→명시 매칭
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java` — enroll/confirm/disable pending 거부 + verify lockout
- Modify(test): `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaPendingFilterTest.java` — enroll/disable-while-pending 차단 테스트 추가
- Create(test): `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerTest.java` — verify lockout + 핸들러 pending 거부

**묶음 B (인프라):**
- Modify: `scripts/bootstrap-vpd.sql` — GRANT ALL 제거, APP_OWNER에 CREATE VIEW, V8 EXEMPT grant 흡수
- Modify: `scripts/bootstrap-external.sql` — 위 + 비번 DEFINE 외부화 + 빈 값 가드
- Modify: `scripts/init-db-external.sh` — env→DEFINE 주입, `:93-94` 하드코딩 제거
- Modify: `core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql` — `GRANT EXEMPT ACCESS POLICY TO APP_OWNER` 제거(부트스트랩으로 이동)
- Modify: `admin-app/src/main/resources/application.yml` — `spring.flyway.user/password` 분리
- Modify(test): `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java` — flyway 자격증명 추가 + audit_log DDL 거부 회귀

---

## 묶음 A — admin-auth (앱)

### Task A1: MfaPendingFilter 허용 경로를 verify로만 축소 (#1-a)

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaPendingFilter.java:73-80`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaPendingFilterTest.java`

- [ ] **Step 1: 실패 테스트 추가 — enroll/disable/confirm-while-pending는 403**

`MfaPendingFilterTest.java`에 기존 `requestWithSession`/`authenticate` 헬퍼를 재사용해 추가:

```java
    @Test
    void pending_mfaEnrollPath_is403() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/mfa/enroll", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(res.getContentAsString()).contains("mfa_required");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void pending_mfaDisablePath_is403() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/mfa/disable", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void pending_mfaConfirmPath_is403() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/mfa/confirm", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        verify(chain, never()).doFilter(req, res);
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.MfaPendingFilterTest'`
Expected: FAIL — 새 3개 테스트가 200(chain 호출됨)을 받아 실패. 기존 `pending_mfaVerifyPath_passesThrough`는 PASS 유지.

- [ ] **Step 3: 허용 경로를 명시 매칭으로 교체**

`MfaPendingFilter.java:73-80`의 `isAllowedWhilePending` 본문 교체:

```java
    private boolean isAllowedWhilePending(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null) return false;
        // Only the second-factor completion endpoint is reachable while pending.
        // enroll/confirm/disable require a fully-authenticated (non-pending)
        // session — narrowing from the old "/admin/api/mfa/" prefix closes the
        // re-enroll bypass where a password-only session overwrote the TOTP secret.
        return uri.equals("/admin/api/mfa/verify")
                || uri.equals("/admin/api/me")
                || uri.equals("/admin/logout");
    }
```

`MfaPendingFilter.java:24-30`의 Javadoc allow-list 목록도 갱신: `/admin/api/mfa/**` → `/admin/api/mfa/verify`로 수정, "verify / enroll endpoints" → "verify endpoint only".

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.MfaPendingFilterTest'`
Expected: PASS (전부)

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaPendingFilter.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaPendingFilterTest.java
git commit -m "fix(admin-mfa): MfaPendingFilter 허용 경로를 verify로 축소 (#1 재등록 우회 1차 방어)"
```

---

### Task A2: MfaController enroll/confirm/disable 핸들러 pending 거부 (#1-b 이중 방어)

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerTest.java` (신규)

- [ ] **Step 1: 신규 슬라이스 테스트 작성 — pending 세션에서 enroll/disable 직접 거부**

`MfaControllerTest.java` 생성. `MockMvc` standalone 대신 핸들러를 직접 호출하는 단위 테스트(MfaController는 의존성 5개라 Mockito로 stub):

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaControllerTest {

    private final TotpService totp = mock(TotpService.class);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final RecoveryCodeService recoveryCodes = mock(RecoveryCodeService.class);
    private final MfaSecretCipher cipher = mock(MfaSecretCipher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);

    // max-attempts=5, duration=15m mirror AdminSecurityConfig defaults.
    private final MfaController controller =
            new MfaController(totp, users, clock, recoveryCodes, cipher, 5, Duration.ofMinutes(15));

    private HttpServletRequest pendingRequest(boolean pending) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))
                .thenReturn(pending ? Boolean.TRUE : null);
        return req;
    }

    private TestingAuthenticationToken auth() {
        return new TestingAuthenticationToken("alice@example.com", "x", "ROLE_PLATFORM_OPERATOR");
    }

    @Test
    void enroll_whilePending_is403_andSecretUntouched() {
        ResponseEntity<?> resp = controller.enroll(auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // critical: pending enroll must NOT overwrite the secret.
        verify(users, never()).save(any());
        verify(totp, never()).newSecretBase32();
    }

    @Test
    void disable_whilePending_is403() {
        ResponseEntity<?> resp = controller.disable(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(users, never()).save(any());
    }

    @Test
    void confirm_whilePending_is403() {
        ResponseEntity<?> resp = controller.confirm(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(users, never()).save(any());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인 (컴파일 실패 단계)**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.MfaControllerTest'`
Expected: FAIL — 컴파일 에러. `MfaController` 생성자가 7인자가 아니고(현재 5), enroll/disable/confirm이 `HttpServletRequest` 파라미터를 안 받음.

- [ ] **Step 3: MfaController 생성자에 lockout 설정 + 핸들러에 pending 가드 추가**

`MfaController.java` 필드/생성자 교체 (현재 35-48행):

```java
    private final TotpService totp;
    private final AdminUserRepository users;
    private final Clock clock;
    private final RecoveryCodeService recoveryCodes;
    private final MfaSecretCipher secretCipher;
    private final int maxAttempts;
    private final java.time.Duration lockDuration;

    public MfaController(TotpService totp, AdminUserRepository users, Clock clock,
                         RecoveryCodeService recoveryCodes, MfaSecretCipher secretCipher,
                         @org.springframework.beans.factory.annotation.Value("${passkey.admin.lockout.max-attempts:5}") int maxAttempts,
                         @org.springframework.beans.factory.annotation.Value("${passkey.admin.lockout.duration:PT15M}") java.time.Duration lockDuration) {
        this.totp = totp;
        this.users = users;
        this.clock = clock;
        this.recoveryCodes = recoveryCodes;
        this.secretCipher = secretCipher;
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
    }
```

공용 pending 가드 헬퍼를 클래스 하단(`mask` 근처)에 추가:

```java
    /**
     * Reject enroll/confirm/disable while the session is still MFA-pending.
     * Defense-in-depth behind MfaPendingFilter (which already blocks these
     * paths) — if the filter is ever bypassed or reordered, the handler still
     * refuses to mutate the secret from a password-only session.
     */
    private static boolean isPending(HttpServletRequest req) {
        var session = req.getSession(false);
        return session != null
                && Boolean.TRUE.equals(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR));
    }

    private static ResponseEntity<?> mfaRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "mfa_required"));
    }
```

`enroll` 시그니처/본문(현재 100행) 교체 — `HttpServletRequest req` 추가 + 진입부 가드:

```java
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(Authentication auth, HttpServletRequest req) {
        if (isPending(req)) return mfaRequired();
        String email = auth.getName();
```
(이후 본문은 기존과 동일)

`confirm` 시그니처(현재 128행) 교체:

```java
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody VerifyRequest body, Authentication auth,
                                     HttpServletRequest req) {
        if (isPending(req)) return mfaRequired();
        String email = auth.getName();
```
(이후 본문 동일)

`disable` 시그니처(현재 150행) 교체:

```java
    @PostMapping("/disable")
    public ResponseEntity<?> disable(@RequestBody VerifyRequest body, Authentication auth,
                                     HttpServletRequest req) {
        if (isPending(req)) return mfaRequired();
        String email = auth.getName();
```
(이후 본문 동일)

> 필요한 import: `jakarta.servlet.http.HttpServletRequest`는 이미 import됨(verify가 사용). 추가 import 없음.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.MfaControllerTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerTest.java
git commit -m "fix(admin-mfa): enroll/confirm/disable 핸들러 pending 거부 (#1 이중 방어) + lockout 설정 주입"
```

---

### Task A3: MfaController.verify에 브루트포스 lockout 적용 (#4)

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java:56-84` (verify)
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerTest.java`

- [ ] **Step 1: 실패 테스트 추가 — verify 실패 누적 lock + 성공 리셋 + lock 중 거부**

`MfaControllerTest.java`에 추가. `AdminUser`는 `AdminUser.create()` 팩토리 + setter 사용, `mfaSecret`/이메일 stub:

```java
    private AdminUser enrolledUser() {
        AdminUser u = AdminUser.create();
        u.setEmail("alice@example.com");
        u.setMfaEnabled(true);
        u.setMfaSecret("sealed-secret");
        return u;
    }

    @Test
    void verify_repeatedFailure_locksAccount() {
        AdminUser u = enrolledUser();
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(any(), any(), anyLong())).thenReturn(false);   // always wrong
        when(recoveryCodes.consume(any(), any())).thenReturn(false);

        HttpServletRequest req = pendingRequest(true);
        for (int i = 0; i < 5; i++) {
            controller.verify(new MfaController.VerifyRequest("000000"), auth(), req);
        }
        // After 5 failures, recordFailedLogin should have set lockedUntil.
        assertThat(u.isLocked(clock.instant())).isTrue();
    }

    @Test
    void verify_whenLocked_rejectsEvenCorrectCode() {
        AdminUser u = enrolledUser();
        u.recordFailedLogin(clock.instant(), 1, Duration.ofMinutes(15)); // already locked
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(any(), any(), anyLong())).thenReturn(true);   // correct code

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verify_success_resetsFailedCount() {
        AdminUser u = enrolledUser();
        u.recordFailedLogin(clock.instant(), 5, Duration.ofMinutes(15)); // 1 fail, not locked
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(any(), any(), anyLong())).thenReturn(true);

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(u.isLocked(clock.instant())).isFalse();
    }
```

import 추가: `import static org.mockito.ArgumentMatchers.anyLong;`

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.MfaControllerTest'`
Expected: FAIL — `verify_whenLocked_rejectsEvenCorrectCode`가 200을 받음(lock 미검사), `verify_repeatedFailure_locksAccount`가 lock 안 됨.

- [ ] **Step 3: verify에 lock 검사 + 실패 누적 + 성공 리셋 추가**

`MfaController.java`의 `verify`(56-84행) 교체:

```java
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody VerifyRequest body,
                                    Authentication auth,
                                    HttpServletRequest req) {
        String email = auth.getName();
        AdminUser u = users.findByEmail(email).orElse(null);

        // Brute-force lockout: reuse the primary-login lockout fields so a
        // throttle exists on the second factor too. A locked account is
        // refused before any code check (fail-closed). The lock also gates
        // primary login via AdminUserDetails.isAccountNonLocked — acceptable
        // because reaching here means the password is already compromised.
        if (u != null && u.isLocked(clock.instant())) {
            log.warn("admin mfa verify blocked (locked): email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }

        String code = body == null ? null : body.code();
        boolean totpOk = validCode(u, code);
        boolean recoveryOk = false;
        if (!totpOk && u != null) {
            recoveryOk = recoveryCodes.consume(u.getId(), code);
        }
        if (!totpOk && !recoveryOk) {
            if (u != null) {
                boolean wasLocked = u.isLocked(clock.instant());
                u.recordFailedLogin(clock.instant(), maxAttempts, lockDuration);
                users.save(u);
                // recordFailedLogin auto-locks once maxAttempts is reached.
                if (!wasLocked && u.isLocked(clock.instant())) {
                    var s = req.getSession(false);
                    if (s != null) s.invalidate();   // kill the pending session on lockout
                    log.warn("admin mfa verify lockout triggered: email={}", mask(email));
                }
            }
            log.warn("admin mfa verify failed: email={}", mask(email));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_code"));
        }
        u.recordSuccessfulLogin();   // reset shared failed-login counter on success
        users.save(u);
        var session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(MfaPendingFilter.MFA_PENDING_ATTR);
        }
        if (recoveryOk) {
            long left = recoveryCodes.remaining(u.getId());
            log.warn("admin mfa verify via recovery code: email={} remaining={}", mask(email), left);
            return ResponseEntity.ok(Map.of("verified", true, "usedRecoveryCode", true, "remaining", left));
        }
        log.info("admin mfa verify success: email={}", mask(email));
        return ResponseEntity.ok(Map.of("verified", true));
    }
```

> 주의: 성공 경로에서 `u.recordSuccessfulLogin()` + `users.save(u)`가 추가됐다. `u`는 이 분기에 도달하면 non-null(`validCode`/`consume`이 u null이면 false라 위에서 401 반환).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.MfaControllerTest'`
Expected: PASS (전부 6개)

- [ ] **Step 5: 묶음 A 전체 admin-app 슬라이스 테스트 회귀 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.*'`
Expected: PASS — 기존 MfaPendingFilterTest 포함 전부 그린.

- [ ] **Step 6: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/MfaControllerTest.java
git commit -m "fix(admin-mfa): verify 브루트포스 lockout (#4) — 1차 로그인 lockout 필드 통합 재사용"
```

- [ ] **Step 7: 묶음 A codex 리뷰**

Run: `/codex:review` (스테이징된 묶음 A diff 대상). P1 발견 시 해소 후 재커밋.

---

## 묶음 B — DB 권한/부트스트랩 (인프라)

> ⚠️ 이 묶음은 Testcontainers Oracle을 실제로 띄운다. 메모리 교훈: Oracle 권한/DDL은 inspection이 못 잡으므로 IT 실행이 검증의 핵심.

### Task B1: AdminFlowIT에 audit_log DDL 거부 회귀 테스트 추가 (현재 GRANT ALL이라 빨강)

**Files:**
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`

- [ ] **Step 1: 런타임 계정(APP_ADMIN_USER)이 audit_log를 변조 못 한다는 테스트 추가**

`AdminFlowIT`는 이미 런타임 풀(`jdbc`, APP_ADMIN_USER)과 owner 풀(`ownerPool`, APP_OWNER)을 가진다. 런타임 풀로 audit_log UPDATE/DELETE/DROP을 시도해 **모두 ORA 권한 오류**가 나는지 검증하는 테스트를 추가:

```java
    @Test
    void runtimeUser_cannotTamperAuditLog() {
        // APP_ADMIN_USER (admin-app runtime) must hold SELECT+INSERT only on
        // audit_log — V10's append-only design. GRANT ALL would break this.
        // This is the regression guard for finding #3.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.execute("UPDATE audit_log SET event_type = 'X' WHERE 1=0"))
            .hasMessageContaining("ORA-");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.execute("DELETE FROM audit_log WHERE 1=0"))
            .hasMessageContaining("ORA-");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.execute("DROP TABLE audit_log"))
            .hasMessageContaining("ORA-");
    }

    @Test
    void runtimeUser_canStillWriteAdminTables() {
        // Negative-of-negative: reducing GRANT ALL must NOT break runtime DML
        // on the tables admin-app actually manages. Smoke a harmless count.
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant", Integer.class);
        assertThat(n).isNotNull();
        Integer k = jdbc.queryForObject("SELECT COUNT(*) FROM api_key", Integer.class);
        assertThat(k).isNotNull();
    }
```

> `jdbc` 필드는 APP_ADMIN_USER 풀(`AdminFlowIT:131-132` 주석 "App-scoped pool (APP_ADMIN_USER)"). `tenant`/`api_key`는 런타임이 DML하는 테이블이라 SELECT 가능해야 한다.

- [ ] **Step 2: 테스트 실행 — 현재 상태에서 빨강 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.AdminFlowIT'`
Expected: `runtimeUser_cannotTamperAuditLog` **FAIL** — 현재 `bootstrap-vpd.sql:90`의 GRANT ALL이라 UPDATE/DELETE/DROP이 성공(예외 안 남). `runtimeUser_canStillWriteAdminTables`는 PASS. **이 빨강이 finding #3의 실재 증명.**

- [ ] **Step 3: 커밋 (빨강 테스트를 회귀 가드로 고정)**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
git commit -m "test(admin): audit_log DDL 변조 거부 회귀 (#3, 현재 GRANT ALL이라 의도적 빨강)"
```

> subagent-driven 실행 시: 이 커밋은 의도적으로 실패하는 테스트를 포함하므로, 다음 Task가 초록으로 만들기 전까지 CI를 돌리지 말 것. 같은 PR 내 연속 작업.

---

### Task B2: V8에서 EXEMPT grant 제거 + 부트스트랩에 APP_OWNER EXEMPT/CREATE VIEW 추가

**Files:**
- Modify: `core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql:55`
- Modify: `scripts/bootstrap-vpd.sql`
- Modify: `scripts/bootstrap-external.sql`

- [ ] **Step 1: V8의 `GRANT EXEMPT ACCESS POLICY TO APP_OWNER` 제거**

`V8__api_key_vpd_policy.sql:55`의 정확한 라인:
```sql
GRANT EXEMPT ACCESS POLICY TO APP_OWNER;
```
이 한 줄을 삭제하고 같은 자리에 주석으로 교체:
```sql
-- EXEMPT ACCESS POLICY for APP_OWNER moved to bootstrap SQL (bootstrap-vpd.sql /
-- bootstrap-external.sql). When Flyway runs as APP_OWNER it cannot grant a
-- system privilege to itself, so SYS must grant it during bootstrap. The
-- definer-rights api_key_lookup_pkg below still relies on APP_OWNER holding it.
```

> ⚠️ **Flyway 체크섬 주의.** V8이 이미 적용된 환경에서 본문 변경은 체크섬 충돌을 일으킨다. Testcontainers IT는 매번 새 DB라 무영향. dogfooding/운영 DB는 B5 이후 `flyway repair`로 체크섬 갱신하거나, 이미 APP_OWNER가 EXEMPT를 보유하므로(부트스트랩이 부여) 재적용 불필요 — V8은 이미 실행된 상태로 남고 새 부트스트랩만 권한을 보강한다. 이 plan은 **신규 DB 기준 깨끗한 상태**를 정의하고, 기존 환경 마이그레이션은 B5 IT 통과 후 별도 운영 단계로 처리.

- [ ] **Step 2: bootstrap-vpd.sql — GRANT ALL 제거 + APP_OWNER 권한 보강**

`bootstrap-vpd.sql`에서:
- `GRANT ALL PRIVILEGES TO APP_ADMIN_USER;`(90행) **삭제**
- APP_OWNER privileges 블록(현재 CREATE TABLE/SEQUENCE/... 나열부)에 추가:

```sql
GRANT CREATE VIEW         TO APP_OWNER;
GRANT EXEMPT ACCESS POLICY TO APP_OWNER;
```

> APP_ADMIN_USER는 `GRANT APP_ADMIN TO APP_ADMIN_USER`(기존)로 audit_log SELECT+INSERT 등 객체 권한을 롤 경유로 받는다. GRANT ALL 제거 후에도 마이그레이션이 부여하는 객체 GRANT로 런타임 DML이 충족된다(B5에서 검증).

- [ ] **Step 3: bootstrap-external.sql — 동일 변경**

`bootstrap-external.sql`에서:
- `GRANT ALL PRIVILEGES TO APP_ADMIN_USER;`(89행) **삭제**
- APP_OWNER privileges 블록(61-68행)에 추가:

```sql
GRANT CREATE VIEW         TO APP_OWNER;
GRANT EXEMPT ACCESS POLICY TO APP_OWNER;
```

- [ ] **Step 4: 커밋**

```bash
git add core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql \
        scripts/bootstrap-vpd.sql scripts/bootstrap-external.sql
git commit -m "fix(db): 런타임 GRANT ALL 제거 + APP_OWNER에 CREATE VIEW/EXEMPT (#3 Approach A)"
```

---

### Task B3: Flyway를 APP_OWNER로 분리 실행 (앱 설정 + IT 설정)

**Files:**
- Modify: `admin-app/src/main/resources/application.yml:10-14`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java:93-119` (`registerProps`)

- [ ] **Step 1: application.yml에 flyway 자격증명 분리 추가**

현재 `application.yml`의 `flyway:` 블록 전체:
```yaml
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: APP_OWNER
    default-schema: APP_OWNER
    # APP_OWNER schema already contains the CTX_PKG package and APP_CTX
    # context created by scripts/bootstrap-vpd.sql before Flyway ever
    # runs. baseline-on-migrate lets Flyway record V0 in its history
    # table on first run instead of refusing to start because the
    # schema is "non-empty". V1+ migrations apply normally afterwards.
    baseline-on-migrate: true
```

`default-schema: APP_OWNER` 다음 줄에 user/password 3줄을 삽입(기존 블록 보존, env 폴백):
```yaml
    default-schema: APP_OWNER
    # Finding #3 (Approach A): Flyway runs as the schema OWNER, not the
    # runtime datasource user. This lets us strip ALL DDL power from the
    # runtime APP_ADMIN_USER while migrations still create/alter objects.
    # url is omitted → Flyway falls back to spring.datasource.url (same DB).
    user: ${SPRING_FLYWAY_USER:APP_OWNER}
    password: ${SPRING_FLYWAY_PASSWORD:app_owner_pw}
```

- [ ] **Step 2: AdminFlowIT registerProps에 flyway 자격증명 등록**

`AdminFlowIT.java`의 `registerProps`(93-119행), `spring.datasource.*` 등록 직후에 추가:

```java
        // Flyway runs as the schema OWNER (APP_OWNER), runtime as APP_ADMIN_USER.
        // Finding #3 (Approach A): the runtime user no longer holds DDL power.
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD); // APP_OWNER pw == SYS_PASSWORD here
```

> `SYS_PASSWORD`는 `AdminFlowIT:79`에서 `"app_owner_pw"`이고 OracleContainer가 APP_OWNER 비번으로도 씀(주석 74-78). 따라서 APP_OWNER 접속 비번 = `SYS_PASSWORD`.

- [ ] **Step 3: IT 실행 — B1의 빨강이 초록으로 전환되는지 확인**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.AdminFlowIT'`
Expected:
- `runtimeUser_cannotTamperAuditLog` → **PASS** (GRANT ALL 제거 + Flyway=APP_OWNER로 APP_ADMIN_USER는 DDL 0)
- `runtimeUser_canStillWriteAdminTables` → PASS
- 기존 `operatorFlowEndToEnd` 11단계 → PASS (Flyway가 APP_OWNER로 완주, V40 뷰 생성 포함)

만약 Flyway가 권한 부족으로 실패하면(예: 특정 마이그레이션이 APP_OWNER 미보유 권한 요구) — 스택트레이스의 ORA 권한명을 확인해 `bootstrap-vpd.sql`의 APP_OWNER privileges 블록에 해당 `GRANT ...`를 추가하고 재실행(반복). spec이 예고한 비-단순 마이그레이션: V3(DBMS_RLS), V19(DROP 패키지/정책), V35(VPD), V40(뷰).

- [ ] **Step 4: 커밋**

```bash
git add admin-app/src/main/resources/application.yml \
        admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
git commit -m "fix(db): Flyway를 APP_OWNER로 분리 실행 (#3) — 런타임 APP_ADMIN_USER는 DDL 0"
```

---

### Task B4: 외부 부트스트랩 비번 DEFINE 외부화 + fail-fast (#2)

**Files:**
- Modify: `scripts/bootstrap-external.sql`
- Modify: `scripts/init-db-external.sh:93-94`

- [ ] **Step 1: bootstrap-external.sql 상단(첫 CREATE USER 앞)에 DEFINE + 빈 값 가드 삽입**

`bootstrap-external.sql`의 `ALTER SESSION SET CONTAINER` 직후, **APP_OWNER CREATE USER(28행) 앞**에 삽입:

```sql
-- ============================================================
-- 계정 비밀번호 — 외부 입력. 미정의/빈 값이면 부트스트랩 중단(fail-fast).
-- ⚠️ DBeaver 등 DEFINE 미지원 클라이언트: 아래 DEFINE 3줄을 강한 실제 비번으로
--    직접 치환한 뒤 실행하라. (sqlplus / init-db-external.sh 경로는 env 주입.)
-- ============================================================
DEFINE app_owner_pw = ""
DEFINE runtime_pw   = ""
DEFINE admin_pw     = ""

-- 빈 값 가드: 하나라도 비면 즉시 중단 (Oracle '' IS NULL → fail-closed).
-- WHENEVER SQLERROR EXIT (상단에 이미 설정됨)이 이 RAISE 를 비0 종료로 만든다.
BEGIN
  IF '&&app_owner_pw' IS NULL OR '&&runtime_pw' IS NULL OR '&&admin_pw' IS NULL THEN
    RAISE_APPLICATION_ERROR(-20001,
      'bootstrap 중단: app_owner_pw/runtime_pw/admin_pw 중 미정의. DEFINE 또는 env(APP_OWNER_PW/RUNTIME_PW/ADMIN_PW) 주입 필요.');
  END IF;
END;
/
```

- [ ] **Step 2: CREATE USER 3곳을 치환 변수로 교체**

`bootstrap-external.sql`의 비번 리터럴 교체:
- `CREATE USER APP_OWNER IDENTIFIED BY app_owner_pw` → `CREATE USER APP_OWNER IDENTIFIED BY "&&app_owner_pw"`
- `CREATE USER APP_RUNTIME_USER IDENTIFIED BY runtime_pw` → `... IDENTIFIED BY "&&runtime_pw"`
- `CREATE USER APP_ADMIN_USER IDENTIFIED BY admin_pw` → `... IDENTIFIED BY "&&admin_pw"`

> `EXECUTE IMMEDIATE 'CREATE USER ... IDENTIFIED BY "&&pw"'` 내부에서도 sqlplus 치환은 작은따옴표 문자열 안에서 동작(sqlplus가 Oracle 파싱 전에 치환). 큰따옴표로 감싸 특수문자 허용. 단, 비번에 `&`가 있으면 충돌 — env 주입 비번은 `&` 회피 권장(주석 명시).

- [ ] **Step 3a: 스크립트 상단에 비번 env 필수 가드 추가**

`init-db-external.sh`의 변수 정의부(SCRIPT_DIR/REPO_ROOT 설정 직후, 대략 47행 근처)에 3개 env 필수 가드를 추가. `:?`는 미설정/빈 값 시 셸이 즉시 비0 종료(fail-fast):
```bash
# 계정 비밀번호는 환경변수 필수 — 하드코딩 제거(보안 감사 #2).
: "${APP_OWNER_PW:?APP_OWNER_PW 환경변수 필요 (강한 비번)}"
: "${RUNTIME_PW:?RUNTIME_PW 환경변수 필요 (강한 비번)}"
: "${ADMIN_PW:?ADMIN_PW 환경변수 필요 (강한 비번)}"
```

- [ ] **Step 3b: bootstrap 호출을 heredoc으로 바꿔 DEFINE prepend**

현재 호출(84행):
```bash
  sqlplus -S "${SYS_CONN}" < "${SCRIPT_DIR}/bootstrap-external.sql"
```
를 heredoc으로 교체 — DEFINE 3줄이 스크립트 본문 앞에 주입되어 본문의 빈 `DEFINE` 기본값을 덮어쓴다:
```bash
  sqlplus -S "${SYS_CONN}" <<SQL
DEFINE app_owner_pw = "${APP_OWNER_PW}"
DEFINE runtime_pw   = "${RUNTIME_PW}"
DEFINE admin_pw     = "${ADMIN_PW}"
@${SCRIPT_DIR}/bootstrap-external.sql
SQL
```

- [ ] **Step 3c: 런타임 datasource 하드코딩 비번 제거**

현재(94행):
```bash
SPRING_DATASOURCE_PASSWORD='admin_pw' \
```
를:
```bash
SPRING_DATASOURCE_PASSWORD="${ADMIN_PW}" \
```
로 교체(Step 3a 가드가 이미 ADMIN_PW 존재를 보장).

- [ ] **Step 4: 셸 문법 검증**

Run: `bash -n scripts/init-db-external.sh && echo "syntax-ok"`
Expected: `syntax-ok`

- [ ] **Step 5: 빈 값 가드 동작 수동 검증 (선택, sqlplus 있으면)**

`command -v sqlplus`가 있으면: DEFINE을 빈 값으로 둔 채 bootstrap-external.sql을 dry 실행해 `ORA-20001`로 중단되는지 확인. 없으면 코드 리뷰로 갈음(가드가 첫 CREATE USER 앞에 있음을 Read로 확인).

- [ ] **Step 6: 커밋**

```bash
git add scripts/bootstrap-external.sql scripts/init-db-external.sh
git commit -m "fix(db): 외부 부트스트랩 비번 DEFINE 외부화 + fail-fast 가드 (#2) + sh 하드코딩 제거"
```

---

### Task B5: 묶음 B 전체 IT 회귀 + codex 리뷰

**Files:** (테스트 실행만)

- [ ] **Step 1: 영향받는 Testcontainers IT 전체 실행**

Run: `./gradlew :admin-app:test --tests '*IT' && ./gradlew :core:test --tests '*IT'`
Expected: PASS — `AdminFlowIT`(audit DDL 거부 + 런타임 DML + 전체 플로우), VPD IT들(`VpdIsolationIT`/`AppLevelIsolationIT` 등 — bootstrap-vpd.sql 변경 영향), `KeyRotationIT`/`MdsSchedulerIT` 모두 그린. Flyway가 APP_OWNER로 완주.

> 다른 IT들도 `bootstrap-vpd.sql`을 쓰므로(예: `VpdIsolationIT`) GRANT 변경의 광범위 영향을 여기서 잡는다. 실패 시 ORA 권한명 확인 → bootstrap APP_OWNER 권한 보강 → 재실행.

- [ ] **Step 2: 전체 빌드 (양 묶음 통합 회귀)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 슬라이스 + IT + 컴파일 전부.

- [ ] **Step 3: 묶음 B codex 리뷰**

Run: `/codex:review` (묶음 B diff 대상). 특히 SQL 치환 안전성·Flyway 분리·권한 최소성 검토. P1 해소 후 재커밋.

---

## 최종 검증

- [ ] **전체 테스트 그린**: `./gradlew build` BUILD SUCCESSFUL
- [ ] **finding별 폐쇄 확인**:
  - #1: `MfaPendingFilterTest` enroll/disable/confirm-while-pending 403 + `MfaControllerTest` 핸들러 거부 PASS
  - #4: `MfaControllerTest` lockout 3종 PASS
  - #3: `AdminFlowIT.runtimeUser_cannotTamperAuditLog` PASS + `canStillWriteAdminTables` PASS + 전체 플로우 PASS
  - #2: bootstrap-external.sql 가드가 첫 CREATE USER 앞 + sh 하드코딩 0 + `bash -n` ok
- [ ] **양 묶음 codex 리뷰 P1 0건**
- [ ] **메모리 갱신**: 하드닝 완료 시 `project_security_audit_2026_06_10.md`에 "최우선 4건 닫음(커밋)" 추가, #3 Approach A 교훈을 `feedback`/`project` 메모로 기록.

---

## 범위 밖 (이 plan 미포함 — spec과 동일)

- medium 7 / low 6 / info 1 — 별도 후속.
- `bootstrap-vpd.sql` 비번 외부화 + docker-compose 1521 → 127.0.0.1 바인딩 — 후속 백로그.
- MFA lockout 운영자 수동 unlock 경로 — 필요 시 후속(현재 시간 자동 해제).
- `MfaController.validCode`의 `mfaEnabled` 검사 — 정상 흐름 파괴 위험으로 제외.
