# Phase 6 — UUID Migration Design

**Date:** 2026-05-26
**Status:** Design (awaiting plan)
**Predecessor:** Phase 5 (admin UI redesign) — tag `phase-5-admin-ui-redesign-complete`

## 1. Goal

전체 8개 JPA entity의 primary key를 **UUID v1 time-ordered (RAW(16) 저장)** 으로 통일한다. (Hibernate 6.6 `@UuidGenerator(Style.TIME)`이 v1을 생성한다 — RFC 9562 v7과 운영 특성 동일하지만 표준 이름은 v1. MAC 노출은 Hibernate가 random node를 사용하므로 없음.) 운영자 가독성을 잃지 않기 위해 `Tenant`는 별도 `slug` 컬럼을 추가해 URL/API contract를 보존하고, `SchedulerLease`는 `name` UNIQUE 컬럼으로 의미 있는 lookup을 유지한다.

본 Phase는 **운영 데이터 없음 (dev/staging만, prod 미배포)** 전제로 진행한다 → V19 migration은 DROP + 재생성 우선 전략. ALTER 기반 backfill 미포함.

## 2. Why UUID, Why Now

장기 운영 관점에서 다음 4가지 요건 중 하나 이상 충족 시 UUID 통일이 정당화된다:

1. **보안 컴플라이언스** (ISMS-P, SOC 2 등) — "ID predictability" finding 회피
2. **외부 RP 통합 데이터 호환** — 시스템 간 ID 충돌 방지
3. **운영 데이터 없음** — backfill 비용 0인 적기
4. **분산/멀티 region 운영 가능성**

사용자가 "조건 중 하나 이상 충족"을 확인하고 사용자 결정에 따라 본 Phase 진행. Phase 4 RP migration guide가 이미 RP 측 client에 한 차례 변경을 안내했으므로, **API contract가 더 굳기 전인 현 시점이 RP 영향 최소화 측면에서도 합리적**이다.

## 3. Architecture

### 3.1 핵심 설계 결정 (확정)

| 항목 | 결정 |
|------|------|
| UUID 버전 | **UUID v1 time-ordered** (Hibernate `Style.TIME`, RFC 4122. random node mode → MAC 미노출. v7과 동일한 시간 정렬/B-tree 친화성) |
| Hibernate 매핑 | `@UuidGenerator(style = UuidGenerator.Style.TIME)` |
| Oracle 저장 타입 | **`RAW(16)`** |
| 매핑 어노테이션 | `@JdbcTypeCode(SqlTypes.UUID)` (Hibernate 6 native) |
| 생성 위치 | 애플리케이션 (entity 인스턴스화 시점) |
| Tenant 식별 전략 | UUID PK + `slug` VARCHAR2(64) UNIQUE 컬럼 추가. 운영자 URL은 slug 기반 |
| SchedulerLease 식별 전략 | UUID PK + `name` VARCHAR2(64) UNIQUE 컬럼. service는 `findByName()` lookup |
| Migration 전략 | **DROP + 재생성 우선** (운영 데이터 없음 전제) |
| 기존 Flyway 버전 처리 | V1~V18은 idempotent하게 유지, V19에서 모든 PK 컬럼 + FK 재작성 |
| Sequence 객체 | 6개 SEQUENCE 모두 DROP |
| RP API contract | `/admin/api/tenants/{slug}` 유지 (`acme`, `globex` 그대로). 내부 FK는 UUID |
| JWT cred_id claim | UUID base64url 인코딩 (16 bytes → 22 chars unpadded) |
| 운영 데이터 backfill | 없음 (조건상 데이터 0) |

### 3.2 전체 흐름

```
Application (Java)
  └─ Entity 생성 시: @UuidGenerator(TIME) → UUID v1 time-ordered 즉시 발급 (DB 왕복 0)
       │
       ▼
Hibernate 6
  └─ @JdbcTypeCode(SqlTypes.UUID) → java.util.UUID ↔ RAW(16) 자동 변환
       │
       ▼
Oracle 19c
  └─ RAW(16) PK + FK / VPD policy / unique slug / unique lease name
```

### 3.3 ID 타입 매트릭스 (Before → After)

