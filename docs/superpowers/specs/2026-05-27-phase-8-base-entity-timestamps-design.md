# Phase 8: BaseEntity 추출 + updated_at 필수화 + admin-ui KST 표시

## 1. 목적과 배경

Phase 7까지 11개 JPA entity가 동일한 UUID PK 보일러플레이트 (`@UuidGenerator(Style.TIME)` + `@JdbcTypeCode(SqlTypes.UUID)` + `RAW(16)`)를 독립적으로 선언했고, `created_at` 컬럼이 6+ entity에 중복 선언되어 있으며, `updated_at`은 `Tenant` 하나에만 존재했다.

Phase 8은 두 가지를 묶어 처리한다:

1. **`@MappedSuperclass BaseEntity` 도입**: UUID PK + `created_at` + `updated_at` + `@PrePersist`/`@PreUpdate` 라이프사이클 콜백을 한 곳에 모아 11개 entity 모두 상속하게 한다. 사용자 요구 "모든 entity에 updated_at 필수"를 달성하는 가장 자연스러운 방법이다.
2. **운영 UI(admin-ui) KST 표시 정책**: 저장은 UTC `Instant` (기존 정책 유지), 표시는 `Asia/Seoul`로 변환. 백엔드 API 계약은 변경 없음 — RP 클라이언트 영향 없음.

부수 효과로 보일러플레이트 약 150줄 감소, 시간 처리 일관성 확보, 향후 audit 컬럼 (`created_by`/`updated_by`) 도입 시 단일 지점 확장 가능성.

## 2. 범위와 비범위

### 2.1 In Scope

- 새 `@MappedSuperclass com.crosscert.passkey.core.entity.BaseEntity`
- 11개 entity (`AdminUser`, `ApiKey`, `ApiKeyScope`, `AuditLog`, `Credential`, `MdsBlobCache`, `SchedulerLease`, `SigningKey`, `Tenant`, `TenantAcceptedFormat`, `TenantAllowedOrigin`) 모두 BaseEntity 상속
- `Tenant.touchUpdatedAt()` 수동 호출 제거 (`@PreUpdate`가 자동 처리)
- V22 Flyway migration: `updated_at` 컬럼 신규 추가 (Tenant 제외), child 테이블 3개에 `created_at`+`updated_at` 신규 추가, `mds_blob_cache`/`scheduler_lease`에 둘 다 추가
- `admin-ui/src/lib/formatDateTime.ts` 유틸 신규
- admin-ui의 모든 timestamp 표시 위치 KST 포맷으로 일괄 교체

### 2.2 Out of Scope

- DB 컬럼 타입 변경 (`TIMESTAMP WITH TIME ZONE` 그대로)
- Java 타입 변경 (`Instant` 그대로 — `LocalDateTime` / `OffsetDateTime` 도입 안 함)
- `TenantScopedEntity` 등 2단계 계층 (Tenant FK 일관성 정리는 별도 Phase)
- Status enum 통일 (`AdminUser.enabledFlag`, `SigningKey.status`, `Tenant.status` 패턴 정리는 별도 Phase)
- `created_by` / `updated_by` audit 컬럼 도입
- 백엔드 응답 단계의 `@JsonFormat(timezone=...)` 적용 — 프론트엔드 렌더링에서만 변환
- passkey-app(RP API) 응답 포맷 변경 — Instant ISO-8601 Z 그대로
- Tenant entity에 `timezone` 컬럼 추가 (테넌트별 TZ 지원은 Phase 9 후보)

### 2.3 운영 데이터

dev/staging 환경 전제. 운영 데이터 백필 절차는 없으며 Flyway `DEFAULT SYSTIMESTAMP NOT NULL`이 기존 row 자동 채움.

## 3. 설계 결정

### 3.1 Java 타입: `Instant` 유지

DB 컬럼이 `TIMESTAMP WITH TIME ZONE`이고 `application-common.yml`에 `hibernate.jdbc.time_zone: UTC`가 이미 설정되어 있다. V1.sql 주석이 명시적으로 "TIMESTAMP WITH TIME ZONE so values are unambiguous across server timezones"라고 의도를 표명. `Instant`는 이 정책의 정확한 Java 표현이다.

