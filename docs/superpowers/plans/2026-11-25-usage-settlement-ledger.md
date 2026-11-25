# 사용량 정산 장부 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 등록·인증 성공 건수를 청구 근거로 쓸 수 있게, 성공 기록을 본 처리와 같은 트랜잭션에 넣고 일별 요약·월 마감·정산 화면을 만든다.

**Architecture:** `ceremony_event` 성공 행을 finish 트랜잭션 안에서 INSERT 해 장부로 쓴다. admin-app 배치가 장부를 `usage_daily` 로 다시 세어 덮어쓰고, 지난 달은 `usage_monthly` + `usage_period_close` 로 INSERT 전용 마감한 뒤 감사 로그 해시 체인에 수치를 남긴다. 화면은 플랫폼 현황 옆 "사용량 정산" 탭이다.

**Tech Stack:** Spring Boot 4.1, core(Java) 엔티티·리포지토리, passkey-app·admin-app(Kotlin 2.4), Oracle(SE2), Thymeleaf, JUnit 5 + Mockito-Kotlin, Testcontainers(Oracle).

**Spec:** `docs/superpowers/specs/2026-11-25-usage-settlement-ledger-design.md` — 각 Task 를 시작하기 전에 읽는다.

## Global Constraints

- 작업 위치는 worktree `.claude/worktrees/usage-ledger`, 브랜치 `feat/usage-settlement-ledger`. 매 Task 시작 시 `git rev-parse --show-toplevel` 과 `git branch --show-current` 로 확인하고, 다르면 중단한다. 파일은 worktree 경로로만 쓴다.
- Oracle SE2 호환. 파티셔닝 등 EE 전용 기능 금지.
- 실제 성공 건수보다 많이 세는 경로를 만들지 않는다. 마감 테이블(`usage_monthly`, `usage_period_close`)에는 INSERT 경로만 둔다. UPDATE·DELETE 코드와 권한을 만들지 않는다.
- 일·월 경계는 KST. 일 버킷은 기존 관례(`TRUNC(created_at)` + `KstTime.dbDay`)를 따른다.
- `CeremonyAction` 의 문자열 값(`REGISTRATION_FINISH_OK`, `AUTHENTICATION_FINISH_OK` 등)은 바꾸지 않는다.
- `credential_auth_event` 기록 방식, `passkey_ceremony_total` 메트릭, passkey-app 스레드·풀 예산 설정은 건드리지 않는다.
- 화면 문구는 아래 값을 그대로 쓴다: 탭 이름 `사용량 정산`, 상태 `진행 중` / `마감` / `부분(원본 삭제됨)`, 집계 기준 `장부` / `이전 방식`, 검증 `감사 로그와 일치` / `감사 로그와 불일치`, 기존 카드 표기 `청구 기준 아님 · 사용량 정산 탭 참조`.
- 감사 action 이름: `USAGE_MONTH_CLOSED`, `USAGE_MONTH_PARTIAL`, `USAGE_BACKFILL_DONE`.
- 전체 `./gradlew build` 는 기존 문제로 실패한다. 회귀 여부는 모듈별 테스트(`:core:test`, `:passkey-app:test`, `:admin-app:test`)를 main 과 비교해 판단한다.
- macOS BSD `grep -P` 는 조용히 실패한다. `grep -E` 를 쓴다.
- 커밋 메시지는 기존 형식(`feat(scope): 한국어 설명`)을 따른다.

## Review Focus

1. **배치가 3일 넘게 멈췄다가 재개됨** → 진행 중인 달의 빠진 날이 다음 실행에서 채워져야 한다. (Task 3)
2. **admin-app 두 인스턴스가 같은 달을 동시에 마감 시도** → 한쪽만 성공하고 감사 기록도 한 건이어야 한다. (Task 4)
3. **`passkey.usage.ledger-start-date` 가 없거나 형식이 틀림** → 앱이 뜨고, 모든 날이 `이전 방식` 으로 표시되며, 마감은 계속 동작해야 한다. (Task 3)
4. **화면 `period` 값이 이상함**(`2026-13`, `abc`, 미래 달, 데이터 없는 과거 달) → 500 이 아니라 이번 달로 돌아가거나 0 으로 표시돼야 한다. (Task 6)
5. **건수는 있는데 테넌트 행이 없는 tenant_id**(삭제·시드 불일치) → 합계에서 빠지지 않고 식별자로 표시돼야 한다. (Task 6)

## File Structure

| 파일 | 책임 |
|---|---|
| `core/.../ceremony/CeremonyEventRecorder.java` (수정) | 트랜잭션 참여 기록 메서드 |
| `core/.../entity/UsageDaily.java`, `UsageDailyId.java`, `UsageMonthly.java`, `UsageMonthlyId.java`, `UsagePeriodClose.java` (신규) | 요약·마감 테이블 매핑 |
| `core/.../repository/UsageDailyRepository.java`, `UsageMonthlyRepository.java`, `UsagePeriodCloseRepository.java` (신규) | 집계 MERGE, 합산, 마감 조회 |
| `core/.../repository/CeremonyEventRepository.java`, `AuditLogRepository.java` (수정) | 최소 시각 조회·보호 조건 삭제, action 조회 |
| `scripts/full-schema-part2-schema.sql` (수정), `scripts/upgrade/2026-11-25-usage-ledger.sql` (신규) | 스키마 기준과 기존 DB 증분 |
| `admin-app/.../usage/UsagePeriods.kt` (신규) | KST 월·일 경계 계산, 설정값 해석 |
| `admin-app/.../usage/UsageRollupService.kt`, `UsageRollupJob.kt` (신규) | 일별 집계와 스케줄 |
| `admin-app/.../usage/UsageCloseService.kt` (신규) | 백필, 부분 월, 월 마감, 삭제 보호 경계 |
| `admin-app/.../usage/UsageSettlementService.kt`, `UsageDto.kt`, `UsageSettlementController.kt` (신규) | 화면용 조회와 감사 로그 대조 |
| `admin-app/.../web/insights/UsageSettlementPageController.kt`, `templates/admin/insights/usage.html` (신규) | 탭 화면과 CSV |
| `admin-app/.../retention/RetentionPurgeService.kt`, `web/insights/InsightsPageController.kt`, `templates/admin/insights/page.html` (수정) | 삭제 보호, 탭 링크, 청구 기준 아님 표기 |

