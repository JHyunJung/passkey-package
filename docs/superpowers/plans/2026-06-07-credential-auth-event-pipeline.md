# credential 단위 인증 이벤트 파이프라인 (P1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** credential 단위 인증 성공/실패를 경량 이벤트 테이블에 best-effort 기록하고, admin-app 에서 credential 별로 조회하는 백엔드 파이프라인을 만든다 (admin-ui 화면은 P2).

**Architecture:** core 에 `credential_auth_event` 테이블 + 엔티티 + repository + recorder(`CeremonyEventRecorder` 패턴 재사용)를 추가한다. passkey-app `AuthenticationFinishService` 가 성공은 afterCommit, 실패는 즉시(독립 커밋) 기록한다. admin-app 이 credential 단위 페이지 조회 API 와 retention purge 를 제공한다.

**Tech Stack:** Java 17, Spring Boot 3.x, JPA/Hibernate, Oracle (Flyway), webauthn4j (기존), JUnit 5.

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `core/.../entity/CredentialAuthEvent.java` | 인증 이벤트 엔티티(BaseEntity 상속) | Create |
| `core/.../repository/CredentialAuthEventRepository.java` | 조회 + retention 삭제 | Create |
| `core/.../ceremony/CredentialAuthEventRecorder.java` | best-effort 기록(record/recordAfterCommit) | Create |
| `core/src/main/resources/db/migration/V43__credential_auth_event.sql` | 테이블·FK·인덱스·GRANT | Create |
| `passkey-app/.../authentication/AuthenticationFinishService.java` | 성공/실패 기록 호출 | Modify |
| `admin-app/.../credential/CredentialAdminDto.java` | `AuthEventView` 추가 | Modify |
| `admin-app/.../credential/CredentialAdminService.java` | `listAuthEvents` 추가 | Modify |
| `admin-app/.../credential/CredentialAdminController.java` | GET auth-events 엔드포인트 | Modify |
| `admin-app/.../retention/RetentionPurgeService.java` | `purgeCredentialAuthEvents` 추가 | Modify |
| `admin-app/.../retention/RetentionPurgeJob.java` | purge 편입 + retention 설정 | Modify |

### 설계 노트 (spec 의 핵심 결정 재확인)

- **성공 vs 실패 기록 방식이 다름**: 성공은 `recordAfterCommit`(outer 커밋 확정 후), 실패는 `record`(즉시, REQUIRES_NEW 독립 커밋). 인증 실패는 예외→outer 롤백이라 afterCommit 콜백이 안 불리므로 실패는 즉시 커밋해야 보존된다.
- **credential 참조는 내부 PK(`cred.getId()`)** — WebAuthn credentialId byte[] 아님. FK `ON DELETE CASCADE`.
- **경량 이벤트** — hash chain 없음. `ceremony_event`(V41) 와 동일 철학·마이그레이션 패턴(ORA-00955 swallow, 개별 GRANT 블록).

---

