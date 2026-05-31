# 데이터 retention/purge (그룹 D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SaaS readiness P1-4를 마감해 무한 증가하는 hash-chain-없는 5개 테이블을 주기적 purge한다(P1 7건 완료).

**Architecture:** admin-app에 `RetentionPurgeJob`(@Scheduled, 하루 1회, SchedulerLease leader election)+`RetentionPurgeService`(테이블별 격리 purge). 4개 테이블은 JPA `@Modifying` bulk DELETE, mds_sync_history는 JdbcTemplate(`MdsHistoryService`)로 native DELETE. 활성 데이터(pending/미만료/미사용)는 쿼리 조건상 절대 미삭제. KeyExpirationJob 패턴(lease+`(scheduler)` audit) 재사용. 신규 스키마 없음. 테스트 최소화.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA(@Modifying), JdbcTemplate(mds), `@Scheduled`, JUnit 5 + Mockito.

**근거 spec:** `docs/superpowers/specs/2026-05-31-data-retention-group-d-design.md`

---

## File Structure

**core (JPA bulk DELETE 메서드):**
- Modify: `core/.../repository/AdminUserInvitationRepository.java` — deleteConsumedOrExpiredBefore
- Modify: `core/.../repository/AdminPasswordResetTokenRepository.java` — deleteConsumedOrExpiredBefore
- Modify: `core/.../repository/AdminUserRecoveryCodeRepository.java` — deleteUsedBefore
- Modify: `core/.../repository/TenantWebauthnSnapshotRepository.java` — deleteTakenBefore

**admin-app (mds native DELETE + job/service):**
- Modify: `admin-app/.../mds/MdsHistoryService.java` — purgeStartedBefore (JdbcTemplate, mds_sync_history는 엔티티 없음)
- Create: `admin-app/.../retention/RetentionPurgeService.java` — 테이블별 purge 조립
- Create: `admin-app/.../retention/RetentionPurgeJob.java` — @Scheduled + lease + 격리 + audit
- Modify: `admin-app/src/main/resources/application.yml` — passkey.retention.*

**책임 분리:** purge 쿼리는 각 repository/MdsHistoryService(데이터 소유처)에. Service는 cutoff 계산+조립, Job은 스케줄+lease+audit. 신규 파일은 retention/ 패키지에 응집.

엔티티 필드명(JPQL 기준, 확인됨): AdminUserInvitation(`expiresAt`,`acceptedAt`), AdminPasswordResetToken(`expiresAt`,`consumedAt`), AdminUserRecoveryCode(`usedAt`), TenantWebauthnSnapshot(`takenAt`). mds_sync_history는 엔티티 없음 — JdbcTemplate native(`started_at` 컬럼).

---

## Task 1: JPA bulk DELETE 메서드 4개

각 repository에 `@Modifying @Transactional @Query` 추가. 활성 데이터 보존 조건 포함.

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserInvitationRepository.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/AdminPasswordResetTokenRepository.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRecoveryCodeRepository.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/TenantWebauthnSnapshotRepository.java`

- [ ] **Step 1: AdminUserInvitationRepository에 추가**

import 추가(`org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `org.springframework.transaction.annotation.Transactional`, `java.time.Instant` — 없는 것만). 인터페이스에 메서드 추가:
```java
    /**
     * P1-4 retention: 완료(수락) 또는 만료된 invitation 중 그 시점이 cutoff 이전인 것 삭제.
     * pending(미수락·미만료)은 쿼리 조건상 절대 매칭 안 됨(활성 보존).
     */
    @Modifying
    @Transactional
    @Query("delete from AdminUserInvitation i where "
         + "(i.acceptedAt is not null and i.acceptedAt < :cutoff) "
         + "or (i.acceptedAt is null and i.expiresAt < :cutoff)")
    int deleteConsumedOrExpiredBefore(@Param("cutoff") Instant cutoff);
```

- [ ] **Step 2: AdminPasswordResetTokenRepository에 추가**

