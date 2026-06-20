# 전체 타임존 KST 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코드베이스 전반의 시간 타입을 `java.time.Instant`(UTC)에서 `java.time.OffsetDateTime`(+09:00 KST)으로 전면 전환하여, DB·자바·화면 모든 지점에서 한국 시간이 보이게 한다.

**Architecture:** 시각 생성의 단일 출처인 `Clock`을 `Asia/Seoul`로 바꾸고, 전 엔티티/서비스/DTO의 `Instant`를 `OffsetDateTime`으로 도메인 슬라이스별 치환한다. JWT·relay·TOTP는 epoch(절대시각) 기반이라 무변경, DB 세션 타임존은 커넥션 init SQL로 KST 고정한다. DB 컬럼은 `TIMESTAMP WITH TIME ZONE` 그대로 유지(DDL 변경 없음).

**Tech Stack:** Java 21 / Kotlin, Spring Boot, Hibernate(OracleDialect), Oracle `TIMESTAMP WITH TIME ZONE`, Flyway, JUnit5 + Testcontainers(Oracle), React/TypeScript(admin-ui).

## Global Constraints

- **표준 타입**: 시간 표현은 `OffsetDateTime`만 사용. `Instant`/`LocalDateTime`을 시간 표현 타입으로 신규 도입 금지.
- **시각 출처**: 모든 "지금"은 주입된 `Clock`에서 `OffsetDateTime.now(clock)`로. 인자 없는 `Instant.now()`/`OffsetDateTime.now()` 직접 호출 금지(엔티티 `@PrePersist`는 §T1에서 zone 상수 명시 처리).
- **Zone/Offset 상수**: `Asia/Seoul` zone, `+09:00` offset은 공용 유틸 `KstTime`에 한 곳 정의(매직스트링 산재 금지).
- **절대시각 비교**: 만료/TTL 비교는 `OffsetDateTime.isBefore/isAfter`. JWT `exp/iat/nbf`·relay·TOTP는 epoch(`toInstant().getEpochSecond()`)로만 직렬화/비교 — 벽시계 문자열 금지.
- **DB 컬럼 DDL 불변**: `TIMESTAMP WITH TIME ZONE` 유지. 기존 V1~V48 마이그레이션 수정 금지(Flyway 체크섬).
- **빌드 게이트**: `./gradlew build`는 pre-existing 실패(SliceConfig 충돌·Oracle 컨테이너 경합)로 머지 게이트로 쓰지 않음. 회귀 판정은 **모듈/클래스 단위 `test` 태스크**로, base(main) 대조로 "새로 깨진 것만" 식별.
- **데이터 리셋 전제**: 운영 실데이터 없음 → 백필 불필요. audit 체인은 리셋으로 형식 혼재 없음.

---

## File Structure (변경 맵)

- **신규**: `core/.../config/KstTime.java` — `ZONE`(`ZoneId`), `OFFSET`(`ZoneOffset`) 상수.
- **신규**: `core/src/main/resources/db/migration/V49__kst_session_timezone.sql` — 세션 TZ 정책 문서/주석(앱은 init SQL로 강제).
- **수정 (인프라)**: `CoreClockConfig.java`, `application-common.yml`, `application-test.yml`.
- **수정 (엔티티)**: `BaseEntity`, `ApiKey`, `AdminUser`, `AdminUserInvitation`, `AdminPasswordResetToken`, `AuditLog`, `SigningKey`, `Credential`, `SecurityPolicy`, `TenantAaguidPolicy`, `TenantWebauthnSnapshot`, `MdsBlobCache`, `SchedulerLease`, `AdminUserRecoveryCode`.
- **수정 (서비스/DTO/Repo/Controller)**: 엔티티 getter 타입을 따라가는 ~60개 파일 — 도메인 슬라이스별로 컴파일 연쇄 해소.
- **수정 (프론트)**: 변경 없음 예상. 회귀 검증만(8개 변환 지점).

각 슬라이스는 "엔티티 타입 치환 → 컴파일 연쇄 해소 → 해당 모듈 테스트 그린 → 커밋"으로 독립 완결된다.

---

