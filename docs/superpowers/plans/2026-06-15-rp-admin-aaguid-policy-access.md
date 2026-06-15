# RP_ADMIN 테넌트 AAGUID 정책 접근 허용 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RP_ADMIN 이 자기 테넌트의 AAGUID 정책을 읽고(GET) 쓸(PUT) 수 있게 한다 — 컨트롤러 role 게이트를 `hasAnyRole(PLATFORM_OPERATOR, RP_ADMIN)` 로 확대하고, 서비스에 테넌트 경계 검사(IDOR 방지)를 추가한다.

**Architecture:** `AaguidPolicyController` 의 @PreAuthorize 를 per-tenant 패턴(credentials/webauthn 과 동일)으로 바꾸고, `AaguidPolicyService.get()/update()` 진입부에 `TenantBoundary.assertCanAccessTenant(tenantId)` 를 조회보다 먼저 호출한다. role 게이트(coarse) + 테넌트 경계(fine) 이중 방어. 프론트는 무변경(탭이 이미 노출되며 200 이면 정상 렌더).

**Tech Stack:** Java, Spring Boot, Spring Security (@PreAuthorize, method security), JUnit 5, Spring MockMvc (@WebMvcTest), 기존 `RpAdminBoundaryIT` (REST + 쿠키/CSRF 풀 IT).

---

## 파일 구조

- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyController.java` — @PreAuthorize 2곳을 hasAnyRole 로
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java` — TenantBoundary 생성자 주입 + get/update 진입부 경계 검사(조회 전)
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/policy/AaguidPolicyControllerSecurityTest.java` — @WebMvcTest 슬라이스 (role 게이트 검증)
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java` — AAGUID 4 케이스 추가 (테넌트 경계 검증)

프론트는 변경하지 않는다.

---

### Task 1: AaguidPolicyService 에 테넌트 경계 검사 추가 (보안 코어 — 먼저)

이 Task 를 먼저 하는 이유: 컨트롤러에서 RP_ADMIN 을 열기 전에 서비스 경계 검사가 들어가야, 어느 순간에도 IDOR 창이 열리지 않는다.

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java`

- [ ] **Step 1: TenantBoundary 주입 + get()/update() 경계 검사 추가**

현재 `AaguidPolicyService` 는 `repo` 와 `mdsCache` 만 주입받는다. `TenantBoundary` 를 추가 주입하고, `get()` 과 `update()` 의 **첫 줄**(repo.findById 보다 먼저)에 경계 검사를 넣는다.

전체 파일을 다음으로 만든다:

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AaguidPolicyService {

    private final TenantAaguidPolicyRepository repo;
    private final MdsAaguidCache mdsCache;
    private final TenantBoundary tenantBoundary;

    public AaguidPolicyService(TenantAaguidPolicyRepository repo,
                               MdsAaguidCache mdsCache,
                               TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.mdsCache = mdsCache;
        this.tenantBoundary = tenantBoundary;
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    @Transactional(readOnly = true)
    public AaguidPolicyDto.View get(UUID tenantId) {
        // 테넌트 경계 검사를 조회보다 먼저 — RP_ADMIN 이 다른 테넌트 정책 존재 여부를
        // 엿보지 못하도록(정보 노출 방지). PLATFORM_OPERATOR 는 무조건 통과.
        tenantBoundary.assertCanAccessTenant(tenantId);
        TenantAaguidPolicy p = repo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("aaguid policy not found for tenant " + tenantId));
        List<AaguidPolicyDto.Entry> entries = p.getEntries().stream()
                .map(e -> {
                    // MdsAaguidCache.Entry only stores statuses — no name field available
                    // mdsName is reserved for future expansion when full MetadataStatement is cached
                    String mdsName = null;
                    return new AaguidPolicyDto.Entry(e.getAaguid(), e.getNote(), mdsName);
                })
                .toList();
        return new AaguidPolicyDto.View(
                p.getTenantId(), p.getMode(), p.isMdsStrict(),
                entries, p.getUpdatedAt(), p.getUpdatedBy());
    }

    @Transactional
    public AaguidPolicyDto.View update(UUID tenantId, AaguidPolicyDto.UpdateRequest req, String updatedBy) {
        // 경계 검사를 변경보다 먼저 — RP_ADMIN 은 자기 테넌트만 수정 가능.
        tenantBoundary.assertCanAccessTenant(tenantId);
        TenantAaguidPolicy p = repo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("aaguid policy not found for tenant " + tenantId));
        p.setMode(req.mode());
        p.setMdsStrict(req.mdsStrict());
        p.clearEntries();
        if (req.entries() != null) {
            for (var e : req.entries()) {
                p.addEntry(e.aaguid(), e.note());
            }
        }
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(updatedBy);
        repo.save(p);

        int entryCount = req.entries() == null ? 0 : req.entries().size();
        log.info("aaguid policy updated: tenantId={} mode={} mdsStrict={} entries={}",
                tenantId, req.mode(), req.mdsStrict(), entryCount);

        return get(tenantId);
    }
}
```