| Entity | Before | After |
|--------|--------|-------|
| AdminUser | `Long id` + ADMIN_USER_SEQ | `UUID id` (v7) |
| ApiKey | `Long id` + API_KEY_SEQ | `UUID id` (v7) |
| AuditLog | `Long id` + AUDIT_LOG_SEQ | `UUID id` (v7) |
| Credential | `Long id` + CREDENTIAL_SEQ | `UUID id` (v7) |
| MdsBlobCache | `Long id` + MDS_BLOB_CACHE_SEQ (singleton) | `UUID id` (singleton seed) |
| SchedulerLease | `String id` (= name) | `UUID id` + `name VARCHAR2(64) UNIQUE` |
| SigningKey | `Long id` + SIGNING_KEY_SEQ | `UUID id` (v7) |
| Tenant | `String id` (= slug) | `UUID id` + `slug VARCHAR2(64) UNIQUE` |

### 3.4 FK 컬럼 영향

다음 FK가 `BIGINT/VARCHAR2` → `RAW(16)` 변경:

- `api_key.tenant_id` (VARCHAR2 → RAW(16))
- `credential.tenant_id` (VARCHAR2 → RAW(16))
- `audit_log.actor_id` (BIGINT → RAW(16), AdminUser.id 참조)

### 3.5 VPD policy 영향

`bootstrap-vpd.sql`의 핵심:

```sql
-- Before
SYS_CONTEXT('PASSKEY', 'TENANT_ID')   -- VARCHAR2 비교
WHERE tenant_id = SYS_CONTEXT(...)

-- After
SYS_CONTEXT('PASSKEY', 'TENANT_ID')   -- RAW(16) hex string
WHERE tenant_id = HEXTORAW(SYS_CONTEXT(...))
```

TenantContextHolder는 `UUID tenantId` 보유 + JDBC session에 `HEX(uuid)` 형식으로 set.

## 4. Component Inventory

### 4.1 신규 Flyway migration

**`core/src/main/resources/db/migration/V19__uuid_migration.sql`**

DROP + 재생성 흐름 (운영 데이터 없음 전제):
1. 모든 FK 제약 DROP
2. 모든 SEQUENCE DROP (6개)
3. 모든 테이블 DROP (Flyway가 V1~V18로 만든 것 전체)
4. 모든 테이블 RAW(16) PK로 재생성
5. Tenant.slug VARCHAR2(64) UNIQUE 컬럼 추가
6. SchedulerLease.name VARCHAR2(64) UNIQUE 컬럼 추가
7. FK 재생성 (RAW(16))
8. 함수 기반 unique index 재생성 (signing_key one_active_uix 등)
9. mds_blob_cache 싱글톤 seed row (id=새 UUID, version=0)
10. APP_RUNTIME GRANTs 재부여
11. (V11 등에서 만든 admin_user seed 2명도 V19에서 재seed — `alice@crosscert.com`, `bob@crosscert.com`)

`signing_key_bootstrap_pkg` 재작성은 별도 task T18에서 진행 — PL/SQL 변경은 SigningKey entity migration과 함께 통합 검증.

**`bootstrap-vpd.sql`** 갱신:
- VPD policy function: tenant_id 비교 시 `HEXTORAW`
- `CTX_PKG.SET_TENANT(uuid_hex VARCHAR2)` 파라미터 형식 명시

### 4.2 신규 / 변경 Java

**`core/src/main/java/com/crosscert/passkey/core/entity/`** — 8 entities REWRITE:

각 entity 패턴:
```java
@Entity
@Table(name = "...")
public class AdminUser {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;
    // ... 나머지 필드
}
```

- AdminUser, ApiKey, AuditLog, Credential, MdsBlobCache, SigningKey: UUID PK
- Tenant: UUID PK + `private String slug; @Column(unique=true)`
- SchedulerLease: UUID PK + `private String name; @Column(unique=true)`

**Repository changes:**
- `JpaRepository<E, Long>` → `JpaRepository<E, UUID>` (6개)
- `JpaRepository<Tenant, String>` → `JpaRepository<Tenant, UUID>` + `Optional<Tenant> findBySlug(String slug)`
- `JpaRepository<SchedulerLease, String>` → `JpaRepository<SchedulerLease, UUID>` + `Optional<SchedulerLease> findByName(String name)`

**TenantContextHolder + DevTenantHeaderFilter:**
- `String tenantId` → `UUID tenantId`
- `TenantAwareDataSource`가 JDBC session에 hex string 형태로 set (Oracle SYS_CONTEXT는 VARCHAR2)

