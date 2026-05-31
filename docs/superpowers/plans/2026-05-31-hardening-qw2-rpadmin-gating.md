# QW-2 — RP_ADMIN 게이팅 + @PreAuthorize 정합 Implementation Plan

**REQUIRED SUB-SKILL**: `superpowers:subagent-driven-development` — execute each Task in an isolated subagent; the orchestrator reviews each Task's verification output before proceeding.

## Goal

admin-app의 역할-게이팅을 두 부류로 정합한다.

1. **좁힘(narrow)**: `SecurityPolicyController.get()` 와 `SystemInfoController.get()` 은 **테넌트 스코프가 없는 전역 데이터**(전역 CORS allowlist·운영자 이메일·인프라/DB 배너)를 반환하므로 `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` → `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")` 로 좁혀 RP_ADMIN 직접 호출을 백엔드에서 403 처리한다. (findings: `sec-rpadmin-global-security-policy-read`, `sec-rpadmin-system-info-infra-disclosure`)
2. **명시화(make explicit, no behavior change)**: `TenantAdminController.list()/get()` 와 `ApiKeyAdminController.list()` 는 현재 메서드-레벨 `@PreAuthorize` 가 없어 `AdminSecurityConfig` 의 `/admin/api/**.authenticated()` 만 적용된다(RP_ADMIN 포함 인증된 모든 역할 허용). RP_ADMIN read 는 **의도된 패턴**(기존 `viewerCanGet`/`viewerCanList` 가 RP_ADMIN→200 을 핀)이므로 동작을 보존하면서 `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` 를 **명시 추가만** 한다(좁히지 않음). (finding: `sec-tenant-get-list-no-preauthorize`)

**불변식(절대 보존)**:
- **SPA 영향 0** — `admin-ui/src/pages/SettingsPage.tsx` 가 system/security 탭과 패널을 `{platform && ...}` (line 32, 65, 66; `platform = isPlatform(me)`, `isPlatform` 은 `PLATFORM_OPERATOR` 만 true)로 게이트한다. RP_ADMIN SPA 는 `systemInfoApi.get()`/`securityPolicyApi.get()` 을 **절대 호출하지 않으므로** PLATFORM-only 로 좁혀도 SPA 흐름·표시값·레이아웃 무변경. 깨지는 것은 정식 경로 밖 RP_ADMIN 직접 API 호출자(curl/Postman)뿐 — 그게 의도된 게이팅.
- **list/get 명시화는 와이어 응답·상태코드 무변경** — 두 역할만 존재하고 `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` 는 인증 가능한 모든 역할을 허용하므로 관측 동작 동일. 서비스 boundary(`currentTenantScope`/`assertCanAccessTenant`)가 2차 방어로 유지됨.
- **기존 `*ControllerSecurityTest` 회귀 0** — `viewerCanGet`(Tenant), `viewerCanList`(ApiKey) 가 RP_ADMIN→200 을 계속 기대.

## Architecture

- **변경 표면**: 컨트롤러 4개의 `@PreAuthorize` 어노테이션만(서비스/DTO/SPA 무변경). 2개는 annotation 값 변경(narrow), 2개 컨트롤러의 3개 메서드는 annotation 신규 추가(explicit).
- **테스트 슬라이스 패턴**: `@WebMvcTest(controllers=X.class, excludeAutoConfiguration={Hibernate/JpaRepositories/DataSource/SpringDataWeb})` + `@Import({AdminSecurityConfig.class, X.JpaStubs.class})`. `AdminSecurityConfig` 가 `@EnableMethodSecurity`(line 47) 를 보유하므로 슬라이스에서 `@PreAuthorize` 가 실제 적용된다. `@EnableJpaRepositories` 가 `jpaSharedEM_entityManagerFactory` 를 요구하므로 `JpaStubs` 의 mock `EntityManagerFactory`(빈 `Metamodel`) + 다수 core repository `@MockBean` 이 필요하다(기존 `TenantAdminControllerSecurityTest`/`ApiKeyAdminControllerSecurityTest` 와 동일 목록).
- **검증 매트릭스**: 각 좁힘 엔드포인트 — anon→401, RP_ADMIN→403, PLATFORM_OPERATOR→200.

## Tech Stack