## Task 1: 인프라 — KstTime 상수 + Clock + Hibernate + 세션 TZ

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/config/KstTime.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/config/CoreClockConfig.java:11-13`
- Modify: `core/src/main/resources/application-common.yml:12` (`hibernate.jdbc.time_zone`) + datasource hikari 블록(`connection-init-sql` 추가)
- Modify: `core/src/test/resources/application-test.yml:25`
- Create: `core/src/main/resources/db/migration/V49__kst_session_timezone.sql`
- Test: `core/src/test/java/com/crosscert/passkey/core/config/CoreClockConfigTest.java`

**Interfaces:**
- Produces: `KstTime.ZONE` (`java.time.ZoneId` = `Asia/Seoul`), `KstTime.OFFSET` (`java.time.ZoneOffset` = `+09:00`). 이후 모든 태스크가 `OffsetDateTime.now(clock)` 및 zone 상수로 이 둘을 참조.
- Produces: `Clock clock()` 빈이 `Asia/Seoul` zone 기반.

- [ ] **Step 1: Write the failing test**

`CoreClockConfigTest.java`:
```java
package com.crosscert.passkey.core.config;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class CoreClockConfigTest {

    @Test
    void clockUsesAsiaSeoulZone() {
        Clock clock = new CoreClockConfig().clock();
        assertThat(clock.getZone()).isEqualTo(KstTime.ZONE);
    }

    @Test
    void offsetDateTimeNowHasPlus9Offset() {
        Clock clock = new CoreClockConfig().clock();
        OffsetDateTime now = OffsetDateTime.now(clock);
        assertThat(now.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.config.CoreClockConfigTest'`
Expected: 컴파일 실패 (`KstTime` 미존재) 또는 `clockUsesAsiaSeoulZone` FAIL (현재 `systemUTC`).

- [ ] **Step 3: Create KstTime + update CoreClockConfig**

`KstTime.java`:
```java
package com.crosscert.passkey.core.config;

import java.time.ZoneId;
import java.time.ZoneOffset;

/** 프로젝트 단일 기준 타임존(KST). 한국 전용·서머타임 없음 → 고정 +09:00. */
public final class KstTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);
    private KstTime() {}
}
```

`CoreClockConfig.java` (line 6-13 영역):
```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CoreClockConfig {
    @Bean
    public Clock clock() {
        return Clock.system(KstTime.ZONE);
    }
}
```

- [ ] **Step 4: Update hibernate + session TZ config**

`application-common.yml`: line 12 `hibernate.jdbc.time_zone: UTC` → `hibernate.jdbc.time_zone: Asia/Seoul`. datasource.hikari 블록에 추가:
```yaml
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      pool-name: passkey-pool
      connection-init-sql: "ALTER SESSION SET TIME_ZONE='Asia/Seoul'"
```

`application-test.yml`: line 25 `hibernate.jdbc.time_zone: UTC` → `hibernate.jdbc.time_zone: Asia/Seoul`.

- [ ] **Step 5: Create V49 migration**

`V49__kst_session_timezone.sql`:
```sql
-- KST 전환: 앱 커넥션은 HikariCP connection-init-sql 로
-- ALTER SESSION SET TIME_ZONE='Asia/Seoul' 를 강제한다(application-common.yml).
-- 이 마이그레이션은 정책을 명시적으로 기록하기 위한 no-op 주석 + 검증용 SELECT.
-- SYSTIMESTAMP DEFAULT 컬럼들은 세션 TIME_ZONE 을 따르므로 별도 DDL 변경 불필요.
SELECT 1 FROM dual;
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.config.CoreClockConfigTest'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/config/KstTime.java \
        core/src/main/java/com/crosscert/passkey/core/config/CoreClockConfig.java \
        core/src/test/java/com/crosscert/passkey/core/config/CoreClockConfigTest.java \
        core/src/main/resources/application-common.yml \
        core/src/test/resources/application-test.yml \
        core/src/main/resources/db/migration/V49__kst_session_timezone.sql
git commit -m "feat(core): KST 기준 타임존 인프라 (Clock=Asia/Seoul, hibernate TZ, 세션 init SQL, KstTime 상수)"
```

---

## Task 2: BaseEntity 슬라이스 (`createdAt`/`updatedAt` → OffsetDateTime)

**Files:**
- Modify: `core/.../entity/BaseEntity.java:12,36-56`
- Test: `core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityTimestampTest.java`

**Interfaces:**
- Consumes: `KstTime.ZONE` (Task 1).
- Produces: `BaseEntity.getCreatedAt()` / `getUpdatedAt()` 반환 타입 `OffsetDateTime`. 모든 BaseEntity 상속 엔티티(Tenant, Credential, AdminUser, ApiKey, SecurityPolicy, TenantAaguidPolicy, TenantWebauthnSnapshot, SigningKey 등)의 timestamp getter가 이를 따른다.

- [ ] **Step 1: Write the failing test**

`BaseEntityTimestampTest.java`:
```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityTimestampTest {

    static class Sample extends BaseEntity {}

    @Test
    void prePersistSetsKstOffsetTimestamps() {
        Sample s = new Sample();
        s.onCreate(); // @PrePersist 콜백 직접 호출
        OffsetDateTime created = s.getCreatedAt();
        assertThat(created).isNotNull();
        assertThat(created.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(s.getUpdatedAt()).isEqualTo(created);
    }

    @Test
    void preUpdateAdvancesUpdatedAt() {
        Sample s = new Sample();
        s.onCreate();
        s.onUpdate();
        assertThat(s.getUpdatedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }
}
```

(주: `onCreate()`/`onUpdate()`를 테스트에서 호출할 수 있도록 `protected` 유지 — 동일 패키지 테스트라 접근 가능.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.entity.BaseEntityTimestampTest'`
Expected: 컴파일 실패 (getter가 `Instant` 반환) 또는 offset 단언 FAIL.

- [ ] **Step 3: Modify BaseEntity**

`BaseEntity.java` — import와 필드/콜백/getter 치환:
```java
import com.crosscert.passkey.core.config.KstTime;
import java.time.OffsetDateTime;
```
```java
    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(KstTime.ZONE);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(KstTime.ZONE);
    }

    public UUID getId() { return id; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
```
(`import java.time.Instant;` 제거.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.entity.BaseEntityTimestampTest'`
Expected: PASS

- [ ] **Step 5: Resolve compile chain in :core**

Run: `./gradlew :core:compileJava`
Expected: BaseEntity getter를 `Instant`로 받던 곳에서 컴파일 에러. 에러 목록의 각 파일에서 `Instant` → `OffsetDateTime`으로 지역 변수/필드/시그니처 치환(값 로직 불변). 다시 `compileJava`가 통과할 때까지 반복.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(core): BaseEntity createdAt/updatedAt Instant→OffsetDateTime(+09:00)"
```

---

## Task 3: ApiKey 슬라이스 (만료 판정 — CRITICAL #4)

**Files:**
- Modify: `core/.../entity/ApiKey.java:8,36-97`
- Modify (연쇄): `core/.../repository/ApiKeyRepository.java`, `admin-app/.../apikey/ApiKeyAdminService.java`, `admin-app/.../apikey/ApiKeyAdminDto.java`, `admin-app/.../tenant/TenantAdminService.java`, `admin-app/.../tenant/TenantLifecycleService.java`

> **실행 정정 (Task 3 완료 후, 3-검토자 합의):** plan이 원래 연쇄 대상으로 적은 `passkey-app/.../security/ApiKeyLookupService.java`·`ApiKeyAuthFilter.java`는 **ApiKey 엔티티에 결합되지 않음** — V8 PL/SQL 패키지 + 로컬 `ApiKeyAuthRow` record + JDBC `Timestamp`/`Instant` 경로라 컴파일러가 강제하지 않는다(`:passkey-app:compileJava` GREEN 유지). `Timestamp.toInstant()`는 offset-무관 절대시각이라 CRITICAL #4 enforcement는 정상. **이 두 파일의 `Instant`는 Task 8에서 별도 판단**(엔티티 전환과 무관하므로 유지해도 안전; 통일하려면 ApiKeyAuthRow record만 손대면 됨).
- Test: `core/src/test/java/com/crosscert/passkey/core/entity/ApiKeyExpiryTest.java`

**Interfaces:**
- Consumes: `KstTime`, `Clock` (Task 1).
- Produces: `ApiKey.isActive(OffsetDateTime now)`, `getLastUsedAt()/getExpiresAt()/getRevokedAt()` → `OffsetDateTime`, `touchLastUsed(OffsetDateTime)`, `revoke(OffsetDateTime)`, `expireAt(OffsetDateTime)`.

- [ ] **Step 1: Write the failing test**

`ApiKeyExpiryTest.java`:
```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyExpiryTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void activeWhenExpiresInFuture() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "pk_live", "hash", "k");
        key.expireAt(NOW.plusHours(1));
        assertThat(key.isActive(NOW)).isTrue();
    }

    @Test
    void inactiveWhenExpired() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "pk_live", "hash", "k");
        key.expireAt(NOW.minusSeconds(1));
        assertThat(key.isActive(NOW)).isFalse();
    }

    @Test
    void inactiveWhenRevoked() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "pk_live", "hash", "k");
        key.revoke(NOW);
        assertThat(key.isActive(NOW.plusHours(1))).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.entity.ApiKeyExpiryTest'`