**Service changes:**
- `TenantAdminService.get(String idOrSlug)` → `findBySlug(slug)` 우선, fallback to `findById(uuid)` (URL이 slug 또는 UUID 둘 다 받음 — backward compat)
- `KeyRotationService` 등 — 내부 id 비교를 UUID로
- `SchedulerLeaseService.tryAcquire(name, ...)` → `findByName(name)` 후 UUID 처리

**JWT cred_id claim:**
- `IdTokenIssuer`: Long → UUID. base64url 인코딩 (16 bytes → 22 chars unpadded base64url)
- `cred_id` claim spec: `b64url(uuid bytes)` 22자 문자열

### 4.3 Test 영향

8개 entity unit test + 10+ controller security test + 5+ IT 모두 ID assertion 영향:

- `42L` 같은 Long literal → `UUID.fromString("...")` 또는 fixture에서 생성된 UUID 캡처
- `"acme"` 같은 Tenant slug literal → 보존 (URL은 slug 유지)
- `Fido2EndToEndIT`의 cred_id claim 검증 → UUID base64url 디코드 후 비교
- `VpdIsolationIT` — RAW(16) tenantId 처리 검증

### 4.4 admin-ui 영향

**거의 없음.** 이유:
- TypeScript에서 UUID는 `string` 타입 — 기존 `id: string` 인터페이스 그대로 사용
- API contract: Tenant URL은 slug 기반 유지 → URL 패턴 무변경
- Phase 4 client.ts envelope unwrap layer + Phase 5 디자인 그대로

유일한 변경:
- `TenantView.id`는 이제 UUID string (`"550e8400-..."`) — admin-ui에서 디스플레이 시 slug 우선 표시 (`displayName ?? slug ?? id`)
- `SigningKeyView.kid`는 그대로 (kid는 SHA-256 thumbprint, UUID 아님)
- `AuditLogView.actorId`는 UUID — UI에서는 actorEmail 우선 표시 (이미 Phase 5에서 actorEmail 컬럼 사용 중)

## 5. Migration order

```
T1.  V19 Flyway migration (DROP + 재생성, RAW(16) PK 전체)
T2.  bootstrap-vpd.sql 갱신 (HEXTORAW VPD policy + CTX_PKG.SET_TENANT 파라미터)
T3.  Hibernate UUID configuration helper + AbstractEntity 시조사 검토
T4.  TenantContextHolder + DevTenantHeaderFilter — UUID tenantId
T5.  TenantAwareDataSource — JDBC session set tenant_id (hex string)

T6.  AdminUser entity + repository (Long → UUID)
T7.  ApiKey entity + repository + service (tenant_id FK 갱신)
T8.  AuditLog entity + repository (actor_id, target_id UUID 처리, AuditChainVerifier UUID 입력)
T9.  Credential entity + repository (tenant_id FK)
T10. MdsBlobCache entity + V19 싱글톤 seed
T11. SchedulerLease entity + repository (name unique + findByName)
T12. SigningKey entity + repository + JwksAssembler (kid는 변경 없음)
T13. Tenant entity + repository (slug unique + findBySlug)

T14. TenantAdminService — slug 기반 URL resolver, backward-compat lookup
T15. KeyRotationService + KeyExpirationJob — UUID id 비교
T16. SchedulerLeaseService — name → UUID id 변환 (findByName 후 UUID 사용)
T17. IdTokenIssuer — cred_id UUID base64url 인코딩
T18. signing_key_bootstrap_pkg (PL/SQL) — UUID 파라미터로

T19. Repository / Service / Controller tests — UUID fixture 패턴
T20. AdminFlowIT / Fido2EndToEndIT / KeyRotationIT / MdsSchedulerIT — UUID assertion
T21. VpdIsolationIT — RAW(16) tenantId 검증

T22. RP migration guide v2 발행 (cred_id 형식 변경 안내)
T23. DoD verify + tag
```

각 task는 자체적으로 build green 유지. Schema 변경(T1) + 첫 entity migration(T6) 묶음이 가장 큰 cliff — 빠르게 통과시켜야 함.

## 6. Testing strategy

### Unit
- Entity 단위 테스트: UUID 자동 발급 검증 (`@UuidGenerator(TIME)`)
- Repository 테스트: `findById(UUID)`, `findBySlug(String)`, `findByName(String)` 동작
- `IdTokenIssuer`의 cred_id base64url 인코딩 round-trip

