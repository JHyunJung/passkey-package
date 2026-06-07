# 개요 탭 등록/인증 성공률 노출 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** passkey-app이 등록/인증 ceremony의 begin/finish 이벤트를 경량 `ceremony_event` 테이블에 best-effort 기록하고, admin FunnelService가 이를 집계해 개요 탭에 실제 성공률이 노출되게 한다.

**Architecture:** hash chain이 없는 단순 카운트 테이블 `ceremony_event`를 신설한다(고빈도 ceremony가 audit_log의 전역 락 병목을 겪지 않도록). core에 엔티티/리포지토리/Recorder를 두고, passkey-app의 4개 ceremony 서비스가 Recorder를 호출한다. admin FunnelService는 데이터 소스를 audit_log → ceremony_event로 교체한다.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data JPA, Oracle XE 21(Flyway), JUnit5 + Testcontainers, React/TS(admin-ui).

작업 위치: 워크트리 `.worktrees/overview-funnel-success-rate`, 브랜치 `feat/overview-funnel-success-rate`. 모든 명령/커밋은 이 워크트리에서 실행한다.

---

## 파일 구조

생성/수정 파일과 책임:

- **Create** `core/src/main/resources/db/migration/V41__ceremony_event.sql` — 테이블 + 인덱스 + GRANT(idempotent)
- **Create** `core/src/main/java/com/crosscert/passkey/core/entity/CeremonyEvent.java` — 엔티티 (BaseEntity 상속, tenantId/action/createdAt)
- **Create** `core/src/main/java/com/crosscert/passkey/core/repository/CeremonyEventRepository.java` — 3개 집계 쿼리
- **Create** `core/src/main/java/com/crosscert/passkey/core/ceremony/CeremonyAction.java` — action 문자열 상수 단일 출처
- **Create** `core/src/main/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorder.java` — best-effort 기록 컴포넌트
- **Create** `core/src/test/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorderTest.java` — best-effort 단위 테스트
- **Modify** `passkey-app/.../registration/RegistrationStartService.java` — REGISTRATION_BEGIN 기록
- **Modify** `passkey-app/.../registration/RegistrationFinishService.java` — REGISTRATION_FINISH_OK 기록
- **Modify** `passkey-app/.../authentication/AuthenticationStartService.java` — AUTHENTICATION_BEGIN 기록
- **Modify** `passkey-app/.../authentication/AuthenticationFinishService.java` — AUTHENTICATION_FINISH_OK 기록
- **Modify** `admin-app/.../funnel/FunnelService.java` — 데이터 소스를 CeremonyEventRepository로 교체
- **Modify** `admin-app/src/test/java/com/crosscert/passkey/admin/funnel/FunnelIT.java` — 실제 카운트 검증으로 강화
- **Create** `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/CeremonyEventRecordingIT.java` — ceremony → ceremony_event 적재 검증
- **Modify** `admin-ui/src/pages/tenant/TenantOverview.tsx` — 폴백 문구 정정

---

## Task 1: Flyway 마이그레이션 — ceremony_event 테이블

**Files:**
- Create: `core/src/main/resources/db/migration/V41__ceremony_event.sql`

- [ ] **Step 1: 마이그레이션 작성**

`V41__ceremony_event.sql` 전체 내용:

```sql
-- ============================================================
-- V41 — ceremony_event: 등록/인증 ceremony 집계용 경량 이벤트 테이블
--
-- 목적: 개요/Funnel 화면의 등록·인증 성공률은 ceremony begin/finish 카운트가
-- 필요하다. audit_log 는 전역 hash-chain 락(AUDIT_CHAIN_LOCK)으로 모든 append 를
-- 직렬화하므로 고빈도 ceremony 기록에 부적합하다. hash chain 없는 단순 카운트
-- 테이블을 따로 둔다.
--
-- 기록자: passkey-app (APP_RUNTIME) — INSERT.  집계자: admin-app (APP_ADMIN) — SELECT.
-- 테이블 소유자는 APP_OWNER(Flyway 실행 스키마)이며 양 런타임 유저에 GRANT.
--
-- Idempotency: 객체 생성은 ORA-00955(name already used)/ORA-00942 를 swallow.
-- 패턴: V24/V25/V38(EXCEPTION 가드), V13/V28(런타임 GRANT) 와 동일.
-- ============================================================

-- 테이블 — ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE ceremony_event ('
    || 'id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY, '
    || 'tenant_id RAW(16) NOT NULL, '
    || 'action VARCHAR2(32) NOT NULL, '
    || 'created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, '
    || 'updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- table already exists
    ELSE RAISE;
    END IF;
END;
/

-- 인덱스 (tenant_id, action, created_at) — FunnelService 카운트/일별집계 커버. ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_ceremony_event_tenant_action_time '
    || 'ON ceremony_event (tenant_id, action, created_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/

-- 런타임 GRANT (각 GRANT 를 개별 블록으로 — 멱등·부분 적용 안전)
BEGIN EXECUTE IMMEDIATE 'GRANT INSERT ON ceremony_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT ON ceremony_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT, INSERT ON ceremony_event TO APP_ADMIN';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
```

> 참고: `updated_at`은 BaseEntity 정책(모든 엔티티 createdAt/updatedAt NOT NULL)을 만족시키기 위해 포함한다. ceremony_event는 append-only지만 BaseEntity의 `@PrePersist`가 둘 다 채운다. GRANT의 `-1917`은 ORA-01917(user/role does not exist) — dev 단일 유저 환경에서 APP_RUNTIME/APP_ADMIN 롤이 없을 때를 swallow(기존 V28 등과 동일 방어).

- [ ] **Step 2: 마이그레이션 SQL 문법 자체 검증(빌드로 확인)**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL (SQL은 런타임에 검증되지만, 이 단계는 core가 깨지지 않았는지 확인). 실제 마이그레이션 적용 검증은 Task 8 IT에서 한다.

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add core/src/main/resources/db/migration/V41__ceremony_event.sql
git commit -m "feat(core): V41 ceremony_event 집계 테이블 추가"
```

---

## Task 2: action 상수 단일 출처

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/ceremony/CeremonyAction.java`

- [ ] **Step 1: 상수 클래스 작성**

`CeremonyAction.java` 전체:

```java
package com.crosscert.passkey.core.ceremony;

/**
 * ceremony_event.action 의 문자열 단일 출처.
 * CeremonyEventRecorder(기록)와 admin FunnelService(집계)가 같은 값을 참조해
 * 문자열 불일치로 인한 집계 누락을 막는다. 값은 기존 FunnelService 상수와 동일.
 */
public final class CeremonyAction {
    public static final String REGISTRATION_BEGIN     = "REGISTRATION_BEGIN";
    public static final String REGISTRATION_SUCCESS   = "REGISTRATION_FINISH_OK";
    public static final String AUTHENTICATION_BEGIN   = "AUTHENTICATION_BEGIN";
    public static final String AUTHENTICATION_SUCCESS = "AUTHENTICATION_FINISH_OK";

    private CeremonyAction() {}
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add core/src/main/java/com/crosscert/passkey/core/ceremony/CeremonyAction.java
git commit -m "feat(core): CeremonyAction 상수 단일 출처 추가"
```

---

## Task 3: CeremonyEvent 엔티티

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/CeremonyEvent.java`

- [ ] **Step 1: 엔티티 작성**

`CeremonyEvent.java` 전체 (BaseEntity의 id/createdAt/updatedAt 상속, tenantId는 AuditLog와 동일하게 `@JdbcTypeCode(SqlTypes.UUID)` + `RAW(16)`):

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 등록/인증 ceremony 집계용 경량 이벤트. hash chain 없음(단순 카운트 지표).
 * FunnelService 가 (tenant_id, action, created_at) 으로 집계한다.
 */
@Entity
@Table(name = "CEREMONY_EVENT")
public class CeremonyEvent extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID tenantId;

    @Column(name = "ACTION", length = 32, nullable = false)
    private String action;

    protected CeremonyEvent() {}

    public CeremonyEvent(UUID tenantId, String action) {
        this.tenantId = tenantId;
        this.action = action;
    }

    public UUID getTenantId() { return tenantId; }
    public String getAction() { return action; }
}
```