`LocalDateTime`은 TZ 정보 손실로 부적합. `OffsetDateTime`은 입력 시점 TZ 보존이 의미 있을 때만 유용한데, `created_at`/`updated_at`은 시스템이 기록하는 절대 시점이므로 offset을 보존할 도메인적 이유가 없다. 또한 9개 entity가 이미 `Instant`라 전환 시 entity + DTO + Service + JSON + 테스트 전반에 ripple effect가 크다.

### 3.2 자동 갱신 메커니즘: JPA `@PrePersist` + `@PreUpdate`

JPA 표준 라이프사이클 콜백 사용. Hibernate `@CreationTimestamp` / `@UpdateTimestamp`는 DB 시계 기준이라 JVM 시계와 미세한 차이 가능성. JPA 콜백은 JVM `Instant.now()` 기준이라 결정적이고 테스트 친화적이다.

Oracle ON UPDATE trigger는 Oracle이 표준 구문을 지원하지 않아 BEFORE UPDATE trigger 10개를 작성해야 하고, JPA가 갱신된 값을 즉시 알기 위해 `@Generated(ALWAYS)` 추가가 필요해 복잡도가 높다.

### 3.3 계층 구조: 단일 BaseEntity

`BaseEntity` 하나에 UUID PK + `createdAt` + `updatedAt` + 콜백 모두 포함. 두 단계 계층(`BaseEntity` → `AuditedEntity`)은 사용자 요구 "모든 entity에 updated_at 필수"와 명시적으로 충돌. 3단계 계층(`TenantScopedEntity` 포함)은 ApiKey/Credential의 raw `tenant_id`와 child 테이블의 `@ManyToOne Tenant` 불일치를 함께 해결해야 해서 Phase 8 범위를 넘는다.

자식 테이블(`TenantAllowedOrigin` 등)이 실질적으로 immutable이라도 BaseEntity를 상속해 `updated_at`을 가진다. 값은 항상 `created_at`과 같아질 뿐이며, 일관성 우선.

`AuditLog`도 BaseEntity를 상속. append-only 의도는 보장되지만 `updated_at` 컬럼이 존재. 값은 항상 `created_at`과 동일.

### 3.4 특수 entity 처리: `MdsBlobCache`, `SchedulerLease`

#### MdsBlobCache

현재 `@Id @JdbcTypeCode(SqlTypes.UUID) UUID id` (fixed, generator 없음). BaseEntity 상속 시 `@UuidGenerator(Style.TIME)`가 적용된다. 기존 row의 PK는 변경 없이 유지되며 (`@UuidGenerator`는 null PK에만 동작), 신규 row만 TIME-ordered UUID 사용.

`fetched_at`은 도메인 의미(MDS BLOB을 마지막으로 fetch한 시점)이므로 별도 컬럼으로 유지. `created_at`/`updated_at`은 BaseEntity가 제공.

#### SchedulerLease

현재 `@Id UUID id` (generator 없음, 호출자가 UUID 생성). BaseEntity 상속으로 TIME generator 적용 + `created_at`/`updated_at` 추가. `expires_at`은 lease 도메인 컬럼이므로 유지.

기존 lease row의 PK는 그대로 보존. 신규 lease만 TIME generator 적용.

### 3.5 Migration 전략: SYSTIMESTAMP 백필

V22 ALTER TABLE 시 `DEFAULT SYSTIMESTAMP NOT NULL`로 기존 row를 migration 실행 시점으로 자동 채움. dev/staging 데이터 손실 없음. Phase 6 UUID migration과 동일한 패턴.

### 3.6 admin-ui TZ 적용 위치: 프론트엔드 렌더링

백엔드는 `Instant` ISO-8601 UTC(`...Z`) 그대로 응답. admin-ui에서 `Date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })`로 표시 시 변환.

백엔드에서 `@JsonFormat(timezone="Asia/Seoul")`을 적용하지 않는 이유:
- DTO 5+ 곳에 같은 어노테이션 산포
- 향후 다른 지역 운영자 추가 시 백엔드 변경 필요 (확장성 저하)
- RP 클라이언트(passkey-app)는 영향받지 않아야 하는데 같은 DTO를 공유하면 부수 효과 위험
- 프론트엔드 단일 유틸 함수로 처리하면 변경 지점 1개

## 4. 컴포넌트 설계

### 4.1 BaseEntity

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