경로의 `...` 은 `src/main/{java|kotlin}/com/crosscert/passkey/{core|admin}` 이다.

---

### Task 1: 성공 기록을 finish 트랜잭션 안으로

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorder.java`
- Modify: `passkey-app/src/main/kotlin/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.kt` (성공 기록 호출 1곳)
- Modify: `passkey-app/src/main/kotlin/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.kt` (성공 기록 호출 1곳)
- Test: `core/src/test/java/com/crosscert/passkey/core/ceremony/CeremonyEventRecorderTest.java`
- Test (신규): `core/src/test/java/com/crosscert/passkey/core/ceremony/CeremonyEventLedgerIT.java`
- Test: `passkey-app/src/test/kotlin/.../registration/RegistrationFinishServiceCharacterizationTest.kt`, `.../authentication/AuthenticationFinishServiceCharacterizationTest.kt`

**Interfaces:**
- Produces: `public void recordInTransaction(UUID tenantId, String action)` — 활성 트랜잭션이 없으면 `IllegalStateException`, 리포지토리 예외는 그대로 전파, `saveAndFlush` 로 즉시 INSERT.

- [ ] **Step 1: 실패하는 단위 테스트 작성** (`CeremonyEventRecorderTest`)

```java
@Test
void recordInTransaction_withoutActiveTransaction_throws() {
    assertThatThrownBy(() -> recorder.recordInTransaction(UUID.randomUUID(), CeremonyAction.REGISTRATION_SUCCESS))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(repo);
}

@Test
void recordInTransaction_flushesInCallerTransaction_andDoesNotOpenNewOne() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
        UUID tenant = UUID.randomUUID();
        recorder.recordInTransaction(tenant, CeremonyAction.AUTHENTICATION_SUCCESS);
        ArgumentCaptor<CeremonyEvent> c = ArgumentCaptor.forClass(CeremonyEvent.class);
        verify(repo).saveAndFlush(c.capture());
        assertThat(c.getValue().getTenantId()).isEqualTo(tenant);
        assertThat(c.getValue().getAction()).isEqualTo(CeremonyAction.AUTHENTICATION_SUCCESS);
        verifyNoInteractions(txManager);
    } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}

@Test
void recordInTransaction_propagatesRepositoryFailure() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("boom"));
        assertThatThrownBy(() -> recorder.recordInTransaction(UUID.randomUUID(), CeremonyAction.REGISTRATION_SUCCESS))
            .isInstanceOf(DataIntegrityViolationException.class);
    } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*CeremonyEventRecorderTest'` → 컴파일 실패(메서드 없음).

- [ ] **Step 3: `recordInTransaction` 구현.** 활성 여부는 `TransactionSynchronizationManager.isActualTransactionActive()` 로 판단한다. try/catch 를 두지 않는다. 클래스 Javadoc 에 "성공 행은 청구 장부라 호출 측 트랜잭션에 참여한다. BEGIN 행은 기존 best-effort 메서드를 쓴다" 를 적는다.

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.

- [ ] **Step 5: 통합 테스트 작성** (`CeremonyEventLedgerIT`). 기반 설정은 `core/src/test/java/com/crosscert/passkey/core/repository/ActivityRepositoryFeedPageIT.java` 를 따른다. `TransactionTemplate` 으로 네 경우를 확인한다.

```java
// 1) 트랜잭션 안에서 기록 후 setRollbackOnly → 해당 tenant 의 행 수 0
// 2) 트랜잭션 안에서 기록 후 커밋 → 행 수 1
// 3) 8개 스레드가 각자 트랜잭션에서 25회씩 기록 후 커밋 → 행 수 정확히 200
// 4) 트랜잭션 밖 호출 → IllegalStateException, 행 수 0
```

각 경우는 새 `UUID.randomUUID()` tenant 로 격리하고 `countByTenantIdAndActionAndCreatedAtAfter` 로 센다.

- [ ] **Step 6: IT 실행** — `./gradlew :core:test --tests '*CeremonyEventLedgerIT'` → PASS.

- [ ] **Step 7: finish 서비스 특성 테스트를 새 기대값으로 수정.**
  - 등록: `verify(ceremonyEvents).recordInTransaction(TENANT, CeremonyAction.REGISTRATION_SUCCESS)` 와 `verify(ceremonyEvents, never()).recordAfterCommit(anyOrNull(), eq(CeremonyAction.REGISTRATION_SUCCESS))`.
  - 인증: `inOrder` 의 마지막 기대값을 `recordInTransaction(TENANT, CeremonyAction.AUTHENTICATION_SUCCESS)` 로 바꾸고, 실패 경로 테스트의 `never()` 검증도 `recordInTransaction` 기준으로 바꾼다.
  - 두 서비스에 새 테스트 추가: `recordInTransaction` 이 `IllegalStateException("ledger")` 을 던지면 `finish` 가 같은 예외를 던지고, 메트릭 `passkey_ceremony_total{phase=finish,result=success}` 는 0, `result=failure` 는 1 이다.

- [ ] **Step 8: 실패 확인** — `./gradlew :passkey-app:test --tests '*FinishServiceCharacterizationTest'` → FAIL.

- [ ] **Step 9: 두 `finish` 의 성공 기록 호출을 `recordInTransaction` 으로 교체.** 호출 위치는 `ceremonyMetrics.recordSuccess` 바로 앞 그대로 둔다(예외가 try/catch 안에서 잡혀 failure 메트릭으로 간다). BEGIN 기록과 `authEvents` 호출은 그대로 둔다.

- [ ] **Step 10: 통과 확인** — `./gradlew :core:test :passkey-app:test` → main 대비 새 실패 없음.

- [ ] **Step 11: Commit** — `feat(ledger): 등록·인증 성공 기록을 finish 트랜잭션에 포함`

---

### Task 2: 스키마와 core 엔티티·리포지토리