동일 import. 메서드:
```java
    /**
     * P1-4 retention: 소비 또는 만료된 reset 토큰 중 그 시점이 cutoff 이전인 것 삭제.
     * 미소비·미만료 토큰은 보존.
     */
    @Modifying
    @Transactional
    @Query("delete from AdminPasswordResetToken t where "
         + "(t.consumedAt is not null and t.consumedAt < :cutoff) "
         + "or (t.consumedAt is null and t.expiresAt < :cutoff)")
    int deleteConsumedOrExpiredBefore(@Param("cutoff") Instant cutoff);
```

- [ ] **Step 3: AdminUserRecoveryCodeRepository에 추가**

동일 import. 메서드(미사용 보존 — MFA 백업):
```java
    /**
     * P1-4 retention: 사용된 recovery code 중 used_at 이 cutoff 이전인 것 삭제.
     * 미사용 코드(used_at is null)는 MFA 백업이므로 보존.
     */
    @Modifying
    @Transactional
    @Query("delete from AdminUserRecoveryCode r where r.usedAt is not null and r.usedAt < :cutoff")
    int deleteUsedBefore(@Param("cutoff") Instant cutoff);
```

- [ ] **Step 4: TenantWebauthnSnapshotRepository에 추가**

동일 import. 메서드(append-only 이력 — 나이 기반):
```java
    /**
     * P1-4 retention: taken_at 이 cutoff 이전인 스냅샷 삭제(append-only 이력 정리).
     */
    @Modifying
    @Transactional
    @Query("delete from TenantWebauthnSnapshot s where s.takenAt < :cutoff")
    int deleteTakenBefore(@Param("cutoff") Instant cutoff);
```

- [ ] **Step 5: 컴파일**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/data-retention-group-d && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/AdminUserInvitationRepository.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminPasswordResetTokenRepository.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AdminUserRecoveryCodeRepository.java \
        core/src/main/java/com/crosscert/passkey/core/repository/TenantWebauthnSnapshotRepository.java
git commit -m "feat(retention): 토큰3+snapshot bulk DELETE 메서드 — 활성 보존 조건 (P1-4)"
```

---

## Task 2: MdsHistoryService.purgeStartedBefore (native DELETE)

mds_sync_history는 JPA 엔티티가 없고 `MdsHistoryService`가 JdbcTemplate로 관리(`APP_OWNER.` prefix). 그 패턴대로 purge 추가.

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryService.java`

- [ ] **Step 1: purgeStartedBefore 추가**

`MdsHistoryService`의 `append` 메서드 근처에 추가(기존 JdbcTemplate `@Transactional` 패턴):
```java
    /**
     * P1-4 retention: started_at 이 cutoff 이전인 sync 이력 삭제. 삭제 건수 반환.
     * MdsBlobStore/recent() 와 동일하게 APP_OWNER. 스키마 prefix 명시(JdbcTemplate raw SQL).
     */
    @Transactional
    public int purgeStartedBefore(java.time.Instant cutoff) {
        return jdbc.update(
                "DELETE FROM APP_OWNER.mds_sync_history WHERE started_at < ?",
                java.sql.Timestamp.from(cutoff));
    }
```

- [ ] **Step 2: 컴파일**

Run: `./gradlew :admin-app:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryService.java
git commit -m "feat(retention): MdsHistoryService.purgeStartedBefore — mds_sync_history native DELETE (P1-4)"
```

---

## Task 3: RetentionPurgeService