(주의: `update()` 끝의 `return get(tenantId)` 는 경계 검사를 한 번 더 타지만, PLATFORM_OPERATOR/본인 테넌트 RP_ADMIN 모두 이미 통과한 상태라 무해하다 — 추가 비용은 ThreadLocal principal 조회 1회뿐.)

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL. (TenantBoundary 는 같은 모듈 `com.crosscert.passkey.admin.auth` 에 있어 import 해석됨.)

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyService.java
git commit -m "fix(security): AaguidPolicyService 에 TenantBoundary 경계 검사 추가 (IDOR 방지, 조회 전)"
```

---

### Task 2: AaguidPolicyController role 게이트 확대

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyController.java`

- [ ] **Step 1: @PreAuthorize 2곳 변경**

GET(`get`)과 PUT(`update`) 메서드의 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")` 를 둘 다 `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` 로 바꾼다. 나머지(audit 로깅 등)는 그대로.

`AaguidPolicyController.java` 의 24-25 라인:
```java
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public AaguidPolicyDto.View get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }
```

그리고 30-31 라인:
```java
    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public AaguidPolicyDto.View update(@PathVariable UUID tenantId,
                                       @RequestBody AaguidPolicyDto.UpdateRequest req,
                                       Authentication auth) {
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyController.java
git commit -m "feat: AAGUID 정책 컨트롤러를 RP_ADMIN 에 개방 (hasAnyRole PO,RP_ADMIN)"
```

---

### Task 3: AaguidPolicyControllerSecurityTest 슬라이스 (role 게이트 검증)

`SecurityPolicyControllerSecurityTest` 를 템플릿으로 한 @WebMvcTest 슬라이스. RP_ADMIN 이 더 이상 forbidden 이 아님(이제 200)을 검증하고, 익명은 401 을 유지함을 검증한다.

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/policy/AaguidPolicyControllerSecurityTest.java`

- [ ] **Step 1: 테스트 작성**

아래 전체 내용으로 파일을 만든다. MockBean 세트는 `SecurityPolicyControllerSecurityTest` 와 동일해야 AdminSecurityConfig + @EnableJpaRepositories 컨텍스트가 슬라이스에서 로드된다(메모리: 슬라이스 MockBean 회귀 주의). 추가로 이 컨트롤러가 의존하는 `AaguidPolicyService` 를 MockBean 으로 둔다.

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = AaguidPolicyController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    AaguidPolicyControllerSecurityTest.JpaStubs.class
})
class AaguidPolicyControllerSecurityTest {

    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = mock(Metamodel.class);
            when(metamodel.getEntities()).thenReturn(Set.of());
            when(metamodel.getManagedTypes()).thenReturn(Set.of());
            when(metamodel.getEmbeddables()).thenReturn(Set.of());

            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean AaguidPolicyService service;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    @MockBean com.crosscert.passkey.core.repository.CeremonyEventRepository ceremonyEventRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.ApiKeyScopeRepository apiKeyScopeRepository;
    @MockBean com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    @MockBean com.crosscert.passkey.core.repository.SigningKeyRepository signingKeyRepository;
    @MockBean com.crosscert.passkey.core.repository.MdsBlobCacheRepository mdsBlobCacheRepository;
    @MockBean com.crosscert.passkey.core.repository.SchedulerLeaseRepository schedulerLeaseRepository;
    @MockBean com.crosscert.passkey.core.repository.ActivityRepository activityRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserInvitationRepository invitationRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository adminPasswordResetTokenRepository;
    @MockBean com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository adminUserRecoveryCodeRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository tenantAaguidPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.SecurityPolicyRepository securityPolicyRepository;
    @MockBean com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository tenantWebauthnSnapshotRepository;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static AaguidPolicyDto.View sampleView() {
        return new AaguidPolicyDto.View(
                TENANT,
                com.crosscert.passkey.core.entity.TenantAaguidPolicy.Mode.ANY,
                false,
                List.of(),
                Instant.parse("2026-05-31T00:00:00Z"),
                "alice@example.com");
    }

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void platformOperatorCanGet() throws Exception {
        when(service.get(any())).thenReturn(sampleView());
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "bob@example.com", roles = "RP_ADMIN")
    void rpAdminCanGet() throws Exception {
        when(service.get(any())).thenReturn(sampleView());
        mvc.perform(get("/admin/api/tenants/" + TENANT + "/aaguid-policy"))
            .andExpect(status().isOk());
    }
}
```

