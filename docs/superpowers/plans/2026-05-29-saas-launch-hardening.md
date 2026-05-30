# SaaS Launch Hardening (P0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SaaS 출시를 차단하는 P0 6건(테넌트 자식 테이블 VPD 누락, 중복 등록 방지, 테넌트 suspend, 사용자 self-service credential, admin MFA, 보안정책 미적용)을 닫아 외부 고객 대상 production SaaS로 안전하게 켤 수 있게 한다.

**Architecture:** 변경은 4개 모듈에 분산된다 — `core`(엔티티/repository/Flyway 마이그레이션), `passkey-app`(RP-facing ceremony + self-service API), `admin-app`(테넌트 라이프사이클 + 보안정책 enforcement + admin 보안). 각 P0는 독립적이며 한 task 그룹으로 구성된다. 기존 패턴을 따른다: VPD는 `DBMS_RLS.ADD_POLICY` + `tenant_predicate`(V20), 엔티티는 `BaseEntity` 상속 + `@JdbcTypeCode(SqlTypes.UUID)`, 서비스는 `@Transactional` + `AuditLogService.append`, 테스트는 JUnit5.

**Tech Stack:** Java 17, Spring Boot 3.x, JPA/Hibernate 6, Oracle XE 21(VPD), Flyway(V35~), JUnit5, Spring Security(form login + TOTP), Redis.

**근거 spec:** `docs/superpowers/specs/2026-05-29-saas-readiness-gap-audit-design.md` (§3 P0-1~P0-6)

---

## 마이그레이션 번호 할당

현재 HEAD는 V34. 본 plan에서 신규 마이그레이션은 아래 순서로 번호를 점유한다 (충돌 방지):

| 버전 | 내용 | P0 |
|---|---|---|
| V35 | 테넌트 자식 테이블 VPD policy | P0-1 |
| V36 | credential `label` 컬럼 + recovery code 테이블 | P0-4, P0-5 |

> suspend(P0-2)는 기존 `tenant.status` 컬럼을 사용하므로 신규 마이그레이션 불필요. MFA(P0-5)의 `mfa_enabled_flag`/`mfa_secret` 컬럼은 이미 V32에 존재 — recovery code 테이블만 V36에서 추가. CORS/password(P0-6)는 기존 `security_policy` 컬럼 사용. TOTP는 RFC 6238을 직접 구현(외부 라이브러리 추가 없음).

---

## 파일 구조 (생성/수정 맵)

**P0-1 VPD (core):**
- Create: `core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql`

**P0-3 excludeCredentials (passkey-app, core):**
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartServiceExcludeTest.java`

**P0-2 테넌트 suspend (admin-app, passkey-app):**
- Modify: `admin-app/.../tenant/TenantAdminService.java`, `TenantAdminController.java`, `TenantAdminDto.java`
- Modify: `passkey-app/.../fido2/registration/RegistrationStartService.java` + `authentication/AuthenticationStartService.java` (suspended tenant 거부)
- Test: `admin-app/.../tenant/TenantSuspendTest.java`

**P0-4 self-service credential (passkey-app, core):**
- Create: `passkey-app/.../api/v1/rp/CredentialSelfServiceController.java` + `dto/` + service
- Modify: `core/.../entity/Credential.java`, `CredentialRepository.java`, migration V36
- Test: `passkey-app/.../api/v1/rp/CredentialSelfServiceTest.java`

**P0-5 admin MFA (admin-app, core):**
- Create: `admin-app/.../auth/TotpService.java`, `MfaController.java`
- Modify: `AdminSecurityConfig.java` (로그인 성공 핸들러에서 MFA 분기), `AdminUserDetailsService.java` (MFA 미완료 표시), migration V36(recovery code, Task 4에 포함)
- Test: `admin-app/.../auth/TotpServiceTest.java`

**P0-6 보안정책 enforcement (admin-app):**
- Create: `admin-app/.../policy/DynamicCorsConfigurationSource.java`, `PasswordPolicyValidator.java`
- Modify: `AdminSecurityConfig.java`, `InvitationService.java`, `MfaController` password 변경 경로
- Test: `admin-app/.../policy/PasswordPolicyValidatorTest.java`

---

## Task 1 (P0-1): 테넌트 자식 테이블 VPD policy

**근거:** spec P0-4. `tenant_allowed_origin`, `tenant_accepted_format`, `tenant_aaguid_policy`, `tenant_aaguid_policy_entry`, `tenant_webauthn_snapshot` 에 VPD 미적용. 격리를 DB로 강제.

**Files:**
- Create: `core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql`

> **주의:** 이 테이블들은 admin-app(EXEMPT ACCESS POLICY 가진 APP_ADMIN role)에서 주로 접근하므로 admin 경로는 정책 영향을 받지 않는다. passkey-app(APP_RUNTIME)이 ceremony 중 읽을 때만 tenant 격리가 적용된다. ceremony 시점에는 `ApiKeyAuthFilter`가 이미 tenant context를 set 하므로 정상 동작한다.

- [ ] **Step 1: 대상 테이블의 컬럼명 확인**

Run: `grep -nE "tenant_id|CREATE TABLE" core/src/main/resources/db/migration/V21__tenant_config_normalize.sql core/src/main/resources/db/migration/V26__tenant_aaguid_policy.sql core/src/main/resources/db/migration/V27__tenant_webauthn_snapshot.sql`
Expected: 각 테이블이 `tenant_id RAW(16)` 컬럼을 가짐을 확인. (정책 predicate가 이 컬럼명을 참조)

- [ ] **Step 2: 마이그레이션 작성**

Create `core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql`:

```sql
-- P0-1: Extend VPD row-level isolation to tenant child tables that were
-- previously protected only at the application layer. Reuses the existing
-- APP_OWNER.tenant_predicate function (V20) — '1=0' when no tenant context,
-- else tenant_id = HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID')).
--
-- admin-app runs as APP_ADMIN (EXEMPT ACCESS POLICY) so these policies do
-- not affect admin queries; they enforce isolation for APP_RUNTIME
-- (passkey-app) reads during ceremonies.
--
-- Idempotent: each ADD_POLICY is wrapped so a re-run (policy already exists,
-- ORA-28101) does not fail the migration.