cutoff 받아 각 purge를 조립. 테이블별 독립 메서드(Job이 격리 호출). 활성 보존 불변식 테스트.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeService.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/retention/RetentionPurgeServiceTest.java`

- [ ] **Step 1: 테스트 작성 (활성 보존 불변식 — mock repo로 쿼리 호출 검증)**

Create `admin-app/src/test/java/com/crosscert/passkey/admin/retention/RetentionPurgeServiceTest.java`:
```java
package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.mds.MdsHistoryService;
import com.crosscert.passkey.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeServiceTest {

    @Mock AdminUserInvitationRepository invitations;
    @Mock AdminPasswordResetTokenRepository resetTokens;
    @Mock AdminUserRecoveryCodeRepository recoveryCodes;
    @Mock TenantWebauthnSnapshotRepository snapshots;
    @Mock MdsHistoryService mdsHistory;
    RetentionPurgeService service;

    @BeforeEach
    void setUp() {
        service = new RetentionPurgeService(invitations, resetTokens, recoveryCodes, snapshots, mdsHistory);
    }

    @Test
    void purgeInvitations_delegates_cutoff_and_returns_count() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(invitations.deleteConsumedOrExpiredBefore(eq(cutoff))).thenReturn(3);
        assertThat(service.purgeInvitations(cutoff)).isEqualTo(3);
    }

    @Test
    void purgeRecoveryCodes_delegates_to_used_before() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(recoveryCodes.deleteUsedBefore(eq(cutoff))).thenReturn(7);
        assertThat(service.purgeRecoveryCodes(cutoff)).isEqualTo(7);
    }

    @Test
    void purgeMdsHistory_delegates_to_history_service() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(mdsHistory.purgeStartedBefore(eq(cutoff))).thenReturn(5);
        assertThat(service.purgeMdsHistory(cutoff)).isEqualTo(5);
    }
}
```

Run: `./gradlew :admin-app:test --tests '*RetentionPurgeServiceTest' -q` → FAIL (service 없음).

- [ ] **Step 2: RetentionPurgeService 구현**

Create `admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeService.java`:
```java
package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.mds.MdsHistoryService;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
import com.crosscert.passkey.core.repository.AdminUserInvitationRepository;
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository;
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * P1-4 데이터 retention purge. 테이블별 독립 메서드로 — RetentionPurgeJob 이
 * 각각을 try/catch 로 격리 호출한다(한 테이블 실패가 나머지를 막지 않음).
 * 각 메서드는 cutoff 이전의 비활성 행만 삭제(활성 데이터는 쿼리 조건상 보존).
 */
@Service
public class RetentionPurgeService {

    private final AdminUserInvitationRepository invitations;
    private final AdminPasswordResetTokenRepository resetTokens;
    private final AdminUserRecoveryCodeRepository recoveryCodes;
    private final TenantWebauthnSnapshotRepository snapshots;
    private final MdsHistoryService mdsHistory;

    public RetentionPurgeService(AdminUserInvitationRepository invitations,
                                 AdminPasswordResetTokenRepository resetTokens,
                                 AdminUserRecoveryCodeRepository recoveryCodes,
                                 TenantWebauthnSnapshotRepository snapshots,
                                 MdsHistoryService mdsHistory) {
        this.invitations = invitations;
        this.resetTokens = resetTokens;
        this.recoveryCodes = recoveryCodes;
        this.snapshots = snapshots;
        this.mdsHistory = mdsHistory;
    }

    public int purgeInvitations(Instant cutoff) {
        return invitations.deleteConsumedOrExpiredBefore(cutoff);
    }

    public int purgeResetTokens(Instant cutoff) {
        return resetTokens.deleteConsumedOrExpiredBefore(cutoff);
    }

    public int purgeRecoveryCodes(Instant cutoff) {
        return recoveryCodes.deleteUsedBefore(cutoff);
    }

    public int purgeSnapshots(Instant cutoff) {
        return snapshots.deleteTakenBefore(cutoff);
    }

    public int purgeMdsHistory(Instant cutoff) {
        return mdsHistory.purgeStartedBefore(cutoff);
    }
}
```

Run test → PASS (3 tests).

- [ ] **Step 3: 컴파일+테스트**

Run: `./gradlew :admin-app:test --tests '*RetentionPurgeServiceTest' -q`
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/retention/RetentionPurgeServiceTest.java
git commit -m "feat(retention): RetentionPurgeService — 테이블별 cutoff purge 조립 (P1-4)"
```

---

## Task 4: RetentionPurgeJob + 설정 + audit

@Scheduled + lease + 테이블별 격리 + audit. Job 격리 테스트.

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeJob.java`
- Modify: `admin-app/src/main/resources/application.yml`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/retention/RetentionPurgeJobTest.java`

- [ ] **Step 1: Job 격리 테스트 작성**

