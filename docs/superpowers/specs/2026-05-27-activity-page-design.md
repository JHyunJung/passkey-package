# Activity 페이지 + audit_log.tenant_id 도입 설계 문서

작성일: 2026-05-27
상태: 검토 대기
선행: Phase 0~8 + sdk-and-sample-rp + admin-tenant-detail + admin-role-separation 완료
관련: Passkey Admin Console 디자인의 PLATFORM_OPERATOR Activity 메뉴 + RP_ADMIN Audit 탭

## 1. 배경과 목표

Passkey Admin Console 디자인의 PLATFORM_OPERATOR 메뉴 중 'Activity' (cross-tenant 활동 피드 + KPI + Top 5) 가 미구현. admin-role-separation phase 에서 `/admin/api/audit` 가 PLATFORM_OPERATOR 전용으로 차단된 것도 audit_log 에 tenant_id 가 없어서. 이번 phase 가 두 가지를 동시에 해결:

1. PLATFORM_OPERATOR 의 Activity 페이지 — 디자인 fidelity 회복
2. RP_ADMIN 의 audit 접근 회복 — TenantDetail 5번째 탭 'Activity'

audit_log 에 tenant_id 컬럼 추가가 둘 다의 공통 인프라.

### 1.1 Definition of Done

1. **DB (V24)**: `audit_log.tenant_id RAW(16) NULL` 컬럼 + 인덱스 `(tenant_id, created_at)`. 기존 row 는 NULL (backfill 없음). 권한 변경 없음. hash 입력 포맷 변경 없음
2. **AuditLog entity**: `tenantId UUID nullable` 필드 + getter. 9-arg constructor
3. **AuditAppendRequest record**: `tenantId UUID nullable` 추가
4. **AuditLogService.append()**: tenantId 를 entity 에 저장. hash 계산엔 미포함 (V10 chain 그대로)
5. **10 곳의 append() 호출 갱신**:
   - TENANT_CREATE / TENANT_UPDATE / CREDENTIAL_REVOKE / API_KEY_ISSUE / API_KEY_REVOKE → tenantId 명시
   - ADMIN_LOGIN / ADMIN_LOGIN_FAILED → null (platform-wide)
   - SIGNING_KEY_ROTATE / SIGNING_KEY_REVOKE / MDS_BLOB_SYNC → null
6. **Activity API**: 단일 endpoint `GET /admin/api/activity` (PLATFORM_OPERATOR only)
   - 응답: `{ kpi, top5, feed }`
   - `?sinceId=` query 로 incremental polling
   - `?category=` query 로 chip filter (all/ops/security)
   - `kpi.p95Ms = null` (후속 phase)
7. **AuditLogController GET /audit 갱신**: `@PreAuthorize hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')` + service 가 tenantId boundary 검사
8. **admin-ui**:
   - 신규 Activity 페이지 (PLATFORM_OPERATOR 사이드바 'Activity' 메뉴)
   - TenantDetail 의 5번째 탭 'Activity' (자기 tenant audit_log scope)
   - 기존 AuditLog 페이지: tenantId input + column 추가, PLATFORM_OPERATOR 전용 유지
9. **테스트**: 자동 IT 2 (`ActivityControllerIT` + `AuditLogTenantScopingIT`) + RpAdminBoundaryIT step 14 갱신

### 1.2 의도적 제외 (후속 phase)

| 항목 | 미루는 이유 |
|---|---|
| p95 응답 metric | audit_log 에 latency 없음. Micrometer 별도 phase |
| WebSocket / SSE | 5초 polling 으로 충분 (dogfood) |
| 기존 row backfill (payload.tenantId 추출) | 24h 윈도우라 historical 미필요. UPDATE 권한도 필요 |
| Audit Chain Monitor (sparkline, 월간 PDF) | 별도 phase |
| 활동 카테고리 i18n / 색상 토큰 분리 | dogfood UI fidelity 후순위 |
| 시간 윈도우 드롭다운 (24h/7d/30d) | 후속 phase — 현재는 24h 고정 |

## 2. DB 마이그레이션 + AuditLog 엔티티 + append() 변경

### 2.1 V24 마이그레이션

`core/src/main/resources/db/migration/V24__audit_log_tenant_id.sql`:

```sql
-- ============================================================
-- V24 — audit_log.tenant_id 컬럼 추가
--
-- 목적: PLATFORM_OPERATOR Activity 페이지 + RP_ADMIN audit 격리.
--
-- 결정: hash chain (V10 SHA-256) 의 입력 포맷 변경 없음 — tenant_id 는
-- 순수 metadata. payload 안의 'tenantId' 키가 이미 hash 에 포함되어 tamper
-- evidence 보존. 기존 row 의 tenant_id 는 NULL — backfill 안 함.
--
-- Idempotency: ALTER ADD / CREATE INDEX 는 재실행 시 ORA-01430 / ORA-00955
-- 로 실패. EXCEPTION 으로 감싸 멱등 (Flyway repair 외에도 안전).
-- ============================================================

-- 1. tenant_id 컬럼 추가 (RAW(16) NULL) — ORA-01430 (column already exists) swallow
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE audit_log ADD (tenant_id RAW(16))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1430 THEN NULL; -- column already exists
    ELSE RAISE;
    END IF;
END;
/

-- 2. 인덱스: tenant_id + created_at — ORA-00955 (name already used) swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX audit_log_tenant_ix ON audit_log (tenant_id, created_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/

-- 3. FK 안 둠 (의도) — tenant 가 삭제돼도 audit 은 forensic 으로 남아야 함.
--    tenant 삭제는 admin-role-separation phase 의 fk_admin_user_tenant 가
--    이미 막고 있어 dangling 위험 없음. 단순화 위해 FK 생략.
--
-- 4. 권한 변경 없음 — APP_USER 가 이미 audit_log 에 SELECT/INSERT 갖고 있음
--    (V01 grants). 컬럼 추가는 기존 grant 를 그대로 상속.
```

### 2.2 AuditLog 엔티티

`core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java` 의 필드 + 생성자 + getter 추가:

```java
@Column(name = "tenant_id", columnDefinition = "RAW(16)")
@JdbcTypeCode(SqlTypes.UUID)
private UUID tenantId;

// 9-arg constructor (기존 8-arg → tenantId 끼움)
public AuditLog(byte[] prevHash, byte[] hash,
                UUID actorId, String actorEmail,
                String action, String targetType, String targetId,
                UUID tenantId,
                String payload,
                Instant createdAtArg) {
    // ... 기존 reflection seed BaseEntity ...
    this.prevHash = prevHash;
    this.hash = hash;
    this.actorId = actorId;
    this.actorEmail = actorEmail;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.tenantId = tenantId;
    this.payload = payload;
}

public UUID getTenantId() { return tenantId; }
```

기존 8-arg constructor 호출처는 AuditLogService.append 한 곳뿐 — 그것만 9-arg 로.

### 2.3 AuditAppendRequest

`admin-app/.../audit/AuditAppendRequest.java`:

```java
public record AuditAppendRequest(
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        UUID tenantId,       // ← 신규
        Map<String, Object> payload
) {}
```

11 곳의 `new AuditAppendRequest(...)` production 호출 모두 갱신 — 한 인자 추가.
호출처 목록은 §2.5 (10 개 append() call site + 1 개 `AuditChainVerifier.recomputeHash`).

**중요**: `AuditChainVerifier.recomputeHash` 안의 `new AuditAppendRequest(...)` (
`admin-app/.../audit/AuditChainVerifier.java:81`) 도 신규 시그니처로 갱신해야 함 —
저장된 row 의 `getTenantId()` 를 인자로 넘긴다. tenant_id 는 hash 입력에 들어가지
않으므로 verifier 의 `computeHash` 결과는 V24 전후로 동일하다 (chain 보존).

테스트 파일에서도 동일 갱신 필요:
- `core/.../entity/AuditLogTest.java` (3 곳, `new AuditLog(...)`)
- `core/.../entity/BaseEntityCallbackIT.java` (1 곳)
- `admin-app/.../audit/AuditLogServiceTest.java` (4 곳 `new AuditAppendRequest`, 1 곳 `new AuditLog`)
- `admin-app/.../audit/AuditChainVerifierTest.java` (2 곳 `new AuditLog`, 1 곳 `new AuditAppendRequest`)

### 2.4 AuditLogService.append() 변경

```java
@Transactional
public AuditLog append(AuditAppendRequest req) {
    em.createNativeQuery("SELECT 1 FROM APP_OWNER.scheduler_lease WHERE name = 'AUDIT_CHAIN_LOCK' FOR UPDATE")
      .getSingleResult();

    Optional<AuditLog> latest = repo.findLatestForUpdate();
    byte[] prevHash = latest.map(AuditLog::getHash).orElse(null);

    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String payloadJson = canonicalJson(req.payload());
    byte[] hash = computeHash(prevHash, req, payloadJson, now);

    AuditLog row = new AuditLog(
            prevHash, hash,
            req.actorId(), req.actorEmail(),
            req.action(), req.targetType(), req.targetId(),
            req.tenantId(),          // ← 신규 — entity 에만 저장, hash 입력 안 함
            payloadJson,
            now);
    return repo.save(row);
}

// computeHash() — 변경 없음
//   prev_hash_hex | actor_id | action | target_type | target_id | iso_timestamp | canonical_json_payload
// tenant_id 는 입력에 안 들어감 — V10 chain 호환성 + payload 의 'tenantId' 키가 tamper evidence
```