DECLARE
  PROCEDURE add_tenant_policy(p_table IN VARCHAR2, p_policy IN VARCHAR2) IS
  BEGIN
    DBMS_RLS.ADD_POLICY(
      object_schema   => 'APP_OWNER',
      object_name     => p_table,
      policy_name     => p_policy,
      function_schema => 'APP_OWNER',
      policy_function => 'TENANT_PREDICATE',
      statement_types => 'SELECT,INSERT,UPDATE,DELETE',
      update_check    => TRUE
    );
  EXCEPTION
    WHEN OTHERS THEN
      IF SQLCODE = -28101 THEN NULL;  -- policy already exists → idempotent
      ELSE RAISE;
      END IF;
  END;
BEGIN
  add_tenant_policy('TENANT_ALLOWED_ORIGIN',      'TENANT_ALLOWED_ORIGIN_ISOLATION');
  add_tenant_policy('TENANT_ACCEPTED_FORMAT',     'TENANT_ACCEPTED_FORMAT_ISOLATION');
  add_tenant_policy('TENANT_AAGUID_POLICY',       'TENANT_AAGUID_POLICY_ISOLATION');
  add_tenant_policy('TENANT_AAGUID_POLICY_ENTRY', 'TENANT_AAGUID_ENTRY_ISOLATION');
  add_tenant_policy('TENANT_WEBAUTHN_SNAPSHOT',   'TENANT_WEBAUTHN_SNAPSHOT_ISOLATION');
END;
/
```

> **검증 필요:** Step 1에서 `tenant_aaguid_policy_entry`가 별도 테이블인지(@ElementCollection이면 테이블명이 다를 수 있음) 확인하고, 또한 각 테이블에 `tenant_id` 컬럼이 실제로 존재하는지 확인한다. 만약 `tenant_aaguid_policy_entry`에 `tenant_id`가 없고 부모 FK만 있다면 해당 줄을 제거하고 그 사실을 plan 실행 노트에 기록한다.

- [ ] **Step 3: Flyway 마이그레이션 검증 (DB 기동 후)**

Run: `docker compose up -d && SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun` (또는 기존 통합테스트가 Flyway migrate를 돌리면 그것으로 대체)
Expected: Flyway가 V35를 적용하고 ORA 에러 없이 부팅. 로그에 `Migrating schema ... to version 35`.

- [ ] **Step 4: 격리 동작 수동 확인**

Run: passkey-app 기동 후 ceremony 한 번 수행 → `tenant_allowed_origin` 조회가 tenant context로 필터되는지 확인. (기존 sample-rp 등록 플로우가 정상 동작하면 통과 — origin 조회가 막히지 않음을 의미)
Expected: 등록 ceremony 정상 완료(기존 동작 회귀 없음).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql
git commit -m "feat(vpd): tenant 자식 테이블 5종 VPD policy 추가 (P0-1)"
```

---

## Task 2 (P0-3): excludeCredentials — 중복 등록 방지

**근거:** spec P0-6. registration start가 기존 credential을 광고하지 않아 중복 등록 가능.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartServiceExcludeTest.java`

- [ ] **Step 1: Repository에 userHandle 기반 조회 추가**

Modify `CredentialRepository.java` — 인터페이스에 메서드 추가:

```java
    /**
     * P0-3: registration/start 가 excludeCredentials 를 채우기 위해 사용.
     * 같은 userHandle 의 기존 credentialId 들을 반환 → 동일 authenticator 중복 등록 방지.
     * VPD 가 tenant 로 필터하므로 tenant 격리는 세션 컨텍스트가 담당.
     */
    @Query("select c.credentialId from Credential c where c.userHandle = :userHandle")
    java.util.List<byte[]> findCredentialIdsByUserHandle(@Param("userHandle") byte[] userHandle);
```

- [ ] **Step 2: Write the failing test**

Create `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartServiceExcludeTest.java`:

```java
package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationStartServiceExcludeTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final ChallengeIssuer challenges = mock(ChallengeIssuer.class);
    private final ChallengeStore store = mock(ChallengeStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void start_includesExcludeCredentials_whenUserHasExistingCredential() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getRpName()).thenReturn("Example");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");

        byte[] existing = new byte[]{1, 2, 3, 4};
        byte[] userHandle = new byte[]{9, 9};
        when(credentials.findCredentialIdsByUserHandle(any())).thenReturn(List.of(existing));

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock);
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
                "Disp", "alice");

        RegistrationStartResponse resp = svc.start(req);

        JsonNode exclude = resp.options().get("excludeCredentials");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();
        assertThat(exclude).hasSize(1);
        assertThat(exclude.get(0).get("type").asText()).isEqualTo("public-key");
        String expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString(existing);
        assertThat(exclude.get(0).get("id").asText()).isEqualTo(expectedId);
    }

    @Test
    void start_emptyExcludeCredentials_whenNoExisting() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getRpName()).thenReturn("Example");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");
        when(credentials.findCredentialIdsByUserHandle(any())).thenReturn(List.of());

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock);
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{9, 9}),
                "Disp", "alice");

        RegistrationStartResponse resp = svc.start(req);
        JsonNode exclude = resp.options().get("excludeCredentials");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();
        assertThat(exclude).isEmpty();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :passkey-app:test --tests "*RegistrationStartServiceExcludeTest*"`
Expected: 컴파일 실패 — `RegistrationStartService` 생성자에 `CredentialRepository` 인자가 없음.

- [ ] **Step 4: RegistrationStartService에 excludeCredentials 구현**

Modify `RegistrationStartService.java`:

1. import 추가: `import com.crosscert.passkey.core.repository.CredentialRepository;` 와 `import java.util.List;`
2. 필드 추가 및 생성자에 `CredentialRepository credentials` 주입:

```java
    private final CredentialRepository credentials;

    public RegistrationStartService(TenantRepository tenants,
                                    CredentialRepository credentials,
                                    ChallengeIssuer challenges,
                                    ChallengeStore store,
                                    ObjectMapper mapper,
                                    Clock clock) {
        this.tenants = tenants;
        this.credentials = credentials;
        this.challenges = challenges;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }
```

3. `start()` 안에서 `options.put("attestation", "indirect");` **직후**, `authenticatorSelection` 블록 **앞**에 excludeCredentials 추가:

```java
        ArrayNode excludeArr = options.putArray("excludeCredentials");
        for (byte[] existingId : credentials.findCredentialIdsByUserHandle(userHandle)) {
            ObjectNode entry = excludeArr.addObject();
            entry.put("type", "public-key");
            entry.put("id", b64url(existingId));
        }
```

> `userHandle` 변수는 이미 `start()` 상단에서 `Base64.getUrlDecoder().decode(req.userHandle())`로 디코드되어 존재한다.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :passkey-app:test --tests "*RegistrationStartServiceExcludeTest*"`
Expected: PASS (2 tests).

- [ ] **Step 6: 전체 passkey-app 테스트로 회귀 확인**

Run: `./gradlew :passkey-app:test`
Expected: BUILD SUCCESSFUL — 기존 `RegistrationStartService`를 생성하는 다른 테스트가 생성자 시그니처 변경으로 깨지면 그 테스트들도 `CredentialRepository` mock을 추가해 수정한다.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartServiceExcludeTest.java
git commit -m "feat(webauthn): registration/start 에 excludeCredentials 추가 — 중복 등록 방지 (P0-3)"
```

---

## Task 3 (P0-2): 테넌트 suspend / activate 라이프사이클

**근거:** spec P0-3. `tenant.status` 컬럼만 있고 전이 메서드·엔드포인트 없음. suspend 시 ceremony 거부 + api-key 일괄 revoke.

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java`
- Modify: `passkey-app/.../fido2/registration/RegistrationStartService.java`
- Modify: `passkey-app/.../fido2/authentication/AuthenticationStartService.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantSuspendTest.java`

- [ ] **Step 1: ApiKeyRepository에 tenant별 활성 키 조회 추가**

Modify `ApiKeyRepository.java` — 메서드 추가:

```java
    /** P0-2: suspend 시 일괄 revoke 대상 — 아직 revoke 되지 않은 tenant 의 키들. */
    @Query("select k from ApiKey k where k.tenantId = :tenantId and k.revokedAt is null")
    java.util.List<com.crosscert.passkey.core.entity.ApiKey> findActiveByTenantId(@Param("tenantId") UUID tenantId);
```

- [ ] **Step 2: Write the failing test**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantSuspendTest.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantSuspendTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final ApiKeyRepository apiKeys = mock(ApiKeyRepository.class);
    private final com.crosscert.passkey.admin.audit.AuditLogService audit =
            mock(com.crosscert.passkey.admin.audit.AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void suspend_setsStatusAndRevokesActiveKeys() {
        UUID tenantId = UUID.randomUUID();
        Tenant t = new Tenant("acme", "Acme", "acme.example.com", "Acme");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        ApiKey k1 = mock(ApiKey.class);
        ApiKey k2 = mock(ApiKey.class);
        when(apiKeys.findActiveByTenantId(tenantId)).thenReturn(List.of(k1, k2));

        TenantLifecycleService svc = new TenantLifecycleService(tenants, apiKeys, audit, clock);

        svc.suspend(tenantId, UUID.randomUUID(), "alice@crosscert.com");

        assertThat(t.getStatus()).isEqualTo("suspended");
        verify(k1).revoke(any());
        verify(k2).revoke(any());
        verify(tenants).save(t);
    }

    @Test
    void activate_setsStatusActive() {
        UUID tenantId = UUID.randomUUID();
        Tenant t = new Tenant("acme", "Acme", "acme.example.com", "Acme");
        t.suspend();
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));

        TenantLifecycleService svc = new TenantLifecycleService(tenants, apiKeys, audit, clock);

        svc.activate(tenantId, UUID.randomUUID(), "alice@crosscert.com");
        assertThat(t.getStatus()).isEqualTo("active");
    }
}
```

> 생성자 시그니처는 검증 완료: `Tenant(slug, displayName, rpId, rpName)`, `ApiKey.revoke(Instant)` 모두 실제 코드와 일치.

- [ ] **Step 3: Tenant 엔티티에 status 전이 헬퍼 추가**

Modify `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java` — `getStatus()` 근처에 추가:

```java
    public void suspend() { this.status = "suspended"; }
    public void activate() { this.status = "active"; }
    public boolean isSuspended() { return "suspended".equals(status); }
```

- [ ] **Step 4: TenantLifecycleService 작성**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantLifecycleService.java`:

```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P0-2: 테넌트 status 전이 (suspend/activate) + suspend 시 활성 API 키 일괄 revoke. */
@Service
public class TenantLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    private final TenantRepository tenants;
    private final ApiKeyRepository apiKeys;
    private final AuditLogService audit;
    private final Clock clock;

    public TenantLifecycleService(TenantRepository tenants, ApiKeyRepository apiKeys,
                                  AuditLogService audit, Clock clock) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @Transactional
    public void suspend(UUID tenantId, UUID actorId, String actorEmail) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found: " + tenantId));
        t.suspend();
        tenants.save(t);
        List<ApiKey> active = apiKeys.findActiveByTenantId(tenantId);
        for (ApiKey k : active) {
            k.revoke(clock.instant());
        }
        audit.append(new AuditAppendRequest(actorId, actorEmail, "TENANT_SUSPEND",
                "TENANT", tenantId.toString(), tenantId,
                Map.of("revokedKeys", active.size())));
        log.warn("tenant suspended: tenantId={} revokedKeys={} actor={}",
                tenantId, active.size(), actorEmail);
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @Transactional
    public void activate(UUID tenantId, UUID actorId, String actorEmail) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found: " + tenantId));
        t.activate();
        tenants.save(t);
        audit.append(new AuditAppendRequest(actorId, actorEmail, "TENANT_ACTIVATE",
                "TENANT", tenantId.toString(), tenantId, Map.of()));
        log.info("tenant activated: tenantId={} actor={}", tenantId, actorEmail);
    }
}
```