Expected: 컴파일 실패 (`isActive(Instant)` 시그니처 불일치).

- [ ] **Step 3: Modify ApiKey**

`ApiKey.java` — `import java.time.Instant;` → `import java.time.OffsetDateTime;`. 필드 `lastUsedAt/expiresAt/revokedAt` 타입 `OffsetDateTime`. getter 3개 반환 타입 `OffsetDateTime`. 메서드 시그니처 치환(로직 동일):
```java
    public boolean isActive(OffsetDateTime now) {
        if (revokedAt != null) return false;
        if (expiresAt != null && !expiresAt.isAfter(now)) return false;
        return true;
    }

    public void touchLastUsed(OffsetDateTime now) { this.lastUsedAt = now; }
    public void revoke(OffsetDateTime now) { this.revokedAt = now; }
    public void expireAt(OffsetDateTime when) { this.expiresAt = when; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.entity.ApiKeyExpiryTest'`
Expected: PASS

- [ ] **Step 5: Resolve compile chain (core + admin + passkey)**

각 호출처에서 `clock.instant()` → `OffsetDateTime.now(clock)`, `Instant` 변수/파라미터 → `OffsetDateTime`로 치환:
```bash
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava
```
Expected: ApiKey 관련 컴파일 에러가 위 6개 파일에 집중. 전부 해소될 때까지 반복. `ApiKeyAdminService.issue`의 `now.plusMonths(...)` 류는 `OffsetDateTime`에서 동일 API라 그대로 동작.