## Task 1: core — CredentialAuthEvent 엔티티

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/CredentialAuthEvent.java`

`CeremonyEvent`(BaseEntity 상속, JdbcTypeCode UUID) 패턴을 따른다. credential 내부 PK, tenantId, result, failureReason, signCount 를 가진다.

- [ ] **Step 1: 엔티티 작성**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * credential 단위 인증 이벤트. hash chain 없는 경량 기록(ceremony_event 와 동일 철학).
 * 성공/실패를 credential 내부 PK 기준으로 적재한다. admin-app 이 credential 상세의
 * "인증 기록" 으로 조회한다. credential 삭제 시 FK ON DELETE CASCADE 로 동반 삭제.
 */
@Entity
@Table(name = "CREDENTIAL_AUTH_EVENT")
public class CredentialAuthEvent extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "CREDENTIAL_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID credentialId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID tenantId;

    @Column(name = "RESULT", length = 16, nullable = false)
    private String result;

    @Column(name = "FAILURE_REASON", length = 64)
    private String failureReason;

    @Column(name = "SIGN_COUNT", nullable = false)
    private long signCount;

    protected CredentialAuthEvent() {}

    public CredentialAuthEvent(UUID credentialId, UUID tenantId, String result,
                               String failureReason, long signCount) {
        this.credentialId = credentialId;
        this.tenantId = tenantId;
        this.result = result;
        this.failureReason = failureReason;
        this.signCount = signCount;
    }

    public UUID getCredentialId() { return credentialId; }
    public UUID getTenantId()     { return tenantId; }
    public String getResult()     { return result; }
    public String getFailureReason() { return failureReason; }
    public long getSignCount()    { return signCount; }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/CredentialAuthEvent.java
git commit -m "feat(core): CredentialAuthEvent 엔티티 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: core — V43 마이그레이션

**Files:**
- Create: `core/src/main/resources/db/migration/V43__credential_auth_event.sql`

V41(ceremony_event) 의 idempotent 패턴을 따른다: 테이블/인덱스 ORA-00955 swallow, 개별 GRANT 블록 ORA-01917/00942 swallow. FK 는 `ON DELETE CASCADE`. credential 테이블 PK 는 `credential(id)` RAW(16).

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- ============================================================
-- V43 — credential_auth_event: credential 단위 인증 이벤트(경량 기록)
--
-- 목적: credential 상세의 "인증 기록"(성공/실패 이력). ceremony_event(V41)는
-- tenant+action 집계라 credential 단위 추적이 불가능하다. hash chain 없는 경량
-- 테이블을 따로 둔다(ceremony_event 와 동일 철학).
--
-- 기록자: passkey-app (APP_RUNTIME) — INSERT.  조회자: admin-app (APP_ADMIN) — SELECT.
-- retention purge: admin-app (APP_ADMIN) — DELETE (RetentionPurgeJob).
-- FK: credential(id) ON DELETE CASCADE — credential 회수(DELETE) 시 이벤트 동반 삭제.
-- VPD: ceremony_event 와 동일하게 미적용(앱 레벨 tenant 격리). 단순 기록 지표.
--
-- Idempotency: ORA-00955(name already used)/ORA-00942/ORA-01917 swallow. V41 패턴.
-- ============================================================

-- 테이블 — ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE credential_auth_event ('
    || 'id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY, '
    || 'credential_id RAW(16) NOT NULL, '
    || 'tenant_id RAW(16) NOT NULL, '
    || 'result VARCHAR2(16) NOT NULL, '
    || 'failure_reason VARCHAR2(64), '
    || 'sign_count NUMBER(19) NOT NULL, '
    || 'created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, '
    || 'updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, '
    || 'CONSTRAINT fk_cred_auth_event_credential '
    || '  FOREIGN KEY (credential_id) REFERENCES credential (id) ON DELETE CASCADE)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- table already exists
    ELSE RAISE;
    END IF;
END;
/

-- 조회 인덱스 (credential_id, created_at DESC) — admin 페이지 조회 커버. ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_cred_auth_event_cred_time '
    || 'ON credential_auth_event (credential_id, created_at DESC)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- retention 인덱스 (created_at) — deleteCreatedBefore 단독 술어용. ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_cred_auth_event_created_at '
    || 'ON credential_auth_event (created_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 런타임 GRANT (각 GRANT 개별 블록 — 멱등·부분 적용 안전)
BEGIN EXECUTE IMMEDIATE 'GRANT INSERT ON credential_auth_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT ON credential_auth_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT, INSERT, DELETE ON credential_auth_event TO APP_ADMIN';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
```

- [ ] **Step 2: SQL 구문 sanity (Flyway 파싱은 통합환경 의존이므로 컴파일 영향 없음 — 파일 존재·인코딩만 확인)**