### Integration
- **VpdIsolationIT** — 가장 결정적. tenant A의 RAW(16) UUID로 set → tenant B 데이터 INSERT 시도 거부 검증
- **AdminFlowIT** — 11-step e2e, slug 기반 URL + UUID FK 검증
- **Fido2EndToEndIT** — 등록/인증 ceremony + cred_id claim UUID 디코드 검증
- **KeyRotationIT** — UUID PK 회전 시나리오
- **MdsSchedulerIT** — singleton seed row UUID 처리

### Manual smoke (DoD)
- admin-ui 6 페이지 클릭 — Tenant slug 기반 URL 동작 확인
- 신규 Tenant 생성 → slug + UUID 모두 정상
- API key 발급 → 새 UUID
- Key rotation → S001 conflict 시 envelope 정상
- audit 로그 actorId가 UUID로 표시 (또는 actorEmail로 대체)
- ⌘K command palette 동작 (URL path 변경 없음)

## 7. Risks & mitigations

| 위험 | 완화 |
|------|------|
| **VPD policy 회귀** (RAW(16) 비교 실패 시 cross-tenant 누수) | VpdIsolationIT가 즉시 catch. T2에서 명시적 HEXTORAW 검증 |
| **Hibernate 6 UUID 매핑 시행착오** | T3에서 sample entity로 매핑 검증 후 T6~T13 일괄 적용 |
| **운영 데이터 손실 가정 깨짐** | 사용자 확인 완료 ("데이터 없음"). Testcontainers는 매번 fresh → 영향 없음 |
| **cred_id JWT claim 형식 변경 → RP 검증 코드 깨짐** | RP migration guide v2 발행. cred_id는 opaque이라 RP가 strict 검증 안 하는 게 일반적이지만 명시 |
| **Tenant slug 운영자 실수** (예: `acme` 중복 입력) | UNIQUE 제약 + TenantCreate 폼에서 사전 중복 체크 (이미 Phase 4 TENANT_DUPLICATE 패턴 존재) |
| **Sequence 객체 DROP 후 rollback 불가** | V19 reversible 설계 — V20 down 작성 (DROP은 reversible 안 됨) → 본 Phase는 forward-only 가정. dev/staging fresh start로 회복 |
| **150+ 테스트 ID assertion 수정 누락** | TDD per task — 각 entity migration 후 즉시 관련 테스트 통과 확인 |
| **TenantContextHolder UUID로 변경 시 cached String 잔재** | T4에서 grep으로 모든 `String tenantId` 사용처 찾아 교체 |
| **JwksController.kid 혼동** (kid != UUID) | kid는 SHA-256 thumbprint 그대로 — UUID 아님. T12에서 명시적으로 분리 |
| **admin-ui type 변경 누락** | TypeScript `string` 타입이 UUID 문자열도 수용 → 코드 변경 없음. 단, slug 우선 표시 로직 검토 (T13 service 변경에 종속) |

## 8. Out-of-scope (의도적 제외)

- **운영 데이터 backfill** — 본 Phase 전제: 데이터 없음
- **timestamps 추상화 (BaseEntity createdAt/updatedAt)** — 별도 Phase로 미룸. UUID 마이그레이션과 분리해 ROI 명확화
- **UUID v4/v8 등 다른 변형** — v7 단일
- **CHAR(36) 저장** — RAW(16) 단일
- **ULID** — 표준 UUID 우선
- **다중 region 인프라** — 본 Phase는 single region 가정
- **Multi-region active-active replication** — 미래
- **`admin_user.id` JWT claim 통합 (single sign-on across services)** — 별도
- **Existing kid (SHA-256 thumbprint) → UUID 변환** — kid는 그대로 유지 (RP가 의존하는 contract)
- **RP API v2 endpoint 신설** — 본 Phase는 v1 contract에 slug 보존으로 호환 유지

## 9. Plan task 예상 수

23 tasks:
- T1~T5 Foundation (migration + VPD + Hibernate + context): 5
- T6~T13 Entity migrations: 8
- T14~T18 Service/Controller adaptation: 5
- T19~T21 Test migrations: 3
- T22 RP guide v2: 1
- T23 DoD + tag: 1

Phase 4 (13) / Phase 5 (16) 보다 약간 크지만 동일 범주. Schema + 모든 entity가 묶여 있어 task 의존성이 강함 — sequential execution 권장 (parallel subagent 부적합).