**Files:**
- Modify: `scripts/full-schema-part2-schema.sql` (테이블 3개, 6절 GRANT)
- Create: `scripts/upgrade/2026-11-25-usage-ledger.sql`, `scripts/upgrade/README.md`
- Create: core 엔티티 5개, 리포지토리 3개 (File Structure 참조)
- Modify: `core/.../repository/CeremonyEventRepository.java`, `AuditLogRepository.java`
- Modify: `docs/entity-relationship-diagram.md`, `deploy/README.md`
- Test (신규): `core/src/test/java/com/crosscert/passkey/core/repository/UsageDailyRepositoryIT.java`

**Interfaces:**
- Produces (테이블):

```sql
CREATE TABLE usage_daily (
    tenant_id     RAW(16)                     NOT NULL,
    usage_date    DATE                        NOT NULL,
    reg_success   NUMBER(12)   DEFAULT 0      NOT NULL,
    auth_success  NUMBER(12)   DEFAULT 0      NOT NULL,
    reg_attempt   NUMBER(12)   DEFAULT 0      NOT NULL,
    auth_attempt  NUMBER(12)   DEFAULT 0      NOT NULL,
    basis         VARCHAR2(8)                 NOT NULL,
    aggregated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_usage_daily_basis CHECK (basis IN ('LEDGER','LEGACY')),
    CONSTRAINT pk_usage_daily PRIMARY KEY (tenant_id, usage_date)
);
CREATE TABLE usage_monthly (
    tenant_id     RAW(16)      NOT NULL,
    period        VARCHAR2(7)  NOT NULL,          -- 'YYYY-MM' (KST)
    reg_success   NUMBER(14)   NOT NULL,
    auth_success  NUMBER(14)   NOT NULL,
    reg_attempt   NUMBER(14)   NOT NULL,
    auth_attempt  NUMBER(14)   NOT NULL,
    CONSTRAINT pk_usage_monthly PRIMARY KEY (tenant_id, period)
);
CREATE TABLE usage_period_close (
    period        VARCHAR2(7)                 NOT NULL,
    status        VARCHAR2(8)                 NOT NULL,
    basis         VARCHAR2(8)                 NOT NULL,
    reg_success   NUMBER(14)                  NOT NULL,
    auth_success  NUMBER(14)                  NOT NULL,
    reg_attempt   NUMBER(14)                  NOT NULL,
    auth_attempt  NUMBER(14)                  NOT NULL,
    tenant_count  NUMBER(8)                   NOT NULL,
    closed_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_usage_close_status CHECK (status IN ('CLOSED','PARTIAL')),
    CONSTRAINT ck_usage_close_basis  CHECK (basis IN ('LEDGER','LEGACY')),
    CONSTRAINT pk_usage_period_close PRIMARY KEY (period)
);
```

- Produces (권한): `PSK_APP_ADMIN` — `usage_daily` SELECT·INSERT·UPDATE, `usage_monthly`·`usage_period_close` SELECT·INSERT 만. `PSK_APP_RUNTIME` — 세 테이블 SELECT(스키마 검증용, `security_incident` 선례 주석과 같은 설명을 단다).
- Produces (리포지토리):

```java
// UsageDailyRepository extends JpaRepository<UsageDaily, UsageDailyId>
int mergeFromLedger(OffsetDateTime from, OffsetDateTime toExclusive, LocalDate ledgerStart, OffsetDateTime aggregatedAt);
List<Object[]> sumByTenant(LocalDate from, LocalDate toExclusive);   // [tenant_id RAW, reg_success, auth_success, reg_attempt, auth_attempt]
Optional<LocalDate> findMinUsageDate();
Optional<LocalDate> findMaxUsageDate();
Optional<OffsetDateTime> findMaxAggregatedAt(LocalDate from, LocalDate toExclusive);

// UsageMonthlyRepository extends JpaRepository<UsageMonthly, UsageMonthlyId>
List<UsageMonthly> findByPeriod(String period);

// UsagePeriodCloseRepository extends JpaRepository<UsagePeriodClose, String>
List<UsagePeriodClose> findAllByOrderByPeriodDesc();

// CeremonyEventRepository (추가)
Optional<OffsetDateTime> findMinCreatedAt();
int deleteCreatedBefore(OffsetDateTime cutoff, int batchSize);   // 기존 유지

// AuditLogRepository (추가)
boolean existsByAction(String action);
List<AuditLog> findByActionIn(Collection<String> actions);
```

`ledgerStart` 는 null 을 넘기지 않는다. 미설정이면 호출 측이 `LocalDate.of(9999, 12, 31)` 을 넘긴다(Oracle 네이티브 쿼리의 null 바인딩 회피).

- [ ] **Step 1: 실패하는 IT 작성** (`UsageDailyRepositoryIT`). `ceremony_event` 행은 `created_at` 을 지정해야 하므로 `JdbcTemplate` 네이티브 INSERT 로 넣는다(`id`, `tenant_id`, `action`, `created_at`, `updated_at`).

```java
// merge_countsSuccessAndAttemptPerTenantPerKstDay:
//   tenant A, 2026-10-31 23:59:59+09:00 에 REGISTRATION_FINISH_OK 1, AUTHENTICATION_FINISH_OK 2, AUTHENTICATION_BEGIN 3
//   tenant A, 2026-11-01 00:00:00+09:00 에 AUTHENTICATION_FINISH_OK 1
//   mergeFromLedger(2026-10-31T00:00+09:00, 2026-11-02T00:00+09:00, 2026-11-01, now)
//   → (A, 2026-10-31): reg_success=1, auth_success=2, auth_attempt=3, reg_attempt=0, basis=LEGACY
//   → (A, 2026-11-01): auth_success=1, basis=LEDGER
// merge_isIdempotent: 같은 호출을 두 번 → 행 수와 값 동일
// merge_overwritesWhenLedgerGrows: 행 1건 추가 후 재호출 → auth_success 가 1 증가
// merge_sentinelLedgerStart_marksAllLegacy: ledgerStart=9999-12-31 → 모든 basis=LEGACY
// sumByTenant_sumsWithinRangeOnly: from=2026-10-01, toExclusive=2026-11-01 → A 의 10월분만
// findMinCreatedAt_returnsOldestLedgerInstant
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*UsageDailyRepositoryIT'` → 컴파일 실패.