- [ ] **Step 6: Run affected module tests**

Run: `./gradlew :core:test --tests '*ApiKey*' :admin-app:test --tests '*ApiKey*' :passkey-app:test --tests '*ApiKey*'`
Expected: PASS (또는 base 대비 신규 실패 없음).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(apikey): ApiKey 만료/사용시각 Instant→OffsetDateTime (CRITICAL #4)"
```

---

## Task 4: Invitation/Reset Token 슬라이스 (만료 — CRITICAL #5, Clock 미주입 메서드 수정)

**Files:**
- Modify: `core/.../entity/AdminUserInvitation.java:7,42-128`
- Modify: `core/.../entity/AdminPasswordResetToken.java`
- Modify (연쇄): `admin-app/.../operator/InvitationService.java`, `admin-app/.../operator/PasswordResetService.java`, `admin-app/.../operator/AdminUserService.java`, 관련 Repository/DTO
- Test: `core/src/test/java/com/crosscert/passkey/core/entity/AdminUserInvitationExpiryTest.java`

**Interfaces:**
- Consumes: `KstTime` (Task 1).
- Produces: `AdminUserInvitation` 생성자 파라미터 `OffsetDateTime expiresAt`, `getCreatedAt/getExpiresAt/getAcceptedAt/getResentAt` → `OffsetDateTime`, `isPending(OffsetDateTime now)`, `isExpired(OffsetDateTime now)`, `accept(OffsetDateTime now)`, `recordResend(OffsetDateTime)`, `markRevoked(OffsetDateTime)`.

**중요(원칙 2 위반 교정):** 현재 `isPending()`/`isExpired()`/`accept()`는 인자 없는 `Instant.now()`를 내부 호출한다. 이를 **`now` 파라미터를 받도록 시그니처를 바꿔** 시각 출처를 호출자(Clock 보유 서비스)로 이동시킨다.

- [ ] **Step 1: Write the failing test**

`AdminUserInvitationExpiryTest.java`:
```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AdminUserInvitationExpiryTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    private AdminUserInvitation newInvitation(OffsetDateTime expiresAt) {
        return new AdminUserInvitation(UUID.randomUUID(), "hash", "prefix12", "admin@x", expiresAt);
    }

    @Test
    void pendingWhenNotAcceptedAndNotExpired() {
        AdminUserInvitation inv = newInvitation(NOW.plusHours(1));
        assertThat(inv.isPending(NOW)).isTrue();
        assertThat(inv.isExpired(NOW)).isFalse();
    }

    @Test
    void expiredWhenPastExpiry() {
        AdminUserInvitation inv = newInvitation(NOW.minusSeconds(1));
        assertThat(inv.isExpired(NOW)).isTrue();
        assertThat(inv.isPending(NOW)).isFalse();
    }

    @Test
    void acceptStampsAcceptedAt() {
        AdminUserInvitation inv = newInvitation(NOW.plusHours(1));
        inv.accept(NOW);
        assertThat(inv.getAcceptedAt()).isEqualTo(NOW);
        assertThat(inv.isAccepted()).isTrue();
    }

    @Test
    void markRevokedForcesPastExpiry() {
        AdminUserInvitation inv = newInvitation(NOW.plusHours(1));
        inv.markRevoked(NOW);
        assertThat(inv.isExpired(NOW)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.entity.AdminUserInvitationExpiryTest'`
Expected: 컴파일 실패 (`isPending()` 인자 없음 / 타입 불일치).

- [ ] **Step 3: Modify AdminUserInvitation**

`import java.time.Instant;` → `import java.time.OffsetDateTime;`. 필드 `createdAt/expiresAt/acceptedAt/resentAt` → `OffsetDateTime`. 생성자 파라미터 `OffsetDateTime expiresAt`, 생성자 내 `this.createdAt = OffsetDateTime.now(KstTime.ZONE);` (import `com.crosscert.passkey.core.config.KstTime`). getter 4개 반환 타입 변경. 비즈니스 헬퍼 시그니처 변경:
```java
    public boolean isPending(OffsetDateTime now) { return acceptedAt == null && now.isBefore(expiresAt); }
    public boolean isExpired(OffsetDateTime now) { return now.isAfter(expiresAt); }
    public boolean isAccepted() { return acceptedAt != null; }
    public void accept(OffsetDateTime now) { this.acceptedAt = now; }
    public void recordResend(OffsetDateTime now) { this.resentCount++; this.resentAt = now; }
    public void markRevoked(OffsetDateTime now) { this.expiresAt = now.minusSeconds(1); }
```
setter(`setAcceptedAt`/`setResentAt`) 파라미터 타입도 `OffsetDateTime`.

`AdminPasswordResetToken.java`: 동일 패턴 — `isExpired(OffsetDateTime now)` 시그니처, timestamp 필드/getter → `OffsetDateTime`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.entity.AdminUserInvitationExpiryTest'`
Expected: PASS

- [ ] **Step 5: Resolve compile chain (admin-app)**

호출처(`InvitationService`/`PasswordResetService`/`AdminUserService`)에서 인자 없이 부르던 `inv.isPending()`/`isExpired()`/`accept()`를 `inv.isPending(OffsetDateTime.now(clock))` 식으로 Clock 경유 호출로 교체. `Instant` 변수 → `OffsetDateTime`.
```bash
./gradlew :core:compileJava :admin-app:compileJava
```
Expected: 컴파일 통과까지 반복.

- [ ] **Step 6: Run affected tests**

Run: `./gradlew :admin-app:test --tests '*Invitation*' --tests '*PasswordReset*' --tests '*RecoveryCode*'`
Expected: PASS (base 대비 신규 실패 없음).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(invitation): 초대·리셋토큰 만료를 OffsetDateTime+Clock 주입으로 (CRITICAL #5)"
```

---

## Task 5: Audit 슬라이스 (HMAC 체인 — CRITICAL #1)

**Files:**
- Modify: `core/.../entity/AuditLog.java`
- Modify: `admin-app/.../audit/AuditLogService.java:131-190` (`computeHash` 시그니처, `now` 타입)
- Modify (연쇄): `admin-app/.../audit/AuditChainMonitorController.java`, `AuditLogController.java`, `AuditChainOverview.java`, audit 체인 verifier, `core/.../repository/AuditLogRepository.java`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditHashKstTest.java`

**Interfaces:**
- Consumes: `Clock` (Task 1).
- Produces: `computeHash(byte[] prevHash, AuditAppendRequest req, String payloadJson, OffsetDateTime now)`. `AuditLog` 생성자/`getCreatedAt` 등 timestamp → `OffsetDateTime`.

**핵심:** `now.toString()`이 HMAC 입력. `OffsetDateTime.toString()`은 `2026-06-20T18:00:00+09:00` 형식 → 새 체인 일관. 마이크로초 truncate 보존을 위해 `clock` 기반 `OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)` 사용.

- [ ] **Step 1: Write the failing test**

`AuditHashKstTest.java`:
```java
package com.crosscert.passkey.admin.audit;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AuditHashKstTest {

    @Test
    void hashIncludesPlus9OffsetTimestampDeterministically() {
        OffsetDateTime now =
                OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));
        AuditAppendRequest req = new AuditAppendRequest(
                UUID.randomUUID(), "a@x", "TENANT_CREATE",
                "TENANT", "t1", UUID.randomUUID(), java.util.Map.of("k", "v"));

        byte[] h1 = AuditLogService.computeHash(null, req, "{\"k\":\"v\"}", now);
        byte[] h2 = AuditLogService.computeHash(null, req, "{\"k\":\"v\"}", now);

        assertThat(h1).isEqualTo(h2);          // 결정적
        assertThat(now.toString()).contains("+09:00"); // KST 형식 확인
    }
}
```
(주: `AuditAppendRequest` 실제 생성자 시그니처는 Step 2 컴파일 에러로 확인 후 맞춘다 — 필드 순서가 다르면 조정.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.audit.AuditHashKstTest'`
Expected: 컴파일 실패 (`computeHash`가 `Instant` 파라미터).

- [ ] **Step 3: Modify AuditLog + AuditLogService**

`AuditLog.java`: timestamp 필드/생성자 파라미터/getter `Instant` → `OffsetDateTime`.

`AuditLogService.java`:
- line 131: `Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);` → `OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);`
- `computeHash` 시그니처: `OffsetDateTime now`. line 181 `input.append(now.toString());` 그대로(이제 `+09:00` 형식).
- `import java.time.Instant;` → `import java.time.OffsetDateTime;` (필요 시 둘 다 정리).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.audit.AuditHashKstTest'`
Expected: PASS

- [ ] **Step 5: Resolve compile chain + chain verifier**

verifier가 DB에서 읽은 `OffsetDateTime`으로 `computeHash` 재계산하도록 타입 일치 확인.
```bash
./gradlew :core:compileJava :admin-app:compileJava
```
Expected: 통과까지 반복.

- [ ] **Step 6: Run audit IT (Testcontainers)**

Run: `./gradlew :admin-app:test --tests '*Audit*'`
Expected: 체인 append→verify 라운드트립 PASS. 실패 시 micros truncate / toString 형식 점검.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(audit): HMAC 체인 타임스탬프 OffsetDateTime(+09:00) 일관화 (CRITICAL #1)"
```

---

## Task 6: License 슬라이스 검증 (CRITICAL #2 — 무변경 확인 위주)

**Files:**
- Inspect/Modify: `core/.../license/LicenseVerifier.java:74-82`, `LicenseStateMachine.java`, `LicenseToken.java`, `LicenseCache.java`, `LicenseController.java`, `LicenseView.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/license/LicenseVerifierKstTest.java`

**Interfaces:**
- Consumes: `Clock` (Task 1).
- Produces: 변경 없음(검증). `LicenseVerifier`의 `nbf/exp` 비교는 JWT `Date.toInstant()` 절대시각 비교라 KST Clock에서도 정확.

**근거:** `LicenseVerifier:74`의 `clock.instant()`는 `Date.toInstant()`(epoch)와 비교 → offset 무관. **이 비교 로직은 건드리지 않는다.** `LicenseStateMachine`이 `OffsetDateTime` 기반 엔티티 필드와 비교하는 경우만 타입 정렬.

- [ ] **Step 1: Write the failing/guard test**

`LicenseVerifierKstTest.java`:
```java
package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;

class LicenseVerifierKstTest {

    @Test
    void clockInstantIsZoneIndependentForExpiry() {
        // 동일 절대시각을 UTC/KST 두 Clock 으로 생성 → instant() 동일해야 함
        Instant fixed = Instant.parse("2026-06-20T09:00:00Z");
        Clock utc = Clock.fixed(fixed, ZoneId.of("UTC"));
        Clock kst = Clock.fixed(fixed, ZoneId.of("Asia/Seoul"));
        assertThat(utc.instant()).isEqualTo(kst.instant());
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.license.LicenseVerifierKstTest'`
Expected: PASS (이미 성립 — 가드 테스트로 회귀 방지 고정).

- [ ] **Step 3: Resolve compile chain**

```bash
./gradlew :core:compileJava :admin-app:compileJava
```
Expected: License 관련 엔티티 timestamp 타입 변경 연쇄만 해소. `LicenseVerifier:74-82` 비교 로직은 수정하지 않음.

- [ ] **Step 4: Run license tests**

Run: `./gradlew :core:test --tests '*License*' :admin-app:test --tests '*License*'`
Expected: base 대비 신규 실패 없음.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(license): 엔티티 타임스탬프 타입 정렬 + KST Clock 만료 비교 불변 가드 (CRITICAL #2)"
```

---

## Task 7: 나머지 엔티티 슬라이스 (SigningKey/Credential/SecurityPolicy/MDS/Scheduler/RecoveryCode 등)

**Files:**
- Modify: `core/.../entity/SigningKey.java`, `Credential.java`, `SecurityPolicy.java`, `TenantAaguidPolicy.java`, `TenantWebauthnSnapshot.java`, `MdsBlobCache.java`, `SchedulerLease.java`, `AdminUser.java`, `AdminUserRecoveryCode.java`
- Modify (연쇄): 대응 Repository/Service/DTO/View (mds, keymgmt, policy, scheduler, credential, tenant 패키지)
- Test: 각 엔티티의 timestamp getter 타입 검증 1개씩 (예: `SigningKeyTimestampTest`)

**Interfaces:**
- Consumes: `KstTime`, `Clock` (Task 1).
- Produces: 해당 엔티티 timestamp/만료 getter·메서드 → `OffsetDateTime`.

이 태스크는 만료 비즈니스 로직이 없는(또는 단순한) 엔티티들의 일괄 타입 치환이다. SchedulerLease(만료 판정 있음)는 `isExpired`/`expiresAt` 비교를 `OffsetDateTime`으로.

> **Task 4 에서 넘어온 검증 항목 (실행 시 반드시 확인):**
> 1. **RetentionPurgeService `Instant cutoff` (안전 — 지금 버그 아님):** `RetentionPurgeService.purgeInvitations(Instant cutoff)`·`purgeResetTokens(Instant cutoff)` + repo native `deleteConsumedOrExpiredBefore(@Param Instant cutoff)` 가 이제 `OffsetDateTime` 매핑 컬럼에 `Instant` 를 바인딩한다. 대상 컬럼은 `TIMESTAMP WITH TIME ZONE`(V29)라 `col < :cutoff` 는 **절대시각 비교 → 지금도 올바른 행을 선택**한다(write-path 가 아닌 read-side라 selection 불변). 따라서 **열린 버그가 아니며**, 타입/스타일 일관성 목적으로만 `Instant cutoff` → `OffsetDateTime` 통일. `RetentionPurgeJob` 의 `clock.instant()` 도 `OffsetDateTime.now(clock)` 로.
> 2. **ctor self-source 분기 점검 (codex P2 패턴):** self-source `.now()` 를 쓰는 생성자/`@PrePersist`(SchedulerLease·MdsBlobCache 직접 구현, 그 외 ctor 에서 stamp 하는 엔티티)에서, **caller 가 별도 `now` 로 만료/관련 시각을 만드는데 ctor 가 createdAt 류를 따로 self-source 하면 시각 출처가 분기**된다(한 레코드의 createdAt 과 expiresAt 이 다른 순간 → fixed-clock 비결정성, 극단적으로 createdAt > expiresAt). 이런 분기가 있으면 Task 4 처럼 caller 의 단일 `now` 를 ctor 로 주입해 통일할 것. zone-상수 self-source 가 허용되는 건 caller 가 동일 작업에서 다른 시각을 만들지 않는 순수 `@PrePersist` 경우뿐.

- [ ] **Step 1: Write one guard test per expiry-bearing entity**

예 — `core/src/test/java/com/crosscert/passkey/core/entity/SchedulerLeaseExpiryTest.java`:
```java
package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class SchedulerLeaseExpiryTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void getExpiresAtReturnsOffsetDateTime() {
        SchedulerLease lease = new SchedulerLease("mds-sync", "node-1", NOW.plusMinutes(5));
        assertThat(lease.getExpiresAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }
}
```
(주: `SchedulerLease` 생성자 실제 시그니처는 컴파일 에러로 확인 후 맞춘다.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests 'com.crosscert.passkey.core.entity.SchedulerLeaseExpiryTest'`
Expected: 컴파일 실패 (`OffsetDateTime` 반환 아님).

- [ ] **Step 3: Modify entities**

각 엔티티 파일에서 `import java.time.Instant;` → `import java.time.OffsetDateTime;`, timestamp/만료 필드·getter·메서드 파라미터를 `OffsetDateTime`으로 치환(로직 불변). `@PrePersist` 직접 구현 엔티티(MdsBlobCache, SchedulerLease)는 `OffsetDateTime.now(KstTime.ZONE)` 사용.

- [ ] **Step 4: Run guard tests**

Run: `./gradlew :core:test --tests '*TimestampTest' --tests '*ExpiryTest'`
Expected: PASS

- [ ] **Step 5: Resolve full compile chain (all modules)**

```bash
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava :rp-app:compileKotlin :sdk-java:compileKotlin
```
Expected: 잔여 `Instant` 사용처(repository 쿼리 파라미터, DTO, view, kotlin) 전부 `OffsetDateTime`으로. Kotlin 파일(`RegRelayCodec.kt`, `InMemoryUserStore.kt`, `RpAppUser.kt`, sdk idtoken)은 epoch 기반이면 무변경, 엔티티 타입 따라가는 곳만 변경. 통과까지 반복.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(entity): 잔여 엔티티 타임스탬프 Instant→OffsetDateTime 일괄 전환"
```

---

## Task 8: 전 모듈 컴파일·테스트 그린화 + 테스트 코드 KST 정렬

**Files:**
- Modify: `**/src/test/**` 중 `Clock.fixed(..., ZoneOffset.UTC)` / 하드코딩 `Instant` 단언 / `"Z"` suffix 가정 테스트
- Test: 전체 테스트 스위트

**Interfaces:**
- Consumes: Task 1~7 전부.

- [ ] **Step 1: 전 모듈 컴파일**

Run: `./gradlew :core:compileTestJava :admin-app:compileTestJava :passkey-app:compileTestJava :rp-app:compileTestKotlin :sdk-java:compileTestKotlin`
Expected: 테스트 컴파일 에러 목록 = 수정 대상.

- [ ] **Step 2: 테스트 코드 정렬**

각 에러 파일에서:
- `Clock.fixed(Instant.parse("...Z"), ZoneOffset.UTC)` → `Clock.fixed(Instant.parse("...Z"), ZoneId.of("Asia/Seoul"))` (기준 instant 불변, zone만 KST).
- `Instant` 비교 단언 → `OffsetDateTime` 비교 또는 `.toInstant()` 비교.
- JSON 응답의 `"...Z"` 기대 → `"+09:00"` 형식으로.
다시 컴파일 통과까지 반복.

- [ ] **Step 3: base(main) 대조 기준선 확보**

Run (base worktree에서): `git -C /Users/jhyun/Git/10-work/crosscert/Passkey2 stash list` 후 main 기준 모듈별 테스트 결과를 기준선으로 기록. (메모리: 슬라이스 `SecurityPolicyService` 누락·core `ORA-01031`은 pre-existing.)

- [ ] **Step 4: 모듈별 테스트 실행 (KST worktree)**

Run: `./gradlew :core:test`
Run: `./gradlew :admin-app:test`
Run: `./gradlew :passkey-app:test`
Run: `./gradlew :rp-app:test :sdk-java:test`
Expected: base 대비 **신규 실패 0건**. 신규 실패가 있으면 해당 테스트가 시간 형식/타입 가정을 잘못 들고 있는지 점검 후 수정.

> **HARD GATE — Audit IT round-trip (CRITICAL #1, Task 5 에서 이월):** Task 5 의 no-DB 하니스는 in-JVM hash/seed 로직만 증명했고, **`OffsetDateTime` → Oracle `TIMESTAMP WITH TIME ZONE` 저장 → Hibernate read-back 이 byte-identical `toString()`(동일 +09:00, 동일 micros, 동일 trailing-zero 생략)을 내는지**는 실행된 적 없다. 이게 audit 체인 검증의 진짜 위험(read-back 정밀도/offset 불일치 시 false tamper alarm). 따라서 admin-app 이 컴파일되는 이 시점에 **Testcontainers audit IT 를 반드시 실행해 통과를 확인**해야 한다:
> Run: `./gradlew :admin-app:test --tests '*Audit*'` (특히 append→store→read→verify roundtrip IT: AuditChainVerifierTest / AuditChainBackfillIT / per-tenant chain IT). **이 IT 들이 GREEN 이어야 Task 5(CRITICAL #1)가 진짜 완료**이며, epic 종료(Task 9)의 선결 조건이다. RED 면 hibernate.jdbc.time_zone/세션 TZ/@TimeZoneStorage 설정과 micros 정밀도부터 점검.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: KST 전환에 맞춰 Clock fixed zone + 타임스탬프 단언 정렬"
```

---

## Task 9: 실DB 부팅 + DBeaver 육안 검증 (최종 증거)

**Files:** 변경 없음(검증 태스크). 필요 시 dev seed 확인.

- [ ] **Step 1: dev 프로파일로 로컬 부팅**

메모리 절차 준수: `SPRING_PROFILES_ACTIVE=local`(또는 dev) env, APP_OWNER 접속, Oracle/Redis 컨테이너 기동, flyway 소문자 식별자, 필요 시 V8 repair. admin-app 부팅.
Run: `SPRING_PROFILES_ACTIVE=local ./gradlew :admin-app:bootRun` (또는 프로젝트 표준 기동 스크립트)
Expected: V49 포함 Flyway 적용 성공, 부팅 완료.

- [ ] **Step 2: 신규 row 생성 후 DB 직접 조회**

테넌트 생성/감사 로그 발생 등으로 신규 row 생성. DBeaver(또는 sqlcl)로 `SELECT created_at FROM app_owner.tenant ...` 조회.
Expected: `created_at`이 **KST(+09:00)** 로 표시 (예: `2026-06-20 18:00:00 +09:00`), UTC 아님. 세션 init SQL이 적용되어 `SYSTIMESTAMP` 기반 컬럼도 KST.

- [ ] **Step 3: admin-ui 화면 회귀 확인 (8개 지점)**

admin-ui에서 Tenant 목록/상세, Credential, ApiKey 만료일, Audit 시각, AdminUsers 표시를 확인.
Expected: 이중변환 없이 정확한 한국 시간 표시(미래 18시간 어긋남 등 없음).

- [ ] **Step 4: 검증 결과를 plan 하단에 기록 후 commit**

```bash
git add -A
git commit -m "docs(plan): KST 전환 실DB·UI 육안 검증 결과 기록" --allow-empty
```

---

## Self-Review (작성자 점검 결과)

- **Spec coverage**: 설계 §2 원칙(4) → Global Constraints. §3.1 Clock/§3.3 hibernate/§3.6 세션TZ·V49 → Task 1. §3.2 엔티티 → Task 2~7. §3.4 audit → Task 5. §3.5 @PrePersist(a) → Task 2(zone 상수). §3.7 license/relay → Task 6(license 무변경 가드), relay/TOTP는 epoch라 Task 7/8 컴파일 연쇄에서만 접촉. §3.8 프론트 → Task 9 회귀. §5 검증 → Task 8·9. CRITICAL 5종 → Task 3/4/5/6 + relay(Task 7/8). 누락 없음.
- **Placeholder scan**: 코드 블록 모두 실제 코드. 일부 생성자 시그니처는 "컴파일 에러로 확인 후 맞춘다"로 명시(실제 시그니처 의존이라 불가피) — 실행자가 따를 절차를 제공함.
- **Type consistency**: `OffsetDateTime` 일관. `isActive(OffsetDateTime)`, `isPending/isExpired(OffsetDateTime)`, `computeHash(..., OffsetDateTime)`, `KstTime.ZONE/OFFSET` 명칭 전 태스크 일치.