### 2.5 10 곳의 append() 호출 갱신

| 위치 | tenantId 인자 |
|---|---|
| TenantAdminService:87 (TENANT_CREATE) | `tenant.getId()` |
| TenantAdminService:126 (TENANT_UPDATE) | `t.getId()` |
| CredentialAdminService:101 (CREDENTIAL_REVOKE) | `tenantId` (method 인자) |
| ApiKeyAdminService:84 (API_KEY_ISSUE) | `req.tenantId()` |
| ApiKeyAdminService:103 (API_KEY_REVOKE) | `k.getTenantId()` |
| AdminSecurityConfig:114 (ADMIN_LOGIN) | `null` (platform-wide) |
| AdminSecurityConfig:134 (ADMIN_LOGIN_FAILED) | `null` |
| KeyRotationService:107 (SIGNING_KEY_ROTATE) | `null` |
| KeyExpirationJob:71 (SIGNING_KEY_REVOKE) | `null` |
| MdsSchedulerService:111 (MDS_BLOB_SYNC) | `null` |

**ADMIN_LOGIN 의 tenantId 결정**: RP_ADMIN 의 로그인이라도 일관성 위해 null. 후속 phase 에서 옵션화 가능.

## 3. Activity API endpoint + Service

### 3.1 ActivityController (신규)

`admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java`:

```java
@RestController
@RequestMapping("/admin/api/activity")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    public ApiResponse<ActivityView> activity(
            @RequestParam(required = false) UUID sinceId,
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(service.snapshot(sinceId, category));
    }
}
```

PLATFORM_OPERATOR 전용. RP_ADMIN 은 TenantDetail 의 Activity 탭에서 `/audit?tenantId=` 호출.

### 3.2 ActivityService

```java
@Service
public class ActivityService {

    private static final Set<String> OPS_ACTIONS = Set.of(
            "TENANT_CREATE", "TENANT_UPDATE",
            "CREDENTIAL_REVOKE",
            "API_KEY_ISSUE", "API_KEY_REVOKE",
            "SIGNING_KEY_ROTATE",
            "ADMIN_LOGIN");

    private static final Set<String> SECURITY_ACTIONS = Set.of(
            "ADMIN_LOGIN_FAILED");

    private final ActivityRepository activity;
    private final TenantRepository tenants;

    @Transactional(readOnly = true)
    public ActivityView snapshot(UUID sinceId, String category) {
        Instant since24h = Instant.now().minus(Duration.ofHours(24));

        long events24h    = activity.countSince(since24h);
        long ops24h       = activity.countByActionsSince(OPS_ACTIONS, since24h);
        long security24h  = activity.countByActionsSince(SECURITY_ACTIONS, since24h);

        List<ActivityView.TopTenant> top5 = activity.topTenantsSince(since24h, 5)
                .stream()
                .map(row -> new ActivityView.TopTenant(
                        row.tenantId(),
                        tenants.findById(row.tenantId()).map(Tenant::getSlug).orElse("(deleted)"),
                        row.count()))
                .toList();

        Set<String> actionFilter = switch (category == null ? "all" : category) {
            case "ops"      -> OPS_ACTIONS;
            case "security" -> SECURITY_ACTIONS;
            default         -> Set.of();
        };
        List<AuditLog> feed = actionFilter.isEmpty()
                ? activity.feed(sinceId, 50)
                : activity.feedFiltered(actionFilter, sinceId, 50);

        Map<UUID, String> slugByTenant = feed.stream()
                .map(AuditLog::getTenantId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        Function.identity(),
                        tid -> tenants.findById(tid).map(Tenant::getSlug).orElse("(deleted)")));

        List<ActivityView.Event> events = feed.stream()
                .map(a -> new ActivityView.Event(
                        a.getId(),
                        a.getAction(),
                        a.getActorEmail(),
                        a.getTargetType(),
                        a.getTargetId(),
                        a.getTenantId(),
                        a.getTenantId() == null ? null : slugByTenant.get(a.getTenantId()),
                        a.getCreatedAt(),
                        categorize(a.getAction())))
                .toList();

        return new ActivityView(
                new ActivityView.Kpi(events24h, ops24h, security24h, null),
                top5, events);
    }

    private String categorize(String action) {
        if (OPS_ACTIONS.contains(action))      return "ops";
        if (SECURITY_ACTIONS.contains(action)) return "security";
        return "system";
    }
}
```

