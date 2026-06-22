# admin-ui 화면 용어 한국어화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-ui 화면에 영어로 노출되는 일반 어드민 용어(상태 배지·네비/탭/헤더·AAGUID 모드)를 한국어로 바꾸고, WebAuthn/FIDO 기술 용어는 영어로 유지한다.

**Architecture:** 중앙 라벨 파일 `src/i18n/labels.ts`에 상태 enum→한국어 매핑과 `statusLabel` 헬퍼를 두고, `StatusBadge` 공통 컴포넌트와 페이지 하드코딩 배지가 모두 이를 경유하게 한다. enum 값·API 계약·코드 로직은 영어 그대로 두고 **화면 표시만** 변환하므로 BE/API 무변경.

**Tech Stack:** React + TypeScript (Vite), vitest + @testing-library/react

## Global Constraints

- **표시만 한국어, enum 값은 영어 유지** — `=== 'ACTIVE'` 같은 비교/필터/API 값은 절대 바꾸지 않는다. 화면에 그릴 때만 라벨로 변환.
- **BE/DB/API 무변경** — FE 전용 작업.
- **영어 유지 (보존)**: `rpId`, `rpName`, `origins`, `attestation`, `attestationConveyance`, `userVerification`, `timeoutMs`, `transports`, `AAGUID`, `MDS`, `FIDO2`, `WebAuthn`, `credential`(필드명), `ceremony`, WebAuthn enum 선택지(`REQUIRED/PREFERRED/DISCOURAGED`, `NONE/INDIRECT/DIRECT/ENTERPRISE`), `Tenant`/`Tenants`(아키텍처 용어).
- **상태어 번역 확정**: ACTIVE=활성, SUSPENDED=정지, REVOKED=회수, EXPIRED=만료, PENDING=대기, INTACT=정상, TAMPERED=위변조, OPEN=처리중, RESOLVED=해결, SUCCESS=성공, FAILED=실패, ROTATED=교체됨, SYNCED=동기화됨, SKIPPED=건너뜀.
- **AAGUID 모드 번역**: ANY=전체 허용, ALLOWLIST=허용 목록, DENYLIST=차단 목록.
- 테스트는 `npx vitest run <path>`, 타입체크는 `npx tsc --noEmit`. 작업 디렉토리는 `admin-ui/`.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` 추가. 브랜치는 `feat/admin-ui-korean-labels` (이미 생성됨).

---

### Task 1: 중앙 라벨 파일 + StatusBadge 통합

**Files:**
- Create: `admin-ui/src/i18n/labels.ts`
- Create: `admin-ui/src/i18n/labels.test.ts`
- Modify: `admin-ui/src/shell/StatusBadge.tsx` (전체 10줄)

**Interfaces:**
- Produces:
  - `STATUS_LABELS: Record<string, string>` — 상태 enum→한국어 매핑
  - `statusLabel(v: string): string` — 매핑된 라벨, 미매핑 시 원본 반환
  - `AAGUID_MODE_LABELS: Record<string, string>` — AAGUID 모드 enum→한국어 (Task 5에서 사용)
  - `StatusBadge`는 동일 시그니처(`{ status: string }`) 유지하되 표시 텍스트만 한국어로

- [ ] **Step 1: Write the failing test**

Create `admin-ui/src/i18n/labels.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { statusLabel, STATUS_LABELS, AAGUID_MODE_LABELS } from './labels';

