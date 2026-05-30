# 설정값 미적용 마감 (그룹 B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SaaS readiness P1-1(per-tenant WebAuthn ceremony 반영) + P1-5(API key scope 검증 + rotation)을 마감해 "설정값 저장만 하고 미작동" 안티패턴을 닫는다.

**Architecture:** P1-1은 ceremony start 2곳이 Tenant의 기존 3필드를 읽도록 교체(신규 스키마 무). P1-5 scope는 인증 성공 후(TenantContextHolder 설정됨 → VPD 정상) JPA로 scope 조회 + 경로→scope 매핑으로 필터에서 403 강제. P1-5 rotation은 신규 발급 + 구 키 expiresAt grace 만료(신규 스키마 무). 인접 cleanup(ApiKey active 정의·findByUserHandle) 포함.

**Tech Stack:** Java 21, Spring Boot 3, Spring Security 6, Spring Data JPA, Oracle, Jackson(ObjectNode 직접 빌드), webauthn4j(verify만), JUnit 5 + Mockito + `@WebMvcTest`.

**근거 spec:** `docs/superpowers/specs/2026-05-31-config-enforcement-group-b-design.md`

---

## File Structure

**passkey-app (ceremony + scope enforcement):**
- Modify: `passkey-app/.../fido2/registration/RegistrationStartService.java` — timeout/attestation/UV 테넌트값
- Modify: `passkey-app/.../fido2/authentication/AuthenticationStartService.java` — timeout/UV 테넌트값 + findByUserHandle
- Modify: `passkey-app/.../security/ApiKeyAuthFilter.java` — scope enforcement 삽입
- Create: `passkey-app/.../security/ApiKeyScopeResolver.java` — 경로→요구 scope 매핑
- Modify: `passkey-app/.../security/ApiKeyLookupService.java` — (scope는 별도 조회라 변경 최소; 필요 시 무변경)

**core (엔티티/repository):**
- Modify: `core/.../entity/Tenant.java` — conveyance 소문자 변환 헬퍼
- Create: `core/.../repository/ApiKeyScopeRepository.java` — scope projection 조회
- Modify: `core/.../repository/ApiKeyRepository.java` — findActiveByTenantId active 정의 통일
- Modify: `core/.../repository/CredentialRepository.java` — findByUserHandle (없으면 추가)

**admin-app (rotation):**
- Modify: `admin-app/.../apikey/ApiKeyAdminService.java` — rotate()
- Modify: `admin-app/.../apikey/ApiKeyAdminController.java` — POST /{id}/rotate
- Modify: `admin-app/.../apikey/ApiKeyAdminDto.java` — RotateResponse(평문 + oldKeyExpiresAt)

**책임 분리:** 경로→scope 매핑은 `ApiKeyScopeResolver` 단일 컴포넌트에 응집(필터에 흩지 않음). conveyance 매핑은 Tenant 도메인. rotation은 issue 로직 재사용.

---

## Task 1: P1-1 — RegistrationStartService 테넌트 설정 반영

ceremony start의 하드코딩 3개를 Tenant 값으로 교체. conveyance 대문자→소문자 변환 헬퍼를 Tenant에 추가.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/entity/TenantConveyanceTest.java`

- [ ] **Step 1: conveyance 매핑 단위 테스트 작성**

Create `core/src/test/java/com/crosscert/passkey/core/entity/TenantConveyanceTest.java`:

```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TenantConveyanceTest {

    private Tenant tenant(String conveyance) {
        Tenant t = new Tenant();
        t.setAttestationConveyance(conveyance);
        return t;
    }

    @Test
    void maps_uppercase_enum_to_webauthn_lowercase() {
        assertThat(tenant("NONE").getAttestationConveyanceLowercase()).isEqualTo("none");
        assertThat(tenant("INDIRECT").getAttestationConveyanceLowercase()).isEqualTo("indirect");
        assertThat(tenant("DIRECT").getAttestationConveyanceLowercase()).isEqualTo("direct");
        assertThat(tenant("ENTERPRISE").getAttestationConveyanceLowercase()).isEqualTo("enterprise");
    }

    @Test
    void unknown_or_null_falls_back_to_none() {
        assertThat(tenant(null).getAttestationConveyanceLowercase()).isEqualTo("none");
        assertThat(tenant("bogus").getAttestationConveyanceLowercase()).isEqualTo("none");
    }
}
```

Note: `Tenant`에 no-arg 생성 경로가 필요. 기존에 `new Tenant()`가 안 되면(생성자 확인), 테스트는 setter 기반 생성 패턴을 따른다 — 먼저 Tenant.java를 읽어 인스턴스화 방법을 확인하고 테스트를 맞춘다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/config-enforcement-group-b && ./gradlew :core:test --tests '*TenantConveyanceTest' -q`
Expected: FAIL — `getAttestationConveyanceLowercase` 없음.

