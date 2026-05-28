# Phase F5 — No-op buttons + Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Close Gap #6, #7, #8, #10, #11, #12, #13 — 7개 no-op 버튼에 의미 있는 동작을 부여 + `fixtures/auditChainState.ts` cleanup. **admin-ui 디자인 무수정.**

**Architecture:** 대부분 onClick 핸들러 추가 + navigate / 어댑터 호출 / 토글 사이클 / 클라이언트 CSV 다운로드. ActivityController 에 `before` query 파라미터 1개만 백엔드 추가 (페이지네이션). 새 헬퍼 `lib/csvExport.ts` 추가.

**Tech Stack:** React + TypeScript, Spring Boot (ActivityController 1줄 변경).

**Spec reference:** `docs/superpowers/specs/2026-05-28-admin-ui-server-gap-fill-design.md` § Phase F5.

---

## Execution policy

1. Tests minimal — typecheck + 백엔드 변경(ActivityController)에 대해서만 smoke 1개
2. Per-task codex review
3. Autonomous decisions

---

## File Structure

```
admin-ui/src/lib/csvExport.ts                                     # new — Blob CSV downloader
admin-ui/src/pages/tenant/TenantOverview.tsx                       # modify — 4 onClick (편집/전체보기/수동검증/월간보고서)
admin-ui/src/pages/ActivityPage.tsx                                # modify — useSearchParams + 내보내기/이전24h/ChevronRight onClick
admin-ui/src/pages/tenant/AuditTab.tsx                             # modify — 보고서/기간토글/내보내기 onClick
admin-ui/src/pages/tenant/CredentialsTab.tsx                       # modify — aaguid필터 토글 + CSV
admin-ui/src/fixtures/auditChainState.ts                           # delete

admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java   # modify — ?before= 파라미터
admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java      # modify — before 인자 처리
core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java       # modify — 페이지네이션용 method
```

---

## Working directory & branch

Worktree `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/gap-fill-f5/` on `worktree-gap-fill-f5` (forked from main at `da86e49`).

---

## Task 1: CSV export helper

**Files:**
- Create: `admin-ui/src/lib/csvExport.ts`

- [ ] **Step 1: Write helper**

```typescript
// Minimal client-side CSV export — no dependencies.
// RFC 4180 lite: quote fields containing comma/quote/newline, escape quotes by doubling.

export function downloadCsv(filename: string, header: string[], rows: (string | number | null | undefined)[][]): void {
  const escape = (v: string | number | null | undefined): string => {
    if (v == null) return '';
    const s = String(v);
    if (s.includes(',') || s.includes('"') || s.includes('\n')) {
      return `"${s.replace(/"/g, '""')}"`;
    }
    return s;
  };

  const lines = [
    header.map(escape).join(','),
    ...rows.map((row) => row.map(escape).join(',')),
  ];
  const csv = '﻿' + lines.join('\n');  // BOM for Excel UTF-8 detection
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
```

- [ ] **Step 2: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 3: Codex + commit**

```bash
git add admin-ui/src/lib/csvExport.ts
```

Codex prompt: "codex review csvExport.ts. Verify: (1) RFC 4180-lite escape (quote when comma/quote/newline; double quotes), (2) BOM prefix for Excel UTF-8 detection, (3) URL.revokeObjectURL after click, (4) no external dependencies. Must-fix only."

```bash
git commit -m "feat(admin-ui): lib/csvExport — client-side CSV blob downloader (Gap #10/#11/#12)"
```

---

## Task 2: ActivityController `?before=` pagination

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java`

- [ ] **Step 1: Inspect**

```bash
cat admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java
ls admin-app/src/main/java/com/crosscert/passkey/admin/activity/
```

Identify the existing `@GetMapping` signature.

- [ ] **Step 2: Add optional `before` Instant query param**

In Controller, add `@RequestParam(required = false) Instant before` to the existing `fetch(...)` (or whatever method name) signature. Forward `before` to the service. Convert nullable string properly if Spring doesn't bind Instant directly — use `@DateTimeFormat(iso=DATE_TIME)` if needed.