> createdAt/updatedAt은 BaseEntity의 `@PrePersist`가 `Instant.now()`로 채운다(AuditLog처럼 caller-supplied 시각이 필요 없으므로 reflection seed 불필요).

- [ ] **Step 2: 컴파일 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add core/src/main/java/com/crosscert/passkey/core/entity/CeremonyEvent.java
git commit -m "feat(core): CeremonyEvent 엔티티 추가"
```

---

## Task 4: CeremonyEventRepository (집계 쿼리)

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/CeremonyEventRepository.java`

- [ ] **Step 1: 리포지토리 작성**

기존 `AuditLogRepository`의 funnel 쿼리 3개를 ceremony_event 대상으로 옮긴다. `{h-schema}`와 native query 패턴은 AuditLogRepository와 동일.

`CeremonyEventRepository.java` 전체:

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CeremonyEventRepository extends JpaRepository<CeremonyEvent, UUID> {

    /** stage attempt/success 카운터. */
    long countByTenantIdAndActionAndCreatedAtAfter(UUID tenantId, String action, Instant since);

    /** 일별 series. [day TIMESTAMP, action VARCHAR, count NUMBER]. */
    @Query(value = "SELECT TRUNC(created_at), action, COUNT(*) "
                 + "FROM {h-schema}ceremony_event "
                 + "WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
                 + "GROUP BY TRUNC(created_at), action",
           nativeQuery = true)
    List<Object[]> aggregateDailyByTenantAndActions(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);

    /** action 별 카운트. [action VARCHAR, count NUMBER]. */
    @Query(value = "SELECT action, COUNT(*) "
                 + "FROM {h-schema}ceremony_event "
                 + "WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
                 + "GROUP BY action",
           nativeQuery = true)
    List<Object[]> aggregateByTenantAndActionsGrouped(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add core/src/main/java/com/crosscert/passkey/core/repository/CeremonyEventRepository.java
git commit -m "feat(core): CeremonyEventRepository 집계 쿼리 추가"
```

---

## Task 5: CeremonyEventRecorder (best-effort 기록) — TDD

**Files:**
- Create: `core/src/test/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorderTest.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorder.java`

- [ ] **Step 1: 실패 테스트 작성**

Recorder의 핵심 계약: (1) 정상 시 repo.save 호출, (2) repo.save가 예외를 던져도 호출자에게 전파하지 않음(best-effort). Mockito로 검증.

`CeremonyEventRecorderTest.java` 전체:

```java
package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CeremonyEventRecorderTest {

    private final CeremonyEventRepository repo = mock(CeremonyEventRepository.class);
    private final CeremonyEventRecorder recorder = new CeremonyEventRecorder(repo);

    @Test
    void record_persistsEvent() {
        UUID tenant = UUID.randomUUID();
        recorder.record(tenant, CeremonyAction.REGISTRATION_BEGIN);
        verify(repo, times(1)).save(any(CeremonyEvent.class));
    }

    @Test
    void record_swallowsRepositoryException() {
        UUID tenant = UUID.randomUUID();
        when(repo.save(any(CeremonyEvent.class)))
                .thenThrow(new RuntimeException("DB down"));
        // best-effort: 예외가 호출자로 전파되면 ceremony 가 깨진다 → 전파되면 안 됨
        assertThatCode(() -> recorder.record(tenant, CeremonyAction.REGISTRATION_BEGIN))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :core:test --tests "com.crosscert.passkey.core.ceremony.CeremonyEventRecorderTest"`
Expected: FAIL — `CeremonyEventRecorder` 클래스가 없어 컴파일 에러.

- [ ] **Step 3: Recorder 구현**

`CeremonyEventRecorder.java` 전체:

```java
package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ceremony 집계 이벤트를 best-effort 로 기록한다. 기록 실패가 등록/인증 ceremony
 * 자체를 깨면 안 되므로 예외를 삼킨다(로그만). REQUIRES_NEW 로 별도 트랜잭션을 써,
 * 호출 측 트랜잭션이 readOnly 이거나 롤백돼도 집계 이벤트 기록이 독립적으로 커밋된다.
 */
@Component
public class CeremonyEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(CeremonyEventRecorder.class);

    private final CeremonyEventRepository repo;

    public CeremonyEventRecorder(CeremonyEventRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, String action) {
        try {
            repo.save(new CeremonyEvent(tenantId, action));
        } catch (Exception e) {
            log.warn("ceremony_event 기록 실패 (무시): tenant={} action={}", tenantId, action, e);
        }
    }
}
```

> `REQUIRES_NEW` 이유: Start 서비스들이 `@Transactional(readOnly = true)`라 같은 트랜잭션에서 INSERT 하면 읽기전용 위반이 날 수 있고, finish 실패로 롤백될 때 begin 집계까지 사라지면 안 된다. 별도 트랜잭션으로 분리한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :core:test --tests "com.crosscert.passkey.core.ceremony.CeremonyEventRecorderTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add core/src/main/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorder.java \
        core/src/test/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorderTest.java
git commit -m "feat(core): CeremonyEventRecorder best-effort 기록 + 단위 테스트"
```

---

## Task 6: passkey-app 4개 서비스에 기록 추가

각 서비스에 `CeremonyEventRecorder`를 주입하고 성공 지점에서 `record(...)`를 호출한다. begin은 start 성공 직후, finish_ok는 finish 성공 직후(각 서비스의 `ceremonyMetrics.recordSuccess(...)` 직전).

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`

- [ ] **Step 1: RegistrationStartService — 생성자 주입 + 기록**

import 추가 (기존 import 블록 하단):
```java
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.ceremony.CeremonyEventRecorder;
```

필드 추가 (`private final CeremonyMetrics ceremonyMetrics;` 다음 줄):
```java
    private final CeremonyEventRecorder ceremonyEvents;
```

생성자 파라미터 추가 (`CeremonyMetrics ceremonyMetrics` 파라미터 뒤에 추가) 및 본문 대입.
생성자 시그니처를 다음으로 교체:
```java
    public RegistrationStartService(TenantRepository tenants,
                                    CredentialRepository credentials,
                                    ChallengeIssuer challenges,
                                    ChallengeStore store,
                                    ObjectMapper mapper,
                                    Clock clock,
                                    CeremonyMetrics ceremonyMetrics,
                                    CeremonyEventRecorder ceremonyEvents) {
        this.tenants = tenants;
        this.credentials = credentials;
        this.challenges = challenges;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        this.ceremonyMetrics = ceremonyMetrics;
        this.ceremonyEvents = ceremonyEvents;
    }
```

기록 호출: `start(...)` 안의 `ceremonyMetrics.recordSuccess("registration", "start");` 바로 위 줄에 추가:
```java
            ceremonyEvents.record(tenantUuid, CeremonyAction.REGISTRATION_BEGIN);
```
(`tenantUuid`는 메서드 상단에서 이미 `TenantContextHolder.get()`로 얻어 둔 변수)

- [ ] **Step 2: AuthenticationStartService — 동일 패턴**

import 2개 추가(위와 동일). 필드 `private final CeremonyEventRecorder ceremonyEvents;` 추가.
생성자에 `CeremonyEventRecorder ceremonyEvents` 파라미터 추가 + `this.ceremonyEvents = ceremonyEvents;` 대입(RegistrationStartService와 동일 형태).
`ceremonyMetrics.recordSuccess("authentication", "start");` 바로 위에 추가:
```java
            ceremonyEvents.record(tenantUuid, CeremonyAction.AUTHENTICATION_BEGIN);
```
(`tenantUuid`는 메서드 상단 `TenantContextHolder.get()` 변수)

- [ ] **Step 3: RegistrationFinishService — 동일 패턴**

import 2개 추가. 필드 추가. 생성자에 `CeremonyEventRecorder ceremonyEvents` 파라미터 추가 + 대입.
`ceremonyMetrics.recordSuccess("registration", "finish");` 바로 위에 추가:
```java
            ceremonyEvents.record(UUID.fromString(ch.tenantId()), CeremonyAction.REGISTRATION_SUCCESS);
```
(`ch.tenantId()`는 finish 본문에서 쓰는 challenge의 tenantId 문자열 — 이미 사용 중. `UUID`는 이미 import됨)

- [ ] **Step 4: AuthenticationFinishService — 동일 패턴**

import 2개 추가. 필드 추가. 생성자에 파라미터 추가 + 대입.
`ceremonyMetrics.recordSuccess("authentication", "finish");` 바로 위에 추가:
```java
            ceremonyEvents.record(UUID.fromString(ch.tenantId()), CeremonyAction.AUTHENTICATION_SUCCESS);
```

> `AuthenticationChallenge.tenantId()`는 `String`이며, 이 서비스는 이미 `java.util.UUID`를 import하고 `UUID.fromString(ch.tenantId())` 패턴을 쓰고 있다(예: `tenants.findById(UUID.fromString(ch.tenantId()))`). 추가 import 불필요.

- [ ] **Step 5: passkey-app 컴파일 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :passkey-app:compileJava -q`
Expected: BUILD SUCCESSFUL. 실패 시 생성자 주입을 쓰는 기존 테스트(@WebMvcTest/단위)가 새 파라미터로 깨질 수 있으니 다음 Step에서 확인.

- [ ] **Step 6: passkey-app 단위/슬라이스 테스트 영향 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :passkey-app:test -q`
Expected: 기존 테스트가 4개 서비스를 직접 `new ...Service(...)`로 생성하면 새 파라미터 누락으로 컴파일 실패할 수 있다. 실패 시 해당 테스트의 생성자 호출에 `mock(CeremonyEventRecorder.class)` 인자를 추가한다(테스트별로 실제 깨지는 것만 수정). Testcontainers IT는 시간이 걸리므로 이 Step에서는 `--tests` 로 단위/슬라이스만 우선 돌려도 된다.

- [ ] **Step 7: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
git commit -m "feat(passkey-app): ceremony begin/finish 이벤트 기록"
```

---

## Task 7: FunnelService 데이터 소스 교체

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelService.java`

- [ ] **Step 1: 의존성/소스 교체**

import 추가:
```java
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
```

import 제거: `import com.crosscert.passkey.core.repository.AuditLogRepository;`

상수 4개를 CeremonyAction 참조로 교체 (필드 정의 부분):
```java
    static final String REGISTRATION_BEGIN     = CeremonyAction.REGISTRATION_BEGIN;
    static final String REGISTRATION_SUCCESS   = CeremonyAction.REGISTRATION_SUCCESS;
    static final String AUTHENTICATION_BEGIN   = CeremonyAction.AUTHENTICATION_BEGIN;
    static final String AUTHENTICATION_SUCCESS = CeremonyAction.AUTHENTICATION_SUCCESS;
```

필드/생성자에서 `AuditLogRepository repo` → `CeremonyEventRepository repo`로 교체:
```java
    private final CeremonyEventRepository repo;
    private final TenantBoundary tenantBoundary;

    public FunnelService(CeremonyEventRepository repo, TenantBoundary tenantBoundary) {
        this.repo = repo;
        this.tenantBoundary = tenantBoundary;
    }
```

> `repo`의 메서드 3개(`countByTenantIdAndActionAndCreatedAtAfter`, `aggregateDailyByTenantAndActions`, `aggregateByTenantAndActionsGrouped`)는 CeremonyEventRepository에 동일 시그니처로 존재(Task 4)하므로 `compute()` 본문은 수정 불필요.

클래스 상단 주석에서 "not yet emitted by passkey-app (out of F3 scope) — dev DB will return 0 counts; that is expected" 문단을 다음으로 교체:
```java
 * <p>Counts come from {@code ceremony_event}, populated by passkey-app's
 * registration/authentication ceremonies (begin/finish). Empty tenants return
 * 0 counts and the DTO contract yields ratio 0.
```

- [ ] **Step 2: admin-app 컴파일 확인**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :admin-app:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelService.java
git commit -m "refactor(admin): FunnelService 데이터 소스를 ceremony_event 로 교체"
```

---

## Task 8: admin-app FunnelIT — 실제 카운트 검증

기존 `FunnelIT`는 "counts expected 0, shape만 검증"이다. ceremony_event에 시드를 넣고 실제 ratio가 계산되는지 검증으로 강화한다.

**Files:**
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/funnel/FunnelIT.java`

- [ ] **Step 1: 시드 + 검증 테스트 추가**

기존 테스트 메서드는 유지하고, ceremony_event 시드 후 카운트를 검증하는 테스트를 추가한다. 시드는 JdbcTemplate(또는 EntityManager native insert)로 직접 INSERT한다. 기존 IT가 사용하는 시드/주입 방식(JdbcTemplate 또는 TestRestTemplate)을 따른다.

추가할 테스트 (FunnelIT 클래스 내부, 기존 필드/주입 활용):

```java
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void funnel_computesRatioFromCeremonyEvents() {
        // given: 특정 tenant 에 REGISTRATION_BEGIN 4건, REGISTRATION_FINISH_OK 3건 시드
        java.util.UUID tenantId = /* 기존 IT 의 시드 tenant id 재사용 */ seededTenantId();
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO ceremony_event (id, tenant_id, action, created_at, updated_at) "
                    + "VALUES (SYS_GUID(), ?, 'REGISTRATION_BEGIN', SYSTIMESTAMP, SYSTIMESTAMP)",
                    toRaw(tenantId));
        }
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO ceremony_event (id, tenant_id, action, created_at, updated_at) "
                    + "VALUES (SYS_GUID(), ?, 'REGISTRATION_FINISH_OK', SYSTIMESTAMP, SYSTIMESTAMP)",
                    toRaw(tenantId));
        }

        // when: GET funnel
        JsonNode body = getFunnel(tenantId, 7); // 기존 IT 의 호출 헬퍼 재사용/추출

        // then: registration ratio = 3/4 = 0.75
        assertThat(body.path("registration").path("attempts").asLong()).isEqualTo(4);
        assertThat(body.path("registration").path("success").asLong()).isEqualTo(3);
        assertThat(body.path("registration").path("ratio").asDouble()).isEqualTo(0.75);
    }

    private static byte[] toRaw(java.util.UUID u) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