Run: `head -5 core/src/main/resources/db/migration/V43__credential_auth_event.sql`
Expected: 헤더 주석 출력(파일 정상 생성)

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/db/migration/V43__credential_auth_event.sql
git commit -m "feat(core): V43 credential_auth_event 테이블(FK CASCADE)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: core — Repository (TDD)

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialAuthEventRepository.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/repository/CredentialAuthEventRepositoryTest.java`

`CeremonyEventRepository.deleteCreatedBefore` 패턴(ROWNUM batched native delete)을 따른다. 조회는 Spring Data 파생 쿼리.

> 참고: core 의 repository 테스트가 H2/Oracle 중 무엇으로 도는지는 모듈 설정에 따른다. 기존 core repository 테스트가 있으면 그 슬라이스 설정(@DataJpaTest 등)을 그대로 모방하라. 없다면 이 task 의 Step 1 테스트는 `@DataJpaTest` + 내장 DB 로 작성하되, native query 의 Oracle 전용 문법(ROWNUM)이 H2 에서 실패하면 deleteCreatedBefore 테스트는 `@Disabled("Oracle-only native query")` 로 두고 파생 쿼리(findBy...)만 검증한다.

- [ ] **Step 1: 실패 테스트 작성 (파생 조회 쿼리)**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CredentialAuthEventRepositoryTest {

    @Autowired CredentialAuthEventRepository repo;

    @Test
    void findsByCredentialIdNewestFirst() {
        UUID cred = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        repo.save(new CredentialAuthEvent(cred, tenant, "SUCCESS", null, 1));
        repo.save(new CredentialAuthEvent(cred, tenant, "FAILED", "SIGN_COUNT_REPLAY", 1));
        repo.save(new CredentialAuthEvent(UUID.randomUUID(), tenant, "SUCCESS", null, 2)); // other cred

        var page = repo.findByCredentialIdOrderByCreatedAtDesc(cred, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(e -> e.getCredentialId().equals(cred));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인 (repository 미존재 → 컴파일 에러)**

Run: `./gradlew :core:test --tests CredentialAuthEventRepositoryTest`
Expected: FAIL (CredentialAuthEventRepository 타입 없음)

- [ ] **Step 3: Repository 작성**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * credential 단위 인증 이벤트 조회 + retention 삭제.
 * deleteCreatedBefore 는 CeremonyEventRepository 와 동일한 batched native 패턴.
 */
public interface CredentialAuthEventRepository extends JpaRepository<CredentialAuthEvent, UUID> {

    /** credential 상세 "인증 기록" 페이지 조회 — 최신순. */
    Page<CredentialAuthEvent> findByCredentialIdOrderByCreatedAtDesc(UUID credentialId, Pageable pageable);

    /** retention: created_at 이 cutoff 이전인 이벤트 batched 삭제. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM {h-schema}credential_auth_event WHERE id IN ("
         + "SELECT id FROM {h-schema}credential_auth_event WHERE "
         + "created_at < :cutoff "
         + "AND ROWNUM <= :batchSize)", nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests CredentialAuthEventRepositoryTest`
Expected: PASS (findsByCredentialIdNewestFirst). deleteCreatedBefore 가 H2 에서 ROWNUM 미지원으로 깨지면, 그 테스트는 작성하지 않았으므로 무관.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/CredentialAuthEventRepository.java \
        core/src/test/java/com/crosscert/passkey/core/repository/CredentialAuthEventRepositoryTest.java