### 3.3 ActivityRepository (신규)

`core/src/main/java/com/crosscert/passkey/core/repository/ActivityRepository.java`:

```java
public interface ActivityRepository extends Repository<AuditLog, UUID> {

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :since")
    long countSince(@Param("since") Instant since);

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.createdAt >= :since AND a.action IN :actions")
    long countByActionsSince(@Param("actions") Set<String> actions, @Param("since") Instant since);

    @Query("""
        SELECT new com.crosscert.passkey.core.repository.ActivityRepository$TenantRow(a.tenantId, COUNT(a))
        FROM AuditLog a
        WHERE a.createdAt >= :since AND a.tenantId IS NOT NULL
        GROUP BY a.tenantId
        ORDER BY COUNT(a) DESC
    """)
    List<TenantRow> topTenantsSinceRaw(@Param("since") Instant since, Pageable limit);

    default List<TenantRow> topTenantsSince(Instant since, int n) {
        return topTenantsSinceRaw(since, PageRequest.of(0, n));
    }

    // sinceId 의 (createdAt, id) tuple 보다 큰 row 만 — 동일 ms timestamp 의 race 방지.
    // ORDER BY 도 같은 tuple 로 — 정렬과 필터가 한 키 위에서 동작해야 polling 누락 0.
    @Query("""
        SELECT a FROM AuditLog a
        WHERE :sinceId IS NULL
           OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
           OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedRaw(@Param("sinceId") UUID sinceId, Pageable limit);

    default List<AuditLog> feed(UUID sinceId, int n) {
        return feedRaw(sinceId, PageRequest.of(0, n));
    }

    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.action IN :actions
          AND (:sinceId IS NULL
               OR a.createdAt > (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
               OR (a.createdAt = (SELECT b.createdAt FROM AuditLog b WHERE b.id = :sinceId)
                   AND a.id > (SELECT b.id FROM AuditLog b WHERE b.id = :sinceId)))
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    List<AuditLog> feedFilteredRaw(@Param("actions") Set<String> actions,
                                   @Param("sinceId") UUID sinceId, Pageable limit);

    default List<AuditLog> feedFiltered(Set<String> actions, UUID sinceId, int n) {
        return feedFilteredRaw(actions, sinceId, PageRequest.of(0, n));
    }

    record TenantRow(UUID tenantId, long count) {}
}
```

### 3.4 ActivityView (응답 DTO)

```java
public record ActivityView(
        Kpi kpi,
        List<TopTenant> top5,
        List<Event> feed
) {
    public record Kpi(
            long events24h,
            long ops24h,
            long security24h,
            Long p95Ms          // null = N/A
    ) {}

    public record TopTenant(
            UUID tenantId,
            String slug,
            long count
    ) {}

    public record Event(
            UUID id,
            String action,
            String actorEmail,
            String targetType,
            String targetId,
            UUID tenantId,
            String tenantSlug,
            Instant createdAt,
            String category       // "ops" | "security" | "system"
    ) {}
}
```

### 3.5 AuditLogController + Service 갱신 (RP_ADMIN audit 접근)

`AuditLogController`:

```java
@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
@GetMapping
public ApiResponse<List<AuditLogView>> list(
        @RequestParam(required = false) String action,
        @RequestParam(required = false) UUID actorId,
        @RequestParam(required = false) UUID tenantId,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
    return ApiResponse.ok(service.search(action, actorId, tenantId, from, to, page, size));
}
```

`AuditLogService.search()`:

```java
public List<AuditLogView> search(String action, UUID actorId, UUID tenantId,
                                  Instant from, Instant to, int page, int size) {
    Optional<UUID> scope = tenantBoundary.currentTenantScope();
    UUID effectiveTenantId = scope.orElse(tenantId);

    // RP_ADMIN 이 다른 tenant query 시도 — 차단
    if (scope.isPresent() && tenantId != null && !scope.get().equals(tenantId)) {
        throw new BusinessException(ErrorCode.ACCESS_DENIED,
                "RP_ADMIN cannot query other tenant audit");
    }

    Page<AuditLog> rows = repo.search(action, actorId, effectiveTenantId, from, to,
                                       PageRequest.of(page, Math.min(size, 200)));
    return rows.map(AuditLogView::from).getContent();
}
```

`AuditLogRepository.search`:

```java
@Query("""
    SELECT a FROM AuditLog a
    WHERE (:action IS NULL OR a.action = :action)
      AND (:actorId IS NULL OR a.actorId = :actorId)
      AND (:tenantId IS NULL OR a.tenantId = :tenantId)
      AND (:from IS NULL OR a.createdAt >= :from)
      AND (:to IS NULL OR a.createdAt < :to)
    ORDER BY a.createdAt DESC, a.id DESC
""")
Page<AuditLog> search(@Param("action") String action,
                      @Param("actorId") UUID actorId,
                      @Param("tenantId") UUID tenantId,
                      @Param("from") Instant from,
                      @Param("to") Instant to,
                      Pageable pageable);
```

`/audit/verify` 는 PLATFORM_OPERATOR 전용 그대로 (chain 전체 검증, tenant scope 없음).

### 3.6 AuditLogView 에 tenantId 추가

```java
public record AuditLogView(
        UUID id,
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        UUID tenantId,            // ← 신규
        String payload,
        Instant createdAt
) {
    public static AuditLogView from(AuditLog a) {
        return new AuditLogView(
                a.getId(), a.getActorId(), a.getActorEmail(),
                a.getAction(), a.getTargetType(), a.getTargetId(),
                a.getTenantId(),
                a.getPayload(), a.getCreatedAt());
    }
}
```

## 4. admin-ui — Activity 페이지 + TenantDetail 5탭

### 4.1 라우트 + 사이드바

`App.tsx`:

```tsx
<Route path="/activity" element={<Activity />} />
```

`Sidebar.tsx` 의 `PLATFORM_NAV` 에 추가 — `Tenants` 와 `Signing Keys` 사이:

```typescript
{ to: '/activity', label: 'Activity', icon: Activity },
```

RP_ADMIN 의 사이드바는 변경 없음.

### 4.2 types.ts + client.ts

```typescript
export interface ActivityKpi {
    events24h: number;
    ops24h: number;
    security24h: number;
    p95Ms: number | null;
}

export interface ActivityTopTenant {
    tenantId: string;
    slug: string;
    count: number;
}

export interface ActivityEvent {
    id: string;
    action: string;
    actorEmail: string;
    targetType: string | null;
    targetId: string | null;
    tenantId: string | null;
    tenantSlug: string | null;
    createdAt: string;
    category: 'ops' | 'security' | 'system';
}

export interface ActivityView {
    kpi: ActivityKpi;
    top5: ActivityTopTenant[];
    feed: ActivityEvent[];
}

export type ActivityCategory = 'all' | 'ops' | 'security';

// AuditLogView 에 tenantId 추가
export interface AuditLogView {
    id: string;
    actorId: string | null;
    actorEmail: string;
    action: string;
    targetType: string | null;
    targetId: string | null;
    tenantId: string | null;       // ← 신규
    payload: string;
    createdAt: string;
}
```

`client.ts`:

```typescript
export const getActivity = (params: { sinceId?: string; category?: ActivityCategory }) => {
    const qs = new URLSearchParams();
    if (params.sinceId) qs.set('sinceId', params.sinceId);
    if (params.category && params.category !== 'all') qs.set('category', params.category);
    const q = qs.toString();
    return api.get<ActivityView>(`/admin/api/activity${q ? '?' + q : ''}`);
};

export const getAuditLog = (params: {
    action?: string;
    actorId?: string;
    tenantId?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
}) => {
    const qs = new URLSearchParams();
    if (params.action)   qs.set('action', params.action);
    if (params.actorId)  qs.set('actorId', params.actorId);
    if (params.tenantId) qs.set('tenantId', params.tenantId);
    if (params.from)     qs.set('from', params.from);
    if (params.to)       qs.set('to', params.to);
    if (params.page !== undefined) qs.set('page', String(params.page));
    if (params.size !== undefined) qs.set('size', String(params.size));
    return api.get<AuditLogView[]>(`/admin/api/audit?${qs}`);
};
```

### 4.3 Activity 페이지

`admin-ui/src/pages/Activity.tsx`:

핵심 동작:
- `PlatformOnlyGuard` 로 wrap (RP_ADMIN 차단)
- 첫 로드: sinceId 없이 전체 snapshot
- 5초 polling: `sinceId=lastIdRef` 로 incremental. KPI/Top5 매번 교체, feed 위에 prepend (max 200)
- 카테고리 chip ('전체'/'운영'/'보안') 클릭 시 lastIdRef reset + 새 snapshot
- KPI 의 `p95Ms` 가 null 이면 'N/A' 문자열

구조: 상단 KPI 4 카드 + 하단 2 column (왼쪽 Top 5 패널 280px / 오른쪽 이벤트 스트림). chip 은 스트림 헤더 오른쪽.

핵심 코드 (전체):