먼저 `KeyExpirationJob`과 `SchedulerLeaseService.tryAcquire` 시그니처를 읽어 정확히 맞춘다(tryAcquire(String name, String holder, Duration ttl)→boolean). Job은 runOnce()에서 lease 획득 후 5개 purge를 try/catch로 호출. 테스트는 lease 획득 성공 가정 + 한 메서드 throw 시 나머지 호출 + audit 기록 검증.

Create `admin-app/src/test/java/com/crosscert/passkey/admin/retention/RetentionPurgeJobTest.java`:
```java
package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeJobTest {

    @Mock RetentionPurgeService service;
    @Mock SchedulerLeaseService leases;
    @Mock AuditLogService audit;
    Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    RetentionPurgeJob job;

    @BeforeEach
    void setUp() {
        job = new RetentionPurgeJob(service, leases, audit, clock,
                Duration.ofDays(90), Duration.ofDays(30), Duration.ofDays(180),
                Duration.ofDays(365), Duration.ofDays(90));
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void skips_when_lease_not_acquired() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        job.runOnce();
        verifyNoInteractions(service);
        verifyNoInteractions(audit);
    }

    @Test
    void one_table_failure_does_not_block_others_and_audits() {
        when(service.purgeInvitations(any())).thenReturn(2);
        when(service.purgeResetTokens(any())).thenThrow(new RuntimeException("db error"));
        when(service.purgeRecoveryCodes(any())).thenReturn(1);
        when(service.purgeSnapshots(any())).thenReturn(0);
        when(service.purgeMdsHistory(any())).thenReturn(4);

        job.runOnce();

        // 실패한 reset 외 4개 모두 호출됨
        verify(service).purgeInvitations(any());
        verify(service).purgeResetTokens(any());
        verify(service).purgeRecoveryCodes(any());
        verify(service).purgeSnapshots(any());
        verify(service).purgeMdsHistory(any());
        // audit 1건 — payload 에 건수 + 실패 목록
        ArgumentCaptor<AuditAppendRequest> cap = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("RETENTION_PURGE");
        assertThat(cap.getValue().payload().get("invitationsPurged")).isEqualTo(2);
        assertThat(cap.getValue().payload().get("failed").toString()).contains("resetTokens");
    }
}
```

Run: `./gradlew :admin-app:test --tests '*RetentionPurgeJobTest' -q` → FAIL (Job 없음).

- [ ] **Step 2: RetentionPurgeJob 구현**