- [ ] **Step 3: Tenant에 conveyance 헬퍼 추가**

`Tenant.java`의 `getAttestationConveyance()` getter 근처에 추가:

```java
    /**
     * WebAuthn options 의 attestation 값(소문자)으로 변환. DB 는 대문자 enum
     * (NONE/INDIRECT/DIRECT/ENTERPRISE, V33 CHECK 제약)을 저장하지만 WebAuthn
     * 표준 conveyance preference 는 소문자다. 알 수 없는/누락 값은 안전 기본 "none".
     */
    public String getAttestationConveyanceLowercase() {
        if (attestationConveyance == null) return "none";
        String v = attestationConveyance.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "none", "indirect", "direct", "enterprise" -> v;
            default -> "none";
        };
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests '*TenantConveyanceTest' -q`
Expected: PASS (2 tests).

- [ ] **Step 5: RegistrationStartService 하드코딩 교체**

`RegistrationStartService.java`에서 (현재 위치: options 빌드 블록):

`options.put("timeout", 60000);` → `options.put("timeout", tenant.getWebauthnTimeoutMs());`

`options.put("attestation", "indirect");` → `options.put("attestation", tenant.getAttestationConveyanceLowercase());`

`sel.put("userVerification", "required");` → `sel.put("userVerification", tenant.isRequireUserVerification() ? "required" : "preferred");`

그리고 로그의 하드코딩된 `60000`도 교체:
`log.info("registration/start issued: tokenTail={} timeoutMs={}", tokenTail(token), 60000);` → `..., tokenTail(token), tenant.getWebauthnTimeoutMs());`

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew :passkey-app:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java \
        core/src/test/java/com/crosscert/passkey/core/entity/TenantConveyanceTest.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java
git commit -m "feat(webauthn): registration/start 에 테넌트 timeout/UV/attestation 반영 (P1-1)"
```

---

## Task 2: P1-1 — AuthenticationStartService 테넌트 설정 + findByUserHandle

authentication start의 timeout/UV를 테넌트 값으로, 인접 cleanup으로 `findAll()`→`findByUserHandle`.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java`

- [ ] **Step 1: CredentialRepository에 findByUserHandle 확인/추가**

먼저 `CredentialRepository.java`를 읽는다. 이미 `findCredentialIdsByUserHandle`(RegistrationStart가 사용)이 있으니, 엔티티 목록이 필요한 `findByUserHandle`가 없으면 추가:

```java
    java.util.List<com.crosscert.passkey.core.entity.Credential> findByUserHandle(byte[] userHandle);
```

(이미 존재하면 이 step은 no-op — 기존 시그니처 재사용.)

- [ ] **Step 2: AuthenticationStartService 교체**

현재 코드:
```java
        // VPD filters to this tenant. Phase 2 will add a derived
        // findByUserHandle query for efficiency.
        List<Credential> userCreds = credentials.findAll();
        if (userHandle != null) {
            final byte[] uh = userHandle;
            userCreds = userCreds.stream()
                    .filter(c -> Arrays.equals(c.getUserHandle(), uh))
                    .toList();
        } else {
            userCreds = List.of();
        }
```
교체:
```java
        // VPD filters to this tenant; derived query avoids the findAll scan.
        List<Credential> userCreds = (userHandle == null)
                ? List.of()                                  // usernameless: advertise nothing
                : credentials.findByUserHandle(userHandle);
```
그리고 옵션 빌드:
`options.put("timeout", 60000);` → `options.put("timeout", tenant.getWebauthnTimeoutMs());`
`options.put("userVerification", "required");` → `options.put("userVerification", tenant.isRequireUserVerification() ? "required" : "preferred");`
로그의 `60000` → `tenant.getWebauthnTimeoutMs()`.

