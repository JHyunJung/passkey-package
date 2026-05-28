# Phase F3 — Funnel + Activity hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** Close Gap #3, #5, #9 — FunnelTab 전체와 TenantOverview 의 funnel/최근활동 카드가 fixture/인라인 상수가 아닌 실제 서버 응답을 사용한다. **admin-ui 디자인 무수정.**

**Architecture:** 신규 `FunnelController` + `FunnelService` (audit_log 집계, no new table). `funnelApi.get` 가 fixture 직반환 → 실 fetch. `TenantOverview` 의 인라인 `FUNNEL_FIXTURE` + `EMPTY_EVENTS` 제거 + `useEffect` 로 funnelApi/activityApi 호출. `recentActivityAdapter.ts` 헬퍼로 ActivityPage 의 mapping 로직 재사용.

**Tech Stack:** Spring Boot, JPA, Oracle, React + TypeScript.

**Spec reference:** `docs/superpowers/specs/2026-05-28-admin-ui-server-gap-fill-design.md` § Phase F3.

---

## Execution policy (carries over from F1/F2)

1. Tests minimal — compile/typecheck + 1 round-trip IT
2. Per-task codex review on staged diff before commit (memory: `feedback_codex_review_before_commit.md`)
3. Autonomous decisions during execution (memory: `feedback_autonomous_decisions.md`)

---

## Schema discovery findings

Inspected during plan authoring:

- **`audit_log` 의 ceremony 이벤트는 현재 적재되지 않음.** passkey-app 에는 audit_log 적재 코드가 없고, `AuditLogService` 는 admin-app 에만 존재 (cross-module call 불가). 즉 spec 의 ceremony action 이름 (`REGISTRATION_BEGIN` 등) 은 미래에 적재될 때 사용할 명칭으로만 의미가 있다.
- **결정:** F3 본문은 endpoint + 어댑터 + UI hook 만 구축한다. 현재 dev DB에서는 ceremony 카운트가 모두 0 이지만, admin 작업 (tenant 생성, API key 발급 등) 으로 적재된 audit_log 행은 ActivityController 가 이미 반환 중이므로 **F3.3 (최근활동 카드)** 는 실제 데이터를 보여준다. F3.1/F3.2 는 "0이지만 endpoint 구조가 갖춰진 상태" 로 종결 — passkey-app 의 ceremony audit 적재는 별도 phase 로 분리 (out of F3 scope).
- 이 결정은 spec § F3.1 의 "audit_log 집계 — 매 호출 시 계산, 새 테이블 불필요" 원칙과 일치한다.

---

## File Structure

### Backend

```
admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelController.java   # new
admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelService.java       # new
admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelDto.java           # new
admin-app/src/test/java/com/crosscert/passkey/admin/funnel/FunnelIT.java            # new — smoke
```

### Frontend

```
admin-ui/src/api/funnel.ts                                    # modify — fixture → real fetch
admin-ui/src/fixtures/funnel.ts                               # delete (types moved to api/funnel.ts)
admin-ui/src/pages/tenant/recentActivityAdapter.ts            # new — extracted from ActivityPage
admin-ui/src/pages/tenant/TenantOverview.tsx                  # modify — drop inline constants, add useEffect
admin-ui/src/pages/ActivityPage.tsx                           # modify — use new shared adapter
admin-ui/src/pages/tenant/FunnelTab.tsx                       # modify — type import path
```

---

## Working directory & branch

Worktree `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/gap-fill-f3/` on `worktree-gap-fill-f3` (forked from main at `3ab6f3d`).

---

## Task 1: FunnelDto

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelDto.java`

Spec mandates the DTO match the existing `fixtures/funnel.ts FunnelData` shape exactly so the UI requires zero edits beyond removing the fixture import.

- [ ] **Step 1: Write the DTO**

```java
package com.crosscert.passkey.admin.funnel;

import java.util.List;

public final class FunnelDto {
    private FunnelDto() {}

    public record View(
            Stage registration,
            Stage authentication,
            List<DailyPoint> series,
            List<EventCount> byEventType
    ) {}

    public record Stage(long attempts, long success, double ratio) {}

    public record DailyPoint(String day, long attempts, long success) {}