`AuditAppendRequest`는 7-arg record `(UUID actorId, String actorEmail, String action, String targetType, String targetId, UUID tenantId, Map<String,Object> payload)`. `SchedulerLeaseService.tryAcquire(name, holder, ttl)`→boolean. Create `admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeJob.java`:
```java
package com.crosscert.passkey.admin.retention;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * P1-4 데이터 retention purge job. 하루 1회, SchedulerLease 로 다중 인스턴스 중
 * 하나만 실행. 5개 테이블을 테이블별 try/catch 로 격리 purge(한 테이블 실패가
 * 나머지·전체 job 을 막지 않음). 끝에 총계+실패 목록을 (scheduler) 액터로 audit.
 * audit_log 자체는 hash-chain forensic 이라 purge 대상 아님(spec).
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);
    private static final String LEASE_NAME = "retention-purge";

    private final RetentionPurgeService service;
    private final SchedulerLeaseService leases;
    private final AuditLogService audit;
    private final Clock clock;
    private final Duration invitationRetention;
    private final Duration resetTokenRetention;
    private final Duration recoveryCodeRetention;
    private final Duration snapshotRetention;
    private final Duration mdsHistoryRetention;

    public RetentionPurgeJob(RetentionPurgeService service,
                             SchedulerLeaseService leases,
                             AuditLogService audit,
                             Clock clock,
                             @Value("${passkey.retention.invitation:P90D}") Duration invitationRetention,
                             @Value("${passkey.retention.password-reset-token:P30D}") Duration resetTokenRetention,
                             @Value("${passkey.retention.recovery-code:P180D}") Duration recoveryCodeRetention,
                             @Value("${passkey.retention.webauthn-snapshot:P365D}") Duration snapshotRetention,
                             @Value("${passkey.retention.mds-sync-history:P90D}") Duration mdsHistoryRetention) {
        this.service = service;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.invitationRetention = invitationRetention;
        this.resetTokenRetention = resetTokenRetention;
        this.recoveryCodeRetention = recoveryCodeRetention;
        this.snapshotRetention = snapshotRetention;
        this.mdsHistoryRetention = mdsHistoryRetention;
    }

    @Scheduled(
            fixedDelayString = "${passkey.retention.fixed-delay:P1D}",
            initialDelayString = "${passkey.retention.initial-delay:PT5M}")
    public void runOnce() {
        String holder = ManagementFactory.getRuntimeMXBean().getName();
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(30))) {
            log.debug("RetentionPurgeJob skipped — another instance holds the lease");
            return;
        }
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();

        purgeOne(payload, failed, "invitationsPurged", "invitations",
                () -> service.purgeInvitations(now.minus(invitationRetention)));
        purgeOne(payload, failed, "resetTokensPurged", "resetTokens",
                () -> service.purgeResetTokens(now.minus(resetTokenRetention)));
        purgeOne(payload, failed, "recoveryCodesPurged", "recoveryCodes",
                () -> service.purgeRecoveryCodes(now.minus(recoveryCodeRetention)));
        purgeOne(payload, failed, "snapshotsPurged", "snapshots",
                () -> service.purgeSnapshots(now.minus(snapshotRetention)));
        purgeOne(payload, failed, "mdsHistoryPurged", "mdsHistory",
                () -> service.purgeMdsHistory(now.minus(mdsHistoryRetention)));

        payload.put("failed", failed);
        try {
            audit.append(new AuditAppendRequest(
                    null, "(scheduler)", "RETENTION_PURGE", null, null, null, payload));
        } catch (RuntimeException e) {
            // purge 는 이미 커밋됨(테이블별 별개 트랜잭션). audit 실패는 job 을 깨지 않음.
            log.error("retention purge audit append failed (purge already applied): cause={}", e.toString());
        }
        log.info("retention purge done: {} failed={}", payload, failed);
    }

    /** 한 테이블 purge 를 격리 실행 — 실패해도 나머지 진행. */
    private void purgeOne(Map<String, Object> payload, List<String> failed,
                          String countKey, String name, IntSupplier purge) {
        try {
            payload.put(countKey, purge.getAsInt());
        } catch (RuntimeException e) {
            log.error("retention purge failed: table={} cause={}", name, e.toString());
            failed.add(name);
            payload.put(countKey, -1);
        }
    }
}
```

Run test → PASS (2 tests).

- [ ] **Step 3: application.yml 설정**

`admin-app/src/main/resources/application.yml`의 `passkey:` 블록 끝에 추가:
```yaml
  retention:
    fixed-delay: P1D            # 하루 1회 실행
    initial-delay: PT5M         # 부팅 후 5분
    invitation: P90D            # 만료/수락 후 90일
    password-reset-token: P30D  # 만료/소비 후 30일
    recovery-code: P180D        # 사용 후 180일
    webauthn-snapshot: P365D    # 스냅샷 1년
    mds-sync-history: P90D      # sync 이력 90일
```

- [ ] **Step 4: 컴파일+테스트+JpaStubs 점검**

Run: `./gradlew :admin-app:compileJava :admin-app:test --tests '*RetentionPurge*' -q`
Expected: PASS. 신규 repository 클래스는 없으나(메서드만 추가), Job/Service가 @Component/@Service라 컴포넌트 스캔에 들어감 — 기존 슬라이스 테스트가 깨질 가능성은 낮으나, `@WebMvcTest` JpaStubs 슬라이스가 깨지면(그룹 A·B·C 패턴) 해당 슬라이스에 영향 확인. 깨지면 그 슬라이스에 필요한 @MockBean 추가.

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeJob.java \
        admin-app/src/main/resources/application.yml \
        admin-app/src/test/java/com/crosscert/passkey/admin/retention/RetentionPurgeJobTest.java