`Arrays` import가 더 안 쓰이면 제거. `List` import는 유지.

- [ ] **Step 3: 컴파일 + 기존 테스트 회귀 확인**

Run: `./gradlew :passkey-app:compileJava :passkey-app:test --tests '*AuthenticationStart*' --tests '*RegistrationStart*' -q`
Expected: BUILD SUCCESSFUL. 기존 start 테스트가 하드코딩 60000/"required"를 단언하면, 그 테스트의 기대값을 테넌트 기본값(60000, UV="required" 기본 "Y")에 맞춰 갱신하거나 테넌트 설정을 명시 stub. (테스트가 깨지면 읽어서 테넌트 fixture의 설정값으로 단언하도록 수정.)

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java
git commit -m "feat(webauthn): authentication/start 테넌트 timeout/UV 반영 + findByUserHandle cleanup (P1-1)"
```

---

## Task 3: P1-5 scope — ApiKeyScopeRepository + ApiKeyScopeResolver

scope 조회(JPA projection) + 경로→요구 scope 매핑 컴포넌트. 아직 필터에 연결 안 함(Task 4에서).

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyScopeRepository.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyScopeResolver.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyScopeResolverTest.java`

- [ ] **Step 1: ApiKeyScopeResolver 단위 테스트 작성**

Create `passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyScopeResolverTest.java`:

```java
package com.crosscert.passkey.app.security;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyScopeResolverTest {

    private final ApiKeyScopeResolver resolver = new ApiKeyScopeResolver();

    @Test
    void registration_paths_require_registration_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/registration/start")).contains("registration");
        assertThat(resolver.requiredScope("/api/v1/rp/registration/finish")).contains("registration");
    }

    @Test
    void authentication_paths_require_authentication_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/authentication/start")).contains("authentication");
        assertThat(resolver.requiredScope("/api/v1/rp/authentication/finish")).contains("authentication");
    }

    @Test
    void credentials_self_service_requires_registration_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/credentials")).contains("registration");
        assertThat(resolver.requiredScope("/api/v1/rp/credentials/abc/label")).contains("registration");
    }

    @Test
    void unmapped_path_requires_no_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/other")).isEmpty();
        assertThat(resolver.requiredScope("/actuator/health")).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :passkey-app:test --tests '*ApiKeyScopeResolverTest' -q`
Expected: FAIL — `ApiKeyScopeResolver` 없음.

- [ ] **Step 3: ApiKeyScopeResolver 구현**

Create `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyScopeResolver.java`:

```java
package com.crosscert.passkey.app.security;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * RP-facing 요청 경로를 요구 API key scope 로 매핑 (P1-5).
 *
 * <p>scope 값은 api_key_scope 테이블의 enum(registration/authentication/admin)과
 * 일치. 매핑 없는 경로는 scope 불요(Optional.empty) — 인증만 통과하면 된다.
 * 매핑을 단일 컴포넌트에 응집해 ApiKeyAuthFilter 가 분기 로직을 갖지 않게 한다.
 */
@Component
public class ApiKeyScopeResolver {

    public Optional<String> requiredScope(String path) {
        if (path == null) return Optional.empty();
        if (path.startsWith("/api/v1/rp/registration"))    return Optional.of("registration");
        if (path.startsWith("/api/v1/rp/authentication"))  return Optional.of("authentication");
        // self-service credential 관리(목록/이름변경/삭제)는 등록 계열 — registration scope.
        if (path.startsWith("/api/v1/rp/credentials"))      return Optional.of("registration");
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :passkey-app:test --tests '*ApiKeyScopeResolverTest' -q`
Expected: PASS (4 tests).

- [ ] **Step 5: ApiKeyScopeRepository 작성**

먼저 `ApiKeyScope` 엔티티 PK 타입 확인(BaseEntity 상속 → UUID). Create `core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyScopeRepository.java`:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.ApiKeyScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface ApiKeyScopeRepository extends JpaRepository<ApiKeyScope, UUID> {

    /**
     * 인증된 API key 의 scope 문자열 집합. 인증 성공 후 호출되므로
     * TenantContextHolder 가 설정돼 있어 VPD 가 정상 작동(키는 그 테넌트 소유).
     */
    @Query("select s.scope from ApiKeyScope s where s.apiKey.id = :apiKeyId")
    Set<String> findScopeValuesByApiKeyId(@Param("apiKeyId") UUID apiKeyId);
}
```

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew :core:compileJava :passkey-app:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyScopeRepository.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyScopeResolver.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyScopeResolverTest.java
git commit -m "feat(security): ApiKeyScopeResolver(경로→scope) + ApiKeyScopeRepository (P1-5)"
```