- [ ] **Step 3: 기준 스키마에 테이블·GRANT 추가.** 테이블은 `ceremony_event` 정의 뒤에, GRANT 는 6절 알파벳 순서 자리에 넣는다. 6절 머리말의 GRANT 건수 표기도 맞춘다. FK 는 걸지 않는다(`ceremony_event` 와 같은 방침: 테넌트 정리와 무관하게 정산 기록을 남긴다).

- [ ] **Step 4: 증분 스크립트 작성** (`scripts/upgrade/2026-11-25-usage-ledger.sql`). PSK_APP_OWNER 로 실행한다. 재실행해도 안전하게 `user_tables` 로 존재를 확인한 뒤 `EXECUTE IMMEDIATE` 로 CREATE 한다. GRANT 는 매번 실행한다. `scripts/upgrade/README.md` 에 규칙 세 줄을 적는다: 파일명은 `YYYY-MM-DD-주제.sql`, 재실행 안전, 기준 스키마 파일과 같은 커밋에서 함께 고친다.

- [ ] **Step 5: 엔티티·리포지토리 구현.** 복합 키는 `@IdClass` 를 쓴다. `mergeFromLedger` 는 네이티브 `MERGE INTO {h-schema}usage_daily` 이고, 소스는 `created_at >= :from AND created_at < :to` 범위를 `tenant_id, TRUNC(created_at)` 로 묶어 action 별 `SUM(CASE ...)` 한다. basis 는 `CASE WHEN TRUNC(created_at) >= :ledgerStart THEN 'LEDGER' ELSE 'LEGACY' END`. 매치되면 여섯 값과 `aggregated_at` 을 덮어쓴다. action 문자열은 `CeremonyAction` 상수를 파라미터로 넘긴다.

- [ ] **Step 6: 통과 확인** — `./gradlew :core:test --tests '*UsageDailyRepositoryIT'` → PASS.

- [ ] **Step 7: 두 앱의 스키마 검증 확인.** `./gradlew :passkey-app:test :admin-app:test` 에서 컨텍스트를 올리는 테스트가 main 대비 새로 실패하지 않아야 한다. admin-app `@WebMvcTest` 슬라이스가 새 리포지토리 빈 때문에 깨지면 기존 슬라이스가 쓰는 방식대로 MockBean 을 추가한다.

- [ ] **Step 8: 문서 갱신.** `docs/entity-relationship-diagram.md` 에 세 테이블을 추가한다. `deploy/README.md` 에 "증분 스크립트를 앱보다 먼저 적용한다. 순서가 바뀌면 passkey-app 이 스키마 검증 실패로 뜨지 않는다" 를 적는다.

- [ ] **Step 9: Commit** — `feat(usage): 사용량 요약·마감 테이블과 리포지토리 추가`

---

### Task 3: 일별 집계 서비스와 배치

**Files:**
- Create: `admin-app/.../usage/UsagePeriods.kt`, `UsageRollupService.kt`, `UsageRollupJob.kt`
- Modify: `admin-app/src/main/resources/application.yml` (기본값), `deploy/.env.example`, `deploy/README.md`
- Test (신규): `admin-app/src/test/kotlin/com/crosscert/passkey/admin/usage/UsagePeriodsTest.kt`, `UsageRollupServiceTest.kt`, `UsageRollupJobTest.kt`

**Interfaces:**
- Consumes: Task 2 의 `UsageDailyRepository`, `UsagePeriodCloseRepository`.
- Produces:

```kotlin
object UsagePeriods {
    val NO_LEDGER: LocalDate = LocalDate.of(9999, 12, 31)
    fun parseLedgerStart(raw: String?): LocalDate          // null·빈 값·형식 오류 → NO_LEDGER (경고 로그 1회)
    fun dayStart(day: LocalDate): OffsetDateTime            // KST 00:00
    fun monthStart(m: YearMonth): OffsetDateTime
    fun periodOf(m: YearMonth): String                      // "2026-10"
    fun parsePeriod(s: String?): YearMonth?                 // 형식 오류 → null
}

@Service class UsageRollupService(daily: UsageDailyRepository, closes: UsagePeriodCloseRepository, clock: Clock,
    @Value("\${passkey.usage.ledger-start-date:}") ledgerStartRaw: String) {
    val ledgerStart: LocalDate
    /** 닫힌 달에 속한 날은 건너뛴다. 반환값은 실제로 집계한 날 수. */
    fun rollupDays(fromDay: LocalDate, toDayInclusive: LocalDate): Int
    /** 정기 실행분: 기본은 오늘 포함 최근 3일. usage_daily 의 마지막 날이 그보다 앞서면 그날부터. */
    fun rollupRecent(): Int
    /** 닫힌 달 검사 없이 한 달 전체를 다시 센다. 마감 직전에만 쓴다. */
    fun rollupMonthForClose(month: YearMonth)
}

@Component class UsageRollupJob(rollup: UsageRollupService, leases: SchedulerLeaseService)
// lease 이름 "usage-rollup", TTL 30분. 설정: passkey.usage.rollup.fixed-delay(기본 PT1H), initial-delay(기본 PT2M)
```

이 Task 에서 Job 은 `rollup.rollupRecent()` 만 호출한다. Task 4 가 생성자에 `UsageCloseService` 를 추가하고 호출을 덧붙인다.

- [ ] **Step 1: 실패하는 테스트 작성.**