설계 노트:
- `setId`는 제공하지 않음 (JPA가 제어)
- `setCreatedAt`/`setUpdatedAt`도 제공하지 않음 — 테스트 fixture에서 직접 값을 세팅할 일이 있으면 reflection으로 처리 (현재 코드베이스에 그런 패턴 없음으로 추정, 작업 중 확인)
- `equals`/`hashCode` 정의하지 않음 — 자식 entity가 자연키 기반 override 시 충돌 방지
- `@PrePersist` 내부의 null-check는 안전망. 생성자에서 명시적으로 설정해도 OK이지만 자식 entity가 잊어버려도 동작하도록 함

### 4.2 11개 entity의 변경 패턴

#### Group A — 단순 상속 (5 entity)

`AdminUser`, `ApiKey`, `AuditLog`, `Credential`, `SigningKey`.

변경 전:
```java
@Entity
public class ApiKey {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;
    // ...
    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;
    // ...
    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
}
```

변경 후:
```java
@Entity
public class ApiKey extends BaseEntity {
    // PK + createdAt 선언 삭제, BaseEntity가 제공
    // updatedAt도 자동
    // 도메인 컬럼 (last_used_at, expires_at, revoked_at)은 유지
}
```

`getId()`, `getCreatedAt()`, `getUpdatedAt()`는 BaseEntity가 제공하므로 자식 클래스에서 삭제.

#### Group B — child 테이블 (3 entity, Phase 7 신규)

`ApiKeyScope`, `TenantAcceptedFormat`, `TenantAllowedOrigin`.

현재 PK + 부모 FK + 도메인 컬럼만 있고 timestamp 없음. BaseEntity 상속으로 `created_at`+`updated_at` 자동 획득. V22에서 두 컬럼 신규 추가.

equals/hashCode가 자연키(tenant.id + value) 기반인데 BaseEntity가 정의하지 않으므로 충돌 없음. 단 BaseEntity의 `getId()`와 자식 클래스의 자연키 비교가 의미적으로 다르다는 점을 유지.

#### Group C — Tenant (1 entity)

이미 PK + createdAt + updatedAt 보유 + 수동 `touchUpdatedAt()` 메서드.

변경:
- BaseEntity 상속으로 PK + createdAt + updatedAt 선언 삭제
- 생성자에서 `Instant now = Instant.now(); this.createdAt = now; this.updatedAt = now;` 삭제 (BaseEntity `@PrePersist`가 처리)
- `touchUpdatedAt()` public 메서드 삭제 (`@PreUpdate`가 자동)
- 호출처 일괄 제거 (`TenantAdminService` 등)

#### Group D — MdsBlobCache, SchedulerLease

PK strategy를 BaseEntity 표준(`@UuidGenerator(Style.TIME)`)에 맞춤. 기존 row PK는 보존.

각각 도메인 컬럼(`fetched_at`, `expires_at`)은 유지, `created_at`/`updated_at`은 BaseEntity가 제공.

### 4.3 V22 Flyway Migration

```sql
-- V22__base_entity_timestamps.sql
-- Adds created_at/updated_at columns to align all entity tables with
-- the new BaseEntity superclass (Phase 8). DB columns use TIMESTAMP
-- WITH TIME ZONE; JPA maps to java.time.Instant; hibernate.jdbc.time_zone
-- remains UTC. Existing rows are backfilled with SYSTIMESTAMP at
-- migration runtime (dev/staging premise).

-- ── Group B (Phase 7 child tables): create_at + updated_at both new ──
ALTER TABLE app_owner.tenant_allowed_origin ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE app_owner.tenant_accepted_format ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE app_owner.api_key_scope ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- ── Group A: updated_at new (created_at already exists) ──
ALTER TABLE app_owner.admin_user ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE app_owner.api_key ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE app_owner.credential ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE app_owner.audit_log ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE app_owner.signing_key ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- ── Group D: created_at + updated_at both new ──
ALTER TABLE app_owner.mds_blob_cache ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
ALTER TABLE app_owner.scheduler_lease ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- Tenant: no change (created_at + updated_at already exist)
```

Migration 특징:
- `DEFAULT SYSTIMESTAMP NOT NULL` — 기존 row 자동 백필
- VPD 정책에 영향 없음 (timestamp 컬럼은 policy predicate에 포함되지 않음)
- ADD COLUMN은 Oracle에서 metadata-only (full table scan 없음)
- Rollback 미제공 (dev/staging 전제)

### 4.4 admin-ui formatDateTime 유틸

`admin-ui/src/lib/formatDateTime.ts`:

```typescript
const FORMATTER = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (isNaN(date.getTime())) return iso;
  return FORMATTER.format(date);
}
```

설계 노트:
- `Intl.DateTimeFormat` 인스턴스를 모듈 레벨에서 한 번 생성 (재사용)
- null/undefined → em-dash 표시 (운영 UI에서 빈 시간 명시)
- 파싱 실패 시 원본 ISO 그대로 (안전망)
- 출력 예: `2026. 05. 27. 12:00:00`

사용처 일괄 교체:
- `TenantList.tsx`: createdAt 표시
- `ApiKeyList.tsx`: createdAt, lastUsedAt, expiresAt, revokedAt 표시
- `AdminUserList.tsx` (있으면): createdAt
- `AuditLogList.tsx` (있으면): createdAt
- `TenantDetail.tsx`: createdAt, updatedAt

작업 중 전체 grep으로 `createdAt|updatedAt|expiresAt|lastUsedAt|revokedAt|rotatedAt|fetchedAt` 표시 위치 식별 후 교체.

## 5. 데이터 흐름

### 5.1 신규 entity 생성 흐름

1. Application: `new ApiKey(...)` → `entityManager.persist(apiKey)` (또는 `repository.save(apiKey)`)
2. Hibernate: `@PrePersist` 호출 → BaseEntity.onCreate() 실행 → createdAt + updatedAt = Instant.now()
3. flush → INSERT문 발행 → DB는 `TSTZ` 컬럼에 UTC 저장 (`hibernate.jdbc.time_zone: UTC`)
4. 응답 시 Jackson: `Instant` → ISO-8601 UTC 문자열 (`"2026-05-27T03:00:00Z"`)
5. admin-ui: `formatDateTime("2026-05-27T03:00:00Z")` → `"2026. 05. 27. 12:00:00"` (KST)

### 5.2 기존 entity 수정 흐름

1. Application: `tenant.setDisplayName("...")` → `repository.save(tenant)` 또는 트랜잭션 커밋
2. Hibernate: `@PreUpdate` 호출 → BaseEntity.onUpdate() 실행 → updatedAt = Instant.now()
3. flush → UPDATE문 발행
4. 응답 시 변경된 `updatedAt`을 포함한 DTO 직렬화

### 5.3 IT 환경에서의 동작

기존 IT들이 사용하는 패턴:
- `repository.saveAndFlush(entity)` — `@PrePersist` 호출 보장
- `entityManager.persist(entity); entityManager.flush()` — 동일
- `@Transactional` 내부에서 `save()` 후 트랜잭션 커밋 — `@PrePersist` 호출 보장

문제될 수 있는 패턴 (있으면 작업 중 수정):
- `repository.save(entity)` 후 `flush()` 없이 즉시 entity의 `createdAt`/`updatedAt` 조회 — null일 수 있음. 안전망(생성자에서 초기화)으로 대응

## 6. 테스트 전략

### 6.1 신규 Unit Test

`core/src/test/java/com/crosscert/passkey/core/entity/BaseEntityTest.java`:

- `@PrePersist 시 createdAt/updatedAt이 동일 시각으로 설정된다`
- `@PreUpdate 시 updatedAt만 갱신되고 createdAt은 불변이다`
- `UUID PK가 TIME-ordered로 자동 생성된다` (생성 순서대로 정렬되는지 검증)
- `생성자에서 명시적으로 값을 설정해도 @PrePersist는 덮어쓰지 않는다`

테스트는 Tenant 같은 구체 entity 통해서 가능. BaseEntity 자체는 추상이라 직접 테스트 못함.

### 6.2 기존 ITs 회귀 검증

- `AdminFlowIT`: tenant + admin-user + api-key 생성 → 모두 `createdAt`/`updatedAt` 자동 채워지는지
- `Fido2EndToEndIT`: credential 생성 + sign_count 갱신 시 `updatedAt`이 진행하는지
- `VpdIsolationIT`: VPD predicate에 timestamp 영향 없음 검증 (negative test: 정책에 안 잡혀야 함)
- `TenantAdminServiceTest`: tenant update 시 `updatedAt`이 자동 갱신되는지 (기존 touchUpdatedAt 호출 코드 삭제 후)
- `MdsSchedulerIT`: scheduler_lease 생성 시 `createdAt`/`updatedAt` 채워지는지

### 6.3 admin-ui 검증