---

## Task 4: P1-5 scope — ApiKeyAuthFilter enforcement

인증 성공 직후 scope 검증 삽입. 하위호환 게이트(scope 빈 키 확인)를 먼저 실행.

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyAuthFilterScopeTest.java`

- [ ] **Step 0: 하위호환 게이트 — scope 빈 키 존재 여부 확인**

worktree에서 (test 리소스나 seed 확인): `grep -rn "addScope\|api_key_scope" core/src/main/resources/db/migration/ | head`로 seed가 scope를 넣는지, 그리고 발급 경로(`ApiKeyAdminService.issue`)가 `req.scopes()`(@NotBlank Set)를 강제하는지 확인. **결론을 사용자에게 보고**: "기존 발급 키는 모두 최소 1개 scope 보유(발급 경로상 강제) — enforcement 안전" 또는 "scope 없는 키 존재 가능 → 운영 공지 필요". seed에 scope 없는 api_key INSERT가 있으면 그 seed를 scope 포함하도록 수정하는 step을 추가.

- [ ] **Step 1: 필터 scope enforcement 슬라이스 테스트 작성**

먼저 기존 `ApiKeyAuthFilter` 테스트(있으면)를 읽어 셋업(MockMvc or 직접 doFilter, lookup/encoder/scopeRepo mock)을 파악. 직접 단위 테스트 패턴으로 Create `passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyAuthFilterScopeTest.java`:

```java
package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.repository.ApiKeyScopeRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiKeyAuthFilterScopeTest {

    ApiKeyLookupService lookup = mock(ApiKeyLookupService.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    ApiKeyScopeRepository scopeRepo = mock(ApiKeyScopeRepository.class);
    ApiKeyScopeResolver resolver = new ApiKeyScopeResolver();
    ApiKeyAuthFilter filter;

    UUID keyId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    String prefix = "pk_ABCDEFGH"; // PREFIX_LEN 자리에 맞춤 — 실제 PREFIX_LEN 확인 후 조정
    String secret = "SECRET";

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(lookup, encoder, scopeRepo, resolver);
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(keyId, tenantId, "HASH", null, null)));
        when(encoder.matches(eq(secret), eq("HASH"))).thenReturn(true);
    }

    private MockHttpServletRequest req(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.addHeader("X-API-Key", prefix + secret);
        r.setRequestURI(path);
        return r;
    }

    @Test
    void allows_when_key_has_required_scope() throws Exception {
        when(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(Set.of("registration"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain);
        verify(chain).doFilter(any(), any());           // passed through
    }

    @Test
    void forbids_when_key_lacks_required_scope() throws Exception {
        when(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(Set.of("authentication"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain);
        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void forbids_when_key_has_no_scopes() throws Exception {
        when(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(Set.of());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain);
        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void allows_unmapped_path_without_scope_check() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req("/api/v1/rp/other"), res, chain);
        verify(chain).doFilter(any(), any());
        verify(scopeRepo, never()).findScopeValuesByApiKeyId(any());  // 매핑 없으면 조회 생략
    }
}
```

Note: `prefix`/`PREFIX_LEN`·생성자 시그니처는 실제 `ApiKeyAuthFilter`에 맞춰 조정한다(아래 Step 3에서 생성자에 scopeRepo/resolver를 추가하므로 테스트 생성자도 그에 맞춤). 기존 필터 생성자가 `(lookup, encoder)`였다면 `(lookup, encoder, scopeRepo, resolver)`로.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :passkey-app:test --tests '*ApiKeyAuthFilterScopeTest' -q`
Expected: FAIL — 생성자 시그니처 불일치 / scope enforcement 없음.

- [ ] **Step 3: ApiKeyAuthFilter에 enforcement 추가**

생성자에 `ApiKeyScopeRepository scopeRepo`, `ApiKeyScopeResolver scopeResolver` 추가(필드 + 주입). `TenantContextHolder.set(row.tenantId());` 직후, MDC 설정 전에 scope 검증 삽입:

```java
        TenantContextHolder.set(row.tenantId());
        // P1-5: 경로가 scope 를 요구하면 키 보유 scope 와 대조. 키는 유효하나
        // 권한 부족이면 403(401 과 구분). 매핑 없는 경로는 scope 검사 생략.
        var required = scopeResolver.requiredScope(req.getRequestURI());
        if (required.isPresent()) {
            Set<String> held = scopeRepo.findScopeValuesByApiKeyId(row.id());
            if (!held.contains(required.get())) {
                TenantContextHolder.clear();
                log.warn("api-key scope denied: prefix={} required={} held={}",
                        prefix, required.get(), held);
                forbidden(res);
                return;
            }
        }
        MDC.put(MDC_API_KEY_PREFIX, prefix);
```

`forbidden` 헬퍼 추가(기존 `unauthorized` 옆):
```java
    /** 403 — 키는 유효하나 요청 경로 scope 미보유. */
    private void forbidden(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"insufficient_scope\"}");
    }
```

`java.util.Set` import 추가.

- [ ] **Step 4: 테스트 통과 + 기존 필터 테스트 회귀 확인**

Run: `./gradlew :passkey-app:test --tests '*ApiKeyAuthFilter*' -q`
Expected: PASS. 기존 `ApiKeyAuthFilter` 테스트가 생성자 `(lookup, encoder)`로 인스턴스화하면 새 생성자 인자(scopeRepo, resolver) 추가로 수정 — 그 테스트들에서 scopeRepo는 빈 동작 mock, resolver는 실제 인스턴스로.

- [ ] **Step 5: Commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyAuthFilterScopeTest.java
git commit -m "feat(security): API key scope enforcement — 경로 요구 scope 미보유 시 403 (P1-5)"
```

---

## Task 5: P1-5 rotation — ApiKeyAdminService.rotate + 컨트롤러

신규 발급(scope 복제) + 구 키 expiresAt grace 만료. active 정의 통일 포함.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java` — active 정의 통일
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java` — expiresAt 설정 메서드(없으면)
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java` — rotate()
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java` — POST /{id}/rotate
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java` — RotateResponse
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyRotateServiceTest.java`

- [ ] **Step 1: active 정의 통일 (findActiveByTenantId)**

`ApiKeyRepository.findActiveByTenantId`를 `countActiveByTenantId`·`ApiKey.isActive()`와 일치시킨다. 현재:
```java
    @Query("select k from ApiKey k where k.tenantId = :tenantId and k.revokedAt is null")
    java.util.List<...ApiKey> findActiveByTenantId(@Param("tenantId") UUID tenantId);
```
이건 suspend 시 일괄 revoke 대상(만료 키도 revoke = no-op 무해)이라 의미가 약간 다르다. rotation이 expiresAt grace 를 쓰므로 "active"를 일관되게: now 파라미터 추가하고 expiresAt 체크 포함:
```java
    /** active = 미revoke AND 미만료. suspend 일괄 revoke·KPI 와 동일 정의. */
    @Query("""
            select k from ApiKey k
            where k.tenantId = :tenantId
              and k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > :now)
            """)
    java.util.List<com.crosscert.passkey.core.entity.ApiKey> findActiveByTenantId(
            @Param("tenantId") UUID tenantId, @Param("now") Instant now);
```
호출부(`TenantAdminService` suspend 경로 등)를 grep `findActiveByTenantId`로 찾아 `clock.instant()` 인자 추가. (호출부가 깨지므로 전부 갱신.)

- [ ] **Step 2: ApiKey에 grace 만료 setter 확인/추가**

`ApiKey.java`에 `expiresAt`을 설정하는 메서드가 없으면(현재 getter만 + revoke만 있음) 추가:
```java
    /** rotation grace: 구 키를 now+grace 에 만료시킨다. */
    public void expireAt(Instant when) { this.expiresAt = when; }
```

- [ ] **Step 3: rotate 서비스 단위 테스트 작성**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyRotateServiceTest.java`. 기존 `ApiKeyAdminServiceTest`의 셋업(mock repo/audit/encoder/clock/tenantBoundary/tenants)을 먼저 읽어 동일 패턴으로:

```java
package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyRotateServiceTest {

    @Mock ApiKeyRepository repo;
    @Mock TenantRepository tenants;
    @Mock PasswordEncoder encoder;
    @Mock com.crosscert.passkey.admin.audit.AuditLogService audit;
    @Mock com.crosscert.passkey.admin.tenant.TenantBoundary tenantBoundary; // 실제 타입명은 기존 서비스 확인 후 맞춤
    Clock clock = Clock.fixed(Instant.parse("2026-05-31T00:00:00Z"), ZoneOffset.UTC);
    Duration grace = Duration.ofHours(24);
    ApiKeyAdminService service;

    UUID tenantId = UUID.randomUUID();
    UUID oldId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 생성자 시그니처는 기존 ApiKeyAdminService 에 맞춤 + grace 설정값 주입
        service = new ApiKeyAdminService(repo, tenants, encoder, audit, tenantBoundary, clock, grace);
        when(encoder.encode(any())).thenReturn("NEWHASH");
        when(repo.findByKeyPrefix(any())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ApiKey activeKey() {
        ApiKey k = new ApiKey(tenantId, "pk_OLD12345", "OLDHASH", "my-key");
        k.addScope("registration");
        k.addScope("authentication");
        return k;
    }

    @Test
    void rotate_issues_new_key_and_expires_old_after_grace() {
        ApiKey old = activeKey();
        when(repo.findById(oldId)).thenReturn(Optional.of(old));
        Tenant t = new Tenant(); // 또는 적절한 생성; isSuspended()=false 보장
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));

        var resp = service.rotate(oldId, UUID.randomUUID(), "op@x.com");

        // 구 키 expiresAt = now + grace
        assertThat(old.getExpiresAt()).isEqualTo(clock.instant().plus(grace));
        // 신규 키 발급 + scope 복제
        ArgumentCaptor<ApiKey> cap = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getScopeValues()).containsExactlyInAnyOrder("registration", "authentication");
        // 응답에 신규 평문 + 구 키 만료시각
        assertThat(resp.oldKeyExpiresAt()).isEqualTo(clock.instant().plus(grace));
    }

    @Test
    void rotate_rejects_already_expired_or_revoked_key() {
        ApiKey revoked = activeKey();
        revoked.revoke(clock.instant().minusSeconds(1));
        when(repo.findById(oldId)).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> service.rotate(oldId, UUID.randomUUID(), "op@x.com"))
                .isInstanceOf(com.crosscert.passkey.core.exception.BusinessException.class);
    }
}
```

Note: `TenantBoundary`/`BusinessException`/생성자 인자 실제 타입·시그니처는 기존 `ApiKeyAdminService.java`·`ApiKeyAdminServiceTest.java`를 읽어 정확히 맞춘다. grace 주입 방식(생성자 vs @Value 필드)도 기존 패턴에 맞춤 — 기존이 `Clock`을 생성자 주입하면 grace도 생성자에 추가하거나 `@Value` 필드로.

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew :admin-app:test --tests '*ApiKeyRotateServiceTest' -q`
Expected: FAIL — `rotate` 없음 / 생성자 불일치.

- [ ] **Step 5: rotate 구현**

`ApiKeyAdminService`에 grace 설정값(`@Value("${passkey.api-key.rotation.grace:PT24H}") Duration rotationGrace` 또는 생성자 주입) 추가하고 `rotate` 메서드 추가(issue 로직 재사용):

```java
    @Transactional
    public ApiKeyAdminDto.ApiKeyRotateResponse rotate(UUID oldKeyId, UUID actorId, String actorEmail) {
        ApiKey old = repo.findById(oldKeyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));
        tenantBoundary.assertCanAccessTenant(old.getTenantId());
        Instant now = clock.instant();
        if (!old.isActive(now)) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND,
                    "cannot rotate inactive (revoked/expired) key: " + oldKeyId);
        }
        Tenant tenant = tenants.findById(old.getTenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        if (tenant.isSuspended()) {
            throw new BusinessException(ErrorCode.TENANT_SUSPENDED,
                    "cannot rotate api key for suspended tenant: " + old.getTenantId());
        }

        // 신규 발급 — 구 키와 동일 tenant·name·scope
        String prefix = generateUniquePrefix();
        String secret = b64url(SECRET_RANDOM_BYTES);
        String hash = encoder.encode(secret);
        ApiKey fresh = new ApiKey(old.getTenantId(), prefix, hash, old.getName());
        for (String scope : old.getScopeValues()) {
            fresh.addScope(scope);
        }
        ApiKey savedNew = repo.saveAndFlush(fresh);

        // 구 키 grace 만료
        Instant oldExpiry = now.plus(rotationGrace);
        old.expireAt(oldExpiry);
        repo.save(old);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("oldPrefix", old.getKeyPrefix());
        payload.put("newPrefix", prefix);
        payload.put("tenantId", old.getTenantId().toString());
        payload.put("oldKeyExpiresAt", oldExpiry.toString());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ROTATED",
                "API_KEY", savedNew.getId().toString(),
                old.getTenantId(), payload));

        log.warn("api-key rotated: oldPrefix={} newPrefix={} tenantId={} oldExpiresAt={}",
                old.getKeyPrefix(), prefix, old.getTenantId(), oldExpiry);

        return new ApiKeyAdminDto.ApiKeyRotateResponse(
                savedNew.getId(), prefix + secret, prefix, fresh.getScopeValues(), oldExpiry);
    }
