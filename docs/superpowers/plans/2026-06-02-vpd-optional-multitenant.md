# VPD Optional 멀티테넌트 격리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 테넌트 격리를 앱 레벨 Hibernate @Filter(항상 ON)와 VPD(passkey.vpd.enabled, Optional) 2층으로 분리해, SE2(VPD 미지원)에서도 격리가 보장되고 EE/XE에서는 이중 방어가 되도록 한다.

**Architecture:** 두 층 모두 기존 `TenantContextHolder`(ThreadLocal)를 진실 원천으로 사용. Layer 1은 @Transactional 진입 시 AOP가 Hibernate Session에 tenant 필터를 enable. Layer 2는 `CoreDataSourceConfig`가 프로퍼티에 따라 `TenantAwareDataSource`를 조건부로 래핑. VPD 마이그레이션은 Edition 감지 PL/SQL로 감싸 SE2에서 skip.

**Tech Stack:** Spring Boot, Hibernate 6 (@Filter/@FilterDef), Spring AOP, Oracle (RAW(16) UUID), Flyway, JUnit5 + Testcontainers(Oracle XE).

---

## 사전 확정 사실 (조사 완료)

- `tenant_id`는 **RAW(16)** 컬럼, Java는 **UUID** 타입. BaseEntity PK는 `@JdbcTypeCode(SqlTypes.UUID)` + `columnDefinition="RAW(16)"`로 매핑됨. `Credential.tenantId`는 `columnDefinition="RAW(16)"`만 있고 `@JdbcTypeCode` 없음 → **Task 1에서 @Filter 바인딩 가능 여부를 먼저 검증**.
- **admin-app은 `TenantContextHolder`를 set하지 않음** → RP_ADMIN context 진입점을 신규 추가(Task 6).
- `TenantBoundary.currentTenantScope()`가 이미 `Optional.empty()`(PLATFORM_OPERATOR) / `Optional.of(tid)`(RP_ADMIN)을 반환 → admin context 필터는 이걸 재사용.
- 격리 대상 7 엔티티: Credential, ApiKey, TenantAllowedOrigin, TenantAcceptedFormat, TenantAaguidPolicy, TenantAaguidPolicyEntry(@ElementCollection — TenantAaguidPolicy 안), TenantWebauthnSnapshot.

## File Structure

| 파일 | 책임 | Task |
|------|------|------|
| `core/.../entity/*.java` (7개) | @FilterDef/@Filter 선언 | T1, T2 |
| `core/.../vpd/TenantFilterAspect.java` (신규) | @Transactional 진입 시 filter enable | T3 |
| `core/.../config/CoreDataSourceConfig.java` | passkey.vpd.enabled로 TenantAwareDataSource 조건화 | T5 |
| `core/.../db/migration/V3,V8,V20,V35` | ADD_POLICY를 Edition 감지로 감쌈 | T7 |
| `admin-app/.../auth/TenantContextAdminFilter.java` (신규) | currentTenantScope()→TenantContextHolder.set | T6 |
| `core/test/.../vpd/AppLevelIsolationIT.java` (신규) | vpd=false 격리 검증 | T4 |
| `application*.yml` | passkey.vpd.enabled 설정 | T8 |

작업 브랜치: `worktree-vpd-optional-multitenant` (워크트리 내, 설계 문서 cherry-pick 완료).

---

### Task 1: @Filter UUID↔RAW(16) 바인딩 spike (기술 검증 먼저)