- Java 17, Spring Boot (Spring Security `@EnableMethodSecurity` + `@PreAuthorize` SpEL), JUnit 5, Spring Security Test (`@WithMockUser`, `SecurityMockMvcRequestPostProcessors`), Mockito (`@MockBean`).
- 백엔드 컴파일: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q` (EXIT 0).
- admin-app 테스트: `./gradlew :admin-app:test --tests '<pattern>'`. (Oracle `*IT` 는 이 환경 flaky — 본 Phase 는 `@WebMvcTest` 슬라이스만이라 무관.)

---

## Task 1 — `SecurityPolicyController.get()` PLATFORM-only + 신규 `SecurityPolicyControllerSecurityTest`

**Finding**: `sec-rpadmin-global-security-policy-read`

### Files
- `admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyController.java` (수정)
- `admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyControllerSecurityTest.java` (신규)

### Steps

**1.1 (확인)** — 변경 전 현재 코드를 확정한다. `SecurityPolicyController.java:24-28` 의 현재 `get()` 은 정확히 아래다(이미 Read 로 확인됨):

```java
@GetMapping
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
public SecurityPolicyDto.View get() {
    return service.get();
}
```

반환 타입은 **raw `SecurityPolicyDto.View`** (PUT update 와 동일, `ApiResponse` 래핑 아님). `SecurityPolicyDto.View` 시그니처: `View(int sessionIdleTimeoutMinutes, int passwordMinLength, boolean mfaRequired, List<String> corsAllowlist, Instant updatedAt, String updatedBy)`.

**1.2 (TDD: 실패 테스트 먼저)** — 신규 파일 `admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyControllerSecurityTest.java` 를 작성한다. `TenantAdminControllerSecurityTest` 의 슬라이스/JpaStubs/@MockBean 목록을 복제하되 대상 컨트롤러/서비스만 교체한다. 이 테스트는 `RP_ADMIN→403` 케이스가 **변경 전엔 200 이 나와 실패**해야 한다(레드).

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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = SecurityPolicyController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    SecurityPolicyControllerSecurityTest.JpaStubs.class
})
class SecurityPolicyControllerSecurityTest {

    /**
     * AdminApplication carries @EnableJpaRepositories which registers
     * jpaSharedEM_entityManagerFactory even in @WebMvcTest slices.
     * We provide a stub EMF with a real (empty) Metamodel so the
     * JPA wiring succeeds without a real DataSource.
     */
    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = mock(Metamodel.class);
            org.mockito.Mockito.when(metamodel.getEntities()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getManagedTypes()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getEmbeddables()).thenReturn(Set.of());

            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            org.mockito.Mockito.when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean SecurityPolicyService service;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    // AdminSecurityConfig wires a DynamicCorsConfigurationSource constructor param.
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
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

    private static SecurityPolicyDto.View sampleView() {
        return new SecurityPolicyDto.View(
                30, 12, false, List.of(), Instant.parse("2026-05-31T00:00:00Z"), "alice@example.com");
    }

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/security-policy")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void rpAdminGetIsForbidden() throws Exception {
        mvc.perform(get("/admin/api/security-policy"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void platformOperatorCanGet() throws Exception {
        when(service.get()).thenReturn(sampleView());
        mvc.perform(get("/admin/api/security-policy"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordMinLength").value(12))
            .andExpect(jsonPath("$.updatedBy").value("alice@example.com"));
    }
}
```

> 주의: 반환이 **raw `View`** 이므로 jsonPath 는 `$.success` 가 아니라 `$.passwordMinLength`/`$.updatedBy` 같은 record 필드를 직접 검증한다(`ApiResponse` 래핑 없음).

**1.3 (레드 확인)** — 테스트 실행. `rpAdminGetIsForbidden` 가 **실패**(현재 RP_ADMIN→200)하는지 확인한다:

```
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.policy.SecurityPolicyControllerSecurityTest'
```
기대: `rpAdminGetIsForbidden` FAILED (`Status expected:<403> but was:<200>`), 나머지 2개 PASS.

**1.4 (구현: 좁힘)** — `SecurityPolicyController.java` 의 `get()` annotation 을 좁힌다. `update()` 의 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")` 와 동일 레벨로 맞춘다.

```java
// before
@GetMapping
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
public SecurityPolicyDto.View get() {

// after
@GetMapping
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
public SecurityPolicyDto.View get() {
```

(편집 시 `old_string` = `@GetMapping\n    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")\n    public SecurityPolicyDto.View get() {`)