```tsx
import { useCallback, useEffect, useRef, useState } from 'react';
import PlatformOnlyGuard from '../components/PlatformOnlyGuard';
import { getActivity } from '../api/client';
import { formatDateTime } from '../lib/formatDateTime';
import type { ActivityView, ActivityCategory } from '../api/types';

const POLL_MS = 5000;
const FEED_MAX = 200;

export default function Activity() {
    return <PlatformOnlyGuard><ActivityInner /></PlatformOnlyGuard>;
}

function ActivityInner() {
    const [view, setView] = useState<ActivityView | null>(null);
    const [category, setCategory] = useState<ActivityCategory>('all');
    const [error, setError] = useState<string | null>(null);
    const lastIdRef = useRef<string | null>(null);

    const loadInitial = useCallback(async () => {
        setError(null);
        try {
            const v = await getActivity({ category });
            setView(v);
            lastIdRef.current = v.feed[0]?.id ?? null;
        } catch (e) {
            setError((e as Error)?.message ?? 'load failed');
        }
    }, [category]);

    const poll = useCallback(async () => {
        try {
            const v = await getActivity({
                category,
                sinceId: lastIdRef.current ?? undefined
            });
            setView((prev) => {
                if (!prev) return v;
                const merged = [...v.feed, ...prev.feed].slice(0, FEED_MAX);
                return { kpi: v.kpi, top5: v.top5, feed: merged };
            });
            if (v.feed.length > 0) lastIdRef.current = v.feed[0].id;
        } catch { /* silent */ }
    }, [category]);

    useEffect(() => { loadInitial(); }, [loadInitial]);
    useEffect(() => {
        const tick = setInterval(poll, POLL_MS);
        return () => clearInterval(tick);
    }, [poll]);

    if (error) return <div className="banner banner--danger">{error}</div>;
    if (!view) return <div className="muted">불러오는 중…</div>;

    return (
        <div className="stack-4">
            <h1 style={{ margin: 0 }}>Activity</h1>

            <div className="row" style={{ gap: 12 }}>
                <KpiCard label="24h 활동량"      value={view.kpi.events24h} />
                <KpiCard label="운영 액션 24h"   value={view.kpi.ops24h} />
                <KpiCard label="보안 이벤트 24h" value={view.kpi.security24h} accent="danger" />
                <KpiCard label="p95 응답 (ms)"   value={view.kpi.p95Ms ?? 'N/A'} muted />
            </div>

            <div className="row" style={{ gap: 16, alignItems: 'flex-start' }}>
                <Top5Panel top5={view.top5} />
                <FeedPanel events={view.feed} category={category} onCategoryChange={(c) => {
                    setCategory(c);
                    lastIdRef.current = null;
                }} />
            </div>
        </div>
    );
}

// KpiCard / Top5Panel / FeedPanel / CategoryChips: 같은 파일 안 inline 컴포넌트
// (spec § 4.3 의 상세 코드 참고)
```

### 4.4 TenantDetail 5번째 탭 — TenantActivityTab

`admin-ui/src/pages/tenant/TenantActivityTab.tsx`:

```tsx
import { useCallback, useEffect, useState } from 'react';
import { getAuditLog } from '../../api/client';
import { formatDateTime } from '../../lib/formatDateTime';
import type { AuditLogView } from '../../api/types';

// Activity 페이지와 동일 5초 polling — 전역 일관성 유지. dogfood scale 에서 한
// tenant 의 audit 조회 부하는 무시할 수준 (단순 indexed query).
const POLL_MS = 5000;

interface Props {
    tenantId: string;
}

export default function TenantActivityTab({ tenantId }: Props) {
    const [rows, setRows] = useState<AuditLogView[]>([]);
    const [error, setError] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        try {
            const r = await getAuditLog({ tenantId, size: 100 });
            setRows(r);
            setError(null);
        } catch (e) {
            setError((e as Error)?.message ?? 'load failed');
        }
    }, [tenantId]);

    useEffect(() => { refresh(); }, [refresh]);
    useEffect(() => {
        const tick = setInterval(refresh, POLL_MS);
        return () => clearInterval(tick);
    }, [refresh]);

    if (error) return <div className="banner banner--danger">{error}</div>;

    return (
        <div className="stack-3">
            <div className="row" style={{ justifyContent: 'space-between' }}>
                <div className="muted">{rows.length} events</div>
                <button className="btn btn--ghost btn--sm" onClick={refresh}>새로고침</button>
            </div>
            <table className="table">
                <thead>
                    <tr><th>action</th><th>actor</th><th>target</th><th>at</th></tr>
                </thead>
                <tbody>
                    {rows.length === 0 && (
                        <tr><td colSpan={4} className="muted" style={{ textAlign: 'center', padding: 24 }}>
                            audit 이벤트 없음
                        </td></tr>
                    )}
                    {rows.map(r => (
                        <tr key={r.id}>
                            <td><span className="badge">{r.action}</span></td>
                            <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{r.actorEmail}</td>
                            <td className="muted" style={{ fontSize: 12 }}>
                                {r.targetType ?? '—'}
                                {r.targetId && <> / {r.targetId.slice(0, 12)}…</>}
                            </td>
                            <td className="muted" style={{ fontSize: 12 }}>{formatDateTime(r.createdAt)}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
```