```kotlin
// UsagePeriodsTest
// parseLedgerStart("2026-11-26") == LocalDate.of(2026,11,26)
// parseLedgerStart(null), (""), ("2026/11/26"), ("내일") == UsagePeriods.NO_LEDGER
// monthStart(YearMonth.of(2026,10)) == OffsetDateTime.parse("2026-10-01T00:00+09:00")
// parsePeriod("2026-13"), ("abc"), (null) == null ; parsePeriod("2026-10") == YearMonth.of(2026,10)

// UsageRollupServiceTest (clock = 2026-11-25T10:00+09:00 고정, 리포지토리 mock)
// rollupRecent_defaultsToLast3Days:
//   findMaxUsageDate()=2026-11-25 → mergeFromLedger(2026-11-23T00:00+09:00, 2026-11-26T00:00+09:00, ledgerStart, any()) 1회
// rollupRecent_catchesUpAfterOutage:        (Review Focus 1)
//   findMaxUsageDate()=2026-11-10 → from 이 2026-11-10T00:00+09:00
// rollupRecent_emptyDaily_usesLast3Days:    findMaxUsageDate()=empty → from 2026-11-23
// rollupDays_skipsClosedMonth:
//   closes 에 "2026-10" 존재, rollupDays(2026-10-30, 2026-11-02) → merge 의 from 이 2026-11-01T00:00+09:00, 반환 2
// rollupDays_allClosed_doesNothing:         merge 호출 없음, 반환 0
// ledgerStart_unsetOrMalformed_passesSentinel:   (Review Focus 3)
//   ledgerStartRaw="" 와 "x" 각각 → merge 의 ledgerStart 인자 == UsagePeriods.NO_LEDGER
// rollupMonthForClose_coversWholeMonth:
//   YearMonth.of(2026,10) → merge(2026-10-01T00:00+09:00, 2026-11-01T00:00+09:00, ...)

// UsageRollupJobTest (RetentionPurgeJobTest 와 같은 Mockito 구성)
// leaseNotAcquired_doesNothing ; leaseAcquired_runsRollup_andReleases ; rollupThrows_stillReleasesLease
```

- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*usage.*'` → 컴파일 실패.

- [ ] **Step 3: 구현.** `rollupDays` 는 범위를 달 단위로 나눠 닫힌 달 구간을 빼고, 남은 연속 구간마다 `mergeFromLedger` 를 한 번 호출한다. Job 은 `RetentionPurgeJob` 의 lease 획득·`try/finally` 반환 구조를 그대로 따른다.

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.

- [ ] **Step 5: 설정 문서화.** `deploy/.env.example` 과 `deploy/README.md` 에 `PASSKEY_USAGE_LEDGER_START_DATE`(형식 `YYYY-MM-DD`, Task 1 이 배포된 다음 날, 비우면 모든 날이 "이전 방식")를 추가한다.

- [ ] **Step 6: Commit** — `feat(usage): 일별 사용량 집계 서비스와 배치`

---

### Task 4: 백필·부분 월·월 마감

**Files:**
- Create: `admin-app/.../usage/UsageCloseService.kt`
- Modify: `admin-app/.../usage/UsageRollupJob.kt` (호출 추가)
- Test (신규): `admin-app/src/test/kotlin/com/crosscert/passkey/admin/usage/UsageCloseServiceTest.kt`, `UsageCloseIT.kt`

**Interfaces:**
- Consumes: Task 2 리포지토리, Task 3 `UsageRollupService`·`UsagePeriods`, 기존 `AuditLogService.append(AuditAppendRequest)`.
- Produces:

```kotlin
@Service class UsageCloseService(
    rollup: UsageRollupService, daily: UsageDailyRepository, monthly: UsageMonthlyRepository,
    closes: UsagePeriodCloseRepository, ceremonyEvents: CeremonyEventRepository,
    auditRepo: AuditLogRepository, audit: AuditLogService, tx: TransactionTemplate, clock: Clock,
    @Value("\${passkey.retention.ceremony-event:P90D}") retention: Duration,
    @Value("\${passkey.usage.close-after:PT2H}") closeAfter: Duration,
) {
    /** 한 번만 실행된다. 실행했으면 true. */
    fun backfillIfNeeded(): Boolean
    /** 마감한 기간 목록(오래된 달부터). */
    fun closeDueMonths(): List<String>
    /** 이 시각 이전의 ceremony_event 만 삭제해도 된다. */
    fun releasedBefore(): OffsetDateTime
}
```

- 규칙(스펙 4.4 를 그대로 옮긴 것):
  - **백필 완료 표식**은 감사 로그 `USAGE_BACKFILL_DONE` 한 건이다. `auditRepo.existsByAction` 이 true 면 `backfillIfNeeded` 는 아무것도 하지 않는다.
  - **백필 순서**: ① `ceremonyEvents.findMinCreatedAt()` 부터 오늘까지 `rollup.rollupDays` ② 그 범위의 지난 달 중 `monthStart(M) < now − retention` 인 달을 `PARTIAL` 로 확정 ③ 맨 마지막에 `USAGE_BACKFILL_DONE` 기록(payload: `backfilledAt`, `oldestLedgerAt`, `partialPeriods`). 원본이 하나도 없으면 ①② 없이 ③만 한다.
  - **마감 대상**: `daily.findMinUsageDate()` 가 속한 달부터 지난 달까지 중 `usage_period_close` 에 없는 달. 오래된 달부터. `now >= monthStart(M+1) + closeAfter` 일 때만. 한 달이 실패하면 그 뒤 달은 시도하지 않는다(순서 보장).
  - **확정 한 건은 한 트랜잭션**: ① `rollup.rollupMonthForClose(M)` ② `daily.sumByTenant` 로 합산해 `usage_monthly` 행(건수가 하나라도 0 이 아닌 테넌트만)과 `usage_period_close` 행 INSERT ③ **맨 마지막에** 감사 기록. 감사 기록이 실패하면 전체 롤백.
  - 월 basis: `monthStart(M)` 의 날짜가 `rollup.ledgerStart` 이상이면 `LEDGER`, 아니면 `LEGACY`.
  - 감사 요청: `AuditAppendRequest(null, "(scheduler)", action, "usage_period", period, null, payload)`. payload 는 `period`, `status`, `basis`, `registrationSuccess`, `authenticationSuccess`, `tenants`(각 원소 `tenantId`, `registrationSuccess`, `authenticationSuccess`, tenantId 문자열 오름차순).
  - `usage_period_close` INSERT 가 PK 중복으로 실패하면(`DataIntegrityViolationException`) 다른 인스턴스가 먼저 마감한 것으로 보고 그 달을 건너뛴다. 트랜잭션이 롤백되므로 감사 기록도 남지 않는다.
  - `releasedBefore()`: `findMinCreatedAt()` 이 속한 달부터 차례로 보아 처음 만나는 "close 행 없는 달"의 `monthStart`. 원본이 없으면 이번 달의 `monthStart`.