```

`ApiKeyAdminDto`에 추가:
```java
    public record ApiKeyRotateResponse(UUID id, String plaintextKey, String prefix,
                                       Set<String> scopes, Instant oldKeyExpiresAt) {}
```
(`ErrorCode`에 적절한 코드가 없으면 기존 `API_KEY_NOT_FOUND`/`TENANT_SUSPENDED` 재사용 — 신규 코드 추가 지양.)

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests '*ApiKeyRotateServiceTest' --tests '*ApiKeyAdminServiceTest' -q`
Expected: PASS — 신규 + 기존 서비스 테스트(생성자 시그니처 변경 시 기존 테스트도 grace 인자 추가).

- [ ] **Step 7: 컨트롤러 엔드포인트 추가**

`ApiKeyAdminController`에 (기존 issue/revoke 옆):
```java
    @PostMapping("/{id}/rotate")
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public ApiKeyAdminDto.ApiKeyRotateResponse rotate(
            @PathVariable UUID id,
            @AuthenticationPrincipal AdminUserDetails principal) {
        return service.rotate(id, principal.getId(), principal.getUsername());
    }
```
(실제 principal 추출 방식은 기존 issue/revoke 핸들러와 동일하게 맞춤 — 기존이 다른 방식으로 actorId/actorEmail을 얻으면 그대로.)