```

> 구현 메모: `seededTenantId()`와 `getFunnel(...)`는 기존 FunnelIT가 이미 tenant를 시드하고 funnel 엔드포인트를 호출하는 코드를 헬퍼로 추출해 재사용한다. 기존 단일 테스트가 인라인으로 갖고 있으면, 그 로직을 private 헬퍼로 뽑아 두 테스트가 공유하게 리팩터한다. RP_ADMIN/PLATFORM 인증 헤더도 기존 테스트 방식을 그대로 따른다.

- [ ] **Step 2: IT 실행 (Testcontainers Oracle 기동 — 수 분 소요)**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :admin-app:test --tests "com.crosscert.passkey.admin.funnel.FunnelIT"`
Expected: PASS — V41 마이그레이션이 컨테이너에 적용되고, ceremony_event 시드 → ratio 0.75 검증 통과. (마이그레이션 SQL 오류가 있으면 여기서 컨텍스트 로드 실패로 드러난다.)

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add admin-app/src/test/java/com/crosscert/passkey/admin/funnel/FunnelIT.java
git commit -m "test(admin): FunnelIT ceremony_event 실제 카운트/ratio 검증"
```

---

## Task 9: passkey-app IT — ceremony → ceremony_event 적재 검증

**Files:**
- Create: `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/CeremonyEventRecordingIT.java`

- [ ] **Step 1: IT 작성**

기존 `Fido2EndToEndIT`의 Testcontainers 스캐폴딩(Oracle+Redis 컨테이너, ApiKey 인증 헤더, registration/authentication start·finish 호출)을 참고해, ceremony 한 사이클 수행 후 ceremony_event row를 검증한다. webauthn4j 등록/인증 더미 자격 생성 로직은 `Fido2EndToEndIT`의 헬퍼를 재사용한다.

검증 핵심:
- registration start → `ceremony_event` 에 REGISTRATION_BEGIN 1건
- registration finish (성공) → REGISTRATION_FINISH_OK 1건
- authentication start → AUTHENTICATION_BEGIN 1건
- authentication finish (성공) → AUTHENTICATION_FINISH_OK 1건

```java
package com.crosscert.passkey.app.fido2;