- [ ] **Step 1: 실패하는 단위 테스트 작성** (`UsageCloseServiceTest`, clock = 2026-12-01T03:00+09:00, retention=P90D, closeAfter=PT2H)

```kotlin
// closeDueMonths_closesLastMonthAfterGrace:
//   minUsageDate=2026-11-03, closes 비어 있음 → 반환 ["2026-11"], rollupMonthForClose(2026-11) 호출,
//   usage_period_close(status=CLOSED) 1건 저장, audit action == "USAGE_MONTH_CLOSED"
// closeDueMonths_beforeGrace_doesNothing:   clock=2026-12-01T01:59+09:00 → 반환 빈 목록
// closeDueMonths_oldestFirst_andStopsOnFailure:
//   minUsageDate=2026-09-10, 2026-10 확정 중 monthly.saveAll 이 예외 → 반환 ["2026-09"], 2026-11 은 시도 안 함
// close_auditIsLastCall:
//   inOrder(rollup, monthly, closes, audit) 로 audit.append 가 마지막
// close_auditFailure_propagates:            audit.append 가 예외 → 예외 전파(삼키지 않음)
// close_skipsZeroTenants_butCountsTenantCount:
//   sumByTenant = [A:(1,2,3,4), B:(0,0,0,5), C:(0,0,0,0)] → usage_monthly 는 A, B 두 행, tenant_count=2
// close_basis:  ledgerStart=2026-11-01 → 2026-11 은 LEDGER ; ledgerStart=2026-11-02 → LEGACY ; NO_LEDGER → LEGACY
// close_duplicatePeriod_isSkippedSilently:  (Review Focus 2)
//   closes.saveAndFlush 가 DataIntegrityViolationException → 반환 목록에 없음, 예외 없음
// backfill_runsOnce:                        existsByAction("USAGE_BACKFILL_DONE")=true → 아무 호출 없음, false 반환
// backfill_marksOldMonthsPartial:
//   clock=2026-11-25T10:00+09:00, minCreatedAt=2026-08-27T10:00+09:00
//   → 2026-08 은 PARTIAL(8/1 < 8/27), 2026-09·2026-10 은 PARTIAL 아님(이후 closeDueMonths 가 CLOSED 로 마감)
//   → audit action 순서: "USAGE_MONTH_PARTIAL"(2026-08), 마지막이 "USAGE_BACKFILL_DONE"
// backfill_noLedgerRows_onlyWritesMarker
// releasedBefore_isStartOfOldestUnclosedMonth:
//   minCreatedAt=2026-08-27, closes={2026-08, 2026-09} → 2026-10-01T00:00+09:00
// releasedBefore_noLedgerRows_isStartOfCurrentMonth
```

- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*UsageCloseServiceTest'` → 컴파일 실패.

- [ ] **Step 3: `UsageCloseService` 구현.** 확정 한 건은 `TransactionTemplate.execute` 로 감싼다(같은 클래스 안 호출이라 `@Transactional` 프록시가 걸리지 않는다). `usage_period_close` 는 `saveAndFlush` 로 넣어 PK 충돌을 그 자리에서 드러낸다.

- [ ] **Step 4: Job 에 호출 추가.** 순서는 `backfillIfNeeded()` → `rollupRecent()` → `closeDueMonths()`. `UsageRollupJobTest` 에 이 순서 검증을 추가한다.

- [ ] **Step 5: 통과 확인** — `./gradlew :admin-app:test --tests '*usage.*'` → PASS.

- [ ] **Step 6: 통합 테스트 작성** (`UsageCloseIT`, 기반은 `admin-app/src/test/kotlin/com/crosscert/passkey/admin/funnel/FunnelIT.kt`). `ceremony_event` 는 네이티브 INSERT 로 시각을 지정한다.

```kotlin
// closesMonth_andAuditPayloadMatchesMonthlyRows:
//   2026-10 에 tenant A 등록 성공 2·인증 성공 5, tenant B 인증 성공 1 → closeDueMonths()
//   → usage_monthly 2행, usage_period_close(reg=2, auth=6, tenant_count=2, status=CLOSED)
//   → audit_log 의 USAGE_MONTH_CLOSED payload.tenants 가 같은 수치
// closedMonth_isNotChangedByLaterRollup:
//   마감 후 2026-10 에 성공 행 추가 → rollupRecent(), rollupDays(2026-10-01, 2026-10-31) → usage_daily·usage_monthly 불변
// closeTwice_secondIsNoop:   closeDueMonths() 두 번 → close 행 1건, USAGE_MONTH_CLOSED 감사 1건
// adminRole_cannotUpdateClosedTables:
//   PSK_APP_ADMIN 접속으로 UPDATE usage_monthly / DELETE FROM usage_period_close → ORA-01031 (권한 없음)
//   (테스트 데이터소스가 owner 계정뿐이면 이 경우는 @Disabled 하지 말고 스키마 파일의 GRANT 문을 파싱해
//    두 테이블에 UPDATE·DELETE GRANT 가 없음을 단언하는 테스트로 대신한다)
```

- [ ] **Step 7: IT 실행** — `./gradlew :admin-app:test --tests '*UsageCloseIT'` → PASS.

- [ ] **Step 8: Commit** — `feat(usage): 월 마감·백필과 감사 로그 기록`

---

### Task 5: 마감 전 원본 삭제 방지

**Files:**
- Modify: `admin-app/.../retention/RetentionPurgeService.kt`
- Test: `admin-app/src/test/kotlin/com/crosscert/passkey/admin/retention/RetentionPurgeServiceTest.kt`
- Modify: `docs/backlog.md` (보존 규칙 서술이 있는 항목에 한 줄 추가)