- [ ] **Step 8: 컨트롤러 슬라이스 테스트 + 컴파일**

기존 `ApiKeyAdminControllerSecurityTest`(JpaStubs 패턴 — 그룹 A에서 본 것)가 있으면 rotate 케이스 추가하거나, 최소한 컴파일 + 권한 게이팅 확인. Run: `./gradlew :admin-app:test --tests '*ApiKeyAdmin*' -q`. JpaStubs 슬라이스에 새 repository(ApiKeyScopeRepository)가 admin-app @EnableJpaRepositories에 잡히면 그룹 A처럼 @MockBean 추가가 필요할 수 있음 — 깨지면 등록.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java \
        core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminDto.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyRotateServiceTest.java
git commit -m "feat(apikey): rotation — 신규 발급 + 구 키 grace 만료 + active 정의 통일 (P1-5)"
```

---

## Task 6: 전체 검증 + followups + 설정 노출

**Files:**
- Modify: `admin-app/src/main/resources/application.yml` 또는 passkey-app — rotation grace 설정 노출
- Modify: `docs/superpowers/followups/...` — 그룹 B 완료 기록

- [ ] **Step 1: rotation grace 설정 application.yml 노출**

rotate가 사는 모듈(admin-app)의 `application.yml`에 `passkey.api-key.rotation.grace` 노출(그룹 A의 lockout 패턴):
```yaml
  api-key:
    rotation:
      grace: PT24H   # rotation 시 구 키 유예 만료(ISO-8601); 이 기간 구·신 키 병존