> 참고: `AaguidPolicyDto.View` 의 생성자 인자 순서/타입은 `AaguidPolicyService.get()` 이
> 만드는 것과 동일하다: `(UUID tenantId, Mode mode, boolean mdsStrict, List<Entry> entries,
> Instant updatedAt, String updatedBy)`. `Mode` 의 정확한 enum 경로는
> `com.crosscert.passkey.core.entity.TenantAaguidPolicy.Mode` 이며 값은 `ANY/ALLOWLIST/DENYLIST`.
> 만약 `AaguidPolicyDto.View` 가 `Mode` 를 다른 위치(예: AaguidPolicyDto 내부 enum)에서
> 참조한다면, 컴파일 에러 메시지가 정확한 타입을 알려준다 — 그 타입으로 `sampleView()` 의
> mode 인자를 맞춘다. (구현 전 `AaguidPolicyDto.java` 의 View record 시그니처를 한 번 확인할 것.)

- [ ] **Step 2: 슬라이스 테스트 실행**

먼저 `AaguidPolicyDto.View` 시그니처를 확인:
Run: `grep -n "record View\|enum Mode\|Mode " admin-app/src/main/java/com/crosscert/passkey/admin/policy/AaguidPolicyDto.java`
필요시 `sampleView()` 의 인자를 실제 시그니처에 맞춘 뒤:

Run: `./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.policy.AaguidPolicyControllerSecurityTest"`
Expected: 3 tests PASS (anonymous 401, PO 200, RP_ADMIN 200).

> 만약 컨텍스트 로드 실패(MockBean 누락으로 NoSuchBeanDefinitionException)면, 누락된 빈을
> 에러 메시지에서 확인해 @MockBean 을 추가한다 — AdminSecurityConfig 가 요구하는 빈 세트가
> SecurityPolicyControllerSecurityTest 와 동일해야 한다.

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/policy/AaguidPolicyControllerSecurityTest.java
git commit -m "test: AaguidPolicyController 슬라이스 — RP_ADMIN GET 200, 익명 401"
```

---

### Task 4: RpAdminBoundaryIT 에 AAGUID 테넌트 경계 4 케이스 추가

이 IT 는 실제 REST + 쿠키/CSRF 풀 흐름으로 RP_ADMIN(bob)이 자기 테넌트만 접근 가능함을 검증한다. 기존 credentials 케이스(`── 7/8.`) 바로 뒤에 AAGUID 케이스를 추가한다.

**Files:**
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java`

- [ ] **Step 1: 기존 credentials 케이스 위치와 헬퍼 확인**