    public record EventCount(String type, long n) {}
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :admin-app:compileJava
```
BUILD SUCCESSFUL.

- [ ] **Step 3: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelDto.java
```
Dispatch codex (Agent tool `subagent_type: "codex:codex-rescue"` or Bash fallback): "codex review FunnelDto. Verify shape matches `fixtures/funnel.ts FunnelData` (registration/authentication objects with attempts/success/ratio; series day/attempts/success; byEventType type/n). Must-fix only."
```bash
git commit -m "feat(admin-app): FunnelDto (View + Stage + DailyPoint + EventCount) (Gap #3/#9)"
```

---

## Task 2: FunnelService — audit_log aggregation

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelService.java`

Ceremony 액션명 4개를 상수로 정의. audit_log 가 비어 있으면 모든 count 가 0 — 정상.

- [ ] **Step 1: Inspect AuditLog entity + repository**

```bash
cat core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java | head -50
find . -name "AuditLogRepository.java" -not -path "*/build/*" 2>/dev/null
```

Confirm `AuditLog` has `tenantId: UUID`, `action: String`, `createdAt: Instant` (or similar). Confirm the repository class name + package — we'll add `@Query` methods.

- [ ] **Step 2: Write FunnelService**

```java
package com.crosscert.passkey.admin.funnel;

import com.crosscert.passkey.admin.audit.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FunnelService {

    static final String REGISTRATION_BEGIN     = "REGISTRATION_BEGIN";
    static final String REGISTRATION_SUCCESS   = "REGISTRATION_FINISH_OK";
    static final String AUTHENTICATION_BEGIN   = "AUTHENTICATION_BEGIN";
    static final String AUTHENTICATION_SUCCESS = "AUTHENTICATION_FINISH_OK";

    private final AuditLogRepository repo;

    public FunnelService(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public FunnelDto.View compute(UUID tenantId, int windowDays) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        long regAttempts = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_BEGIN, since);
        long regSuccess  = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, REGISTRATION_SUCCESS, since);
        long authAttempts = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_BEGIN, since);
        long authSuccess  = repo.countByTenantIdAndActionAndCreatedAtAfter(tenantId, AUTHENTICATION_SUCCESS, since);

        FunnelDto.Stage registration   = new FunnelDto.Stage(regAttempts, regSuccess,   ratio(regSuccess, regAttempts));
        FunnelDto.Stage authentication = new FunnelDto.Stage(authAttempts, authSuccess, ratio(authSuccess, authAttempts));

        List<FunnelDto.DailyPoint> series = buildSeries(tenantId, since, windowDays);
        List<FunnelDto.EventCount> byType = buildByEventType(tenantId, since);

        return new FunnelDto.View(registration, authentication, series, byType);
    }

    private double ratio(long success, long attempts) {
        return attempts == 0 ? 0.0 : (double) success / (double) attempts;
    }

    private List<FunnelDto.DailyPoint> buildSeries(UUID tenantId, Instant since, int windowDays) {
        // Group attempts+success by day across the window. Fill empty days with 0.
        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (Object[] row : repo.aggregateDailyByTenantAndActions(
                tenantId,
                List.of(REGISTRATION_BEGIN, AUTHENTICATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_SUCCESS),
                since)) {
            LocalDate day = ((java.sql.Timestamp) row[0]).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            String action = (String) row[1];
            long count = ((Number) row[2]).longValue();
            long[] cell = byDay.computeIfAbsent(day, k -> new long[]{0L, 0L});
            if (action.equals(REGISTRATION_BEGIN) || action.equals(AUTHENTICATION_BEGIN)) cell[0] += count;
            else cell[1] += count;
        }
        List<FunnelDto.DailyPoint> series = new ArrayList<>(windowDays);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = windowDays - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] cell = byDay.getOrDefault(d, new long[]{0L, 0L});
            series.add(new FunnelDto.DailyPoint(d.toString().substring(5), cell[0], cell[1]));
        }
        return series;
    }

    private List<FunnelDto.EventCount> buildByEventType(UUID tenantId, Instant since) {
        return repo.aggregateByTenantAndActionsGrouped(
                tenantId,
                List.of(REGISTRATION_BEGIN, REGISTRATION_SUCCESS, AUTHENTICATION_BEGIN, AUTHENTICATION_SUCCESS),
                since)
                .stream()
                .map(row -> new FunnelDto.EventCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}
```

- [ ] **Step 3: Add repository methods**

In `AuditLogRepository.java`, add three method signatures. The exact entity field name (`tenantId`) and column (`createdAt`) must match — verify via `grep -n "tenantId\|createdAt" core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`.

```java
import com.crosscert.passkey.core.entity.AuditLog;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
// ... existing imports

    long countByTenantIdAndActionAndCreatedAtAfter(UUID tenantId, String action, Instant since);

    @Query("SELECT FUNCTION('TRUNC', a.createdAt) AS day, a.action, COUNT(a) "
         + "FROM AuditLog a "
         + "WHERE a.tenantId = :tenantId AND a.action IN :actions AND a.createdAt >= :since "
         + "GROUP BY FUNCTION('TRUNC', a.createdAt), a.action")
    List<Object[]> aggregateDailyByTenantAndActions(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);

    @Query("SELECT a.action, COUNT(a) "
         + "FROM AuditLog a "
         + "WHERE a.tenantId = :tenantId AND a.action IN :actions AND a.createdAt >= :since "
         + "GROUP BY a.action")
    List<Object[]> aggregateByTenantAndActionsGrouped(
            @Param("tenantId") UUID tenantId,
            @Param("actions") List<String> actions,
            @Param("since") Instant since);
```

- [ ] **Step 4: Compile**

```bash
./gradlew :admin-app:compileJava
```
BUILD SUCCESSFUL. If `FUNCTION('TRUNC', …)` JPQL doesn't resolve in your Hibernate dialect, fall back to native query:

```java
@Query(value = "SELECT TRUNC(created_at) AS day, action, COUNT(*) "
             + "FROM audit_log WHERE tenant_id = :tenantId AND action IN (:actions) AND created_at >= :since "
             + "GROUP BY TRUNC(created_at), action",
       nativeQuery = true)
```

(Note: native uses `tenant_id` column; UUID binding may need `RAW(16)` conversion if Hibernate doesn't handle it transparently. If issues arise, fall back to fetching all rows in window and grouping in Java — performance acceptable for dev seed scale.)

- [ ] **Step 5: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogRepository.java
```
Codex prompt: "codex review FunnelService + repository methods. Verify: (1) JPQL aggregate queries valid for Oracle Hibernate dialect, (2) windowDays loop builds N days incl. today, (3) action name constants match spec REGISTRATION_BEGIN/FINISH_OK/AUTHENTICATION_BEGIN/FINISH_OK, (4) ratio handles attempts=0 case. Must-fix only."
```bash
git commit -m "feat(admin-app): FunnelService aggregates audit_log (Gap #3/#9)"
```

---

## Task 3: FunnelController

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelController.java`

- [ ] **Step 1: Write the controller**

```java
package com.crosscert.passkey.admin.funnel;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/api/tenants/{tenantId}/funnel")
public class FunnelController {

    private final FunnelService service;

    public FunnelController(FunnelService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    public FunnelDto.View get(
            @PathVariable UUID tenantId,
            @RequestParam(name = "windowDays", defaultValue = "7") int windowDays) {
        if (windowDays != 1 && windowDays != 7 && windowDays != 30) {
            // Clamp to spec values rather than 400 — design only emits 1/7/30 but be lenient.
            windowDays = 7;
        }
        return service.compute(tenantId, windowDays);
    }
}
```

> RP_ADMIN tenant-scoping is enforced upstream by the existing `@TenantScoped`/VPD machinery (other tenant-scoped controllers like `AaguidPolicyController` use the same role gate without per-method tenant check). If F3 IT later reveals that's incomplete, fix at that point.

- [ ] **Step 2: Compile**

```bash
./gradlew :admin-app:compileJava
```
BUILD SUCCESSFUL.

- [ ] **Step 3: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/funnel/FunnelController.java
```
Codex prompt: "codex review FunnelController. Verify: (1) RBAC matches AaguidPolicyController pattern, (2) windowDays clamped to {1,7,30}, (3) path template matches frontend funnelApi.get URL. Must-fix only."
```bash
git commit -m "feat(admin-app): FunnelController GET /admin/api/tenants/{id}/funnel (Gap #3/#9)"
```

---

## Task 4: FunnelIT smoke

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/funnel/FunnelIT.java`

Per execution policy §1: 1 smoke test only.

- [ ] **Step 1: Copy scaffold**

Use `TenantKpiIT.java` (F2 Task 5) as template — same self-contained Testcontainers pattern.

- [ ] **Step 2: Write IT**

```java
package com.crosscert.passkey.admin.funnel;

// === Copy imports/scaffold/loginAs from TenantKpiIT.java VERBATIM ===

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class FunnelIT {

    // === Copy OracleContainer + Redis + @DynamicPropertySource + loginAs ===

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    @Test
    void getFunnelReturnsShapeWithZeroOrMoreCounts() {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // Get a tenant id first
        ResponseEntity<JsonNode> tenants = rest.exchange(
                "http://localhost:" + port + "/admin/api/tenants",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(tenants.getStatusCode().value()).isEqualTo(200);
        // Server returns either raw array OR ApiResponse envelope — handle both per TenantKpiIT pattern
        JsonNode arr = tenants.getBody().isArray() ? tenants.getBody() : tenants.getBody().get("data");
        assertThat(arr).isNotEmpty();
        String tenantId = arr.get(0).get("id").asText();

        // GET funnel
        ResponseEntity<JsonNode> funnel = rest.exchange(
                "http://localhost:" + port + "/admin/api/tenants/" + tenantId + "/funnel?windowDays=7",
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(funnel.getStatusCode().value()).isEqualTo(200);
        JsonNode body = funnel.getBody().isArray() ? funnel.getBody() : funnel.getBody().has("data") ? funnel.getBody().get("data") : funnel.getBody();

        // Shape assertions — ceremony events not adopted yet, so attempts/success likely 0
        assertThat(body.has("registration")).isTrue();
        assertThat(body.get("registration").has("attempts")).isTrue();
        assertThat(body.get("registration").has("success")).isTrue();
        assertThat(body.get("registration").has("ratio")).isTrue();
        assertThat(body.has("authentication")).isTrue();
        assertThat(body.has("series")).isTrue();
        assertThat(body.get("series").isArray()).isTrue();
        assertThat(body.get("series").size()).isEqualTo(7);
        assertThat(body.has("byEventType")).isTrue();
        assertThat(body.get("byEventType").isArray()).isTrue();
    }
}
```

- [ ] **Step 3: Run**

```bash
./gradlew :admin-app:test --tests FunnelIT
```
Expected: 1 test passes.

- [ ] **Step 4: Codex + commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/funnel/FunnelIT.java
```
Codex prompt: "codex review FunnelIT smoke. Verify field-name assertions match FunnelDto.View serialization. Must-fix only."
```bash
git commit -m "test(admin-app): FunnelIT smoke shape verification (Gap #3/#9)"
```

---

## Task 5: funnel.ts adapter — fixture → real fetch

**Files:**
- Modify: `admin-ui/src/api/funnel.ts`

- [ ] **Step 1: Inspect current state + fixture shape**

```bash
cat admin-ui/src/api/funnel.ts
cat admin-ui/src/fixtures/funnel.ts | head -30
```

The fixture file exports types `FunnelData`, `FunnelSeries`, `FunnelByEventType`. Move those type definitions to `api/funnel.ts` so the fixture file becomes deletable.

- [ ] **Step 2: Replace `api/funnel.ts` with adapter + types**

```typescript
import { api } from './client';

export type FunnelStage = {
  attempts: number;
  success: number;
  ratio: number;
};

export type FunnelSeries = {
  day: string;
  attempts: number;
  success: number;
};

export type FunnelByEventType = {
  type: string;
  n: number;
};

export type FunnelData = {
  registration: FunnelStage;
  authentication: FunnelStage;
  series: FunnelSeries[];
  byEventType: FunnelByEventType[];
};

export const funnelApi = {
  get: (tenantId: string, windowDays: 1 | 7 | 30 = 7): Promise<FunnelData> =>
    api.get<FunnelData>(`/admin/api/tenants/${tenantId}/funnel?windowDays=${windowDays}`),
};
```

Verify against `client.ts` — if no envelope-aware `get` exists for this controller (the controller returns raw `FunnelDto.View` per F2 webauthn pattern), use `api.getRaw` instead. **Inspect `client.ts` and pick consistent method.** Aaguid/webauthn controllers use `getRaw/putRaw` because they return DTOs directly without `ApiResponse` envelope; SecurityPolicyController does the same. FunnelController returns `FunnelDto.View` directly → use `api.getRaw`.

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```
Expected: errors at `pages/tenant/FunnelTab.tsx` (imports `from '@/fixtures/funnel'`) — fixed in Task 6.

- [ ] **Step 4: Codex + commit (defer; combined with Task 6)**

Don't commit yet — Task 6 fixes the FunnelTab import.

---

## Task 6: FunnelTab type-import path + delete fixture

**Files:**
- Modify: `admin-ui/src/pages/tenant/FunnelTab.tsx` — change import path
- Delete: `admin-ui/src/fixtures/funnel.ts`

- [ ] **Step 1: Update FunnelTab type import**

Find:
```typescript
import type { FunnelData, FunnelSeries, FunnelByEventType } from '@/fixtures/funnel';
```

Replace with:
```typescript
import type { FunnelData, FunnelSeries, FunnelByEventType } from '@/api/funnel';
```

- [ ] **Step 2: Verify zero other references**

```bash
grep -rn "fixtures/funnel\|getFunnel\b" admin-ui/src/
```
Expected: zero outside the fixture file itself.

- [ ] **Step 3: Delete fixture**

```bash
git rm admin-ui/src/fixtures/funnel.ts
```

- [ ] **Step 4: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```
Expected: zero errors. If TenantOverview.tsx errors (it uses `FUNNEL_FIXTURE` inline constant — Task 7 territory), leave.

- [ ] **Step 5: Codex + combined commit (Task 5 + 6)**

```bash
git add admin-ui/src/api/funnel.ts admin-ui/src/pages/tenant/FunnelTab.tsx
git status --short  # should show D fixtures/funnel.ts
```
Codex prompt: "codex review combined Task 5+6 staged diff. Verify: funnel.ts is now an adapter + type re-exports, FunnelTab type-only import re-routed, fixture file deleted, no remaining references in admin-ui/src/. Must-fix only."
```bash
git commit -m "feat(admin-ui): funnelApi uses real server endpoint; types moved out of fixture (Gap #3/#9)"
```

---

## Task 7: recentActivityAdapter helper

**Files:**
- Create: `admin-ui/src/pages/tenant/recentActivityAdapter.ts`

`ActivityPage.tsx` has an `adaptServerView` function (lines ~49-76) that converts server `ActivityView.feed` items to a display shape. We extract just the feed-item mapping to a shared helper so `TenantOverview` (Task 8) reuses it.

- [ ] **Step 1: Inspect ActivityPage.tsx**

```bash
sed -n '36,76p' admin-ui/src/pages/ActivityPage.tsx
```

Identify the `DisplayEvent` type (lines ~37-47) and the inline mapping logic that turns each `ActivityView.feed` entry into a `DisplayEvent`.

- [ ] **Step 2: Write the helper**

```typescript
import type { ActivityView } from '@/api/types';

export type RecentActivityEvent = {
  id: string;
  ts: string;
  tenantId: string | null;
  tenantName: string;
  tenantSlug: string | null;
  type: string;
  actorType: 'ADMIN' | 'RP_SERVICE' | string;
  actorId: string | null;
  subjectId: string;
  category: 'ops' | 'security' | 'system';
};

export function adaptFeedItems(view: ActivityView): RecentActivityEvent[] {
  return view.feed.map((e) => ({
    id: e.id,
    ts: e.createdAt,
    tenantId: e.tenantId,
    tenantName: e.tenantSlug ?? e.tenantId ?? '—',
    tenantSlug: e.tenantSlug,
    type: e.action,
    actorType: e.actorEmail ? 'ADMIN' : 'RP_SERVICE',
    actorId: e.actorEmail ?? null,
    subjectId: e.targetId ?? '—',
    category: e.category,
  }));
}
```

- [ ] **Step 3: Refactor ActivityPage to use the helper**

In `ActivityPage.tsx`, replace the inline `DisplayEvent` type + `adaptServerView` function body that handles `feed` mapping with imports from the helper. Keep ActivityPage's `kpi` and `topTenants` mapping in-place (those are page-specific).

Original (around lines 36-76):
```typescript
type DisplayEvent = {
  id: string;
  ts: string;
  // ... 9 fields
};

function adaptServerView(view: ActivityView): {
  events: DisplayEvent[];
  kpi: { events24h: number; ops24h: number; security24h: number; p95Ms: number | null };
  topTenants: { tenantId: string; tenantName: string; tenantSlug: string; count: number }[];
} {
  const events: DisplayEvent[] = view.feed.map((e) => ({
    // ...
  }));
  const topTenants = view.top5.map(...);
  return { events, kpi: view.kpi, topTenants };
}
```

Replace with:
```typescript
import { adaptFeedItems, type RecentActivityEvent } from './tenant/recentActivityAdapter';

type DisplayEvent = RecentActivityEvent;

function adaptServerView(view: ActivityView): {
  events: DisplayEvent[];
  kpi: { events24h: number; ops24h: number; security24h: number; p95Ms: number | null };
  topTenants: { tenantId: string; tenantName: string; tenantSlug: string; count: number }[];
} {
  const events = adaptFeedItems(view);
  const topTenants = view.top5.map((t) => ({
    tenantId: t.tenantId,
    tenantName: t.slug,
    tenantSlug: t.slug,
    count: t.count,
  }));
  return { events, kpi: view.kpi, topTenants };
}
```

> ActivityPage's `DisplayEvent` type alias becomes an alias for `RecentActivityEvent` — identical field set, so all downstream consumers compile unchanged.

- [ ] **Step 4: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```
Expected: zero errors.

- [ ] **Step 5: Codex + commit**

```bash
git add admin-ui/src/pages/tenant/recentActivityAdapter.ts admin-ui/src/pages/ActivityPage.tsx
```
Codex prompt: "codex review recentActivityAdapter extraction. Verify: (1) RecentActivityEvent field set matches the original DisplayEvent in ActivityPage exactly, (2) ActivityPage refactored to use the helper without behavior change, (3) DisplayEvent alias maintains backward compatibility for downstream code. Must-fix only."
```bash
git commit -m "refactor(admin-ui): extract recentActivityAdapter helper (Gap #5 prep)"
```

---

## Task 8: TenantOverview — drop inline constants + add useEffect calls

**Files:**
- Modify: `admin-ui/src/pages/tenant/TenantOverview.tsx`

## CRITICAL: ZERO design changes

JSX trees, className, KV/MetricCard/EventDot components, surrounding cards — all stay identical. Only state, useEffect calls, and expression sources change.

- [ ] **Step 1: Inspect**

```bash
sed -n '140,220p' admin-ui/src/pages/tenant/TenantOverview.tsx
```

Identify:
- `EMPTY_EVENTS` inline constant (around line 151)
- `FUNNEL_FIXTURE` inline constant (around line 154-157)
- The funnel KPI cards (around lines 174-175) using `f.registration.attempts > 0 ? … : '—'`
- The recent activity card (around lines 197-215) iterating `EMPTY_EVENTS.slice(0, 5)`

- [ ] **Step 2: Remove inline constants + add useEffect for funnel and activity**

Add imports near the top of the file:
```typescript
import { funnelApi, type FunnelData } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import { adaptFeedItems, type RecentActivityEvent } from './recentActivityAdapter';
```

Remove the inline constants:
- `type AuditEventFixture = …` (around line 145)
- `const EMPTY_EVENTS: AuditEventFixture[] = []` (line 151)
- `const FUNNEL_FIXTURE = { … }` (lines 154-157)

Inside `TenantOverview({ tenant })`, add state:
```typescript
  const [funnel, setFunnel] = useState<FunnelData | null>(null);
  const [recentEvents, setRecentEvents] = useState<RecentActivityEvent[]>([]);
```

(Keep existing `chainState` state and useEffect untouched.)

Add useEffects:
```typescript
  useEffect(() => {
    funnelApi.get(tenant.id, 7).then(setFunnel).catch(() => setFunnel(null));
  }, [tenant.id]);

  useEffect(() => {
    activityApi
      .fetch(tenant.id, undefined)
      .then((view) => setRecentEvents(adaptFeedItems(view).slice(0, 5)))
      .catch(() => setRecentEvents([]));
  }, [tenant.id]);
```

Replace `const f = FUNNEL_FIXTURE;` with:
```typescript
  const f = funnel ?? { registration: { attempts: 0, success: 0, ratio: 0 }, authentication: { attempts: 0, success: 0, ratio: 0 } };
```

> The funnel KPI cards already check `f.registration.attempts > 0 ? … : '—'`, so when `funnel === null` (initial state or fetch failure), they show `—` exactly as before.

Replace the `EMPTY_EVENTS.slice(0, 5).map(...)` with `recentEvents.map(...)`. Update the empty-state condition:
```typescript
{recentEvents.length === 0 ? (
  <div className="muted" style={{ padding: "20px", fontSize: 13, textAlign: "center" }}>최근 활동 없음</div>
) : (
  recentEvents.map((e, i) => (
    // existing JSX (e.type, e.subjectId, e.ts) — RecentActivityEvent has the same field names
  ))
)}
```

Note: the existing empty-state message `"최근 활동 없음 — Phase E3 에서 연결 예정"` should drop the "Phase E3 에서 연결 예정" suffix since we ARE the connection now. This is a copy change to a message that was a placeholder; per execution policy autonomous decisions, drop it. Surface in commit message.

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```
Expected: zero errors.

- [ ] **Step 4: Codex + commit**

```bash
git add admin-ui/src/pages/tenant/TenantOverview.tsx
```
Codex prompt: "codex review TenantOverview.tsx. CRITICAL: verify ZERO design changes — JSX trees, classNames, KV/MetricCard/EventDot, card structure byte-identical. Only state, useEffect, expression sources changed. Inline FUNNEL_FIXTURE + EMPTY_EVENTS removed. Empty-state copy drops 'Phase E3 에서 연결 예정' suffix. Must-fix only."
```bash
git commit -m "feat(admin-ui): TenantOverview wires funnelApi + activityApi (Gap #3/#5)"
```

---

## Task 9: Phase F3 regression + cumulative codex + `--no-ff` merge to main

**Files:** (none — verification step)

- [ ] **Step 1: Backend test suite**

```bash
./gradlew :admin-app:test
```
Expected: BUILD SUCCESSFUL. New FunnelIT passes. F1/F2 ITs still pass.

If the 20 pre-existing `*ControllerSecurityTest` failures from F1/F2 remain — those are not F3 regressions, document and proceed.

- [ ] **Step 2: TS check**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 3: Cumulative codex review**

```bash
git diff main..HEAD --stat
```

Dispatch codex on full F3 diff. Prompt: "codex review the cumulative F3 diff (excluding docs/superpowers/plans/). Final regression gate before merge. Focus: (1) FunnelController/Service/DTO shape matches frontend FunnelData type exactly, (2) audit_log aggregate queries are Oracle-compatible JPQL or fall-back native, (3) TenantOverview design unchanged (only state/useEffect/expression sources), (4) recentActivityAdapter extraction preserved ActivityPage behavior. Must-fix only. APPROVED for merge if clean."

Apply must-fix as a follow-up commit `fix(f3): codex final review feedback`.

- [ ] **Step 4: Merge `--no-ff` to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git checkout main
git merge --no-ff worktree-gap-fill-f3 -m "Merge Phase F3 — Funnel + Activity hook (Gap #3/#5/#9)"
git log --oneline -5
```

- [ ] **Step 5: Manual smoke checklist for user**

- TenantDetail → Funnel 탭: 7d/30d/1d 토글이 모두 fetch 트리거. Empty seed → 모든 값 0. (현재 정상 동작; 미래 ceremony audit이 적재되면 자동 채워짐.)
- TenantDetail → Overview → `등록 성공률 (7d)` / `인증 성공률 (7d)` 카드: empty seed → '—' 표시 (이전과 동일하지만 이제는 서버에서 가져온 데이터).
- TenantDetail → Overview → `최근 활동` 카드: tenant의 실제 admin operations (API key 발급, AAGUID 정책 변경 등) 가 노출되어야 함. 빈 tenant 는 "최근 활동 없음" (Phase E3 suffix 제거됨).
- DB verify: `INSERT INTO audit_log (..., action, tenant_id, created_at) VALUES (..., 'REGISTRATION_BEGIN', '<tenantId>', SYSTIMESTAMP);` 후 Funnel 탭 refresh → 시도 카운트 +1.

---

## Phase F3 Summary

**What ships:**
- 신규 백엔드: `FunnelController` + `FunnelService` + `FunnelDto`
- AuditLogRepository 3 derived/@Query methods
- 새 IT: `FunnelIT` shape smoke
- 어댑터: `funnelApi` 실 fetch (fixture → real)
- 새 헬퍼: `recentActivityAdapter.ts` (ActivityPage + TenantOverview 공유)
- TenantOverview 인라인 상수 제거 + useEffect 추가
- 삭제: `fixtures/funnel.ts`

**Design impact:** zero.

**Closed gaps:** #3 (Overview funnel KPI), #5 (Overview 최근활동), #9 (FunnelTab 전체).

**Out of F3 scope:** passkey-app 의 ceremony audit 적재는 별도 phase. 데이터 없는 환경에서도 endpoint/UI 가 정상 동작 (0 표시).

**Next phase:** F4 — MDS / SystemInfo / Monthly PDF.