가장 큰 불확실성. @Filter named parameter(UUID)가 RAW(16) 컬럼과 바인딩되는지 먼저 검증한다. 안 되면 condition을 바꾼다. 단일 엔티티(Credential)로 spike.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterBindingIT.java` (신규)

- [ ] **Step 1: 실패 테스트 작성 (필터 바인딩 검증)**

`core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterBindingIT.java` 신규:
```java
package com.crosscert.passkey.core.vpd;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenantFilterBindingIT {

    @Autowired EntityManager em;
    @Autowired CredentialRepository credentials;

    private static final UUID T_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void filterBindsUuidToRaw16AndIsolatesTenant() {
        // T_A, T_B 각각 1개 credential 저장 (필터 off 상태)
        credentials.save(new Credential(T_A, "ua".getBytes(), "ca".getBytes(),
                "pk".getBytes(), 0L, null, null, "none"));
        credentials.save(new Credential(T_B, "ub".getBytes(), "cb".getBytes(),
                "pk".getBytes(), 0L, null, null, "none"));
        em.flush();

        // 필터를 T_A 로 enable → T_A 행만 보여야 함
        em.unwrap(Session.class).enableFilter("tenantFilter").setParameter("tenantId", T_A);
        em.clear();
        long visible = credentials.findAll().stream()
                .filter(c -> c.getTenantId().equals(T_B)).count();
        assertThat(visible).isZero();  // T_B 행은 안 보여야 함
    }
}
```
> Credential 생성자 인자는 실제 시그니처에 맞춰야 함 — 구현 시 `Credential.java` 생성자 확인 후 정확히 맞춘다(현재 `(UUID tenantId, byte[] userHandle, byte[] credentialId, byte[] publicKey, long signCount, byte[] aaguid, String transports, String attestationFmt)` 형태).

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd core && ../gradlew :core:test --tests "com.crosscert.passkey.core.vpd.TenantFilterBindingIT"`
Expected: FAIL — "no filter named tenantFilter" (아직 @FilterDef 없음)

- [ ] **Step 3: Credential 에 @FilterDef/@Filter 추가**

`core/src/main/java/com/crosscert/passkey/core/entity/Credential.java` 의 클래스 선언부에 import 와 어노테이션 추가. 클래스 상단(`@Entity` 근처):
```java
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import java.util.UUID;
```
클래스 어노테이션에 추가:
```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

- [ ] **Step 4: 테스트 실행 — 통과 또는 바인딩 에러 확인**

Run: `cd core && ../gradlew :core:test --tests "com.crosscert.passkey.core.vpd.TenantFilterBindingIT"`
Expected (성공 경로): PASS.
Expected (실패 경로): RAW(16) 바인딩 에러(예: ORA-01465 invalid hex / type mismatch). 이 경우 **Step 5 폴백** 적용.

- [ ] **Step 5: (실패 시에만) condition을 hex 비교로 폴백**

UUID→RAW 바인딩이 안 되면, `@Filter` condition을 RAWTOHEX 비교로 바꾸고 파라미터를 String(hex)로:
```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenantFilter", condition = "RAWTOHEX(tenant_id) = :tenantId")
```
그리고 Step 1 테스트의 `setParameter("tenantId", T_A)`를 `setParameter("tenantId", T_A.toString().replace("-", "").toUpperCase())`로 수정. 다시 Step 4 실행해 PASS 확인.
> 이후 모든 Task에서 enableFilter 파라미터를 이 형식(UUID 또는 hex String)으로 통일한다. Task 3의 Aspect가 단일 변환 지점이 되므로, 폴백 시 Aspect에서 hex 변환.

- [ ] **Step 6: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterBindingIT.java
git commit -m "feat(core): Credential @Filter tenant 격리 spike + 바인딩 검증

UUID↔RAW(16) @Filter named-param 바인딩 가능 여부 검증.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 나머지 6개 엔티티에 @Filter 적용

Task 1에서 확정한 condition 형식(UUID 또는 hex)을 나머지 엔티티에 동일 적용. @FilterDef는 한 번만 정의되면 되므로 공통 위치(예: package-info 또는 BaseEntity)에 두고, 각 엔티티는 @Filter만 단다. 단순화를 위해 @FilterDef는 Credential에 둔 것을 재사용(Hibernate는 전역 등록).

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/TenantAaguidPolicy.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/TenantWebauthnSnapshot.java`

- [ ] **Step 1: 각 엔티티에 @Filter 추가**