In Service, when `before != null`, change the query to `created_at < :before` AND order by `created_at DESC` — return same `ActivityView` shape but the feed window shifts. KPIs/topTenants should still be computed over the latest 24h window (don't shift them; only the feed paginates).

Verify the existing query method on `AuditLogRepository` (or wherever the activity feed comes from). If a new method is needed, add a derived signature like `findByCreatedAtBeforeOrderByCreatedAtDesc(Instant before, Pageable page)` — but only if there isn't already a suitable one.

- [ ] **Step 3: Compile**

```bash
./gradlew :admin-app:compileJava
```

- [ ] **Step 4: Smoke test (manual via curl)** — skip formal IT per execution policy.

- [ ] **Step 5: Codex + commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java
# also add repository if modified
git status --short
```

Codex prompt: "codex review ActivityController/Service `?before=` pagination. Verify: (1) before is optional, defaults to now (or 'no filter'), (2) feed query changed to created_at < before when supplied, (3) KPIs/topTenants NOT shifted (they remain 24h-from-now), (4) response shape unchanged. Must-fix only."

```bash
git commit -m "feat(admin-app): ActivityController ?before= pagination (Gap #13)"
```

---

## Task 3: TenantOverview 4 onClick handlers

**Files:**
- Modify: `admin-ui/src/pages/tenant/TenantOverview.tsx`

## CRITICAL: ZERO design changes

- [ ] **Step 1: Inspect existing no-op buttons**

```bash
grep -n '<button className="btn btn--sm">\(편집\|전체 보기\|수동 검증\|월간 보고서\)' admin-ui/src/pages/tenant/TenantOverview.tsx
```

Expected matches:
- Line ~116, 117 (inside `ChainStatusCard` — `수동 검증` + `월간 보고서`)
- Line ~138, 139 (TAMPERED state — same 2 buttons duplicated)
- Line ~200 (`편집` button in WebAuthn 요약 카드 header)
- Line ~216 (`전체 보기` button in 최근 활동 카드 header)

- [ ] **Step 2: Add imports + navigate hook**

If `useNavigate` is already imported, reuse. Otherwise add:
```typescript
import { useNavigate } from 'react-router-dom';
```

Add hook inside `TenantOverview` component:
```typescript
const navigate = useNavigate();
```

If `auditChainApi.verifyTenant` is already imported (it is — see the existing `useEffect` calling it), reuse for `수동 검증`. Also import `useToast` if not already:
```typescript
import { useToast } from '@/shell/ToastHost';
const toast = useToast();
```

- [ ] **Step 3: Wire 4 buttons (or 6 occurrences — duplicate 수동 검증/월간 보고서)**

For `편집`:
```typescript
<button className="btn btn--sm" onClick={() => navigate(`/tenants/${tenant.id}/webauthn-config`)}>
  편집 <Icons.ChevronRight size={12} />
</button>
```

For `전체 보기`:
```typescript
<button className="btn btn--sm" onClick={() => navigate(`/activity?tenantId=${tenant.id}`)}>
  전체 보기 <Icons.ChevronRight size={12} />
</button>
```

For `수동 검증` (verify chain for this tenant):
```typescript
<button className="btn btn--sm" onClick={async () => {
  try {
    const result = await auditChainApi.verifyTenant(tenant.id);
    setChainState(result);
    toast({ kind: 'ok', title: '수동 검증 완료', message: result.intact ? '체인 무결' : '위변조 감지' });
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    toast({ kind: 'err', title: '수동 검증 실패', message: msg });
  }
}}>
  <Icons.Hash size={12} /> 수동 검증
</button>
```

For `월간 보고서`:
```typescript
<button className="btn btn--sm" onClick={() => navigate('/audit-chain')}>
  <Icons.Download size={12} /> 월간 보고서
</button>
```

> Apply to BOTH occurrences of 수동 검증/월간 보고서 in the chain status card (intact and TAMPERED render branches).

> If `ChainStatusCard` is a separate component receiving callbacks via props, you may need to thread the handlers down. Prefer passing handlers as props rather than putting the logic inside the subcomponent — keeps the navigation/api concern at the page level.

- [ ] **Step 4: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 5: Codex + commit**

```bash
git add admin-ui/src/pages/tenant/TenantOverview.tsx
```

Codex prompt: "codex review TenantOverview 4 onClick handlers. CRITICAL: verify ZERO design changes — only onClick attributes added to existing buttons, JSX byte-identical. 편집→navigate webauthn-config, 전체 보기→navigate /activity?tenantId=, 수동 검증→auditChainApi.verifyTenant + toast + setChainState, 월간 보고서→navigate /audit-chain. Duplicated chain buttons (intact + TAMPERED branches) both wired. Must-fix only."

```bash
git commit -m "feat(admin-ui): TenantOverview no-op buttons wired (Gap #6/#7/#8)"
```

---

## Task 4: ActivityPage — useSearchParams + 3 onClick

**Files:**
- Modify: `admin-ui/src/pages/ActivityPage.tsx`

## CRITICAL: ZERO design changes

- [ ] **Step 1: Add `?tenantId=` reading via useSearchParams**

Add import:
```typescript
import { useSearchParams } from 'react-router-dom';
```

Inside component:
```typescript
const [searchParams] = useSearchParams();
const tenantFilter = searchParams.get('tenantId');
```

Pass `tenantFilter` to `activityApi.fetch(...)` (first arg — sinceId or tenantId depending on signature). **IMPORTANT**: in F3 Task 8 we found `activityApi.fetch(sinceId, category)` — the first arg is sinceId, NOT tenantId. So the URL query param translates to a server-side filter only if ActivityController supports it. Inspect first:

```bash
grep -n "fetch\|@RequestParam\|tenantId" admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityController.java
```

If the controller doesn't already accept tenantId, options:
- (a) Add `@RequestParam(required=false) UUID tenantId` to controller + service — minimal change
- (b) Filter client-side after fetch

Pick **(a)** — small backend addition matches the spec, F2 already established this pattern.

If (a): also extend `activityApi.fetch` signature to accept `tenantId?: string` and append `&tenantId=...` to query string.

- [ ] **Step 2: Wire 3 no-op buttons**

`내보내기` (~line 290):
```typescript
<button className="btn btn--sm" onClick={() => {
  if (!events) return;
  downloadCsv(
    `activity-${new Date().toISOString().slice(0,10)}.csv`,
    ['timestamp', 'tenant', 'type', 'category', 'actor', 'subject'],
    events.map((e) => [e.ts, e.tenantSlug ?? e.tenantId ?? '', e.type, e.category, e.actorId ?? '', e.subjectId]),
  );
}}>
  <Icons.Download size={12} /> 내보내기
</button>
```

`이전 24시간 더 보기` (~line 394):
```typescript
<button className="btn btn--sm" onClick={async () => {
  if (!events || events.length === 0) return;
  const oldest = events[events.length - 1].ts;
  try {
    const more = await activityApi.fetch(null, categoryFilter === 'all' ? undefined : categoryFilter, oldest, tenantFilter);
    const adapted = adaptServerView(more);
    setEvents([...events, ...adapted.events]);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    toast({ kind: 'err', title: '추가 로드 실패', message: msg });
  }
}}>
  이전 24시간 더 보기
</button>
```

> Adjust `activityApi.fetch` signature to add `before?: string` and `tenantId?: string` params. Both nullable.

이벤트 행 `ChevronRight` (~line 381):
```typescript
<button className="btn btn--ghost btn--xs" onClick={() => {
  toast({ kind: 'info', title: e.type, message: `id: ${e.id} · subject: ${e.subjectId}` });
}}>
  <Icons.ChevronRight size={12} />
</button>
```

- [ ] **Step 3: Update `activityApi` signature**

In `admin-ui/src/api/activity.ts`, extend `fetch` to accept `(sinceId, category, before, tenantId)`. All except first nullable.

- [ ] **Step 4: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 5: Codex + commit**

```bash
git add admin-ui/src/pages/ActivityPage.tsx admin-ui/src/api/activity.ts
```

Codex prompt: "codex review ActivityPage F5 wiring + activityApi extension. CRITICAL: verify ZERO design changes — only onClick + useSearchParams + activityApi signature extension. 내보내기→csvExport, 이전 24시간 더 보기→pagination via ?before=, ChevronRight→toast info. tenantFilter URL param threads through to activityApi. Must-fix only."

```bash
git commit -m "feat(admin-ui): ActivityPage no-op buttons wired + tenant filter URL (Gap #7/#12/#13)"
```

---

## Task 5: AuditTab — 3 no-op buttons

**Files:**
- Modify: `admin-ui/src/pages/tenant/AuditTab.tsx`

## CRITICAL: ZERO design changes

- [ ] **Step 1: Inspect**

```bash
sed -n '125,140p' admin-ui/src/pages/tenant/AuditTab.tsx
sed -n '420,440p' admin-ui/src/pages/tenant/AuditTab.tsx
```

Identify 3 no-op buttons (보고서, 최근 24시간 ▾, 내보내기).

- [ ] **Step 2: Wire onClick**

`보고서` (line ~130 area): `onClick={() => navigate('/audit-chain')}` — same target as TenantOverview's 월간 보고서.

`최근 24시간 ▾` (line ~425 area): toggle cycle `24h → 7d → 30d → 24h`. Use local state `[windowHours, setWindowHours] = useState<24 | 168 | 720>(24)` and on click cycle. Update the button label to match: `최근 24시간 ▾` / `최근 7일 ▾` / `최근 30일 ▾`. This means the JSX text inside the button changes — that's an expression source change, NOT a design change.

The window state should feed into the audit API call (existing `auditApi.list(tenantId, ...)` likely already accepts time range; if not, extend it).

`내보내기` (line ~428 area):
```typescript
onClick={() => {
  if (!rows || rows.length === 0) return;
  downloadCsv(
    `audit-${tenant.slug}-${new Date().toISOString().slice(0,10)}.csv`,
    ['timestamp', 'action', 'actor', 'targetType', 'targetId', 'payload'],
    rows.map((r) => [r.createdAt, r.action, r.actorEmail ?? '', r.targetType ?? '', r.targetId ?? '', JSON.stringify(r.payload ?? {})]),
  );
}}
```

Add `import { downloadCsv } from '@/lib/csvExport';` and `import { useNavigate } from 'react-router-dom';` (or reuse if already imported).

- [ ] **Step 3: tsc**

- [ ] **Step 4: Codex + commit**

```bash
git add admin-ui/src/pages/tenant/AuditTab.tsx
```

Codex prompt: "codex review AuditTab 3 onClick wiring. CRITICAL: ZERO design changes for JSX structure; only button text labels in 최근 24시간 토글 cycle change (expression source). 보고서→navigate /audit-chain, 최근 24시간→cycle state 24/168/720, 내보내기→csvExport. Must-fix only."

```bash
git commit -m "feat(admin-ui): AuditTab no-op buttons wired (Gap #11)"
```

---

## Task 6: CredentialsTab — 2 no-op buttons

**Files:**
- Modify: `admin-ui/src/pages/tenant/CredentialsTab.tsx`

## CRITICAL: ZERO design changes (no new visual elements)

- [ ] **Step 1: Wire CSV button**

Line ~149 area:
```typescript
<button className="btn btn--sm" onClick={() => {
  if (!filtered || filtered.length === 0) return;
  downloadCsv(
    `credentials-${tenant.slug}-${new Date().toISOString().slice(0,10)}.csv`,
    ['credentialId', 'externalUserId', 'nickname', 'status', 'aaguid', 'signatureCounter', 'lastUsedAt', 'createdAt'],
    filtered.map((c) => [c.credentialId, c.externalUserId, c.nickname ?? '', c.status, c.aaguid ?? '', c.signatureCounter, c.lastUsedAt ?? '', c.createdAt]),
  );
}}>
  <Icons.Download size={12} /> CSV
</button>
```

- [ ] **Step 2: Wire `aaguid · status` filter toggle**

Per spec § F5.3 conservative approach: cycle search input MODE (placeholder + filtering logic). Add `searchMode` state `'keyword' | 'aaguid' | 'status'`. Button cycles. Search input `placeholder` changes per mode. Filter logic in `filtered` useMemo branches per mode.

Original button (line ~145):
```typescript
<button className="btn btn--sm" onClick={() => {
  setSearchMode((m) => m === 'keyword' ? 'aaguid' : m === 'aaguid' ? 'status' : 'keyword');
}}>
  <Icons.Filter size={12} /> aaguid · status
</button>
```

Original input placeholder:
```typescript
placeholder={
  searchMode === 'aaguid' ? 'aaguid 검색 (예: ea9b8d66)'
  : searchMode === 'status' ? 'status: ACTIVE 또는 REVOKED'
  : 'externalUserId · nickname · credentialId 검색'
}
```

Update the `filtered` memo to branch per mode:
```typescript
const filtered = useMemo(() => {
  const q = searchQuery.toLowerCase();
  return items.filter((c) => {
    if (searchMode === 'aaguid') {
      return c.aaguid?.toLowerCase().includes(q) ?? false;
    }
    if (searchMode === 'status') {
      return c.status.toLowerCase().includes(q);
    }
    return c.externalUserId.toLowerCase().includes(q)
        || (c.nickname?.toLowerCase().includes(q) ?? false)
        || c.credentialId.toLowerCase().includes(q);
  });
}, [items, searchQuery, searchMode]);
```

> All JSX (`<input className="input" ...>`, button etc.) stays byte-identical. Only the `placeholder` and `filter` logic branch.

- [ ] **Step 3: tsc**

- [ ] **Step 4: Codex + commit**

```bash
git add admin-ui/src/pages/tenant/CredentialsTab.tsx
```

Codex prompt: "codex review CredentialsTab 2 no-op button wiring. CRITICAL: ZERO design elements added — only placeholder text + filter branch change on existing search input. CSV button uses csvExport with all credential fields. Search mode cycle through keyword/aaguid/status. Must-fix only."

```bash
git commit -m "feat(admin-ui): CredentialsTab no-op buttons wired (Gap #10)"
```

---

## Task 7: Cleanup `fixtures/auditChainState.ts`

**Files:**
- Delete: `admin-ui/src/fixtures/auditChainState.ts`

- [ ] **Step 1: Verify zero references**

```bash
grep -rn "fixtures/auditChainState\|auditChainStateFixture" admin-ui/src/
```

Expected: zero matches outside the file itself. If matches exist, report BLOCKED.

- [ ] **Step 2: Delete**

```bash
git rm admin-ui/src/fixtures/auditChainState.ts
```

- [ ] **Step 3: tsc**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 4: Codex + commit**

```bash
git diff --cached
```

Codex prompt: "codex review staged deletion of fixtures/auditChainState.ts. Verify no remaining references in admin-ui/src/. Must-fix only."

```bash
git commit -m "chore(admin-ui): delete fixtures/auditChainState.ts (Gap audit cleanup)"
```

---

## Task 8: F5 regression + cumulative codex + `--no-ff` merge

**Files:** (none)

- [ ] **Step 1: Backend test suite**

```bash
./gradlew :admin-app:test
```

Pre-existing 20 `*ControllerSecurityTest` failures persist (F1-F4 era). New F5 backend changes: ActivityController `?before=` parameter, possibly `?tenantId=` parameter. If any new regression, drill in.

- [ ] **Step 2: TS check**

```bash
cd admin-ui && npx tsc --noEmit
```

- [ ] **Step 3: Cumulative codex review**

```bash
git diff main..HEAD --stat
```

Codex prompt: "codex review the cumulative Phase F5 diff. Focus: (1) admin-ui design unchanged across 4 modified pages (only onClick/state/placeholder/expression sources changed; no new DOM elements added except possibly new dropdown menus — but plan said no new dropdowns, only state cycles), (2) csvExport helper used consistently with proper escape, (3) ActivityController query param is optional + backward-compat, (4) auditChainState fixture deletion has zero remaining references, (5) all 7 no-op buttons (#6, #7, #8, #10, #11, #12, #13) now have onClick handlers. Must-fix only. APPROVED for merge if clean."

Apply must-fix as `fix(f5): codex final review feedback`.

- [ ] **Step 4: Merge `--no-ff` to main**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff worktree-gap-fill-f5 -m "Merge Phase F5 — No-op buttons + cleanup (Gap #6/#7/#8/#10/#11/#12/#13)"
git log --oneline -5
```

- [ ] **Step 5: Final F-series acceptance gate**

Verify all 4 final criteria from spec § "Acceptance — 전체 phase 통과 기준":

```bash
# 1. fixtures/ directory has only activity.ts
ls admin-ui/src/fixtures/
# 2. zero 'fixture' references in pages/shell/extras (except imports of api/types)
grep -rn "fixture" admin-ui/src/pages admin-ui/src/shell admin-ui/src/extras | grep -v "from '@/api"
# 3. every <button has onClick or type=submit or disabled
grep -rE "<button " admin-ui/src/pages | grep -v "onClick\|type=\"submit\"\|disabled" | head
# 4. (manual smoke — user verifies)
```

- [ ] **Step 6: Manual smoke checklist for user**

**TenantDetail → Overview:**
- WebAuthn 요약 카드 `편집` 클릭 → WebauthnConfig 탭으로 이동
- 최근 활동 카드 `전체 보기` 클릭 → ActivityPage 이동 + `?tenantId=` URL 확인
- 체인 상태 카드 `수동 검증` 클릭 → 토스트 + chainState 즉시 업데이트
- 체인 상태 카드 `월간 보고서` 클릭 → AuditChainPage 이동

**ActivityPage:**
- `?tenantId=<id>` 로 진입 → 해당 tenant 만 노출
- `내보내기` 클릭 → CSV 다운로드
- `이전 24시간 더 보기` 클릭 → 추가 events append
- 이벤트 행 `ChevronRight` 클릭 → 이벤트 ID 토스트

**TenantDetail → Audit 탭:**
- `보고서` 클릭 → AuditChainPage 이동
- `최근 24시간 ▾` 클릭 → 라벨 cycle (24h → 7d → 30d → 24h) + 데이터 갱신
- `내보내기` 클릭 → CSV 다운로드

**TenantDetail → Credentials 탭:**
- `aaguid · status` 클릭 → 검색 모드 토글 (placeholder 변경)
- `CSV` 클릭 → CSV 다운로드

**Fixture cleanup:**
- `admin-ui/src/fixtures/` 에 `activity.ts` 한 개만 남음

## Report

Status, backend test, tsc, codex verdict, merge SHA, acceptance gate result, manual smoke checklist.

---

## Phase F5 Summary

**What ships:**
- `lib/csvExport.ts` 헬퍼 (BOM + RFC 4180-lite)
- ActivityController `?before=` + `?tenantId=` 파라미터
- 4개 페이지에 onClick 핸들러 추가 (TenantOverview / ActivityPage / AuditTab / CredentialsTab)
- `fixtures/auditChainState.ts` 삭제

**Design impact:** zero (button text in 최근 24시간 토글은 expression source 변경, JSX 구조 동일).

**Closed gaps:** #6, #7, #8, #10, #11, #12, #13 (no-op 버튼 7건 + cleanup).

**Phase F1-F5 합산:**
- 닫힌 gap: 20개 전부 (Critical 2 + Major 10 + Minor 8)
- 디자인 영향: 0
- 마이그레이션: V30 (점거됨), V31~V34 신규 4개
- 신규 컨트롤러: 5개 (SecurityPolicy, Funnel, SystemInfo, MonthlyReport, MdsHistory endpoint)
- 신규 어댑터: 7개 (securityPolicy, monthlyReport, systemInfo, mdsStatus history method 등)
- 삭제된 fixture: 6개 (securityPolicy, adminMfa, tenantKpi, funnel, systemInfo, auditChainState)
- 새 audit 이벤트: 2종 (SECURITY_POLICY_UPDATED, MONTHLY_REPORT_GENERATED)