- `formatDateTime` 단위 테스트: ISO 입력 → KST 출력 검증, null → em-dash, invalid → 원본
- 수동 smoke: tenant 생성 → list 화면에서 KST 시각 표시 확인

## 7. 위험과 완화

| 위험 | 영향 | 완화 |
|---|---|---|
| `repository.save()` 후 `flush()` 없이 createdAt/updatedAt 조회 시 null | 테스트 실패 또는 NPE | (a) BaseEntity 생성자에서도 안전 초기값 설정 (b) 작업 중 `save\(.*\).*get(Created\|Updated)At` 패턴 grep |
| Tenant.touchUpdatedAt() 호출 누락 | Tenant 수정 시 updatedAt 안 갱신 → 회귀 | T8에서 grep으로 모든 호출처 식별 + 제거, IT로 검증 |
| MdsBlobCache PK strategy 변경 시 기존 row 호환성 | 기존 캐시 row 무효화 가능성 | `@UuidGenerator`는 null PK에만 동작 — 기존 row는 PK 유지. 신규만 TIME generator |
| SchedulerLease PK generator 신규 도입 시 호출자 코드가 명시적으로 UUID 생성 중일 가능성 | 두 PK가 충돌 가능 | T11에서 호출자 코드 grep, 명시적 UUID 생성 제거 또는 그대로 둠 (둘 다 호환) |
| 11개 entity 동시 변경 → 컴파일 에러 누적 | 작업 흐름 차단 | BaseEntity (T1) → V22 (T2) → entity 1개씩 (T3-T11) 순차 적용. 각 task별 commit |
| admin-ui formatDateTime 누락 위치 | 일부 페이지가 raw ISO 표시 | T-final에서 grep `createdAt\|updatedAt\|expiresAt\|...` 일괄 검증 |
| Hibernate가 `@MappedSuperclass`의 @PrePersist를 인식 못 하는 경우 | 컬럼 null로 INSERT 실패 | JPA spec에 명시된 표준 동작. Hibernate 6.6+ 이상에서 검증됨. 단위 테스트에서 확인 |
| 자식 entity의 equals/hashCode가 BaseEntity의 id를 사용한다고 잘못 가정 | 자연키 비교 깨짐 | BaseEntity가 equals/hashCode 정의 안 함. 자식의 자연키 비교 그대로 동작 |
| `mds_blob_cache.fetched_at`과 `BaseEntity.updated_at`의 의미 중복 | 운영 혼선 | fetched_at은 BLOB fetch 도메인 이벤트, updated_at은 row 변경 일반. 의미 다름. 둘 다 유지 |
| 운영 UI 외 admin API 응답을 RP가 소비할 가능성 | TZ 변환 안 일어남 | 백엔드는 항상 UTC ISO-8601 응답. RP 측은 자기 TZ로 변환 (RP 책임). admin-ui만 KST 표시 |

## 8. 검증 기준 (DoD)

- BaseEntity가 존재하고 11개 entity 모두 상속
- V22 migration이 적용되어 모든 테이블이 created_at/updated_at 컬럼 보유
- 전체 단위 테스트 + IT 통과 (`./gradlew clean build` SUCCESS)
- `BaseEntityTest` 신규 추가, 모든 콜백 시나리오 검증
- `Tenant.touchUpdatedAt()` 호출처 0개 (grep)
- admin-ui에 `formatDateTime` 유틸 존재 + 모든 timestamp 표시 위치에서 사용
- 수동 smoke: tenant 생성 → admin-ui list에서 KST 시각 표시
- VPD IT 통과 (timestamp 컬럼이 정책에 영향 없음 재확인)
- `phase-8-base-entity-timestamps-complete` 태그 생성

## 9. 후속 (Phase 9+ 후보)

- **TenantScopedEntity 추출 + Tenant FK 일관성 정리**: ApiKey/Credential의 raw `tenant_id` ↔ child 테이블의 `@ManyToOne Tenant` 불일치 해소
- **Status enum 통일**: `AdminUser.enabled_flag` / `SigningKey.status` / `Tenant.status` 패턴 통합
- **`created_by` / `updated_by` audit 컬럼**: BaseEntity에 sealed extension으로 추가
- **Tenant.timezone 컬럼**: 테넌트별 TZ 설정 (운영 UI가 테넌트 TZ로 표시)
- **AuditedRevisionEntity**: append-only entity (AuditLog 등) immutability를 JPA 레벨에서 강제