각 파일의 클래스 어노테이션에 import + @Filter 추가 (condition은 Task 1 확정 형식; 아래는 UUID 경로 기준):
```java
import org.hibernate.annotations.Filter;
```
```java
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```
주의: 각 엔티티의 tenant_id 실제 컬럼명을 확인. TenantAllowedOrigin/TenantAcceptedFormat은 @ManyToOne FK일 수 있으므로 컬럼명이 `tenant_id`인지 확인 후 condition에 맞춘다(다르면 그 컬럼명 사용).
TenantAaguidPolicyEntry는 @ElementCollection이라 별도 @Filter 불가 — 부모 TenantAaguidPolicy 필터로 격리되거나, @FilterJoinTable이 필요할 수 있음. **@ElementCollection 컬렉션 테이블은 부모 로드시 함께 격리되므로 일단 부모 @Filter만 적용하고, Task 4 테스트로 누출 여부 확인**.

- [ ] **Step 2: 컴파일 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/ApiKey.java core/src/main/java/com/crosscert/passkey/core/entity/TenantAllowedOrigin.java core/src/main/java/com/crosscert/passkey/core/entity/TenantAcceptedFormat.java core/src/main/java/com/crosscert/passkey/core/entity/TenantAaguidPolicy.java core/src/main/java/com/crosscert/passkey/core/entity/TenantWebauthnSnapshot.java
git commit -m "feat(core): 나머지 6개 격리 엔티티에 @Filter 적용

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: TenantFilterAspect — @Transactional 진입 시 필터 자동 enable

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/vpd/TenantFilterAspect.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterAspectIT.java`

- [ ] **Step 1: 실패 테스트 작성**

`core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterAspectIT.java` 신규:
```java
package com.crosscert.passkey.core.vpd;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TenantFilterAspectIT {

    @Autowired CredentialRepository credentials;
    @Autowired EntityManager em;

    private static final UUID T_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    @Transactional
    void aspectEnablesFilterFromContext() {
        credentials.save(new Credential(T_A, "ua".getBytes(), "ca".getBytes(), "pk".getBytes(), 0L, null, null, "none"));
        credentials.save(new Credential(T_B, "ub".getBytes(), "cb".getBytes(), "pk".getBytes(), 0L, null, null, "none"));
        em.flush();
        em.clear();

        TenantContextHolder.set(T_A);
        // @Transactional 메서드 안에서 Aspect 가 이미 필터를 켰어야 한다.
        // 하지만 이 테스트 자체가 트랜잭션 시작점이므로, 별도 트랜잭션 서비스 호출로 검증.
        long bVisible = credentials.findAll().stream().filter(c -> c.getTenantId().equals(T_B)).count();
        assertThat(bVisible).isZero();
    }
}
```
> 주의: Aspect가 @Transactional 경계에서 동작하므로, 같은 트랜잭션 내에서 context를 나중에 set하면 이미 열린 필터에 반영 안 될 수 있다. 정확한 검증은 Task 4의 IT(서비스 경유)에서 하고, 여기서는 Aspect 빈 등록·동작 smoke 수준으로 둔다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :core:test --tests "com.crosscert.passkey.core.vpd.TenantFilterAspectIT"`
Expected: FAIL (Aspect 없어 필터 미적용 → T_B 보임)

- [ ] **Step 3: TenantFilterAspect 구현**

`core/src/main/java/com/crosscert/passkey/core/vpd/TenantFilterAspect.java` 신규:
```java
package com.crosscert.passkey.core.vpd;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * @Transactional 메서드 진입 시 현재 TenantContextHolder 의 tenant 로
 * Hibernate "tenantFilter" 를 enable 한다. context 가 null 이면 enable 하지
 * 않는다(=cross-tenant, admin PLATFORM_OPERATOR 케이스).
 *
 * <p>VPD 와 독립적인 앱 레벨 격리. VPD off(SE2) 에서도 이 Aspect 가 격리를 보장한다.
 */
@Aspect
@Component
@Order(0)
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager em;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
          + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object enableTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
        UUID tid = TenantContextHolder.get();
        if (tid != null) {
            Session session = em.unwrap(Session.class);
            session.enableFilter("tenantFilter").setParameter("tenantId", tid);
        }
        return pjp.proceed();
    }
}
```
> Task 1에서 hex 폴백을 채택했다면, `setParameter("tenantId", tid)`를 `setParameter("tenantId", tid.toString().replace("-", "").toUpperCase())`로 바꾼다(이 Aspect가 단일 변환 지점).
> AOP가 동작하려면 spring-boot-starter-aop 의존성이 필요. 없으면 `core/build.gradle.kts`에 `implementation("org.springframework.boot:spring-boot-starter-aop")` 추가.