git commit -m "feat(core): CredentialAuthEventRepository 조회+retention

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: core — Recorder (TDD)

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/ceremony/CredentialAuthEventRecorder.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/ceremony/CredentialAuthEventRecorderTest.java`

`CeremonyEventRecorder` 의 REQUIRES_NEW + best-effort + afterCommit 패턴을 그대로 따른다. 단 **두 메서드**: `record`(즉시), `recordAfterCommit`(커밋 후).

- [ ] **Step 1: 실패 테스트 작성 (record 가 repo.save 호출 + 예외 swallow)**

```java
package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CredentialAuthEventRecorderTest {

    private final CredentialAuthEventRepository repo = mock(CredentialAuthEventRepository.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    private CredentialAuthEventRecorder newRecorder() {
        // TransactionTemplate.executeWithoutResult 가 즉시 콜백 실행하도록 getTransaction stub
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new CredentialAuthEventRecorder(repo, tx);
    }

    @Test
    void recordSavesEvent() {
        CredentialAuthEventRecorder r = newRecorder();
        r.record(UUID.randomUUID(), UUID.randomUUID(), "SUCCESS", null, 5);
        verify(repo).save(any(CredentialAuthEvent.class));
    }

    @Test
    void recordSwallowsRepositoryFailure() {
        CredentialAuthEventRecorder r = newRecorder();
        doThrow(new RuntimeException("db down")).when(repo).save(any());
        // 예외가 전파되지 않아야 한다 (best-effort)
        r.record(UUID.randomUUID(), UUID.randomUUID(), "FAILED", "SIGN_COUNT_REPLAY", 0);
        verify(repo).save(any(CredentialAuthEvent.class));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests CredentialAuthEventRecorderTest`
Expected: FAIL (CredentialAuthEventRecorder 타입 없음)

- [ ] **Step 3: Recorder 작성**

```java
package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * credential 인증 이벤트를 best-effort 로 기록한다. 기록 실패가 인증 ceremony 를
 * 깨면 안 된다(CeremonyEventRecorder 와 동일 철학·구조).
 *
 * <p>성공: {@link #recordAfterCommit}(outer 커밋 확정 후). 실패: {@link #record}
 * (즉시, REQUIRES_NEW 독립 커밋) — 인증 실패는 예외→outer 롤백이라 afterCommit
 * 콜백이 안 불리므로 실패 이벤트는 즉시 커밋해야 보존된다.
 */
@Component
public class CredentialAuthEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(CredentialAuthEventRecorder.class);

    private final CredentialAuthEventRepository repo;
    private final TransactionTemplate txTemplate;

    public CredentialAuthEventRecorder(CredentialAuthEventRepository repo,
                                       PlatformTransactionManager txManager) {
        this.repo = repo;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 즉시 독립 커밋 기록(실패 경로용 — outer 롤백과 무관하게 보존). */
    public void record(UUID credentialId, UUID tenantId, String result,
                       String failureReason, long signCount) {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(result, "result");
        try {
            txTemplate.executeWithoutResult(status ->
                    repo.save(new CredentialAuthEvent(credentialId, tenantId, result, failureReason, signCount)));
        } catch (Exception e) {
            log.warn("credential_auth_event 기록 실패 (무시): cred={} result={}", credentialId, result, e);
        }
    }

    /** outer 트랜잭션 커밋 확정 후 기록(성공 경로용). 활성 동기화 없으면 즉시 폴백. */
    public void recordAfterCommit(UUID credentialId, UUID tenantId, String result,
                                  String failureReason, long signCount) {
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(result, "result");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    record(credentialId, tenantId, result, failureReason, signCount);
                }
            });
        } else {
            record(credentialId, tenantId, result, failureReason, signCount);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests CredentialAuthEventRecorderTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/ceremony/CredentialAuthEventRecorder.java \
        core/src/test/java/com/crosscert/passkey/core/ceremony/CredentialAuthEventRecorderTest.java
git commit -m "feat(core): CredentialAuthEventRecorder best-effort 기록

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: passkey-app — 인증 성공/실패 기록 호출

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`

성공: `cred.recordAuthentication(...)` + `saveAndFlush(cred)` 직후 → `recordAfterCommit(cred.getId(), tenantUuid, "SUCCESS", null, newCounter)`.
실패(replay): `throw new IllegalArgumentException("signCount replay detected")` **직전** → `record(cred.getId(), tenantUuid, "FAILED", "SIGN_COUNT_REPLAY", newCounter)`.

> tenantId 타입: `ch.tenantId()` 가 String 이면 `UUID.fromString(ch.tenantId())`. RegistrationFinishService 가 `UUID.fromString(ch.tenantId())` 를 쓰므로 동일하게.

- [ ] **Step 1: 의존성 주입 추가**

`AuthenticationFinishService` 생성자와 필드에 `CredentialAuthEventRecorder` 추가. 기존 필드 선언부(예: `private final CredentialRepository credentials;`) 근처에:

```java
    private final com.crosscert.passkey.core.ceremony.CredentialAuthEventRecorder authEvents;
```

생성자 파라미터 끝에 추가하고 `this.authEvents = authEvents;` 대입. (기존 생성자 시그니처를 찾아 마지막 파라미터로 추가하라 — 다른 파라미터 순서는 건드리지 말 것.)

- [ ] **Step 2: 실패(replay) 기록 추가**

기존 코드:

```java
                throw new IllegalArgumentException("signCount replay detected");
```

바로 위에 기록 호출 삽입 (SecurityAlertEvent publish 다음, throw 직전):

```java
                authEvents.record(cred.getId(), UUID.fromString(ch.tenantId()),
                        "FAILED", "SIGN_COUNT_REPLAY", newCounter);
                throw new IllegalArgumentException("signCount replay detected");
```

- [ ] **Step 3: 성공 기록 추가**

기존 코드:

```java
            record.setCounter(newCounter);
            cred.recordAuthentication(newCounter, clock.instant());
            credentials.saveAndFlush(cred);
```

`saveAndFlush(cred);` 다음 줄에 삽입:

```java
            credentials.saveAndFlush(cred);
            authEvents.recordAfterCommit(cred.getId(), UUID.fromString(ch.tenantId()),
                    "SUCCESS", null, newCounter);
```

> `UUID` import 가 이미 있는지 확인. 없으면 `import java.util.UUID;` 추가.

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :passkey-app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 기존 인증 finish 테스트 회귀 확인**

Run: `./gradlew :passkey-app:test --tests "*AuthenticationFinish*"`
Expected: 기존 테스트가 새 협력자(authEvents) 누락으로 깨지면, 해당 테스트의 생성자 호출/목에 `CredentialAuthEventRecorder` mock 을 추가하라(다른 recorder mock 패턴 모방). 그 후 PASS.

- [ ] **Step 6: Commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
git add -u passkey-app/src/test 2>/dev/null || true
git commit -m "feat(passkey-app): 인증 성공/실패를 credential_auth_event 로 기록

성공은 afterCommit, replay 실패는 즉시(독립 커밋). best-effort.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: admin-app — 조회 API (TDD)

**Files:**
- Modify: `admin-app/.../credential/CredentialAdminDto.java` (AuthEventView 추가)
- Modify: `admin-app/.../credential/CredentialAdminService.java` (listAuthEvents)
- Modify: `admin-app/.../credential/CredentialAdminController.java` (GET 엔드포인트)
- Test: `admin-app/.../credential/CredentialAdminServiceAuthEventsTest.java` (신규 단위 테스트)

revoke 와 동일한 credentialId(base64url) → credential 조회 → 내부 PK 로 이벤트 페이지 조회. tenant boundary 재사용.

- [ ] **Step 1: DTO 에 AuthEventView 추가**

`CredentialAdminDto.java` 안에 (기존 record 들 옆):

```java
    public record AuthEventView(
            String result,
            String failureReason,
            long signCount,
            java.time.Instant createdAt) {}
```

- [ ] **Step 2: 실패 테스트 작성 (service.listAuthEvents)**

`CredentialAdminServiceAuthEventsTest.java` — 기존 `CredentialAdminService` 단위 테스트가 있으면 그 mock 셋업(creds, audit, tenantBoundary)을 모방. 신규 협력자 `CredentialAuthEventRepository` mock 추가.

```java
package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CredentialAdminServiceAuthEventsTest {

    // 주: 실제 CredentialAdminService 생성자 시그니처에 맞춰 mock 을 구성하라.
    // 아래는 핵심 협력자만 표시 — 기존 테스트의 생성자 호출을 복사해 authEvents 만 추가.

    @Test
    void listAuthEventsMapsToView() {
        var creds = mock(com.crosscert.passkey.core.repository.CredentialRepository.class);
        var authEvents = mock(CredentialAuthEventRepository.class);
        var tenantBoundary = mock(com.crosscert.passkey.admin.auth.TenantBoundary.class);
        var audit = mock(com.crosscert.passkey.admin.audit.AuditLogService.class);

        UUID tenant = UUID.randomUUID();
        UUID credPk = UUID.randomUUID();
        String credB64 = "AAAA"; // base64url decodable

        Credential c = mock(Credential.class);
        when(c.getTenantId()).thenReturn(tenant);
        when(c.getId()).thenReturn(credPk);
        when(creds.findByCredentialId(any())).thenReturn(Optional.of(c));

        when(authEvents.findByCredentialIdOrderByCreatedAtDesc(eq(credPk), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new CredentialAuthEvent(credPk, tenant, "SUCCESS", null, 3))));

        CredentialAdminService svc = new CredentialAdminService(/* 기존 인자… */ creds, audit, tenantBoundary, authEvents);

        var page = svc.listAuthEvents(tenant, credB64, 0, 50);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).result()).isEqualTo("SUCCESS");
        verify(tenantBoundary).assertCanAccessTenant(tenant);
    }
}
```

> 주의: 위 생성자 호출은 **실제 시그니처에 맞춰 수정**하라. 기존 `CredentialAdminService` 의 생성자를 읽고, 새 협력자 `CredentialAuthEventRepository authEvents` 를 **마지막 파라미터로** 추가한 형태로 호출한다. `findByCredentialId` 메서드명이 실제와 다르면(예: `findByCredentialIdForUpdate`) 실제 read-only 조회 메서드로 맞춘다 — 없으면 Step 3 에서 repository 에 `findByCredentialId` 파생 메서드를 추가한다.

- [ ] **Step 3: 서비스 구현**

`CredentialAdminService` 에 협력자 `CredentialAuthEventRepository authEvents` 를 생성자 마지막 파라미터로 추가하고 필드 대입. 메서드 추가:

실제 패키지(확인됨): `BusinessException`/`ErrorCode`/`PageView` 는 모두 `com.crosscert.passkey.core.api`, `TenantBoundary` 는 `com.crosscert.passkey.admin.auth`, `CredentialRepository` 는 `com.crosscert.passkey.core.repository`. 기존 `CredentialAdminService` 가 이미 이들을 import 하고 있으므로 추가 import 불필요(아래 코드는 그 import 들을 전제로 단순명으로 작성).

```java
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public PageView<CredentialAdminDto.AuthEventView> listAuthEvents(
            UUID tenantId, String credentialIdB64, int page, int size) {
        tenantBoundary.assertCanAccessTenant(tenantId);
        byte[] credId;
        try {
            credId = java.util.Base64.getUrlDecoder().decode(credentialIdB64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "credentialId 가 base64url 형식이 아님");
        }
        Credential c = creds.findByCredentialId(credId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음"));
        if (!c.getTenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "tenant boundary 위반");
        }
        var p = authEvents.findByCredentialIdOrderByCreatedAtDesc(
                c.getId(), org.springframework.data.domain.PageRequest.of(page, size));
        var items = p.getContent().stream()
                .map(e -> new CredentialAdminDto.AuthEventView(
                        e.getResult(), e.getFailureReason(), e.getSignCount(), e.getCreatedAt()))
                .toList();
        return new PageView<>(items, p.getTotalElements(), page, size);
    }