describe('statusLabel', () => {
  it('maps known status enums to Korean', () => {
    expect(statusLabel('ACTIVE')).toBe('활성');
    expect(statusLabel('SUSPENDED')).toBe('정지');
    expect(statusLabel('INTACT')).toBe('정상');
    expect(statusLabel('TAMPERED')).toBe('위변조');
    expect(statusLabel('OPEN')).toBe('처리중');
    expect(statusLabel('RESOLVED')).toBe('해결');
    expect(statusLabel('SUCCESS')).toBe('성공');
    expect(statusLabel('FAILED')).toBe('실패');
  });

  it('returns the original value for unmapped status (safe fallback)', () => {
    expect(statusLabel('UNKNOWN_FUTURE')).toBe('UNKNOWN_FUTURE');
  });

  it('covers all spec-defined status enums', () => {
    const expected = {
      ACTIVE: '활성', SUSPENDED: '정지', REVOKED: '회수', EXPIRED: '만료',
      PENDING: '대기', INTACT: '정상', TAMPERED: '위변조', OPEN: '처리중',
      RESOLVED: '해결', SUCCESS: '성공', FAILED: '실패', ROTATED: '교체됨',
      SYNCED: '동기화됨', SKIPPED: '건너뜀',
    };
    expect(STATUS_LABELS).toEqual(expected);
  });

  it('maps AAGUID policy modes to Korean', () => {
    expect(AAGUID_MODE_LABELS).toEqual({
      ANY: '전체 허용', ALLOWLIST: '허용 목록', DENYLIST: '차단 목록',
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd admin-ui && npx vitest run src/i18n/labels.test.ts`
Expected: FAIL — "Failed to resolve import './labels'" (파일 미존재)

- [ ] **Step 3: Create the labels module**

Create `admin-ui/src/i18n/labels.ts`:

```ts
// 화면 표시 라벨 — enum 값(영어)은 코드 로직·API에 그대로 쓰고, 화면에 그릴 때만 변환한다.

/** 상태 배지 공용 라벨. enum 값은 영어 유지, 표시만 한국어. */
export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '활성',
  SUSPENDED: '정지',
  REVOKED: '회수',
  EXPIRED: '만료',
  PENDING: '대기',
  INTACT: '정상',
  TAMPERED: '위변조',
  OPEN: '처리중',
  RESOLVED: '해결',
  SUCCESS: '성공',
  FAILED: '실패',
  ROTATED: '교체됨',
  SYNCED: '동기화됨',
  SKIPPED: '건너뜀',
};

/** 상태 enum 값을 한국어 표시 라벨로. 미매핑 값은 원본 그대로 반환(안전한 fallback). */
export const statusLabel = (v: string): string => STATUS_LABELS[v] ?? v;

/** AAGUID 정책 모드 enum → 한국어. enum 값(ANY/ALLOWLIST/DENYLIST)은 영어 유지. */
export const AAGUID_MODE_LABELS: Record<string, string> = {
  ANY: '전체 허용',
  ALLOWLIST: '허용 목록',
  DENYLIST: '차단 목록',
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd admin-ui && npx vitest run src/i18n/labels.test.ts`
Expected: PASS (4 tests)

- [ ] **Step 5: Update StatusBadge to use statusLabel**

Replace the entire contents of `admin-ui/src/shell/StatusBadge.tsx` with:

```tsx
import { statusLabel } from '@/i18n/labels';

export function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    ACTIVE: 'success',
    REVOKED: 'danger',
    EXPIRED: 'warning',
    SUSPENDED: 'warning',
    PENDING: 'info',
  };
  return <span className={`badge badge--${map[status] || 'default'} badge--dot`}>{statusLabel(status)}</span>;
}
```

Note: 색상 매핑(`map`)은 enum 값 기준이라 그대로 둔다. 표시 텍스트만 `statusLabel(status)`로 변환.

- [ ] **Step 6: Verify the `@/` import alias resolves**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: 에러 없음 (exit 0). `@/i18n/labels` 가 해석되는지 확인. (만약 `@/` alias가 없다면 상대경로 `../i18n/labels`로 변경 후 재실행.)

- [ ] **Step 7: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add admin-ui/src/i18n/labels.ts admin-ui/src/i18n/labels.test.ts admin-ui/src/shell/StatusBadge.tsx
git commit -m "feat(admin-ui): 상태 라벨 한국어 매핑 + StatusBadge 통합

src/i18n/labels.ts 에 STATUS_LABELS/statusLabel/AAGUID_MODE_LABELS 추가.
StatusBadge 가 statusLabel 을 경유해 한국어 표시. enum 값은 영어 유지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 하드코딩 상태 배지 한국어화 (AuditChain / TenantOverview / CredentialDetail)

**Files:**
- Modify: `admin-ui/src/pages/AuditChainPage.tsx` (라인 146, 352-353, 378-380, 401-404, 439-442)
- Modify: `admin-ui/src/pages/tenant/TenantOverview.tsx` (라인 122, 142)
- Modify: `admin-ui/src/pages/tenant/CredentialDetailDialog.tsx` (라인 17-23)

**Interfaces:**
- Consumes: `statusLabel` from `@/i18n/labels` (Task 1)

이 Task는 순수 텍스트 치환이라 컴포넌트 단위 테스트 대신 타입체크 + 정확한 문자열 교체로 검증한다. (배지 텍스트는 JSX 리터럴이라 단위 테스트 ROI가 낮고, 최종 browse 육안 확인이 Task 6에서 커버.)

- [ ] **Step 1: AuditChainPage — INTACT/TAMPERED 배지 3곳 교체**

`admin-ui/src/pages/AuditChainPage.tsx` 상단 import에 `statusLabel` 추가 (기존 import 블록에 한 줄):

```tsx
import { statusLabel } from '@/i18n/labels';
```

라인 146 (MonthlyReportDialog 내부) — 다음을 찾아:

```tsx
{c.intact ? <span className="badge badge--success">INTACT</span> : <span className="badge badge--danger">TAMPERED</span>}
```

다음으로 교체:

```tsx
{c.intact ? <span className="badge badge--success">{statusLabel('INTACT')}</span> : <span className="badge badge--danger">{statusLabel('TAMPERED')}</span>}
```

라인 352-353 (카드 헤더 메트릭) — 다음을 찾아:

```tsx
<span className="badge badge--success badge--dot">INTACT {totals.tenantsIntact}</span>
{totals.tenantsTampered > 0 && <span className="badge badge--danger badge--dot">TAMPERED {totals.tenantsTampered}</span>}
```

다음으로 교체:

```tsx
<span className="badge badge--success badge--dot">{statusLabel('INTACT')} {totals.tenantsIntact}</span>
{totals.tenantsTampered > 0 && <span className="badge badge--danger badge--dot">{statusLabel('TAMPERED')} {totals.tenantsTampered}</span>}
```

라인 378-380 (테이블 상태 셀) — 다음을 찾아:

```tsx
{c.intact ? (
  <span className="badge badge--success badge--dot">INTACT</span>
) : (
  <span className="badge badge--danger badge--dot">TAMPERED</span>
)}
```

다음으로 교체:

```tsx
{c.intact ? (
  <span className="badge badge--success badge--dot">{statusLabel('INTACT')}</span>
) : (
  <span className="badge badge--danger badge--dot">{statusLabel('TAMPERED')}</span>
)}
```

- [ ] **Step 2: AuditChainPage — OPEN/RESOLVED 배지 2곳 교체**

라인 401-404 (Incident 카운트 배지) — 다음을 찾아:

```tsx
{incidents.some((i) => i.status === 'OPEN') && (
  <span className="badge badge--danger badge--dot">
    OPEN {incidents.filter((i) => i.status === 'OPEN').length}
  </span>
)}
```

다음으로 교체 (비교 `=== 'OPEN'` 은 그대로, 표시 텍스트만 변환):

```tsx
{incidents.some((i) => i.status === 'OPEN') && (
  <span className="badge badge--danger badge--dot">
    {statusLabel('OPEN')} {incidents.filter((i) => i.status === 'OPEN').length}
  </span>
)}
```

라인 439-442 (Incident 테이블 상태) — 다음을 찾아:

```tsx
{inc.status === 'OPEN' ? (
  <span className="badge badge--danger badge--dot">OPEN</span>
) : (
  <span className="badge badge--dot">RESOLVED</span>
)}
```

다음으로 교체:

```tsx
{inc.status === 'OPEN' ? (
  <span className="badge badge--danger badge--dot">{statusLabel('OPEN')}</span>
) : (
  <span className="badge badge--dot">{statusLabel('RESOLVED')}</span>
)}
```

- [ ] **Step 3: TenantOverview — INTACT/TAMPERED 배지 교체**

`admin-ui/src/pages/tenant/TenantOverview.tsx` 상단 import에 추가:

```tsx
import { statusLabel } from '@/i18n/labels';
```

라인 122 — `<span className="badge badge--success badge--dot">INTACT</span>` 를 찾아:

```tsx
<span className="badge badge--success badge--dot">{statusLabel('INTACT')}</span>
```

라인 142 — `<span className="badge badge--danger badge--dot">TAMPERED</span>` 를 찾아:

```tsx
<span className="badge badge--danger badge--dot">{statusLabel('TAMPERED')}</span>
```

- [ ] **Step 4: CredentialDetailDialog — ResultBadge 교체**

`admin-ui/src/pages/tenant/CredentialDetailDialog.tsx` 상단 import에 추가:

```tsx
import { statusLabel } from '@/i18n/labels';
```

라인 17-23 — 다음을 찾아:

```tsx
function ResultBadge({ result }: { result: 'SUCCESS' | 'FAILED' }) {
  const ok = result === 'SUCCESS';
  return (
    <span className={`badge badge--dot badge--${ok ? 'success' : 'danger'}`}>
      {result}
    </span>
  );
}
```

다음으로 교체 (비교 `=== 'SUCCESS'` 그대로, 표시만 변환):

```tsx
function ResultBadge({ result }: { result: 'SUCCESS' | 'FAILED' }) {
  const ok = result === 'SUCCESS';
  return (
    <span className={`badge badge--dot badge--${ok ? 'success' : 'danger'}`}>
      {statusLabel(result)}
    </span>
  );
}
```

- [ ] **Step 5: Run typecheck**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: 에러 없음 (exit 0)

- [ ] **Step 6: Verify no remaining hardcoded English status badges in these files**

Run: `cd admin-ui && grep -nE ">INTACT<|>TAMPERED<|>OPEN |>OPEN<|>RESOLVED<|>SUCCESS<|>FAILED<|{result}" src/pages/AuditChainPage.tsx src/pages/tenant/TenantOverview.tsx src/pages/tenant/CredentialDetailDialog.tsx`
Expected: 빈 결과 (모두 statusLabel 경유로 교체됨). 단 `=== 'OPEN'` 같은 **비교 로직은 남아있어야 정상** (이 grep은 `>...<` 표시 텍스트만 잡음).

- [ ] **Step 7: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add admin-ui/src/pages/AuditChainPage.tsx admin-ui/src/pages/tenant/TenantOverview.tsx admin-ui/src/pages/tenant/CredentialDetailDialog.tsx
git commit -m "feat(admin-ui): 하드코딩 상태 배지 한국어화 (INTACT/TAMPERED/OPEN/RESOLVED/SUCCESS/FAILED)

AuditChainPage·TenantOverview·CredentialDetailDialog 의 직접 렌더 배지를
statusLabel 경유로 변환. enum 비교 로직은 그대로 유지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 네비/탭/브레드크럼 라벨 한국어화 (Sidebar / App)

**Files:**
- Modify: `admin-ui/src/shell/Sidebar.tsx` (라인 23-40, 158)
- Modify: `admin-ui/src/App.tsx` (라인 52-70)
- Modify: `admin-ui/src/shell/Sidebar.test.tsx` (기존 테스트가 라벨 문자열에 의존하면 갱신)

**Interfaces:**
- Consumes: 없음 (직접 문자열 교체)

보존: `Tenants`(NAV_PLATFORM, 브레드크럼, 뒤로가기 버튼), `WebAuthn`, `AAGUID 정책`, `Credentials`, `개요`, `설정` — 그대로 둔다.

- [ ] **Step 1: 기존 Sidebar 테스트가 영어 라벨에 의존하는지 확인**

Run: `cd admin-ui && grep -nE "Activity|Audit Chain|License|Funnel|API Keys|Audit Logs|Tenants" src/shell/Sidebar.test.tsx`
Expected: 매칭되는 줄을 기록해 둔다. 매칭이 있으면 Step 6에서 해당 단언을 새 한국어 라벨로 갱신한다. 매칭이 없으면 Step 6는 skip.

- [ ] **Step 2: Sidebar NAV_PLATFORM — Activity / Audit Chain 교체**

`admin-ui/src/shell/Sidebar.tsx` 라인 23-28 — 다음을 찾아:

```tsx
const NAV_PLATFORM: { id: TopLevelRouteName; label: string; icon: string }[] = [
  { id: 'tenants', label: 'Tenants', icon: 'Building' },
  { id: 'activity', label: 'Activity', icon: 'Activity' },
  { id: 'audit-chain', label: 'Audit Chain', icon: 'Hash' },
  { id: 'settings', label: '설정', icon: 'Cog' },
];
```

다음으로 교체 (`Tenants`·`설정`은 유지):

```tsx
const NAV_PLATFORM: { id: TopLevelRouteName; label: string; icon: string }[] = [
  { id: 'tenants', label: 'Tenants', icon: 'Building' },
  { id: 'activity', label: '활동', icon: 'Activity' },
  { id: 'audit-chain', label: '감사 체인', icon: 'Hash' },
  { id: 'settings', label: '설정', icon: 'Cog' },
];
```

- [ ] **Step 3: Sidebar NAV_LICENSE — License 교체**

라인 30 — 다음을 찾아:

```tsx
const NAV_LICENSE: { id: TopLevelRouteName; label: string; icon: string } = { id: 'license', label: 'License', icon: 'Key' };
```

다음으로 교체:

```tsx
const NAV_LICENSE: { id: TopLevelRouteName; label: string; icon: string } = { id: 'license', label: '라이선스', icon: 'Key' };
```

- [ ] **Step 4: Sidebar NAV_RP — API Keys / Audit Logs / Funnel 교체**

라인 32-40 — 다음을 찾아:

```tsx
const NAV_RP = [
  { id: 'overview', label: '개요', icon: 'Activity' },
  { id: 'webauthn', label: 'WebAuthn', icon: 'Globe' },
  { id: 'aaguid', label: 'AAGUID 정책', icon: 'Shield' },
  { id: 'apikeys', label: 'API Keys', icon: 'Key' },
  { id: 'credentials', label: 'Credentials', icon: 'Fingerprint' },
  { id: 'audit', label: 'Audit Logs', icon: 'Receipt' },
  { id: 'funnel', label: 'Funnel', icon: 'Activity' },
];
```

다음으로 교체 (`WebAuthn`·`AAGUID 정책`·`Credentials`·`개요`는 유지):

```tsx
const NAV_RP = [
  { id: 'overview', label: '개요', icon: 'Activity' },
  { id: 'webauthn', label: 'WebAuthn', icon: 'Globe' },
  { id: 'aaguid', label: 'AAGUID 정책', icon: 'Shield' },
  { id: 'apikeys', label: 'API 키', icon: 'Key' },
  { id: 'credentials', label: 'Credentials', icon: 'Fingerprint' },
  { id: 'audit', label: '감사 로그', icon: 'Receipt' },
  { id: 'funnel', label: '퍼널', icon: 'Activity' },
];
```

- [ ] **Step 5: App.tsx — 브레드크럼 탭 라벨 + Activity / Audit Chain Monitor / License 교체**

`admin-ui/src/App.tsx` 라인 52-61 (tabName 객체) — 다음을 찾아:

```tsx
const tabName: Record<string, string> = {
  overview: '개요',
  webauthn: 'WebAuthn',
  aaguid: 'AAGUID 정책',
  apikeys: 'API Keys',
  credentials: 'Credentials',
  audit: 'Audit Logs',
  funnel: 'Funnel',
};
```

다음으로 교체:

```tsx
const tabName: Record<string, string> = {
  overview: '개요',
  webauthn: 'WebAuthn',
  aaguid: 'AAGUID 정책',
  apikeys: 'API 키',
  credentials: 'Credentials',
  audit: '감사 로그',
  funnel: '퍼널',
};
```

라인 62-70 — 다음을 찾아:

```tsx
} else if (route.name === 'activity') {
  items.push({ label: 'Activity' });
} else if (route.name === 'audit-chain') {
  items.push({ label: 'Audit Chain Monitor' });
} else if (route.name === 'settings') {
  items.push({ label: '설정' });
} else if (route.name === 'license') {
  items.push({ label: 'License' });
}
```

다음으로 교체:

```tsx
} else if (route.name === 'activity') {
  items.push({ label: '활동' });
} else if (route.name === 'audit-chain') {
  items.push({ label: '감사 체인 모니터' });
} else if (route.name === 'settings') {
  items.push({ label: '설정' });
} else if (route.name === 'license') {
  items.push({ label: '라이선스' });
}
```

- [ ] **Step 6: Sidebar 테스트 갱신 (Step 1에서 매칭이 있었던 경우만)**

Step 1에서 기록한 영어 라벨 단언이 있으면, 해당 단언의 기대 문자열을 새 한국어 라벨로 바꾼다. 예: `getByText('Activity')` → `getByText('활동')`, `'Audit Chain'` → `'감사 체인'`, `'Funnel'` → `'퍼널'`, `'API Keys'` → `'API 키'`, `'Audit Logs'` → `'감사 로그'`, `'License'` → `'라이선스'`. (`Tenants`·`WebAuthn`·`Credentials`·`AAGUID 정책` 단언은 그대로.)

- [ ] **Step 7: Run Sidebar test + typecheck**

Run: `cd admin-ui && npx vitest run src/shell/Sidebar.test.tsx && npx tsc --noEmit`
Expected: PASS + 타입 에러 없음 (exit 0)

- [ ] **Step 8: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add admin-ui/src/shell/Sidebar.tsx admin-ui/src/App.tsx admin-ui/src/shell/Sidebar.test.tsx
git commit -m "feat(admin-ui): 네비/탭/브레드크럼 라벨 한국어화

활동/감사 체인/감사 로그/API 키/퍼널/라이선스로 변환.
Tenants·WebAuthn·Credentials·AAGUID 정책 등은 영어/기존 유지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 테이블 헤더 한국어화 (TenantsListPage / ApiKeysTab)

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (라인 176-186)
- Modify: `admin-ui/src/pages/tenant/ApiKeysTab.tsx` (라인 200-207)

**Interfaces:**
- Consumes: 없음 (직접 문자열 교체)

보존: `Tenant`, `RP ID`, `API Keys`(이건 컬럼 헤더 — 일관성 위해 `API 키`로? → 아래 결정), `Key Prefix`. **결정: 테이블 헤더의 `Credentials`→자격증명, `API Keys`→API 키, `Status`→상태, `Slug`→슬러그, `Key Prefix`→키 접두사. `Tenant`·`RP ID`는 유지.**

- [ ] **Step 1: TenantsListPage 테이블 헤더 교체**

`admin-ui/src/pages/TenantsListPage.tsx` 라인 176-186 — 다음을 찾아:

```tsx
<tr>
  <th>Tenant</th>
  <th>Slug</th>
  <th>RP ID</th>
  <th style={{ textAlign: 'right' }}>Credentials</th>
  <th style={{ textAlign: 'right' }}>API Keys</th>
  <th>Status</th>
  <th>마지막 이벤트</th>
  <th>생성일</th>
  <th style={{ width: 40 }}></th>
</tr>
```

다음으로 교체 (`Tenant`·`RP ID`·`Credentials`는 기술/고유 용어로 유지, 나머지 한국어화):

```tsx
<tr>
  <th>Tenant</th>
  <th>슬러그</th>
  <th>RP ID</th>
  <th style={{ textAlign: 'right' }}>Credentials</th>
  <th style={{ textAlign: 'right' }}>API 키</th>
  <th>상태</th>
  <th>마지막 이벤트</th>
  <th>생성일</th>
  <th style={{ width: 40 }}></th>
</tr>
```

Note: `Credentials`는 WebAuthn 고유 용어(보존 목록)이므로 헤더에서도 영어 유지. `Slug`는 일반 어드민 용어라 한국어화.

- [ ] **Step 2: ApiKeysTab 테이블 헤더 교체**

`admin-ui/src/pages/tenant/ApiKeysTab.tsx` 라인 200-207 — 다음을 찾아:

```tsx
<tr>
  <th>Key Prefix</th>
  <th>이름</th>
  <th>상태</th>
  <th>마지막 사용</th>
  <th>생성일</th>
  <th>만료일</th>
  <th style={{ textAlign: 'right' }}>액션</th>
</tr>
```

다음으로 교체 (`Key Prefix`만 영어 → 키 접두사로):

```tsx
<tr>
  <th>키 접두사</th>
  <th>이름</th>
  <th>상태</th>
  <th>마지막 사용</th>
  <th>생성일</th>
  <th>만료일</th>
  <th style={{ textAlign: 'right' }}>액션</th>
</tr>
```

- [ ] **Step 3: Run typecheck**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: 에러 없음 (exit 0)

- [ ] **Step 4: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/pages/tenant/ApiKeysTab.tsx
git commit -m "feat(admin-ui): 테이블 헤더 한국어화 (슬러그/상태/API 키/키 접두사)

Tenant·RP ID·Credentials 등 기술/고유 용어 헤더는 영어 유지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: AAGUID 모드 + 다이얼로그 잔여 영어 (AaguidPolicyTab / AuditChainPage)

**Files:**
- Modify: `admin-ui/src/pages/tenant/AaguidPolicyTab.tsx` (라인 115-119, 133, 199-203)
- Modify: `admin-ui/src/pages/AuditChainPage.tsx` (라인 132, 136)

**Interfaces:**
- Consumes: `AAGUID_MODE_LABELS` from `@/i18n/labels` (Task 1)

- [ ] **Step 1: AaguidPolicyTab — 모드 정의 배열에서 enum 값(v)과 표시 라벨(t) 분리**

현재 `t`가 enum 값과 같은 영어(`'ANY'`)를 표시에 쓰고 있다. `t`만 한국어로 바꾸고 `v`(로직용 값)는 영어 유지한다.

`admin-ui/src/pages/tenant/AaguidPolicyTab.tsx` 상단 import에 추가:

```tsx
import { AAGUID_MODE_LABELS } from '@/i18n/labels';
```

라인 115-119 — 다음을 찾아:

```tsx
{[
  { v: 'ANY', t: 'ANY', d: '모든 authenticator 허용' },
  { v: 'ALLOWLIST', t: 'ALLOWLIST', d: '지정된 AAGUID만 허용' },
  { v: 'DENYLIST', t: 'DENYLIST', d: '지정된 AAGUID만 차단' },
].map((o) => (
```

다음으로 교체 (`v`는 그대로, `t`를 한국어 라벨로):

```tsx
{[
  { v: 'ANY', t: AAGUID_MODE_LABELS.ANY, d: '모든 authenticator 허용' },
  { v: 'ALLOWLIST', t: AAGUID_MODE_LABELS.ALLOWLIST, d: '지정된 AAGUID만 허용' },
  { v: 'DENYLIST', t: AAGUID_MODE_LABELS.DENYLIST, d: '지정된 AAGUID만 차단' },
].map((o) => (
```

Note: 라인 133의 `{o.t}` 렌더는 그대로 둔다 — 이제 `o.t`가 한국어 라벨이다. `o.v`(로직/저장 값)는 영어 유지.

- [ ] **Step 2: AaguidPolicyTab — MDS strict 토글 ON/OFF 한국어화**

라인 199-203 — 다음을 찾아:

```tsx
<Toggle
  on={draft.mdsStrict}
  onChange={(v) => setDraft({ ...draft, mdsStrict: v })}
  label={draft.mdsStrict ? 'MDS strict ON' : 'MDS strict OFF'}
/>
```

다음으로 교체 (`MDS` 기술 용어 유지, ON/OFF만 한국어):

```tsx
<Toggle
  on={draft.mdsStrict}
  onChange={(v) => setDraft({ ...draft, mdsStrict: v })}
  label={draft.mdsStrict ? 'MDS strict 켜짐' : 'MDS strict 꺼짐'}
/>
```

- [ ] **Step 3: AuditChainPage — from/to 라벨 한국어화**

`admin-ui/src/pages/AuditChainPage.tsx` 라인 132 — `<label className="label">from</label>` 를 찾아:

```tsx
<label className="label">시작일</label>
```

라인 136 — `<label className="label">to</label>` 를 찾아:

```tsx
<label className="label">종료일</label>
```

- [ ] **Step 4: Run typecheck**

Run: `cd admin-ui && npx tsc --noEmit`
Expected: 에러 없음 (exit 0)

- [ ] **Step 5: 모드 enum 값(o.v)이 안 바뀌었는지 확인 (회귀 가드)**

Run: `cd admin-ui && grep -nE "'ANY'|'ALLOWLIST'|'DENYLIST'" src/pages/tenant/AaguidPolicyTab.tsx`
Expected: `v: 'ANY'` 등 enum 값은 그대로 남아있어야 한다 (로직/저장용). 표시용 `t`만 `AAGUID_MODE_LABELS` 경유로 바뀜.

- [ ] **Step 6: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add admin-ui/src/pages/tenant/AaguidPolicyTab.tsx admin-ui/src/pages/AuditChainPage.tsx
git commit -m "feat(admin-ui): AAGUID 모드 표시·다이얼로그 잔여 영어 한국어화

ANY/ALLOWLIST/DENYLIST 표시를 전체 허용/허용 목록/차단 목록으로(값은 영어 유지),
MDS strict ON/OFF→켜짐/꺼짐, from/to→시작일/종료일.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 전체 검증 — 빌드/타입체크 + dev 재기동 + browse 육안 확인

**Files:** (검증 전용, 코드 수정 없음. 단 회귀 발견 시 해당 Task로 복귀)

**Interfaces:** 없음

- [ ] **Step 1: 전체 vitest 통과 확인**

Run: `cd admin-ui && npx vitest run`
Expected: 모든 테스트 PASS (labels.test.ts 포함, Sidebar.test.tsx 갱신분 포함)

- [ ] **Step 2: 타입체크 + 프로덕션 빌드**

Run: `cd admin-ui && npx tsc --noEmit && npx vite build`
Expected: 타입 에러 없음 + 빌드 성공 (`✓ built in ...`)

- [ ] **Step 3: 보존 용어가 안 바뀌었는지 전수 가드**

Run:
```bash
cd admin-ui && grep -rnE "rpId|origins|attestation|userVerification|AAGUID|ceremony|WebAuthn|FIDO2" src/ | grep -ivE "한국어|//|test" | head -20
```
Expected: 기술 용어들이 영어 그대로 남아있음 (한국어로 바뀐 것이 없어야 함). 육안 확인.

- [ ] **Step 4: enum 비교 로직이 안 깨졌는지 가드**

Run:
```bash
cd admin-ui && grep -rnE "=== '(ACTIVE|SUSPENDED|REVOKED|EXPIRED|PENDING|OPEN|RESOLVED|SUCCESS|FAILED|ANY|ALLOWLIST|DENYLIST)'" src/ | head -20
```
Expected: 비교 로직의 enum 값이 영어 그대로 남아있음 (한국어 비교로 바뀐 게 없어야 함).

- [ ] **Step 5: dev admin-app 재기동**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
PID=$(lsof -nP -iTCP:8081 -sTCP:LISTEN -t 2>/dev/null); [ -n "$PID" ] && kill "$PID"
sleep 3
SPRING_PROFILES_ACTIVE=dev nohup ./gradlew :admin-app:bootRun --console=plain > /tmp/admin-dev-boot-kr.log 2>&1 &
```
Wait until log shows `Started AdminApplication`. (모니터/폴링으로 대기.)

- [ ] **Step 6: browse 로 주요 화면 한국어 표시 육안 확인**

`alice@crosscert.com` / `alice-temp-pw` 로 로그인 후 다음 화면을 browse 스크린샷으로 확인:
- 테넌트 목록 (`/admin/tenants`): 상태 컬럼 "활성/정지", 헤더 "슬러그/상태/API 키"
- 테넌트 상세 Audit Chain 탭 + Audit Chain Monitor (`/admin/audit-chain`): "정상/위변조", Incident "처리중/해결"
- API Keys 탭: 헤더 "키 접두사", 상태 배지 "활성/만료/회수"
- AAGUID 정책 탭: 모드 "전체 허용/허용 목록/차단 목록"
- 사이드바: "활동/감사 체인/감사 로그/퍼널/라이선스" (Tenants/WebAuthn/Credentials 영어 유지)

각 화면 스크린샷을 Read 로 확인. 영어 잔여나 깨진 라벨 발견 시 해당 Task로 복귀해 수정.

- [ ] **Step 7: main 머지 (사용자 승인 후)**

검증 완료 후 사용자에게 보고하고 승인 받으면:

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git checkout main
git merge --no-ff feat/admin-ui-korean-labels -m "Merge: admin-ui 화면 용어 한국어화

상태 배지·네비/탭/헤더·AAGUID 모드를 한국어로, WebAuthn/FIDO 기술 용어는 영어 유지.
표시만 한국어이고 enum 값·API는 영어 유지(FE 전용). 검증: vitest/tsc/vite build 통과,
dev 재기동 후 browse 육안 확인.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git branch -d feat/admin-ui-korean-labels
```

---

## 검증 요약

| 검증 | 방법 | 위치 |
|---|---|---|
| 라벨 매핑 정확성 | vitest 단위 테스트 | Task 1 |
| 타입 안전성 | `tsc --noEmit` | 각 Task |
| 빌드 | `vite build` | Task 6 |
| enum 값 불변 | grep 가드 | Task 5, 6 |
| 보존 용어 불변 | grep 가드 | Task 6 |
| 화면 표시 | browse 스크린샷 | Task 6 |