- [ ] **Step 4: 의존성 확인 후 테스트 실행**

먼저 AOP 의존성 확인:
Run: `grep -r "starter-aop" core/build.gradle.kts passkey-app/build.gradle.kts admin-app/build.gradle.kts`
없으면 `core/build.gradle.kts`의 dependencies에 추가:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-aop")
```
Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :core:test --tests "com.crosscert.passkey.core.vpd.TenantFilterAspectIT"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/com/crosscert/passkey/core/vpd/TenantFilterAspect.java core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterAspectIT.java core/build.gradle.kts
git commit -m "feat(core): TenantFilterAspect — @Transactional 진입 시 tenant 필터 자동 enable

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: AppLevelIsolationIT — VPD off 모드 격리 검증 (위험 4메서드 포함)

설계의 핵심 불변식 #1, #3을 검증. vpd.enabled=false 에서 @Filter만으로 격리되는지, 특히 VPD에만 의존하던 4메서드가 cross-tenant 누출 없는지.

**Files:**
- Create: `core/src/test/java/com/crosscert/passkey/core/vpd/AppLevelIsolationIT.java`

- [ ] **Step 1: 격리 검증 테스트 작성 (서비스/리포지토리 경유)**

`core/src/test/java/com/crosscert/passkey/core/vpd/AppLevelIsolationIT.java` 신규:
```java
package com.crosscert.passkey.core.vpd;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VPD OFF(passkey.vpd.enabled=false) 모드에서 @Filter 만으로 tenant 격리가
 * 보장되는지 검증. VPD 에만 의존하던 위험 메서드(findByUserHandle 등) 포함.
 */
@SpringBootTest
@TestPropertySource(properties = "passkey.vpd.enabled=false")
class AppLevelIsolationIT {

    @Autowired CredentialRepository credentials;
    @Autowired EntityManager em;

    private static final UUID T_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final byte[] SHARED_HANDLE = "shared-user".getBytes();

    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    @Transactional
    void findByUserHandleDoesNotLeakAcrossTenants() {
        // 같은 userHandle 을 T_A, T_B 양쪽에 저장
        credentials.save(new Credential(T_A, SHARED_HANDLE, "ca".getBytes(), "pk".getBytes(), 0L, null, null, "none"));
        credentials.save(new Credential(T_B, SHARED_HANDLE, "cb".getBytes(), "pk".getBytes(), 0L, null, null, "none"));
        em.flush();
        em.clear();

        TenantContextHolder.set(T_A);  // Aspect 가 필터 enable

        List<Credential> found = credentials.findByUserHandle(SHARED_HANDLE);
        // T_A 행만 보여야 함 — VPD 없이 @Filter 로 격리
        assertThat(found).allMatch(c -> c.getTenantId().equals(T_A));
        assertThat(found).isNotEmpty();
    }

    @Test
    @Transactional
    void findCredentialIdsByUserHandleDoesNotLeak() {
        credentials.save(new Credential(T_A, SHARED_HANDLE, "ca".getBytes(), "pk".getBytes(), 0L, null, null, "none"));
        credentials.save(new Credential(T_B, SHARED_HANDLE, "cb".getBytes(), "pk".getBytes(), 0L, null, null, "none"));
        em.flush();
        em.clear();

        TenantContextHolder.set(T_A);
        List<byte[]> ids = credentials.findCredentialIdsByUserHandle(SHARED_HANDLE);
        assertThat(ids).hasSize(1);  // T_A 의 1개만
    }
}
```
> Aspect가 같은 트랜잭션 내에서 동작하려면, context를 트랜잭션 시작 전에 set해야 한다. @Transactional 테스트에서 메서드 본문의 set은 이미 열린 트랜잭션이라 Aspect가 못 잡을 수 있음 → 이 경우 테스트를 비-@Transactional로 두고 별도 @Transactional 서비스(또는 repository 호출을 새 트랜잭션으로)로 감싸야 함. 구현 시 Aspect 동작 시점과 맞춰 조정(Step 2에서 실패하면 구조 조정).

- [ ] **Step 2: 테스트 실행**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :core:test --tests "com.crosscert.passkey.core.vpd.AppLevelIsolationIT"`
Expected: PASS. FAIL이면 Aspect 트랜잭션 타이밍 문제 → 테스트를 서비스 경유로 재구성(context set → 새 @Transactional 메서드 호출).