```

> **반드시 확인할 것**:
> 1. `CredentialRepository` 에는 lock 없는 `findByCredentialId(byte[])` 가 **없다** — `findByCredentialIdForUpdate`(pessimistic lock)만 있다. 조회 API 에 lock 은 부적절하므로, `CredentialRepository` 에 lock 없는 파생/명시 메서드를 추가하라. 기존 `findByCredentialIdForUpdate` 의 `@Query`(byte[] credentialId 컬럼 매칭)를 복사해 `@Lock` 없이 `Optional<Credential> findByCredentialId(@Param("credentialId") byte[] credentialId)` 로 만든다(이름 충돌 없음).
> 2. `PageView` 생성자 시그니처(`new PageView<>(items, total, page, size)`)는 기존 `list` 메서드의 반환부에서 **실제 인자 순서**를 확인해 맞춰라(여기서는 추정). 다르면 그 순서로 교정.
> 3. `revoke` 가 쓰는 `creds`/`tenantBoundary`/`audit` 필드명을 그대로 재사용.

- [ ] **Step 4: 컨트롤러 엔드포인트 추가**

`CredentialAdminController` 에:

```java
    @GetMapping("/{credentialId}/auth-events")
    public ApiResponse<PageView<CredentialAdminDto.AuthEventView>> authEvents(
            @PathVariable UUID tenantId,
            @PathVariable String credentialId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.listAuthEvents(tenantId, credentialId, page, size));
    }