**1.5 (그린 확인)** — 동일 테스트 재실행. 3개 모두 PASS:
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.policy.SecurityPolicyControllerSecurityTest'
```
기대: `anonymousGetIsUnauthorized` PASS, `rpAdminGetIsForbidden` PASS, `platformOperatorCanGet` PASS. EXIT 0.

**1.6 (불변식 검증 — SPA/와이어 무변경)** — 두 가지를 확인한다:
- **SPA 무영향 근거**: `admin-ui/src/pages/SettingsPage.tsx` line 32(`{platform && (` 탭 버튼), line 66(`{platform && tab === 'security' && <SecurityPolicyTab />}`) 로 `SecurityPolicyTab` 은 `platform`(=`isPlatform(me)`, `PLATFORM_OPERATOR` 만 true)일 때만 렌더된다 → RP_ADMIN SPA 는 `GET /admin/api/security-policy` 를 호출하지 않는다. 따라서 좁힘은 SPA 흐름/표시값/레이아웃 무변경. (Read 로 확인: line 66 존재.)
- **PLATFORM 응답 형태 불변**: `platformOperatorCanGet` 가 200 + raw record 필드(`$.passwordMinLength`,`$.updatedBy`) 를 그대로 받음 → 와이어 응답 바이트 불변.

**1.7 (컴파일 게이트)**:
```
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q
```
기대: EXIT 0.

**1.8 (commit)**:
```
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/SecurityPolicyController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyControllerSecurityTest.java
git commit -m "$(cat <<'EOF'
fix(admin-sec): SecurityPolicy GET을 PLATFORM_OPERATOR 전용으로 좁힘

전역 보안정책(CORS allowlist·운영자 이메일)은 테넌트 스코프가 없어
RP_ADMIN 열람 불요. @PreAuthorize hasAnyRole(...) → hasRole(PLATFORM_OPERATOR).
SPA는 SettingsPage가 platform 게이트(SettingsPage.tsx:66)라 RP_ADMIN이
이 엔드포인트를 호출하지 않으므로 SPA 무영향. PUT update는 이미 PLATFORM 전용.
신규 @WebMvcTest 슬라이스: anon→401, RP_ADMIN→403, PLATFORM→200.
finding: sec-rpadmin-global-security-policy-read

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 — `SystemInfoController.get()` PLATFORM-only + 신규 `SystemInfoControllerSecurityTest`

**Finding**: `sec-rpadmin-system-info-infra-disclosure`

### Files
- `admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoController.java` (수정)
- `admin-app/src/test/java/com/crosscert/passkey/admin/system/SystemInfoControllerSecurityTest.java` (신규)

### Steps

**2.1 (확인)** — 현재 `SystemInfoController.java:17-21` (이미 Read 로 확인):

```java
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@GetMapping
public ApiResponse<SystemInfoView> get() {
    return ApiResponse.ok(service.get());
}
```

여기는 Task 1 과 달리 반환이 **`ApiResponse<SystemInfoView>` 래핑**(`ApiResponse.ok(...)`). `SystemInfoView` 는 중첩 record(`Host`, `Component`) 를 포함 — `SystemInfoView(String serverVersion, String deployedAt, Integer apiP95Ms, Integer apiAvgMs, Integer apiP99Ms, Double uptimePercent, Long uptimeDays, Long uptimeIncidentMinutes, Host host, List<Component> components)`. 200 케이스에서는 `components=List.of()`, `host=null` 로 **최소 생성**하고 응답은 `jsonPath("$.success")` 만 검증한다(중첩 record 전수 생성 비용 회피).

**2.2 (TDD: 실패 테스트 먼저)** — 신규 파일 `admin-app/src/test/java/com/crosscert/passkey/admin/system/SystemInfoControllerSecurityTest.java`:

```java
package com.crosscert.passkey.admin.system;

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

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = SystemInfoController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration.class
    }
)
@Import({
    com.crosscert.passkey.admin.config.AdminSecurityConfig.class,
    SystemInfoControllerSecurityTest.JpaStubs.class
})
class SystemInfoControllerSecurityTest {

    /**
     * AdminApplication carries @EnableJpaRepositories which registers
     * jpaSharedEM_entityManagerFactory even in @WebMvcTest slices.
     * We provide a stub EMF with a real (empty) Metamodel so the
     * JPA wiring succeeds without a real DataSource.
     */
    @TestConfiguration
    static class JpaStubs {
        @Bean
        EntityManagerFactory entityManagerFactory() {
            Metamodel metamodel = mock(Metamodel.class);
            org.mockito.Mockito.when(metamodel.getEntities()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getManagedTypes()).thenReturn(Set.of());
            org.mockito.Mockito.when(metamodel.getEmbeddables()).thenReturn(Set.of());

            EntityManagerFactory emf = mock(EntityManagerFactory.class);
            org.mockito.Mockito.when(emf.getMetamodel()).thenReturn(metamodel);
            return emf;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean SystemInfoService service;
    @MockBean AdminUserRepository admins;
    @MockBean com.crosscert.passkey.admin.audit.AuditLogService audit;
    @MockBean com.crosscert.passkey.admin.auth.AdminUserDetailsService uds;
    @MockBean java.time.Clock clock;
    @MockBean org.springframework.security.crypto.password.PasswordEncoder encoder;
    // AdminSecurityConfig wires a DynamicCorsConfigurationSource constructor param.
    @MockBean com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource corsSource;
    // Prevent @EnableJpaRepositories from wiring real Spring Data repos
    @MockBean com.crosscert.passkey.core.repository.TenantRepository tenantRepository;
    @MockBean com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
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

    private static SystemInfoView sampleView() {
        // Minimal construction: nested Host null, empty components — only $.success is asserted.
        return new SystemInfoView(
                "1.0.0", "2026-05-31T00:00:00Z",
                null, null, null, null, null, null,
                null, List.<SystemInfoView.Component>of());
    }

    @Test
    void anonymousGetIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/system/info")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "RP_ADMIN")
    void rpAdminGetIsForbidden() throws Exception {
        mvc.perform(get("/admin/api/system/info"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice@example.com", roles = "PLATFORM_OPERATOR")
    void platformOperatorCanGet() throws Exception {
        when(service.get()).thenReturn(sampleView());
        mvc.perform(get("/admin/api/system/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
```

> 주의: 반환이 `ApiResponse<SystemInfoView>` 이므로 PLATFORM 200 케이스는 `$.success` 만 검증(`ApiResponse.ok` 래핑). `SystemInfoView` 는 `host=null`, `components=List.of()` 로 최소 생성 — Jackson 직렬화 시 null/빈 배열 모두 정상.

**2.3 (레드 확인)**:
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.system.SystemInfoControllerSecurityTest'
```
기대: `rpAdminGetIsForbidden` FAILED (현재 RP_ADMIN→200), 나머지 2개 PASS.

**2.4 (구현: 좁힘)** — `SystemInfoController.java` 의 annotation 좁힘:

```java
// before
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@GetMapping
public ApiResponse<SystemInfoView> get() {

// after
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
@GetMapping
public ApiResponse<SystemInfoView> get() {
```

(편집 시 `old_string` = `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")\n    @GetMapping\n    public ApiResponse<SystemInfoView> get() {`)

**2.5 (그린 확인)**:
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.system.SystemInfoControllerSecurityTest'
```
기대: 3개 모두 PASS. EXIT 0.

**2.6 (불변식 검증 — SPA/와이어 무변경)**:
- **SPA 무영향 근거**: `SettingsPage.tsx` line 32(탭 버튼 `{platform && (`), line 65(`{platform && tab === 'system' && <SystemInfoTab />}`) → `SystemInfoTab` 은 `platform` 일 때만 렌더 → RP_ADMIN SPA 는 `GET /admin/api/system/info` 미호출. 좁힘 무영향. (Read 로 확인: line 65 존재.)
- **PLATFORM 응답 형태 불변**: `platformOperatorCanGet` 가 200 + `$.success=true` (`ApiResponse` 래핑) 유지.

**2.7 (commit)**:
```
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins
git add admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/system/SystemInfoControllerSecurityTest.java
git commit -m "$(cat <<'EOF'
fix(admin-sec): SystemInfo GET을 PLATFORM_OPERATOR 전용으로 좁힘

인프라 메타(호스트·리전·DB 배너·deployment.mode)는 전역 데이터로
RP_ADMIN 열람 불요(정찰 표면). @PreAuthorize hasAnyRole(...) →
hasRole(PLATFORM_OPERATOR). SPA는 SettingsPage가 platform 게이트
(SettingsPage.tsx:65)라 RP_ADMIN이 호출하지 않으므로 SPA 무영향.
신규 @WebMvcTest 슬라이스: anon→401, RP_ADMIN→403, PLATFORM→200
(ApiResponse 래핑이라 $.success만 검증, SystemInfoView 최소 생성).
finding: sec-rpadmin-system-info-infra-disclosure

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 — `TenantAdminController.list()/get()` + `ApiKeyAdminController.list()` 에 `@PreAuthorize` 명시 추가 (동작 보존)

**Finding**: `sec-tenant-get-list-no-preauthorize`

### Files
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java` (수정)
- `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java` (수정)
- (테스트 신규 없음 — 기존 `TenantAdminControllerSecurityTest.viewerCanGet` / `ApiKeyAdminControllerSecurityTest.viewerCanList` 가 회귀 가드)

> **핵심**: 이 Task 는 **좁히지 않는다**. RP_ADMIN read 는 의도된 패턴(기존 테스트가 RP_ADMIN→200 을 핀)이므로 `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` 를 **명시 추가만** 한다. 추가 후에도 두 역할만 존재하므로 관측 동작 동일(순수 defense-in-depth/일관성 보강).

### Steps

**3.1 (회귀 baseline 확보)** — 변경 **전** 기존 테스트가 green 인지 먼저 확인한다(나중에 회귀 0 을 증명하기 위한 기준선):
```
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminControllerSecurityTest' --tests 'com.crosscert.passkey.admin.apikey.ApiKeyAdminControllerSecurityTest'
```
기대: 두 테스트 클래스 모두 PASS. 특히 `viewerCanGet`(Tenant, RP_ADMIN→200), `viewerCanList`(ApiKey, RP_ADMIN→200), `anonymousGetIsUnauthorized`(401) 가 green.

**3.2 (구현: TenantAdminController.list/get 명시 추가)** — `list()` 와 `get()` 에 annotation 을 추가한다. 같은 클래스의 mutating 메서드(`update`/`diff` 는 `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')`, `create`/`suspend`/`activate` 는 `hasRole('PLATFORM_OPERATOR')`)와 일관된 스타일로 read 는 `hasAnyRole` 을 단다.

```java
// before (TenantAdminController.java:33-41)
@GetMapping
public ApiResponse<List<TenantAdminDto.TenantView>> list() {
    return ApiResponse.ok(service.list());
}

@GetMapping("/{idOrSlug}")
public ApiResponse<TenantAdminDto.TenantView> get(@PathVariable String idOrSlug) {
    return ApiResponse.ok(service.get(idOrSlug));
}

// after
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@GetMapping
public ApiResponse<List<TenantAdminDto.TenantView>> list() {
    return ApiResponse.ok(service.list());
}

@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@GetMapping("/{idOrSlug}")
public ApiResponse<TenantAdminDto.TenantView> get(@PathVariable String idOrSlug) {
    return ApiResponse.ok(service.get(idOrSlug));
}
```

`org.springframework.security.access.prepost.PreAuthorize` import 는 이미 존재(line 7) — 추가 import 불요.

**3.3 (구현: ApiKeyAdminController.list 명시 추가)**:

```java
// before (ApiKeyAdminController.java:27-31)
@GetMapping
public ApiResponse<List<ApiKeyAdminDto.ApiKeyView>> list(
        @RequestParam(required = false) String tenantId) {
    return ApiResponse.ok(service.list(tenantId));
}

// after
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@GetMapping
public ApiResponse<List<ApiKeyAdminDto.ApiKeyView>> list(
        @RequestParam(required = false) String tenantId) {
    return ApiResponse.ok(service.list(tenantId));
}
```

`PreAuthorize` import 이미 존재(ApiKeyAdminController.java:7) — 추가 import 불요.

**3.4 (불변식 검증 — 회귀 0)** — 변경 **후** 동일 테스트를 재실행. 3.1 과 결과 동일(green)해야 한다:
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminControllerSecurityTest' --tests 'com.crosscert.passkey.admin.apikey.ApiKeyAdminControllerSecurityTest'
```
기대: 변경 전과 동일하게 모두 PASS. 특히:
- `TenantAdminControllerSecurityTest.viewerCanGet` (RP_ADMIN→200) PASS — 동작 보존 증명.
- `TenantAdminControllerSecurityTest.getReturnsApiErrorWhenTenantNotFound` (RP_ADMIN→404) PASS — get 도 RP_ADMIN 통과.
- `ApiKeyAdminControllerSecurityTest.viewerCanList` (RP_ADMIN→200) PASS — 동작 보존 증명.
- `anonymousGetIsUnauthorized` (401) PASS.

> 회귀 0 의 근거: 두 역할만 존재하고 `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` 는 인증 가능한 모든 역할을 허용하므로 `authenticated()` 만 있던 기존과 관측 동작 동일. SPA 무영향(RP_ADMIN/PLATFORM 모두 list/get 을 사용하는 정상 화면이 그대로 200).

**3.5 (컴파일 게이트)**:
```
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q
```
기대: EXIT 0.

**3.6 (commit)**:
```
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java
git commit -m "$(cat <<'EOF'
fix(admin-sec): tenant/api-key read 엔드포인트에 @PreAuthorize 명시 추가

TenantAdminController.list/get, ApiKeyAdminController.list는 메서드-레벨
@PreAuthorize가 없어 mutating 메서드와 비대칭이었음. RP_ADMIN read는
의도된 패턴(viewerCanGet/viewerCanList가 RP_ADMIN→200 핀)이므로
hasAnyRole(PLATFORM_OPERATOR,RP_ADMIN)을 명시 추가만(좁히지 않음).
두 역할만 존재 → 관측 동작 동일, 서비스 boundary는 2차 방어로 유지.
기존 *SecurityTest 회귀 0 확인.
finding: sec-tenant-get-list-no-preauthorize

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4 — Phase 전체 회귀 게이트

### Files
- (없음 — 검증 전용)

### Steps

**4.1 (admin-app 전체 SecurityTest 회귀)** — 본 Phase 가 손댄 클래스 + 동일 슬라이스 패턴을 쓰는 인접 SecurityTest 들이 모두 green 인지 한 번에 확인한다(`AdminSecurityConfig` import 가 공유되므로 회귀 표면):
```
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.policy.SecurityPolicyControllerSecurityTest' --tests 'com.crosscert.passkey.admin.system.SystemInfoControllerSecurityTest' --tests 'com.crosscert.passkey.admin.tenant.TenantAdminControllerSecurityTest' --tests 'com.crosscert.passkey.admin.apikey.ApiKeyAdminControllerSecurityTest' --tests 'com.crosscert.passkey.admin.config.MeControllerSecurityTest' --tests 'com.crosscert.passkey.admin.auth.MfaControllerSecurityTest' --tests 'com.crosscert.passkey.admin.keymgmt.KeyMgmtControllerSecurityTest' --tests 'com.crosscert.passkey.admin.audit.AuditLogControllerSecurityTest' --tests 'com.crosscert.passkey.admin.mds.MdsAdminControllerSecurityTest'
```
기대: 모든 SecurityTest 클래스 PASS. EXIT 0.

**4.2 (전체 컴파일 게이트)**:
```
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q
```
기대: EXIT 0.

**4.3 (Oracle *IT 표기)** — `RpAdminBoundaryIT`/`PlatformOperatorUnrestrictedIT` 등 Oracle Testcontainers `*IT` 는 이 환경에서 flaky. 실행을 시도하되 환경 실패 시 별도 표기(이전 Phase 컨벤션). 본 Phase 변경은 `@PreAuthorize` 만이고 좁힘 대상(SecurityPolicy/SystemInfo)은 IT 가 RP_ADMIN GET 허용을 핀하지 않으므로(finding verifyNote: `SecurityPolicyIT` 는 GET 을 alice/PLATFORM 으로만 검증) IT 회귀 리스크 없음:
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.*RpAdminBoundaryIT' --tests 'com.crosscert.passkey.admin.*PlatformOperatorUnrestrictedIT' || echo "IT env failure (Oracle Testcontainers flaky) — record in followups, not a regression"
```
기대: green 이면 회귀 없음; env 실패면 followups 에 "Oracle IT env-flaky, slice 테스트로 게이팅 충족" 기록.

**4.4 (커밋 전 게이트 — 메모리 지침)** — 누적 diff 에 대해 `/codex review` 실행(보안 경계 변경이므로 필수). 좁힘 2건이 의도된 게이팅인지, 명시 추가 3건이 동작을 바꾸지 않는지를 독립 검토받는다. 지적 사항 있으면 해당 Task 로 돌아가 수정.

---

## Self-Review

### Spec coverage (이 Phase 의 모든 finding id 가 Task 로 커버되는가)

| finding id | Task | 정책 | 검증 |
|---|---|---|---|
| `sec-rpadmin-global-security-policy-read` | Task 1 | **좁힘** SecurityPolicy GET → `hasRole('PLATFORM_OPERATOR')` | 신규 SecurityPolicyControllerSecurityTest (anon 401 / RP_ADMIN 403 / PLATFORM 200), SPA SettingsPage.tsx:66 게이트 |
| `sec-rpadmin-system-info-infra-disclosure` | Task 2 | **좁힘** SystemInfo GET → `hasRole('PLATFORM_OPERATOR')` | 신규 SystemInfoControllerSecurityTest (anon 401 / RP_ADMIN 403 / PLATFORM 200, ApiResponse 래핑 → `$.success`), SPA SettingsPage.tsx:65 게이트 |
| `sec-tenant-get-list-no-preauthorize` | Task 3 | **명시 추가(동작 보존)** TenantAdmin list/get + ApiKeyAdmin list → `hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` | 기존 viewerCanGet/viewerCanList(RP_ADMIN→200) 회귀 0 |

→ spec §4 의 3개 finding 전부 Task 로 커버됨. §4.1(좁힘 2건)=Task 1·2, §4.2(명시 추가)=Task 3. PUT 경로는 이미 PLATFORM-only 라 무변경(spec §4.1 마지막 줄과 일치).

### Placeholder scan
- "적절히 처리"/"TBD"/"테스트 추가" 류 placeholder **없음**. 모든 코드 Step 은 실제 코드 블록(annotation 변경 정확 위치, 전체 테스트 클래스 본문) + 실제 gradle 명령 + 기대 출력(`Status expected:<403> but was:<200>`, EXIT 0) 포함.
- TDD 적용: Task 1·2 는 레드(1.3/2.3)→구현(1.4/2.4)→그린(1.5/2.5). Task 3 은 순수 annotation 추가(동작 불변)라 "변경 전후 동일 green"(3.1 baseline → 3.4 회귀 0) 방식으로 불변 검증.

### Type/시그니처 일관성
- **반환 타입 구분 정확**: Task 1 SecurityPolicy `get()` 은 **raw `SecurityPolicyDto.View`** → jsonPath `$.passwordMinLength`/`$.updatedBy` (래핑 없음). Task 2 SystemInfo `get()` 은 **`ApiResponse<SystemInfoView>`** → jsonPath `$.success` (래핑). 혼동 없음.
- `SecurityPolicyDto.View` 생성자 6-인자 시그니처(`int,int,boolean,List<String>,Instant,String`) 와 `sampleView()` 인자 순서 일치.
- `SystemInfoView` 10-인자 시그니처와 `sampleView()` 일치(`host=null`, `components=List.<SystemInfoView.Component>of()` 로 제네릭 명시 — 추론 모호성 회피).
- `@MockBean` 목록은 기존 `TenantAdminControllerSecurityTest`/`ApiKeyAdminControllerSecurityTest` 와 동일(대상 Service 만 교체: Task 1=`SecurityPolicyService`+`AuditLogService audit`, Task 2=`SystemInfoService`). `WebauthnDiffService`/`TenantLifecycleService` 는 Tenant 컨트롤러 전용 의존이라 신규 두 테스트에서 제외(컨트롤러 빈에 불필요).
- `@PreAuthorize` import 는 Task 3 의 두 컨트롤러 모두 이미 존재(TenantAdminController.java:7, ApiKeyAdminController.java:7) → 신규 import 불요.

### 리스크 노트
- **신규 두 테스트의 @MockBean 목록 완전성**: `AdminSecurityConfig` + `@EnableJpaRepositories` 가 요구하는 빈 묶음을 기존 통과 테스트에서 1:1 복제했으므로 컨텍스트 로딩 실패 리스크 낮음. 만약 신규 컨트롤러가 추가 빈을 요구해 컨텍스트 로딩이 실패하면(예외 메시지에 `NoSuchBeanDefinition`/missing bean 출현) 해당 빈을 `@MockBean` 으로 추가 — 단 `SecurityPolicyController`/`SystemInfoController` 의 의존은 각각 `SecurityPolicyService`+`AuditLogService` / `SystemInfoService` 뿐이라 추가 필요 가능성 낮음.
- Task 3 은 좁히지 않으므로 SPA(PLATFORM·RP_ADMIN 양쪽이 tenant/api-key 목록을 보는 화면) 무영향.