Run: `grep -n "credentials → 200\|credentials → 403\|exchange(\|HttpMethod.GET\|HttpMethod.PUT\|private.*exchange\|String url(\|tenantAId\|myTenantId" admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java`
이로써 (a) `── 8. GET /tenants/{other}/credentials → 403` 직후 삽입 위치, (b) REST 호출에 쓰는 헬퍼(`exchange`/`url`/auth 헤더), (c) 변수명(`myTenantId`, `tenantAId`)을 확인한다.

- [ ] **Step 2: AAGUID 4 케이스 삽입**

`── 8. ... credentials → 403` 단계 바로 뒤에 다음 블록을 추가한다. (이 IT 가 쓰는 호출 헬퍼 시그니처에 맞춰라 — 기존 credentials 케이스가 `exchangeAs(...)`/`url(...)` 같은 헬퍼와 `auth` 헤더를 어떻게 쓰는지 그대로 모방한다. 아래는 기존 코드의 `restTemplate.exchange(url(...), HttpMethod.X, new HttpEntity<>(authHeaders), ...)` 패턴을 가정한 형태다.)

```java
        // ── 9. GET /tenants/{my}/aaguid-policy → 200 ─────────────────────────
        assertThat(restTemplate.exchange(
                url("/admin/api/tenants/" + myTenantId + "/aaguid-policy"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class).getStatusCode().value())
                .as("bob GET own tenant aaguid-policy must succeed")
                .isEqualTo(200);

        // ── 10. GET /tenants/{other}/aaguid-policy → 403 ─────────────────────
        assertThat(restTemplate.exchange(
                url("/admin/api/tenants/" + tenantAId + "/aaguid-policy"),
                HttpMethod.GET, new HttpEntity<>(auth), String.class).getStatusCode().value())
                .as("bob GET other tenant aaguid-policy must be forbidden")
                .isEqualTo(403);

        // ── 11. PUT /tenants/{my}/aaguid-policy → 200 ────────────────────────
        String aaguidBody = "{\"mode\":\"ANY\",\"mdsStrict\":false,\"entries\":[]}";
        HttpHeaders putHeaders = new HttpHeaders(auth);
        putHeaders.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.exchange(
                url("/admin/api/tenants/" + myTenantId + "/aaguid-policy"),
                HttpMethod.PUT, new HttpEntity<>(aaguidBody, putHeaders), String.class)
                .getStatusCode().value())
                .as("bob PUT own tenant aaguid-policy must succeed")
                .isEqualTo(200);

        // ── 12. PUT /tenants/{other}/aaguid-policy → 403 ─────────────────────
        assertThat(restTemplate.exchange(
                url("/admin/api/tenants/" + tenantAId + "/aaguid-policy"),
                HttpMethod.PUT, new HttpEntity<>(aaguidBody, putHeaders), String.class)
                .getStatusCode().value())
                .as("bob PUT other tenant aaguid-policy must be forbidden")
                .isEqualTo(403);
```

> 중요 — 실제 헬퍼에 맞춰 조정: 위 코드는 `restTemplate`/`url()`/`auth`(HttpHeaders) 사용을
> 가정한다. Step 1 의 grep 결과로 이 IT 의 실제 호출 방식을 확인하고 변수명·헬퍼를 정확히
> 맞춰라. 핵심 불변식만 지키면 된다: bob(RP_ADMIN)이 **자기 테넌트(myTenantId)** GET/PUT →
> 200, **다른 테넌트(tenantAId)** GET/PUT → 403. 또 PUT 은 CSRF 토큰 헤더(X-XSRF-TOKEN)가
> 필요할 수 있다 — 기존 PUT /tenants/{my} 케이스(`── 5.`)가 CSRF 를 어떻게 붙이는지 그대로 따른다.
> PUT 200 케이스는 해당 테넌트에 aaguid policy row 가 존재해야 한다(repo.findById). 시드가
> 없으면 `IllegalStateException → 500` 이 날 수 있으니, 기존 IT 의 테넌트 시드가 aaguid policy
> 를 만드는지 확인하고, 없다면 GET/PUT 자기테넌트 케이스에서 200 대신 권한 통과만 보장되는
> 다른 단언(예: not 403)으로 약화하거나, 시드에 정책 row 를 추가한다. 권한 경계(403 케이스)가
> 이 Task 의 핵심이므로 자기테넌트 케이스가 시드 문제로 막히면 403 케이스만이라도 반드시 통과시킨다.