> **검증:** `AuditAppendRequest` 생성자 인자 순서/타입을 기존 사용처(`CredentialAdminService.java`의 `new AuditAppendRequest(actorId, actorEmail, "CREDENTIAL_REVOKE", "CREDENTIAL", credentialIdB64, tenantId, payload)`)와 정확히 맞춘다 — 위 코드는 그 패턴을 따른다.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :admin-app:test --tests "*TenantSuspendTest*"`
Expected: PASS (2 tests).

- [ ] **Step 6: Controller 엔드포인트 추가**

Modify `TenantAdminController.java` — `TenantLifecycleService`를 생성자에 주입하고 엔드포인트 추가:

```java
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/{id}/suspend")
    public ApiResponse<Void> suspend(@PathVariable UUID id, Authentication auth) {
        lifecycle.suspend(id, currentActorId(auth), auth.getName());
        return ApiResponse.ok(null);
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PostMapping("/{id}/activate")
    public ApiResponse<Void> activate(@PathVariable UUID id, Authentication auth) {
        lifecycle.activate(id, currentActorId(auth), auth.getName());
        return ApiResponse.ok(null);
    }
```

> `currentActorId(auth)` 헬퍼가 기존 컨트롤러에 있는지 확인하고, 없으면 `CredentialAdminController`가 actorId를 얻는 방식(보통 `AdminUserDetails`에서 추출)을 그대로 복제한다. `ApiResponse.ok(null)` 시그니처도 기존 컨트롤러 반환 패턴과 맞춘다.

- [ ] **Step 7: passkey-app ceremony에서 suspended tenant 거부**

Modify `RegistrationStartService.java` — `Tenant tenant = tenants.findById(...)` 직후에 추가:

```java
        if (tenant.isSuspended()) {
            throw new IllegalStateException("tenant suspended: " + tenantId);
        }
```

Modify `AuthenticationStartService.java` — 동일하게 tenant 조회 직후 동일 가드 추가. (해당 서비스에서 `Tenant` 객체를 조회하는 위치를 찾아 그 뒤에 삽입.)

> **참고:** 더 견고하게는 `ApiKeyAuthFilter`에서 tenant status를 확인해 모든 RP API를 막는 것이지만, 필터는 tenant 엔티티를 조회하지 않고 lookup 패키지만 쓰므로 P0 범위에서는 ceremony start 2곳 가드로 충분하다. (suspend 시 키도 revoke되므로 이중 방어.)

- [ ] **Step 8: 전체 회귀 테스트**

Run: `./gradlew :admin-app:test :passkey-app:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantLifecycleService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java \
        core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java \
        core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantSuspendTest.java
git commit -m "feat(tenant): suspend/activate 라이프사이클 + ceremony 거부 + 키 일괄 revoke (P0-2)"
```

---

## Task 4 (P0-5): credential `label` 컬럼 + recovery code 테이블 마이그레이션

> P0-4(self-service)와 P0-5(MFA)가 공유하는 스키마 변경을 한 마이그레이션으로 묶는다.

**Files:**
- Create: `core/src/main/resources/db/migration/V36__credential_label_and_mfa_recovery.sql`

- [ ] **Step 1: 기존 ElementCollection/테이블 패턴 확인**

Run: `grep -rn "admin_user_recovery\|recovery_code\|GRANT" core/src/main/resources/db/migration/V32__admin_user_mfa.sql`
Expected: V32가 `admin_user`에 `mfa_enabled_flag`, `mfa_secret` 컬럼을 추가했음을 확인. recovery code 테이블은 없음.

- [ ] **Step 2: 마이그레이션 작성**

Create `core/src/main/resources/db/migration/V36__credential_label_and_mfa_recovery.sql`:

```sql
-- P0-4: end-user 가 자신의 passkey 를 식별할 수 있도록 label 컬럼 추가.
-- nullable — 기존 credential 은 label 없이 동작.
ALTER TABLE CREDENTIAL ADD (LABEL VARCHAR2(128));

-- P0-5: admin MFA recovery code. 1회용 코드의 sha-256 hash 만 저장.
-- admin_user 와 1:N. tenant 무관(platform 자원)이므로 VPD 없음.
CREATE TABLE ADMIN_USER_RECOVERY_CODE (
  ID            RAW(16)      DEFAULT SYS_GUID() PRIMARY KEY,
  ADMIN_USER_ID RAW(16)      NOT NULL,
  CODE_HASH     VARCHAR2(64) NOT NULL,
  USED_AT       TIMESTAMP,
  CREATED_AT    TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT FK_RECOVERY_ADMIN_USER FOREIGN KEY (ADMIN_USER_ID)
    REFERENCES ADMIN_USER (ID) ON DELETE CASCADE
);
CREATE INDEX IX_RECOVERY_ADMIN_USER ON ADMIN_USER_RECOVERY_CODE (ADMIN_USER_ID);

-- APP_RUNTIME 은 credential.label 을 읽고 쓸 수 있어야 한다 (self-service API).
-- recovery code 테이블은 admin-app(APP_ADMIN)만 접근 — runtime grant 불필요.
GRANT SELECT, INSERT, UPDATE, DELETE ON CREDENTIAL TO APP_RUNTIME_ROLE;
```

> **검증 필요:** runtime role 이름(`APP_RUNTIME_ROLE`)이 기존 grant 마이그레이션(V4/V12/V13/V16)에서 쓰는 정확한 식별자와 일치하는지 확인하고 맞춘다. CREDENTIAL 에 이미 광범위 grant가 있으면(V4) 마지막 GRANT 줄은 중복이므로 제거한다. admin_user 테이블의 PK 컬럼명이 `ID`인지(BaseEntity 패턴) 확인.

- [ ] **Step 3: Flyway 적용 확인**

Run: admin-app 기동 또는 통합테스트로 Flyway migrate.
Expected: V36 적용, 에러 없음.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/resources/db/migration/V36__credential_label_and_mfa_recovery.sql
git commit -m "feat(schema): credential.label + admin MFA recovery code 테이블 (P0-4,P0-5)"
```

---

## Task 5 (P0-4): 사용자 self-service credential API

**근거:** spec P0-5. end-user 가 자신의 passkey 를 조회·이름변경·삭제할 RP-facing API 없음.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/CredentialSelfServiceController.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/dto/CredentialView.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfService.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfServiceTest.java`

> **설계:** RP 백엔드가 X-API-Key 로 인증된 상태에서 특정 user(userHandle 기준)의 credential 을 관리한다. tenant 격리는 VPD(이미 set 된 context)가 담당. end-user 본인 인증은 RP 책임 — passkey-app 은 "이 tenant 의 이 userHandle 의 credential" 범위만 보장.

- [ ] **Step 1: Credential 엔티티에 label 추가**

Modify `Credential.java`:

```java
    @Column(name = "LABEL", length = 128)
    private String label;
```

그리고 getter/setter:

```java
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
```

- [ ] **Step 2: Repository에 userHandle 조회 + 삭제 추가**

Modify `CredentialRepository.java`:

```java
    /** P0-4: self-service — 특정 userHandle 의 모든 credential (VPD 가 tenant 격리). */
    java.util.List<Credential> findByUserHandle(byte[] userHandle);

    /** P0-4: self-service 삭제 — credentialId + userHandle 동시 일치 (소유권 확인). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Credential c where c.credentialId = :credentialId and c.userHandle = :userHandle")
    Optional<Credential> findOwnedForUpdate(@Param("credentialId") byte[] credentialId,
                                            @Param("userHandle") byte[] userHandle);
```

- [ ] **Step 3: Write the failing test**

Create `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfServiceTest.java`:

```java
package com.crosscert.passkey.app.fido2.credential;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CredentialSelfServiceTest {

    private final CredentialRepository creds = mock(CredentialRepository.class);

    @Test
    void list_returnsCredentialsForUserHandle() {
        byte[] uh = {1, 2};
        Credential c = mock(Credential.class);
        when(c.getCredentialId()).thenReturn(new byte[]{9});
        when(c.getLabel()).thenReturn("MacBook");
        when(creds.findByUserHandle(uh)).thenReturn(List.of(c));

        CredentialSelfService svc = new CredentialSelfService(creds);
        var views = svc.list(uh);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).label()).isEqualTo("MacBook");
    }

    @Test
    void rename_setsLabel() {
        byte[] uh = {1, 2};
        byte[] cid = {9};
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c));

        CredentialSelfService svc = new CredentialSelfService(creds);
        svc.rename(uh, cid, "iPhone");

        verify(c).setLabel("iPhone");
    }

    @Test
    void delete_removesOwnedCredential() {
        byte[] uh = {1, 2};
        byte[] cid = {9};
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c));

        CredentialSelfService svc = new CredentialSelfService(creds);
        svc.delete(uh, cid);

        verify(creds).delete(c);
    }

    @Test
    void delete_throwsWhenNotOwned() {
        when(creds.findOwnedForUpdate(any(), any())).thenReturn(Optional.empty());
        CredentialSelfService svc = new CredentialSelfService(creds);
        assertThatThrownBy(() -> svc.delete(new byte[]{1}, new byte[]{2}))
                .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :passkey-app:test --tests "*CredentialSelfServiceTest*"`
Expected: 컴파일 실패 — `CredentialSelfService` 클래스 없음.

- [ ] **Step 5: DTO + Service 구현**

Create `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/dto/CredentialView.java`:

```java
package com.crosscert.passkey.app.api.v1.rp.dto;

import java.time.Instant;

public record CredentialView(
        String credentialId,   // base64url
        String label,
        String aaguidHex,
        Instant lastUsedAt
) {}
```

Create `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfService.java`:

```java
package com.crosscert.passkey.app.fido2.credential;

import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** P0-4: end-user self-service credential 관리. tenant 격리는 VPD 가 담당. */
@Service
public class CredentialSelfService {

    private final CredentialRepository creds;

    public CredentialSelfService(CredentialRepository creds) {
        this.creds = creds;
    }

    @Transactional(readOnly = true)
    public List<CredentialView> list(byte[] userHandle) {
        return creds.findByUserHandle(userHandle).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void rename(byte[] userHandle, byte[] credentialId, String label) {
        Credential c = creds.findOwnedForUpdate(credentialId, userHandle)
                .orElseThrow(() -> new IllegalArgumentException("credential not found or not owned"));
        c.setLabel(label);
    }

    @Transactional
    public void delete(byte[] userHandle, byte[] credentialId) {
        Credential c = creds.findOwnedForUpdate(credentialId, userHandle)
                .orElseThrow(() -> new IllegalArgumentException("credential not found or not owned"));
        creds.delete(c);
    }

    private CredentialView toView(Credential c) {
        return new CredentialView(
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                c.getLabel(),
                c.getAaguid() == null ? null : HexFormat.of().formatHex(c.getAaguid()),
                c.getLastUsedAt());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :passkey-app:test --tests "*CredentialSelfServiceTest*"`
Expected: PASS (4 tests).

- [ ] **Step 7: Controller 작성**

Create `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/CredentialSelfServiceController.java`:

```java
package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView;
import com.crosscert.passkey.app.fido2.credential.CredentialSelfService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;

/**
 * P0-4: RP 백엔드가 X-API-Key 인증 상태에서 특정 user(userHandle)의 passkey 를 관리.
 * tenant 격리는 ApiKeyAuthFilter 가 set 한 context + VPD 가 담당.
 */
@RestController
@RequestMapping("/api/v1/rp/credentials")
public class CredentialSelfServiceController {

    private final CredentialSelfService service;

    public CredentialSelfServiceController(CredentialSelfService service) {
        this.service = service;
    }

    @GetMapping
    public List<CredentialView> list(@RequestParam @NotBlank String userHandle) {
        return service.list(decode(userHandle));
    }

    public record RenameRequest(@NotBlank String userHandle, @NotBlank String label) {}

    @PostMapping("/{credentialId}/label")
    public void rename(@PathVariable String credentialId, @RequestBody RenameRequest req) {
        service.rename(decode(req.userHandle()), decode(credentialId), req.label());
    }

    @DeleteMapping("/{credentialId}")
    public void delete(@PathVariable String credentialId, @RequestParam @NotBlank String userHandle) {
        service.delete(decode(userHandle), decode(credentialId));
    }

    private static byte[] decode(String b64url) {
        return Base64.getUrlDecoder().decode(b64url);
    }
}
```

> **검증:** `/api/v1/rp/credentials/**` 경로가 `ApiKeyAuthFilter`의 보호 대상에 포함되는지 확인한다(필터의 path matcher). 기존 `/api/v1/rp/**` 패턴이면 자동 포함. 또한 응답 envelope 표준화(`ApiResponse`)를 다른 RP 컨트롤러가 쓰는지 확인하고 동일 패턴으로 맞춘다.

- [ ] **Step 8: 전체 회귀 테스트**

Run: `./gradlew :passkey-app:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java \
        core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/CredentialSelfServiceController.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/dto/CredentialView.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfService.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfServiceTest.java
git commit -m "feat(rp): end-user self-service credential list/rename/delete API (P0-4)"
```

---

## Task 6 (P0-5): Admin MFA (TOTP) enforcement

**근거:** spec P0-1. `mfa_enabled_flag`/`mfa_secret` 컬럼만 있고 로그인 시 TOTP 검증 단계가 없음. RFC 6238 TOTP를 직접 구현하고 로그인 후 2nd-factor 게이트를 둔다.

**설계:** form login 성공 → 사용자가 `mfa_enabled=Y`이면 세션을 "MFA 미완료(pre-auth)" 상태로 두고 `/admin/api/mfa/verify`에서 TOTP 코드 검증 후에만 `/admin/api/**` 접근 허용. Spring Security의 `GrantedAuthority`로 단계 구분: MFA 완료 전엔 `ROLE_PRE_MFA`, 완료 후 실제 역할 부여. 가장 단순한 구현은 **세션 속성 플래그 + 필터**다.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/TotpService.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/auth/TotpServiceTest.java`

- [ ] **Step 1: Write the failing test (TOTP 검증 로직)**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/auth/TotpServiceTest.java`:

```java
package com.crosscert.passkey.admin.auth;

import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService svc = new TotpService();

    @Test
    void verify_acceptsCodeGeneratedForSameTimestep() {
        // RFC 6238 test vector: secret "12345678901234567890" (ASCII) at T=59s → 94287082 (SHA1, 8 digits).
        // We use 6-digit default; derive expected for a known secret+time via the service's own generator.
        String secret = svc.newSecretBase32();
        long timeStepMillis = 30_000L;
        long fixedTime = 1_700_000_000_000L;
        String code = svc.generate(secret, fixedTime);
        assertThat(svc.verifyAt(secret, code, fixedTime)).isTrue();
    }

    @Test
    void verify_acceptsPreviousWindow_forClockSkew() {
        String secret = svc.newSecretBase32();
        long t = 1_700_000_000_000L;
        String prevCode = svc.generate(secret, t - 30_000L);
        // current time t, but user submits code from previous 30s window → allowed (±1 step)
        assertThat(svc.verifyAt(secret, prevCode, t)).isTrue();
    }

    @Test
    void verify_rejectsWrongCode() {
        String secret = svc.newSecretBase32();
        assertThat(svc.verifyAt(secret, "000000", 1_700_000_000_000L)).isFalse();
    }

    @Test
    void newSecret_isValidBase32() {
        String secret = svc.newSecretBase32();
        assertThat(new Base32().decode(secret)).hasSizeGreaterThanOrEqualTo(20);
    }
}
```

> **참고:** `org.apache.commons.codec.binary.Base32`는 Spring Boot 의존성에 commons-codec이 포함되어 사용 가능. 없으면 `TotpService`가 자체 base32 인코딩을 제공하고 테스트도 그에 맞춘다. Step 2 구현 후 가용성 확인.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :admin-app:test --tests "*TotpServiceTest*"`
Expected: 컴파일 실패 — `TotpService` 없음.

- [ ] **Step 3: TotpService 구현 (RFC 6238)**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/auth/TotpService.java`:

```java
package com.crosscert.passkey.admin.auth;

import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.nio.ByteBuffer;

/**
 * P0-5: RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30s step). 외부 라이브러리 없이 직접 구현.
 * ±1 time-step 윈도우를 허용해 시계 오차를 흡수한다.
 */
@Service
public class TotpService {

    private static final int DIGITS = 6;
    private static final long STEP_MILLIS = 30_000L;
    private static final SecureRandom RNG = new SecureRandom();
    private final Base32 base32 = new Base32();

    /** 새 secret 생성 — 20 bytes (160 bit), base32 인코딩(authenticator 앱 표준). */
    public String newSecretBase32() {
        byte[] buf = new byte[20];
        RNG.nextBytes(buf);
        return base32.encodeToString(buf).replace("=", "");
    }

    public String generate(String secretBase32, long timeMillis) {
        long counter = timeMillis / STEP_MILLIS;
        return hotp(base32.decode(padBase32(secretBase32)), counter);
    }

    /** 현재 시각 기준 ±1 step 윈도우에서 코드 일치 여부. */
    public boolean verifyAt(String secretBase32, String code, long timeMillis) {
        if (code == null || code.length() != DIGITS) return false;
        byte[] key = base32.decode(padBase32(secretBase32));
        long counter = timeMillis / STEP_MILLIS;
        for (long c = counter - 1; c <= counter + 1; c++) {
            if (constantTimeEquals(hotp(key, c), code)) return true;
        }
        return false;
    }

    private static String padBase32(String s) {
        int pad = (8 - (s.length() % 8)) % 8;
        return s + "=".repeat(pad);
    }

    private static String hotp(byte[] key, long counter) {
        try {
            byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0xf;
            int bin = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = bin % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :admin-app:test --tests "*TotpServiceTest*"`
Expected: PASS (4 tests). commons-codec Base32가 없어 컴파일 실패하면 `admin-app/build.gradle.kts`에 `implementation("commons-codec:commons-codec")` 추가(Spring BOM이 버전 관리).

- [ ] **Step 5: 로그인 성공 핸들러에서 MFA 분기 + MfaController**

Modify `AdminSecurityConfig.java` — `adminLoginSuccessHandler`에서, 사용자가 `u.isMfaEnabled()`이면 세션에 `MFA_PENDING=true` 속성을 설정하고 응답에 `mfaRequired:true`를 포함:

```java
            if (u.isMfaEnabled()) {
                req.getSession().setAttribute("MFA_PENDING", Boolean.TRUE);
                res.setStatus(HttpServletResponse.SC_OK);
                res.setContentType("application/json");
                res.getWriter().write(mapper.writeValueAsString(
                        Map.of("email", email, "role", u.getRole(), "mfaRequired", true)));
                return;
            }
```

그리고 filter chain의 `authorizeHttpRequests`에 MFA 검증 엔드포인트를 인증 필요로 추가하고, `/admin/api/**` 접근 전 `MFA_PENDING` 세션 속성을 검사하는 필터를 추가한다. 가장 단순하게는 `addFilterAfter`로 작은 필터를 둔다:

```java
            .addFilterAfter(new MfaPendingFilter(), AuthorizationFilter.class)
```

`MfaPendingFilter`(같은 파일 내 static class 또는 별도 파일): 인증된 세션이고 `MFA_PENDING==true`이며 요청 경로가 `/admin/api/mfa/**`도 `/admin/logout`도 아니면 403 JSON `{"error":"mfa_required"}` 반환.

Create `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java`:

```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.Map;

/** P0-5: 로그인 후 TOTP 2nd-factor 검증 + MFA 등록(secret 발급). */
@RestController
@RequestMapping("/admin/api/mfa")
public class MfaController {

    private final TotpService totp;
    private final AdminUserRepository users;
    private final Clock clock;

    public MfaController(TotpService totp, AdminUserRepository users, Clock clock) {
        this.totp = totp;
        this.users = users;
        this.clock = clock;
    }

    public record VerifyRequest(@NotBlank String code) {}

    @PostMapping("/verify")
    @Transactional(readOnly = true)
    public ResponseEntity<?> verify(@RequestBody VerifyRequest req, Authentication auth, HttpServletRequest http) {
        AdminUser u = users.findByEmail(auth.getName()).orElseThrow();
        if (u.getMfaSecret() == null || !totp.verifyAt(u.getMfaSecret(), req.code(), clock.millis())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_code"));
        }
        http.getSession().removeAttribute("MFA_PENDING");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** MFA 미설정 사용자가 secret 을 발급받아 authenticator 앱에 등록. */
    @PostMapping("/enroll")
    @Transactional
    public ResponseEntity<?> enroll(Authentication auth) {
        AdminUser u = users.findByEmail(auth.getName()).orElseThrow();
        String secret = totp.newSecretBase32();
        u.setMfaSecret(secret);
        u.setMfaEnabled(true);
        users.save(u);
        return ResponseEntity.ok(Map.of("secret", secret));
    }
}
```

> **검증:** `AuthorizationFilter` import는 `AdminSecurityConfig`에 이미 존재. `MfaController.enroll`은 `/admin/api/mfa/**`가 `authenticated()`이면서 `MfaPendingFilter`가 `/admin/api/mfa/**`를 통과시키므로 호출 가능. enroll은 보안상 추후 "현재 비밀번호 재확인"을 요구하는 것이 이상적이나 P0 범위에선 인증 세션이면 허용.

- [ ] **Step 6: 회귀 테스트**

Run: `./gradlew :admin-app:test`
Expected: BUILD SUCCESSFUL. 기존 로그인 통합 테스트가 있으면 MFA 미사용 사용자(alice/bob: `mfa_enabled=N`)는 영향받지 않음을 확인.

- [ ] **Step 7: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/TotpService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/auth/TotpServiceTest.java \
        admin-app/build.gradle.kts
git commit -m "feat(admin): TOTP MFA enforcement — 로그인 후 2nd-factor 게이트 (P0-5)"
```

---

## Task 7 (P0-6): 보안정책 enforcement — CORS allowlist + password 최소길이

**근거:** spec P0-2. `security_policy.cors_allowlist`/`password_min_length`가 DB에 저장만 되고 적용 안 됨.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/PasswordPolicyValidator.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/policy/DynamicCorsConfigurationSource.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/policy/PasswordPolicyValidatorTest.java`

- [ ] **Step 1: Write the failing test (password validator)**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/policy/PasswordPolicyValidatorTest.java`:

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordPolicyValidatorTest {

    private final SecurityPolicyRepository repo = mock(SecurityPolicyRepository.class);

    private SecurityPolicy policyWithMin(int min) {
        SecurityPolicy p = mock(SecurityPolicy.class);
        when(p.getPasswordMinLength()).thenReturn(min);
        return p;
    }

    @Test
    void validate_passesWhenLongEnough() {
        when(repo.findById(1L)).thenReturn(Optional.of(policyWithMin(12)));
        PasswordPolicyValidator v = new PasswordPolicyValidator(repo);
        assertThatNoException().isThrownBy(() -> v.validate("aVeryLongPassword1"));
    }

    @Test
    void validate_throwsWhenTooShort() {
        when(repo.findById(1L)).thenReturn(Optional.of(policyWithMin(12)));
        PasswordPolicyValidator v = new PasswordPolicyValidator(repo);
        assertThatThrownBy(() -> v.validate("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :admin-app:test --tests "*PasswordPolicyValidatorTest*"`
Expected: 컴파일 실패 — `PasswordPolicyValidator` 없음.

- [ ] **Step 3: PasswordPolicyValidator 구현**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/policy/PasswordPolicyValidator.java`:

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** P0-6: security_policy.password_min_length 를 실제 강제. */
@Component
public class PasswordPolicyValidator {

    private static final Long SINGLETON_ID = 1L;
    private final SecurityPolicyRepository repo;

    public PasswordPolicyValidator(SecurityPolicyRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public void validate(String password) {
        SecurityPolicy p = repo.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("security_policy singleton missing"));
        int min = p.getPasswordMinLength();
        if (password == null || password.length() < min) {
            throw new IllegalArgumentException(
                    "password 가 정책 최소 길이(" + min + ")보다 짧습니다");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :admin-app:test --tests "*PasswordPolicyValidatorTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: InvitationService.accept 에 validator 연결**

Modify `InvitationService.java` — `PasswordPolicyValidator`를 생성자에 주입하고 `accept()` 안 `user.setBcryptHash(...)` **직전**에:

```java
        passwordPolicyValidator.validate(password);
```

> 기존 `InvitationService` 생성자/필드에 `private final PasswordPolicyValidator passwordPolicyValidator;`와 생성자 인자를 추가한다. (생성자 본문에서 필드 대입도 추가.)

- [ ] **Step 6: DynamicCorsConfigurationSource 구현 + filter chain 연결**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/policy/DynamicCorsConfigurationSource.java`:

```java
package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

/** P0-6: security_policy.cors_allowlist 를 실제 CORS 정책으로 적용. */
@Component
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

    private static final Long SINGLETON_ID = 1L;
    private final SecurityPolicyRepository repo;
    private final ObjectMapper mapper;

    public DynamicCorsConfigurationSource(SecurityPolicyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        List<String> allow = repo.findById(SINGLETON_ID)
                .map(SecurityPolicy::getCorsAllowlistJson)
                .map(this::parse)
                .orElse(List.of());
        if (allow.isEmpty()) {
            return null; // CORS 비활성 (same-origin 만 허용)
        }
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allow);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        return cfg;
    }

    private List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

Modify `AdminSecurityConfig.java` — `adminFilterChain`에 CORS 연결. `DynamicCorsConfigurationSource`를 메서드 파라미터로 주입받고 체인에 추가:

```java
            .cors(cors -> cors.configurationSource(corsSource))
```

(메서드 시그니처에 `DynamicCorsConfigurationSource corsSource` 추가.)

- [ ] **Step 7: 회귀 테스트**

Run: `./gradlew :admin-app:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/policy/PasswordPolicyValidator.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/policy/DynamicCorsConfigurationSource.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/operator/InvitationService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/policy/PasswordPolicyValidatorTest.java
git commit -m "feat(policy): CORS allowlist + password 최소길이 enforcement (P0-6)"
```

---

## 최종 검증 (전체 P0 완료 후)

- [ ] **전체 빌드 + 테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 모든 모듈 컴파일 + 테스트 통과.

- [ ] **codex review (메모리 지침: 커밋 전 독립 리뷰)**

각 task 커밋 전 staged diff에 대해 `/codex:review`를 돌려 회귀·보안 이슈를 점검한다. (P0-1 VPD, P0-5 TOTP, P0-6 CORS는 특히 보안 민감 — 반드시 리뷰.)

- [ ] **수동 통합 시나리오**

1. dev 프로필 3서버 기동 → sample-rp 등록/인증 정상(회귀 없음).
2. excludeCredentials: 같은 user 재등록 시 authenticator가 "이미 등록됨" 반환.
3. 테넌트 suspend → 해당 tenant API key로 ceremony 호출 시 거부.
4. MFA: alice 계정에 `/admin/api/mfa/enroll` → authenticator 등록 → 재로그인 시 코드 요구.
5. password 정책: min=20으로 올린 뒤 짧은 password로 invitation accept → 거부.

---

## 범위 밖 (P1으로 이월)

본 plan은 P0만 다룬다. spec의 P1(per-tenant 설정 ceremony 반영, metrics, alerting, retention, API key rotation, password reset/lockout, health check)과 P2는 별도 plan으로 수립한다. 특히 **admin account lockout(P1-6)**은 MFA와 인접하나 P0 범위에 넣지 않았다 — MFA가 1차 방어선이고 lockout은 brute-force 정교화 대응이라 P1로 분류.