```
(기존 `passkey:` 루트 구조에 맞춤.)

- [ ] **Step 2: 전체 빌드 + 단위/슬라이스 테스트**

Run: `./gradlew :core:test :passkey-app:test --tests '*Test' :admin-app:test --tests '*Test' -q`
Expected: BUILD SUCCESSFUL. 깨진 기존 테스트(생성자 시그니처 변경: AuthStart, ApiKeyAuthFilter, ApiKeyAdminService, findActiveByTenantId 호출부)는 전부 새 시그니처로 갱신.

- [ ] **Step 3: ceremony 통합 확인 (가능 시)**

ceremony start가 테넌트 값을 반영하는지 — 테넌트 설정을 비기본값(예: timeout 30000, UV "preferred"=N, conveyance DIRECT)으로 둔 fixture로 reg/auth start 응답 JSON을 검증하는 테스트가 기존에 있으면 확장. 없으면 Task 1·2 단위 테스트로 충분.

- [ ] **Step 4: followups 갱신**

`docs/superpowers/followups/2026-05-31-config-enforcement-group-b-followups.md` 신설: P1-1·P1-5 완료 표시, 남은 P1(P1-2/3/4/7 = 그룹 C·D), scope enforcement의 경로 매핑이 하드코딩(향후 self-service credentials의 정확한 scope 재검토 여지), rotation이 신규 스키마 없이 expiresAt 재사용한 점 기록. 그리고 `2026-05-30-saas-launch-hardening-followups.md`의 P1-1·P1-5 행을 ✅ 해결로 갱신.

- [ ] **Step 5: 커밋 전 게이트 — codex review (가능 시) + code quality**

메모리 지침: 누적 diff(`4b797d1..HEAD`)에 `/codex review`(6/1 quota 리셋 후). 특히 scope 403 경계·rotation grace·conveyance 매핑.

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/resources/application.yml \
        docs/superpowers/followups/
git commit -m "docs(followups): 그룹 B 완료 (P1-1 ceremony 반영 + P1-5 scope/rotation) + grace 설정 노출"
```