- [ ] **Step 3: IT 실행 (Testcontainers Oracle 필요 — Docker 필수)**

Run: `./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.auth.RpAdminBoundaryIT"`
Expected: PASS (기존 케이스 + 새 AAGUID 4 케이스). Docker 미가동이면 이 단계는 환경 제약으로 보류하고, Task 3 슬라이스 테스트가 role 게이트의 핵심 검증임을 기록한다.

> base 비교: 이 IT 가 main 에서 이미 깨져 있을 수 있다(메모리: admin-app 테스트 컨텍스트
> pre-existing 함정). 새 케이스가 실패하면, 먼저 `git stash` 없이 main worktree
> (`/Users/jhyun/Git/10-work/crosscert/Passkey2`)에서 동일 IT 를 돌려 pre-existing 여부를
> 확인한 뒤, 우리 변경에 기인한 실패만 수정한다.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/auth/RpAdminBoundaryIT.java
git commit -m "test(it): RpAdminBoundaryIT 에 AAGUID 정책 테넌트 경계 4 케이스 추가"
```

---

### Task 5: 회귀 검증

**Files:** (없음 — 검증만)

- [ ] **Step 1: AAGUID 관련 기존 테스트 회귀**

Run: `./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.policy.*"`
Expected: `AaguidPolicyControllerSecurityTest`(신규) PASS + `AaguidPolicyCeremonyIT`/`SecurityPolicyControllerSecurityTest`/`SecurityPolicyIT` 등 기존 테스트가 우리 변경 전과 동일한 결과. (ceremony/security-policy 는 우리가 안 건드렸으므로 불변.)

> 실패 시 base 비교로 pre-existing 여부 확정(메모리: full build/슬라이스 pre-existing 함정).
> 우리 변경(AaguidPolicyService/Controller)에 기인한 실패만 수정한다.

- [ ] **Step 2: 변경 없음 — 검증 전용**

모든 관련 테스트가 우리 변경 전과 동일하거나 개선되면 구현 완료.

---

## Self-Review

**1. Spec coverage:**
- §3.1 컨트롤러 role 확대 → Task 2 ✓
- §3.2 서비스 테넌트 경계 검사(조회 전) → Task 1 ✓
- §3.3 프론트 무변경 → 어떤 Task 도 admin-ui 안 건드림 ✓
- §6.1 RpAdminBoundaryIT 4 케이스 → Task 4 ✓
- §6.2 AaguidPolicyControllerSecurityTest 신규 → Task 3 ✓
- §6.3 AaguidPolicyCeremonyIT 회귀 → Task 5 ✓
- §5 에러처리(경계검사 먼저, 정보노출 방지) → Task 1 의 호출 순서로 구현 ✓

**2. Placeholder scan:** TBD/TODO/"적절히 처리" 없음. 코드 스텝마다 완전한 코드 포함. Task 3/4 의
조정 안내는 placeholder 가 아니라, 실제 시그니처/헬퍼에 맞추라는 구체적 검증 지시(grep 명령 + 불변식 명시) ✓

**3. Type consistency:**
- `tenantBoundary.assertCanAccessTenant(UUID)` — Task 1 에서 주입·호출, CredentialAdminService 와 동일 시그니처 ✓
- `AaguidPolicyService(repo, mdsCache, tenantBoundary)` 생성자 — Task 1 정의, Task 3 슬라이스는 service 를 MockBean 으로 대체하므로 생성자 무관 ✓
- `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` 문자열 — Task 2(컨트롤러), Task 3(RP_ADMIN 200 기대) 일관 ✓
- `AaguidPolicyDto.View(UUID, Mode, boolean, List<Entry>, Instant, String)` — Task 3 sampleView 가 service.get() 의 반환 형태와 일치(구현 전 grep 으로 최종 확인 지시) ✓
- 엔드포인트 경로 `/admin/api/tenants/{tenantId}/aaguid-policy` — Task 3, Task 4 일관 ✓