// import 및 컨테이너 스캐폴딩은 Fido2EndToEndIT 와 동일하게 구성한다.
// (OracleContainer + GenericContainer<redis>, @DynamicPropertySource,
//  ApiKey 발급/헤더 세팅, webauthn4j 기반 attestation/assertion 생성 헬퍼)

class CeremonyEventRecordingIT /* extends 또는 복제한 Fido2EndToEndIT 스캐폴딩 */ {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.Test
    void ceremony_recordsBeginAndFinishEvents() {
        // given: 테스트 tenant + api key (Fido2EndToEndIT 헬퍼 재사용)
        // when: registration start → finish → authentication start → finish 한 사이클 수행
        //       (Fido2EndToEndIT 의 전체 플로우 헬퍼 재사용)

        // then: 각 action 이 정확히 1건씩 적재됨
        assertActionCount("REGISTRATION_BEGIN", 1);
        assertActionCount("REGISTRATION_FINISH_OK", 1);
        assertActionCount("AUTHENTICATION_BEGIN", 1);
        assertActionCount("AUTHENTICATION_FINISH_OK", 1);
    }

    private void assertActionCount(String action, long expected) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ceremony_event WHERE action = ?", Long.class, action);
        org.assertj.core.api.Assertions.assertThat(n).isEqualTo(expected);
    }
}
```

> 구현 메모: 가장 견고한 방식은 `Fido2EndToEndIT`가 이미 가진 "정상 등록→인증 1사이클" 흐름을 그대로 한 번 돌린 뒤 ceremony_event 카운트만 추가 검증하는 것이다. 만약 `Fido2EndToEndIT`에 그 흐름이 단일 테스트로 있으면, 이 IT는 별도 클래스로 두되 스캐폴딩/헬퍼를 복제하지 말고 공유 베이스(또는 기존 IT에 검증 추가)로 구성해 중복을 피한다. **대안**: 신규 클래스 대신 `Fido2EndToEndIT`의 happy-path 테스트 끝에 `assertActionCount(...)` 4건을 추가하는 것이 더 DRY하다 — 실행자는 둘 중 중복이 적은 쪽을 택한다.

- [ ] **Step 2: IT 실행**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :passkey-app:test --tests "com.crosscert.passkey.app.fido2.CeremonyEventRecordingIT"`
Expected: PASS — 4개 action 각 1건. (REQUIRES_NEW 트랜잭션이 readOnly start 컨텍스트에서도 정상 커밋되는지 함께 검증됨.)

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add passkey-app/src/test/java/com/crosscert/passkey/app/fido2/CeremonyEventRecordingIT.java
git commit -m "test(passkey-app): ceremony begin/finish 가 ceremony_event 에 적재됨 검증"
```

---

## Task 10: 개요 탭 폴백 문구 정정

**Files:**
- Modify: `admin-ui/src/pages/tenant/TenantOverview.tsx:225-226`

- [ ] **Step 1: 폴백 문구 수정**

attempts=0일 때의 sub 문구 "Phase E3 연결 예정"을 정확한 표현으로 바꾼다. 225~226행의 두 MetricCard:

기존:
```tsx
        <MetricCard label="등록 성공률 (7d)" value={f.registration.attempts > 0 ? `${(f.registration.ratio * 100).toFixed(1)}%` : '—'} sub={f.registration.attempts > 0 ? `${fmt(f.registration.success)} / ${fmt(f.registration.attempts)} 시도` : 'Phase E3 연결 예정'} />
        <MetricCard label="인증 성공률 (7d)" value={f.authentication.attempts > 0 ? `${(f.authentication.ratio * 100).toFixed(1)}%` : '—'} sub={f.authentication.attempts > 0 ? `${fmt(f.authentication.success)} / ${fmt(f.authentication.attempts)} 시도` : 'Phase E3 연결 예정'} />