---

## Self-Review

**Spec coverage:**
- P1-1 registration start (§2.1): Task 1 ✅
- P1-1 authentication start (§2.2) + findByUserHandle (§5.2): Task 2 ✅
- P1-1 conveyance 매핑 (§2.4): Task 1 (Tenant 헬퍼) ✅
- P1-1 finish 무변경 (§2.3): 명시적으로 안 건드림 ✅
- P1-5 scope 조회 JPA (§3.1): Task 3 (ApiKeyScopeRepository) ✅
- P1-5 경로 매핑 (§3.2): Task 3 (ApiKeyScopeResolver) ✅
- P1-5 enforcement + 403 + 하위호환 (§3.3/§3.4): Task 4 ✅
- P1-5 rotation (§4): Task 5 ✅
- ApiKey active 정의 통일 (§5.1): Task 5 Step 1 ✅
- 에러 처리/보안 경계 (§6): Task 4(403)·Task 5(만료키 거부)·Task 1(conveyance fallback) ✅
- 테스트 전략 (§7): 각 Task TDD ✅
- 설정 노출 + followups: Task 6 ✅

**Placeholder scan:** 모든 step에 실제 코드/명령/기대값. "기존 X 읽어 맞춤"은 생성자/타입 시그니처가 프로젝트 실제에 의존하기 때문의 실행 지시(읽을 파일 명시) — placeholder 아님.

**Type consistency:**
- `ApiKeyScopeResolver.requiredScope(String)→Optional<String>` — Task 3·4 일치 ✅
- `ApiKeyScopeRepository.findScopeValuesByApiKeyId(UUID)→Set<String>` — Task 3·4 일치 ✅
- `ApiKeyAuthFilter` 생성자 `(lookup, encoder, scopeRepo, resolver)` — Task 4 일관 ✅
- `ApiKeyAdminService.rotate(UUID,UUID,String)→ApiKeyRotateResponse` — Task 5 일치 ✅
- `ApiKeyRotateResponse(UUID,String,String,Set<String>,Instant)` — Task 5 일치 ✅
- `Tenant.getAttestationConveyanceLowercase()→String` — Task 1 일치 ✅
- `ApiKey.expireAt(Instant)` — Task 5 일치 ✅
- `findActiveByTenantId(UUID, Instant)` — Task 5 Step 1(호출부 갱신 명시) ✅