### 4.5 TenantDetail.tsx — 5번째 탭 통합

```tsx
type TabKey = 'overview' | 'webauthn' | 'credentials' | 'apikeys' | 'activity';

const TABS: { key: TabKey; label: string }[] = [
    { key: 'overview',    label: 'Overview' },
    { key: 'webauthn',    label: 'WebAuthn Configuration' },
    { key: 'credentials', label: 'Credentials' },
    { key: 'apikeys',     label: 'API Keys' },
    { key: 'activity',    label: 'Activity' },
];

// 렌더 분기에 추가:
{tab === 'activity' && <TenantActivityTab tenantId={tenant.id} />}
```

### 4.6 AuditLog 페이지 (기존) — tenantId input 추가

`PlatformOnlyGuard` 유지. 기존 filter 옆에:

```tsx
<input className="input" placeholder="tenantId (UUID)"
       value={tenantId} onChange={(e) => setTenantId(e.target.value)} />
```

테이블 column 추가:

```tsx
<th>TENANT</th>
...
<td className="muted" style={{ fontSize: 12 }}>
    {r.tenantId ? r.tenantId.slice(0, 8) + '…' : '—'}
</td>
```

### 4.7 파일 변경 요약

| 파일 | 변경 |
|---|---|
| `api/types.ts` | ActivityView/Kpi/TopTenant/Event/Category 신규 + AuditLogView.tenantId |
| `api/client.ts` | getActivity + getAuditLog helper |
| `pages/Activity.tsx` | 신규 — PlatformOnlyGuard wrapping + polling |
| `pages/tenant/TenantActivityTab.tsx` | 신규 |
| `pages/TenantDetail.tsx` | TABS 배열 + 렌더 분기 |
| `pages/AuditLog.tsx` | tenantId input + column |
| `components/Sidebar.tsx` | PLATFORM_NAV 에 Activity 메뉴 |
| `App.tsx` | /activity 라우트 |

## 5. 테스트 전략 (개발 속도 우선, 축소판)

### 5.1 자동 IT 2

| 테스트 | 위치 | 왜 필수 |
|---|---|---|
| `ActivityControllerIT` | `admin-app/.../activity/` | KPI + Top 5 + feed snapshot + sinceId incremental |
| `AuditLogTenantScopingIT` | `admin-app/.../audit/` | RP_ADMIN cross-tenant 차단 회귀 채널 |

### 5.2 ActivityControllerIT 시나리오 (5 assertion)

```
1. alice 로 tenant_A, tenant_B 생성 + tenant_A API key 발급
2. bob 잘못된 비번으로 로그인 → ADMIN_LOGIN_FAILED
3. GET /admin/api/activity → kpi.events24h >= 5, ops24h >= 3, security24h >= 1, p95Ms == null,
   top5 에 tenant_A 1개 (ADMIN_LOGIN 들은 tenant_id NULL 이라 제외), feed DESC 정렬
4. sinceId={feed[0].id} 로 polling → 빈 feed
5. bob 으로 정상 로그인 → 새 ADMIN_LOGIN → 같은 sinceId → 새 row 1개 보임
```

### 5.3 AuditLogTenantScopingIT 시나리오 (5 assertion)

```
1. alice 로 tenant_A 생성 → audit_log TENANT_CREATE row 가 tenant_id=tenant_A.id
2. bob (RP_ADMIN, demo-rp) 로그인
3. GET /admin/api/audit?tenantId={demo-rp.id} → 200, demo-rp row 만, tenant_A 미포함
4. GET /admin/api/audit (tenantId 생략) → 200, 자기 tenant 만 (service 가 자동 scope)
5. GET /admin/api/audit?tenantId={tenant_A.id} → 403 ACCESS_DENIED
```

### 5.4 기존 IT 갱신