git commit -m "feat(retention): RetentionPurgeJob — @Scheduled+lease+테이블별 격리+audit (P1-4)"
```

---

## Task 5: 전체 검증 + followups

**Files:**
- Create: `docs/superpowers/followups/2026-05-31-data-retention-group-d-followups.md`

- [ ] **Step 1: 전체 빌드 + 단위/슬라이스 테스트**

Run: `./gradlew :core:test :admin-app:test --tests '*Test' -q`
Expected: BUILD SUCCESSFUL. 깨진 기존 테스트가 있으면(컴포넌트 스캔 영향) 갱신.

- [ ] **Step 2: 활성 보존 불변식 재확인 (코드 리뷰 수준)**

각 repository 쿼리를 눈으로 재확인: invitation(pending=미수락·미만료 미매칭), reset(미소비·미만료 미매칭), recovery(미사용 미매칭), snapshot/mds(나이만 — 상태 무관 의도). 활성 데이터가 어떤 cutoff에서도 삭제 안 됨을 확인.

- [ ] **Step 3: followups 갱신**

Create `docs/superpowers/followups/2026-05-31-data-retention-group-d-followups.md`: P1-4 완료. deferred: audit_log purge(anchor-based, 감사 보존기간 짧아지면 별도 phase), credential soft-delete(현재 hard-delete), mds_history가 JPA 엔티티 아닌 JdbcTemplate이라 purge도 native(일관), retention 기간 운영 조정 여지, bulk DELETE 대량 시 배치 분할(현재 단일 DELETE — 행 수 많으면 lock/undo 부담 가능, 추후 chunk). 그리고 `2026-05-30-saas-launch-hardening-followups.md`의 P1-4 행 ✅ 해결 + "P1 7건 전부 완료" 명시.

- [ ] **Step 4: 커밋 전 게이트 — codex review (가능 시) + code quality**

메모리 지침: 누적 diff(`b838f5a..HEAD`)에 `/codex review`(6/1 리셋 후). 특히 활성 보존 쿼리, 테이블별 격리, native DELETE.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/followups/
git commit -m "docs(followups): 그룹 D 완료 (P1-4 retention/purge) — P1 7건 전부 완료"
```

---

## Self-Review

**Spec coverage:**
- 대상 5개 테이블 (§1): Task 1(JPA 4) + Task 2(mds native) ✅
- 활성 보존 조건 (§3): Task 1 쿼리(pending/미소비/미사용 미매칭) ✅
- VPD/권한 (§4): 스케줄러 컨텍스트, @PreAuthorize 불요 — Job이 엔드포인트 아님 ✅
- 설정값 (§5): Task 4 yml ✅
- audit 기록 (§6): Task 4 Job(RETENTION_PURGE, (scheduler), payload 건수+failed) ✅
- 에러처리/격리 (§7): Task 4 purgeOne try/catch + audit 격리 ✅
- audit_log/credential/challenge 제외: 의도적, Task 없음(범위 밖) ✅
- 테스트 최소화 (§8): Service 3 + Job 2 ✅
- followups: Task 5 ✅

**Placeholder scan:** 모든 step에 실제 코드/명령. "KeyExpirationJob 읽어 맞춤"은 lease 시그니처 확인 실행 지시(읽을 파일 명시).

**Type consistency:**
- `RetentionPurgeService(invitations, resetTokens, recoveryCodes, snapshots, mdsHistory)` 5-arg — Task 3 일치 ✅
- `purgeInvitations/ResetTokens/RecoveryCodes/Snapshots/MdsHistory(Instant)→int` — Task 3·4 일치 ✅
- repository `deleteConsumedOrExpiredBefore`/`deleteUsedBefore`/`deleteTakenBefore(Instant)→int` — Task 1·3 일치 ✅
- `MdsHistoryService.purgeStartedBefore(Instant)→int` — Task 2·3 일치 ✅
- `RetentionPurgeJob(service, leases, audit, clock, 5×Duration)` — Task 4 일치 ✅
- `SchedulerLeaseService.tryAcquire(String,String,Duration)→boolean`, `AuditAppendRequest` 7-arg — Task 4(읽어 확인 명시) ✅