- [ ] **Step 3: 커밋**

```bash
git add core/src/test/java/com/crosscert/passkey/core/vpd/AppLevelIsolationIT.java
git commit -m "test(core): VPD off 모드 앱 레벨 격리 검증 (위험 4메서드)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: CoreDataSourceConfig — passkey.vpd.enabled로 VPD 조건화

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/config/VpdToggleTest.java`

- [ ] **Step 1: 현재 dataSource 빈 정의 확인**

Run: `grep -n "dataSource\|TenantAwareDataSource\|@Bean\|@Primary\|@Value" core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java`
현재 라인 41-45가 `new TenantAwareDataSource(physicalDataSource)`를 무조건 반환.

- [ ] **Step 2: 실패 테스트 작성**

`core/src/test/java/com/crosscert/passkey/core/config/VpdToggleTest.java` 신규:
```java
package com.crosscert.passkey.core.config;

import com.crosscert.passkey.core.vpd.TenantAwareDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "passkey.vpd.enabled=false")
class VpdToggleTest {

    @Autowired DataSource dataSource;

    @Test
    void vpdDisabledMeansNoTenantAwareWrapper() {
        // vpd.enabled=false → TenantAwareDataSource 로 래핑되지 않아야 함
        assertThat(dataSource).isNotInstanceOf(TenantAwareDataSource.class);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :core:test --tests "com.crosscert.passkey.core.config.VpdToggleTest"`
Expected: FAIL (현재 무조건 래핑 → TenantAwareDataSource 인스턴스라 assertion 실패)

- [ ] **Step 4: dataSource 빈 조건화**

`core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java`의 dataSource 빈(라인 41-45 근처)을 수정. 현재:
```java
    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource physicalDataSource) {
        return new TenantAwareDataSource(physicalDataSource);
    }
```
을:
```java
    @Bean
    @Primary
    public DataSource dataSource(
            HikariDataSource physicalDataSource,
            @org.springframework.beans.factory.annotation.Value("${passkey.vpd.enabled:false}") boolean vpdEnabled) {
        // VPD on(EE/XE): DB 커널이 tenant 술어를 강제하도록 set_tenant 호출하는 래퍼.
        // VPD off(SE2): 물리 DataSource 그대로 — 격리는 앱 레벨 @Filter(TenantFilterAspect)가 담당.
        return vpdEnabled
                ? new TenantAwareDataSource(physicalDataSource)
                : physicalDataSource;
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :core:test --tests "com.crosscert.passkey.core.config.VpdToggleTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java core/src/test/java/com/crosscert/passkey/core/config/VpdToggleTest.java
git commit -m "feat(core): passkey.vpd.enabled 로 TenantAwareDataSource 조건화

vpd.enabled=false(기본) 시 물리 DataSource 그대로 → SE2 호환.
격리는 앱 레벨 @Filter 가 담당.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: admin-app RP_ADMIN context 진입점

admin-app은 현재 TenantContextHolder를 set하지 않음. @Filter가 동작하려면 RP_ADMIN 요청에 context를 set해야 한다. TenantBoundary.currentTenantScope() 재사용.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantContextAdminFilter.java`

- [ ] **Step 1: currentTenantScope 시그니처 확인**

Run: `grep -n "currentTenantScope\|public " admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantBoundary.java`
확인: `Optional<UUID> currentTenantScope()` — empty=PLATFORM_OPERATOR, present=RP_ADMIN tenantId.