```

변경:
```tsx
        <MetricCard label="등록 성공률 (7d)" value={f.registration.attempts > 0 ? `${(f.registration.ratio * 100).toFixed(1)}%` : '—'} sub={f.registration.attempts > 0 ? `${fmt(f.registration.success)} / ${fmt(f.registration.attempts)} 시도` : '최근 7일 등록 시도 없음'} />
        <MetricCard label="인증 성공률 (7d)" value={f.authentication.attempts > 0 ? `${(f.authentication.ratio * 100).toFixed(1)}%` : '—'} sub={f.authentication.attempts > 0 ? `${fmt(f.authentication.success)} / ${fmt(f.authentication.attempts)} 시도` : '최근 7일 인증 시도 없음'} />
```

- [ ] **Step 2: 빌드 확인**

Run: `cd .worktrees/overview-funnel-success-rate/admin-ui && npm run build 2>&1 | tail -4`
Expected: 빌드 성공(타입 오류 없음).

- [ ] **Step 3: Commit**

```bash
cd .worktrees/overview-funnel-success-rate
git add admin-ui/src/pages/tenant/TenantOverview.tsx
git commit -m "fix(admin-ui): 개요 탭 성공률 폴백 문구를 실제 상태로 정정"
```

---

## Task 11: 전체 검증 + 마무리

- [ ] **Step 1: core + admin-app + passkey-app 전체 테스트**

Run: `cd .worktrees/overview-funnel-success-rate && ./gradlew :core:test :admin-app:test :passkey-app:test`
Expected: 전부 PASS. (Testcontainers IT 포함 — 수 분 소요)

- [ ] **Step 2: admin-ui 테스트**

Run: `cd .worktrees/overview-funnel-success-rate/admin-ui && npx vitest run`
Expected: 전부 PASS (기존 62 + 변경 영향 없음).

- [ ] **Step 3: codex 독립 리뷰 (커밋 전 사용자 선호)**

Run: `cd .worktrees/overview-funnel-success-rate && codex review --base main`
Expected: 회귀 없음. 지적 사항이 있으면 수정 후 재검증.

- [ ] **Step 4: 최종 상태 확인**

Run: `cd .worktrees/overview-funnel-success-rate && git log --oneline main..HEAD`
Expected: Task 1~10의 커밋이 순서대로 존재.

> 머지(main으로 --no-ff)는 본 plan 범위 밖 — 실행 완료 후 사용자 승인 하에 진행한다.

---

## Self-Review 결과

- **Spec 커버리지**: §4 테이블(Task 1) / §4.2 엔티티·리포(Task 3,4) / §5.1 Recorder+4서비스(Task 5,6) / §5.2 FunnelService(Task 7) / §5.3 프론트(Task 10) / §6 에러처리(Task 5 best-effort) / §7 테스트(Task 8,9, Recorder 단위 Task 5) / §7.1 격리(Task 7에서 TenantBoundary 유지) — 모두 매핑됨.
- **Placeholder**: 코드 스텝은 전체 코드 포함. Task 8/9의 "기존 헬퍼 재사용" 부분은 기존 IT 코드에 의존하므로 구체 코드 대신 명확한 지시 + 검증 코드 제공(기존 테스트 파일을 읽어야 정확한 헬퍼명을 알 수 있는 불가피한 부분).
- **타입 일관성**: `record(UUID, String)` 시그니처가 Recorder 정의(Task 5)와 4개 호출처(Task 6)에서 일치. action 상수는 CeremonyAction 단일 출처. Repository 메서드명 3개가 FunnelService 사용처와 일치.