```

> `PageView`/`ApiResponse`/`CredentialAdminDto` import 가 없으면 추가.

- [ ] **Step 5: 테스트 통과 + 컴파일**

Run: `./gradlew :admin-app:test --tests CredentialAdminServiceAuthEventsTest`
Expected: PASS. 슬라이스/다른 테스트가 새 협력자 누락으로 깨지면 해당 테스트 생성자 호출에 authEvents mock 추가.

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/credential/ \
        admin-app/src/test/java/com/crosscert/passkey/admin/credential/CredentialAdminServiceAuthEventsTest.java
git add -u core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java 2>/dev/null || true
git commit -m "feat(admin-app): credential 단위 인증 이벤트 조회 API

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: admin-app — retention purge 편입

**Files:**
- Modify: `admin-app/.../retention/RetentionPurgeService.java` (purgeCredentialAuthEvents)
- Modify: `admin-app/.../retention/RetentionPurgeJob.java` (purge 호출 + 설정)

`ceremony_event` purge 와 동일 패턴. `RetentionPurgeService` 에 메서드, `RetentionPurgeJob` 에 retention 설정 + `purgeOne` 호출 추가.

- [ ] **Step 1: RetentionPurgeService 에 메서드 추가**

`RetentionPurgeService` 의 `purgeCeremonyEvents` 와 동일 형태로:

```java
    public int purgeCredentialAuthEvents(java.time.Instant cutoff) {
        return credentialAuthEvents.deleteCreatedBefore(cutoff, batchSize);
    }