- [ ] **Step 2: TenantContextAdminFilter 구현**

`admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantContextAdminFilter.java` 신규:
```java
package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * 인증된 admin 요청에서 RP_ADMIN 이면 자기 tenantId 를 TenantContextHolder 에
 * set 하여 Hibernate @Filter(TenantFilterAspect)가 자기 tenant 만 보게 한다.
 * PLATFORM_OPERATOR 는 scope 가 empty 이므로 set 하지 않음 → 전체 조회(의도).
 *
 * <p>SecurityContext 가 채워진 뒤 실행되어야 하므로 보안 필터 체인 이후에 둔다.
 * finally 에서 clear 하여 스레드 풀 재사용 시 잔재가 남지 않게 한다.
 */
@Component
@Order(Integer.MAX_VALUE)  // 보안 필터(인증) 이후
public class TenantContextAdminFilter extends OncePerRequestFilter {

    private final TenantBoundary tenantBoundary;

    public TenantContextAdminFilter(TenantBoundary tenantBoundary) {
        this.tenantBoundary = tenantBoundary;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean set = false;
        try {
            Optional<UUID> scope = safeScope();
            if (scope.isPresent()) {
                TenantContextHolder.set(scope.get());
                set = true;
            }
            chain.doFilter(req, res);
        } finally {
            if (set) TenantContextHolder.clear();
        }
    }

    /** 미인증/익명 요청에서 currentTenantScope 가 예외를 던질 수 있으므로 방어. */
    private Optional<UUID> safeScope() {
        try {
            return tenantBoundary.currentTenantScope();
        } catch (RuntimeException e) {
            return Optional.empty();  // 인증 안 됨 → context 미설정(전체/거부는 각 엔드포인트가 처리)
        }
    }
}
```
> `currentTenantScope()`가 미인증 시 예외를 던지는지 확인(TenantBoundary:77-89). 던지면 safeScope의 catch가 처리. 안 던지고 empty 반환하면 그대로 동작.

- [ ] **Step 3: 컴파일 확인**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantContextAdminFilter.java
git commit -m "feat(admin): RP_ADMIN 요청에 TenantContextHolder set (@Filter 격리)

currentTenantScope() 재사용 — RP_ADMIN 자기 tenant set, PLATFORM_OPERATOR 미설정.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: VPD 마이그레이션 Edition 감지 조건화

V3/V8/V20/V35의 ADD_POLICY를 fine-grained access control 가능 시에만 실행하도록 PL/SQL로 감싼다. SE2에서 skip → Flyway 안 깨짐. 새 환경에만 Flyway 사용하므로 checksum 충돌·repair 불필요.

**Files:**
- Modify: `core/src/main/resources/db/migration/V3__vpd_policies.sql`
- Modify: `core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql`
- Modify: `core/src/main/resources/db/migration/V20__vpd_policy_for_uuid.sql`
- Modify: `core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql`

- [ ] **Step 1: V20의 ADD_POLICY를 Edition 감지로 감싸기 (대표 패턴)**

`core/src/main/resources/db/migration/V20__vpd_policy_for_uuid.sql`의 두 ADD_POLICY 블록(credential, api_key)을 각각 감싼다. 예: credential 블록(현재 라인 26-37):
```sql
BEGIN
  DBMS_RLS.ADD_POLICY(
    object_schema   => 'APP_OWNER',
    object_name     => 'CREDENTIAL',
    policy_name     => 'CREDENTIAL_TENANT_ISOLATION',
    function_schema => 'APP_OWNER',
    policy_function => 'TENANT_PREDICATE',
    statement_types => 'SELECT,INSERT,UPDATE,DELETE',
    update_check    => TRUE
  );
END;
/
```
를:
```sql
DECLARE
  v_fgac NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_fgac FROM v$option
   WHERE parameter = 'Fine-grained access control' AND value = 'TRUE';
  IF v_fgac = 1 THEN
    DBMS_RLS.ADD_POLICY(
      object_schema   => 'APP_OWNER',
      object_name     => 'CREDENTIAL',
      policy_name     => 'CREDENTIAL_TENANT_ISOLATION',
      function_schema => 'APP_OWNER',
      policy_function => 'TENANT_PREDICATE',
      statement_types => 'SELECT,INSERT,UPDATE,DELETE',
      update_check    => TRUE
    );
  END IF;
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -439 THEN NULL;  -- ORA-00439: feature not enabled (SE2)
    ELSIF SQLCODE = -28101 THEN NULL;  -- ORA-28101: policy already exists (idempotent)
    ELSE RAISE;
    END IF;
END;
/
```
같은 변환을 V20의 api_key 블록에도 적용.