| IT | 영향 | 갱신 |
|---|---|---|
| `AdminFlowIT` | V24 후에도 audit chain hash 유지 — verify() ok=true 그대로 | 변경 없음 예상. 깨지면 V24 또는 entity 의도치 않은 영향 |
| `RpAdminBoundaryIT` | step 14 `GET /audit → 403` 의미 변경 (이제 RP_ADMIN 도 GET 가능) | `GET /audit?tenantId={other} → 403` 으로 변경 |
| `PlatformOperatorUnrestrictedIT` | step 10 `GET /audit → 200` 그대로 | 변경 없음 |

### 5.5 의도적 제외

- ActivityService.categorize() 단위 테스트
- ActivityRepository JPQL 단위 테스트
- admin-ui Activity 페이지 polling / TenantActivityTab — manual smoke
- KPI p95 null 처리 — IT 응답 검증으로 충분

### 5.6 통과 기준

- `./gradlew :admin-app:test --tests ActivityControllerIT --tests AuditLogTenantScopingIT` → 2 통과
- `./gradlew :admin-app:test --tests '*IT'` → 7 IT 모두 통과
- admin-ui `npx tsc --noEmit` + `npm run build` 통과
- 수동 smoke 8 단계 (followup)

### 5.7 수동 smoke 체크리스트 (followup)

1. alice 로그인 → 사이드바 Activity 메뉴 보임 → 클릭하면 페이지 로드
2. Activity 페이지 → KPI 4 카드 (p95 = 'N/A'), Top 5, 이벤트 스트림
3. 5초 polling — alice 가 별 탭에서 tenant 생성 → 5초 안에 feed 에 새 row
4. 카테고리 chip — '운영' / '보안' / '전체' 정확 filter
5. bob (RP_ADMIN) 로그인 → 사이드바에 Activity 없음. /activity URL 직접 → detail 로 리다이렉트
6. bob detail 의 5번째 탭 'Activity' → 자기 tenant 의 audit 만
7. alice 가 bob 의 demo-rp detail Activity 탭 → 동일 데이터
8. AuditLog 페이지 (PLATFORM_OPERATOR) → tenantId input 으로 filter 가능

## 6. 위험 + 후속

### 6.1 위험과 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| V24 가 audit_log hash chain 깨뜨림 | verify() ok=false | tenant_id 가 hash 입력에 안 들어가도록 명시. AdminFlowIT step ⑦ 가 회귀 채널 |
| V24 재실행 (Flyway repair / 수동) → ORA-01430/00955 실패 | 마이그 막힘 | EXCEPTION + SQLCODE swallow 로 ALTER ADD / CREATE INDEX 모두 멱등 (§2.1) |
| 11 곳의 `new AuditAppendRequest(...)` 호출 중 누락 (10 append + AuditChainVerifier.recomputeHash) | 컴파일 실패 (record signature 변경 강제) | 자동 발견 — silent 누락 없음. verifier 누락은 chain 검증이 빈 byte[] 반환해 즉시 broken 으로 노출 |
| Top 5 의 tenant slug N+1 query | DB cost | service 의 distinct().toMap() 가 unique tenant 만 1회 조회 |
| sinceId polling 의 race (동일 ms timestamp) | feed 누락 가능 | feedRaw / feedFilteredRaw 의 WHERE 가 `(createdAt, id)` tuple 비교. ORDER BY 도 같은 tuple. 동일 ms 누락 0 |
| Feed 브라우저 메모리 leak | 누적 증가 | FEED_MAX=200 상한 + slice |
| Polling 실패 silent | 운영자 모름 | 첫 로드만 banner. 후속에서 indicator 가능 |
| audit_log historical row tenant_id NULL → Top 5 제외 | 운영자가 historical 못 봄 | 24h 윈도우라 자연 무관. backfill 후속 옵션 |
| RP_ADMIN tenantId 누락 호출 | service 가 자동 scope | 일치 검사로 안전. AuditLogTenantScopingIT 가 회귀 채널 |
| ADMIN_LOGIN 의 tenantId null 결정 | RP_ADMIN 의 자기 로그인 안 보임 | 의도된 결정. 후속 phase 에서 옵션화 |

### 6.2 후속 작업

`docs/superpowers/followups/2026-05-27-activity-page-followups.md` 에 다음 8 개 항목 추적:

1. p95 응답 metric (Micrometer/Actuator)
2. WebSocket 또는 SSE
3. historical audit_log tenant_id backfill
4. Audit Chain Monitor 페이지 (sparkline, 월간 PDF)
5. 운영자 관리 UI (admin-role-separation 의 deferred)
6. ADMIN_LOGIN tenantId 분류 옵션
7. Activity 페이지 시간 윈도우 선택 (24h/7d/30d)
8. action 분류 i18n + 색상 토큰 분리