**Interfaces:**
- Consumes: Task 4 `UsageCloseService.releasedBefore(): OffsetDateTime`.
- Produces: `purgeCeremonyEvents(cutoff)` 가 `minOf(cutoff, usageClose.releasedBefore())` 로 삭제한다. 시그니처는 그대로다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
// purgeCeremonyEvents_cutoffEarlierThanReleased_usesCutoff:
//   cutoff=2026-08-27T10:00+09:00, releasedBefore=2026-10-01T00:00+09:00 → deleteCreatedBefore(2026-08-27T10:00+09:00, BATCH)
// purgeCeremonyEvents_unclosedMonthBlocksPurge:
//   cutoff=2026-12-15T00:00+09:00, releasedBefore=2026-09-01T00:00+09:00 → deleteCreatedBefore(2026-09-01T00:00+09:00, BATCH)
// purgeCredentialAuthEvents_unaffected:  cutoff 그대로 전달
```

- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*RetentionPurgeServiceTest'` → FAIL.

- [ ] **Step 3: 구현.** `RetentionPurgeService` 생성자에 `UsageCloseService` 를 추가하고 `purgeCeremonyEvents` 만 바꾼다. KDoc 에 "속한 달이 마감되기 전에는 지우지 않는다. 청구 근거 원본이다" 를 적는다. `CeremonyEventRepository.deleteCreatedBefore` 의 Javadoc 에서 "funnel 쿼리는 최대 30일…안전하게 제거 가능" 문장을 현재 사실에 맞게 고친다.

- [ ] **Step 4: 통과 확인** — `./gradlew :admin-app:test --tests '*retention.*'` → PASS.

- [ ] **Step 5: Commit** — `feat(usage): 마감되지 않은 달의 장부 원본은 보존 배치가 지우지 않게`

---

### Task 6: 사용량 정산 화면과 CSV

**Files:**
- Create: `admin-app/.../usage/UsageDto.kt`, `UsageSettlementService.kt`, `UsageSettlementController.kt`
- Create: `admin-app/.../web/insights/UsageSettlementPageController.kt`, `admin-app/src/main/resources/templates/admin/insights/usage.html`
- Modify: `admin-app/.../web/insights/InsightsPageController.kt`(탭 모델, `export` 주석), `templates/admin/insights/page.html`(탭 줄, 월간 카드 안내 문구)
- Test (신규): `admin-app/src/test/kotlin/com/crosscert/passkey/admin/usage/UsageSettlementServiceTest.kt`, `.../web/insights/UsageSettlementPageControllerTest.kt`
- Test: `.../web/insights/InsightsPageControllerTest.kt`

**Interfaces:**
- Consumes: Task 2 리포지토리, Task 3 `UsagePeriods`, 기존 `TenantRepository`, `AuditLogRepository.findByActionIn`.
- Produces:

```kotlin
object UsageDto {
    enum class Status { IN_PROGRESS, CLOSED, PARTIAL }
    enum class Verification { MATCH, MISMATCH, NOT_APPLICABLE }   // 진행 중인 달은 NOT_APPLICABLE
    data class TenantRow(val tenantId: UUID, val slug: String?, val name: String, val regSuccess: Long, val authSuccess: Long)
    data class MonthRow(val period: String, val status: Status, val regSuccess: Long, val authSuccess: Long,
                        val activeTenants: Int, val authAttempt: Long)
    data class View(val period: String, val status: Status, val basis: String, val asOf: OffsetDateTime?,
                    val verification: Verification, val tenants: List<TenantRow>,
                    val totalReg: Long, val totalAuth: Long, val trend: List<MonthRow>, val periods: List<String>)
}
@Service class UsageSettlementService { fun view(period: YearMonth): UsageDto.View }
@RestController @RequestMapping("/admin/api/platform/usage") class UsageSettlementController {
    @GetMapping @PreAuthorize("hasRole('PLATFORM_OPERATOR')") fun get(@RequestParam period: String?): UsageDto.View
}
// 화면: GET /admin/insights/usage?period=YYYY-MM
// CSV : GET /admin/insights/usage.csv?period=YYYY-MM&kind=tenants|trend
```

- 조회 규칙:
  - 마감·부분 월은 `usage_monthly` + `usage_period_close` 에서, 진행 중인 달은 `usage_daily.sumByTenant` 에서 읽는다. `asOf` 는 마감 시각 또는 `findMaxAggregatedAt`.
  - 테넌트 목록은 `TenantRepository` 의 전체 테넌트에 건수를 붙이고 없으면 0. 건수는 있는데 테넌트가 없는 id 는 `name = tenantId.toString()`, `slug = null` 로 덧붙인다. 정렬은 합계 내림차순, 같으면 이름.
  - 검증: `findByActionIn(["USAGE_MONTH_CLOSED","USAGE_MONTH_PARTIAL"])` 중 payload 의 `period` 가 같은 건을 찾아, 테넌트별 두 수치와 전체 합계가 `usage_monthly`·`usage_period_close` 와 모두 같으면 `MATCH`. 감사 건이 없거나 하나라도 다르면 `MISMATCH`.
  - `trend` 는 최근 12개월(이번 달 포함, 최신 먼저). `activeTenants` 는 그달 성공 건수가 1 이상인 테넌트 수. 데이터가 없는 달은 0 행으로 채운다.
  - `periods` 는 `usage_daily` 최소 날짜의 달부터 이번 달까지(최신 먼저). 데이터가 없으면 이번 달 하나.
  - `period` 파라미터가 null·형식 오류·미래 달이면 이번 달로 대체한다. `periods` 밖의 과거 달은 그대로 받아 0 으로 표시한다.

- [ ] **Step 1: 실패하는 서비스 테스트 작성** (`UsageSettlementServiceTest`, clock = 2026-11-25T10:00+09:00)

```kotlin
// closedMonth_readsMonthlyRows_andVerifiesAgainstAudit_match
// closedMonth_auditPayloadDiffers_mismatch:  usage_monthly 의 authSuccess 만 1 다르게 → MISMATCH
// closedMonth_noAuditRow_mismatch
// currentMonth_readsDaily_statusInProgress_verificationNotApplicable
// partialMonth_statusPartial
// tenantWithoutUsage_isListedWithZero
// usageForUnknownTenant_isListedById_andIncludedInTotal        (Review Focus 5)
//   sumByTenant 에 tenants 에 없는 id X (reg 3, auth 4) → 행 name == X.toString(), totalReg·totalAuth 에 포함
// trend_has12Months_newestFirst_missingMonthsAreZero
// trend_authRate_usesAttemptColumn:  authSuccess 90, authAttempt 100 → MonthRow 에 그대로 담김(계산은 화면)
```

- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*UsageSettlementServiceTest'` → 컴파일 실패.

- [ ] **Step 3: `UsageDto`, `UsageSettlementService`, `UsageSettlementController` 구현.** 서비스는 `@Transactional(readOnly = true)`.

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.

- [ ] **Step 5: 실패하는 화면 테스트 작성** (`UsageSettlementPageControllerTest`, 기존 `InsightsPageControllerTest` 의 구성과 로그인 도우미를 따른다)

```kotlin
// platformOperator_seesPage:  GET /admin/insights/usage → 200, 본문에 "사용량 정산", 선택 기간, 테넌트 행
// closedMonth_showsLabels:    "마감", "장부"(basis=LEDGER) 또는 "이전 방식"(LEGACY), "감사 로그와 일치"
// mismatch_showsWarning:      "감사 로그와 불일치"
// partial_showsLabel:         "부분(원본 삭제됨)"
// inProgress_showsLabel:      "진행 중"
// rpAdmin_pageRedirectsHome:  GET /admin/insights/usage → 3xx (기존 insights 와 같은 규칙)
// rpAdmin_csvForbidden:       GET /admin/insights/usage.csv → 403
// badPeriod_fallsBackToCurrentMonth:                           (Review Focus 4)
//   period=2026-13, period=abc, period=2099-01 각각 → 200, 선택 기간이 2026-11
// oldPeriodWithoutData_showsZeros:  period=2020-01 → 200, 합계 0
// csvTenants: 헤더 == "period,tenantId,slug,name,registrationSuccess,authenticationSuccess,total,basis,status,closedAt"
//             filename == "usage-settlement-2026-10.csv"
// csvTrend:   헤더 == "period,status,registrationSuccess,authenticationSuccess,activeTenants,authenticationAttempts,authSuccessRate"
//             filename == "usage-trend-2026-11-25.csv"
// csvUnknownKind: 기존 export 와 같은 오류(BusinessException ENTITY_NOT_FOUND)
```

`InsightsPageControllerTest` 에 추가: 현황 페이지 본문에 `청구 기준 아님 · 사용량 정산 탭 참조` 와 `/admin/insights/usage` 링크가 있다. `kind=monthly`·`kind=tenants` CSV 의 첫 줄(헤더)은 기존과 같다(외부에서 파싱할 수 있으므로 헤더는 바꾸지 않는다).

- [ ] **Step 6: 실패 확인** — `./gradlew :admin-app:test --tests '*insights.*'` → FAIL.

- [ ] **Step 7: 화면 구현.**
  - `UsageSettlementPageController` 는 `InsightsPageController` 와 같은 구조(REST 컨트롤러 호출, `me(model).platform` 검사, `CsvWriter`, `KstFormat`)를 따른다. CSV 의 `status` 값은 `IN_PROGRESS`/`CLOSED`/`PARTIAL`, `basis` 값은 `LEDGER`/`LEGACY`, `authSuccessRate` 는 기존 `rate4` 형식, 시도 0 이면 빈 칸.
  - `usage.html` 은 `page.html` 의 셸·카드·표 조각을 재사용한다. 구성: 탭 줄(`현황` | `사용량 정산`), 기간 선택(`periods`), 상태 줄(상태 · 집계 기준 · 검증 결과 · 기준 시각), 테넌트 표(테넌트, 등록 성공, 인증 성공, 합계, 맨 아래 전체), 월별 추이 표(월, 등록 성공, 인증 성공, 사용 테넌트, `인증 성공률(참고)`), CSV 버튼 두 개.
  - `page.html` 에 같은 탭 줄을 넣고, 월간 카드의 안내 문구 끝에 `청구 기준 아님 · 사용량 정산 탭 참조` 를 붙인다. 테넌트 비교 표의 CSV 버튼 옆에도 같은 문구를 둔다.
  - `InsightsPageController.export` 의 KDoc 에서 "(과금·라이선스 자료)" 를 "(운영 참고용. 청구 기준은 사용량 정산 탭)" 으로 고친다.
  - 사이드바는 바꾸지 않는다(현황 메뉴 아래 탭으로만 들어간다).

- [ ] **Step 8: 통과 확인** — `./gradlew :admin-app:test` → main 대비 새 실패 없음. 슬라이스 테스트가 새 빈 때문에 깨지면 MockBean 을 추가한다.

- [ ] **Step 9: 실제 화면 확인.** `docs/dev-setup.md` 대로 local 프로필로 admin-app 을 띄우고(`SPRING_PROFILES_ACTIVE=local`), `ceremony_event` 에 지난달·이번 달 성공 행을 몇 건 넣은 뒤 배치를 한 번 실행시켜 `/admin/insights/usage` 에서 진행 중인 달과 마감된 달을 각각 확인한다. alice(PLATFORM_OPERATOR)로 보이고 bob(RP_ADMIN)으로는 홈으로 돌아가는지 본다. 브라우저 조작은 ego-browser 만 쓴다.

- [ ] **Step 10: Commit** — `feat(admin): 사용량 정산 탭과 CSV`

---

## 마무리 확인 (전 Task 완료 후)

- [ ] `./gradlew :core:test :passkey-app:test :admin-app:test` 결과를 main 과 비교해 새 실패가 없음을 기록한다.
- [ ] 빈 스키마에 `scripts/apply-schema.sh` 를 적용해 두 앱이 뜨는지, 기존 스키마에 `scripts/upgrade/2026-11-25-usage-ledger.sql` 을 **두 번** 적용해도 오류가 없는지 확인한다.
- [ ] 스펙 6절(검증 기준)의 각 줄에 대응하는 테스트 이름을 PR 설명에 적는다.
- [ ] QA 번들 이미지는 passkey-app·admin-app 재빌드가 필요하다는 점을 `docs/backlog.md` 배포 전 항목에 적는다.