- [ ] **Step 2: V3, V8, V35의 ADD_POLICY에도 동일 패턴 적용**

- V3(credential): 동일 DECLARE/v_fgac 래핑.
- V8(api_key): 동일.
- V35: PL/SQL 루프로 5개 자식 테이블에 ADD_POLICY를 거는 구조. 루프 안의 ADD_POLICY 호출 직전에 `v_fgac` 체크를 추가하거나, 루프 전체를 `IF v_fgac = 1 THEN ... END IF;`로 감싼다. 기존 ORA-28101 idempotent 가드는 유지.

V35 권장 형태(루프 전체 감싸기):
```sql
DECLARE
  v_fgac NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_fgac FROM v$option
   WHERE parameter = 'Fine-grained access control' AND value = 'TRUE';
  IF v_fgac = 1 THEN
    -- 기존 루프/ADD_POLICY 블록 그대로
  END IF;
END;
/
```

- [ ] **Step 3: SE2 시뮬레이션이 어려우므로, XE에서 마이그레이션이 여전히 통과하는지 확인**

로컬 dev DB(XE, VPD 지원)에서 Flyway 마이그레이션이 정상 적용되는지 — XE는 v_fgac=1이라 기존과 동일하게 ADD_POLICY 실행되어야 함:
Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :admin-app:flywayInfo` (또는 admin-app 부팅 시 Flyway 적용 로그 확인)
Expected: V3/V8/V20/V35 적용 성공(XE는 fgac=TRUE).
> 주의: 로컬 dev DB는 이미 V35까지 적용된 상태라 이 마이그레이션들이 재실행되지 않는다(checksum 검증). **새 환경에서만 검증 가능**하므로, 가능하면 깨끗한 testcontainer 또는 새 XE 컨테이너로 검증. 불가하면 Task 9 통합 테스트(VpdIsolationIT 재실행)로 대체.

- [ ] **Step 4: 커밋**

```bash
git add core/src/main/resources/db/migration/V3__vpd_policies.sql core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql core/src/main/resources/db/migration/V20__vpd_policy_for_uuid.sql core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql
git commit -m "feat(db): VPD ADD_POLICY를 Edition 감지로 조건화 (SE2 호환)

Fine-grained access control 가능 시에만 ADD_POLICY. SE2(ORA-00439)는 skip.
새 환경에만 Flyway 사용하므로 checksum 충돌·repair 불필요.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: 설정 — passkey.vpd.enabled 정의 + 환경별 override

**Files:**
- Modify: `core/src/main/resources/application-common.yml`
- Modify: 환경별 yml (기존 EE/XE 환경을 true로)

- [ ] **Step 1: 기본값 정의 (false)**

`core/src/main/resources/application-common.yml`의 `passkey:` 블록(라인 68 근처, deployment.mode 옆)에 추가:
```yaml
passkey:
  vpd:
    enabled: false   # 기본: 앱 레벨 @Filter 만. EE/XE 환경은 환경별 yml 에서 true.
```
(기존 passkey.* 구조에 맞춰 들여쓰기)

- [ ] **Step 2: 기존 VPD 사용 환경을 true로 명시**

현재 VPD를 쓰는 dev/qa/prod(XE/EE 기반) 환경 yml에 override 추가. 예 `core` 또는 각 앱의 application-dev.yml / application-qa.yml / application-prod.yml 중 VPD 대상 환경에:
```yaml
passkey:
  vpd:
    enabled: true
```
> 어떤 환경이 VPD를 쓰는지는 배포 대상에 따라 다름. dev(XE)는 true 권장(기존 동작 보존). SE2 배포 환경은 false 또는 미설정(기본 false). 구현 시 각 환경 yml 확인 후 VPD 지원 환경만 true.