```

> `credentialAuthEvents`(CredentialAuthEventRepository) 를 생성자에 주입(기존 `ceremonyEvents` 옆에). `batchSize` 는 기존 필드 재사용. 기존 `purgeCeremonyEvents` 구현을 그대로 복사해 repository 만 바꾸면 된다.

- [ ] **Step 2: RetentionPurgeJob 에 설정 + 호출 추가**

생성자에 retention 설정 추가 (기존 `ceremonyEventRetention` 옆):

```java
                             @Value("${passkey.retention.credential-auth-event:P90D}") Duration credentialAuthEventRetention,
```

필드 선언·대입 추가, `runOnce()` 의 ceremony purge 호출 다음에:

```java
            purgeOne(payload, failed, "credentialAuthEventsPurged", "credentialAuthEvents",
                    () -> service.purgeCredentialAuthEvents(now.minus(credentialAuthEventRetention)));
```

- [ ] **Step 3: 컴파일 + 기존 retention 테스트 회귀 확인**

Run: `./gradlew :admin-app:compileJava :admin-app:test --tests "*Retention*"`
Expected: BUILD SUCCESSFUL. RetentionPurgeJob 테스트가 새 협력자/설정 누락으로 깨지면 mock·생성자 인자 추가.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/retention/
git add -u admin-app/src/test 2>/dev/null || true
git commit -m "feat(admin-app): credential_auth_event retention purge 편입

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 전체 빌드 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: P1 대상 모듈 빌드**

Run: `./gradlew :core:build :passkey-app:compileJava :passkey-app:compileTestJava :admin-app:compileJava :admin-app:compileTestJava --console=plain`
Expected: BUILD SUCCESSFUL

> 주: passkey-app/admin-app 의 full `build`(IT 포함)는 Oracle/Redis 인프라 의존이라 ORA-12541 등 환경 실패가 날 수 있다. 그 경우 컴파일 + 단위/슬라이스 테스트만으로 검증하고, 인프라 의존 IT 실패는 "환경 미가동"으로 구분해 보고하라(코드 결함 아님).

- [ ] **Step 2: 단위/슬라이스 테스트 모음 실행**

Run: `./gradlew :core:test --tests "CredentialAuthEvent*" :admin-app:test --tests "CredentialAdminServiceAuthEventsTest" --console=plain`
Expected: 모두 PASS

---

## Self-Review 체크

- **Spec coverage**: §5.1 엔티티(Task1), §5.2 V43 마이그레이션(Task2), §5.3 repository(Task3), §5.4 recorder record/recordAfterCommit(Task4), §6 passkey-app 성공 afterCommit·실패 즉시(Task5), §7 admin 조회 API(Task6), §8 에러 처리(Task6 boundary/decode + Task4 swallow), §9 테스트(Task3/4/6 + Task5 회귀), retention(Task7) — 전 항목 매핑.
- **Placeholder 스캔**: 모든 코드 step 에 실제 코드. "기존 시그니처에 맞춰 수정"류는 placeholder 가 아니라 기존 코드 연동 지시(정확한 패키지/필드는 코드에서 복사하도록 명시) — 단, Task6 의 생성자 호출·PageView·findByCredentialId 는 실제 코드 확인이 필요한 지점이라 주(主)로 명시.
- **Type 일관성**: `CredentialAuthEvent(credentialId, tenantId, result, failureReason, signCount)` 생성자가 Task1 정의와 Task3/4/6 사용처 일치. `recordAfterCommit`/`record` 5-arg 시그니처가 Task4 정의와 Task5 호출 일치. `findByCredentialIdOrderByCreatedAtDesc`/`deleteCreatedBefore` 가 Task3 정의와 Task6/7 사용 일치. `AuthEventView(result, failureReason, signCount, createdAt)` 가 Task6 내부 일치.