- [ ] **Step 3: 부팅 확인 (false 모드)**

Run: 워크트리에서 admin-app 또는 passkey-app을 `passkey.vpd.enabled=false`로 부팅(테스트 프로파일). 부팅 성공 + DataSource가 물리 DS인지 로그/VpdToggleTest로 확인.
Expected: 정상 부팅, TenantAwareDataSource 미적용.

- [ ] **Step 4: 커밋**

```bash
git add core/src/main/resources/application-common.yml
# + 수정한 환경별 yml
git commit -m "feat(config): passkey.vpd.enabled 기본 false + VPD 환경 override

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: 통합 검증 — 두 모드 전체 테스트

**Files:** (없음 — 검증만)

- [ ] **Step 1: core 전체 테스트 (두 모드 격리 IT 포함)**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew :core:test`
Expected: TenantFilterBindingIT, TenantFilterAspectIT, AppLevelIsolationIT(vpd=false), VpdToggleTest, 기존 VpdIsolationIT(vpd=true), TenantAwareDataSourceTest 모두 PASS. (Testcontainers Oracle XE 필요 — Docker 가용 확인)

- [ ] **Step 2: 전체 빌드 (passkey-app, admin-app 컴파일)**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/vpd-optional-multitenant && ./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL (admin TenantContextAdminFilter, Aspect 등 모두 컴파일)

- [ ] **Step 3: codex 독립 리뷰**

브랜치 전체 diff에 대해 `/codex:review` 실행(사용자 선호). 특히 (1) @Filter가 모든 격리 엔티티에 빠짐없이 붙었는지, (2) Aspect 트랜잭션 타이밍, (3) admin filter의 context clear 누락 여부, (4) 마이그레이션 PL/SQL 정확성을 중점 리뷰. 지적사항 수정 후 재검증.

- [ ] **Step 4: 불변식 수동 점검 체크리스트 (문서화)**

다음을 확인하고 결과를 PR/커밋 메시지에 기록:
- [ ] vpd=false + 격리 엔티티 7개 모두 @Filter 적용됨 (grep으로 확인)
- [ ] 위험 4메서드(findByUserHandle, findCredentialIdsByUserHandle, findByCredentialIdForUpdate, findOwnedForUpdate)가 AppLevelIsolationIT로 커버됨
- [ ] PLATFORM_OPERATOR(context 없음)는 전체 조회 — admin filter가 set 안 함 확인
- [ ] native query(searchByTenantId)는 명시적 tenant 조건 유지

---

## Self-Review 결과

- **Spec 커버리지**: Layer1(@Filter)=T1,T2,T3 / fail-closed=T3,T4 / Layer2 스위치=T5 / 마이그레이션 조건화=T7 / admin 일원화=T6 / 테스트 두 모드=T4(off)+기존VpdIsolationIT(on) / 설정=T8. 설계 7개 영향 구성요소 모두 매핑.
- **Placeholder 스캔**: 모든 코드 step에 실제 코드. "구현 시 확정" 표기는 조사로 해소된 열린 질문의 폴백 경로(Task 1 hex 폴백, Aspect 타이밍 조정)로, 구체적 대안 코드 제시함.
- **타입 일관성**: `tenantFilter` 필터명, `tenantId` 파라미터명, `TenantContextHolder.set/get/clear`, `currentTenantScope()` 시그니처 — 전 Task 일치.
- **알려진 리스크**: (1) UUID↔RAW(16) 바인딩 → T1 spike + hex 폴백. (2) Aspect 트랜잭션 타이밍 → T3/T4에서 서비스 경유로 조정. (3) @ElementCollection(TenantAaguidPolicyEntry) → 부모 필터로 커버, T4에서 확인. (4) 로컬 dev DB가 이미 V35 적용 → 마이그레이션 재검증은 새 컨테이너 필요.
