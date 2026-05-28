# Phase E3 — Admin UI Redesign: 보조 화면 + 마무리

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** ActivityPage + AuditChainPage + SettingsPage (4 탭) + FunnelTab 실제 구현 + CommandPalette 전역 검색 fixture 연결 + 사이드바 chain status 실시간. 이 phase 끝나면 디자인 패키지 39 스크린샷 모두 픽셀 일치.

**Architecture:** 디자인 `pages-4.jsx` (ActivityPage), `pages-5.jsx` (AuditChainPage), `pages-6.jsx` (SettingsPage 4 탭), `pages-3.jsx` 의 FunnelTab/BarChart/EventDistribution 1:1 포팅. fixture 모듈 추가 (funnel, activity, auditChain, adminMfa, systemInfo, securityPolicy).

**Tech Stack:** E1/E2 와 동일.

---

## Conventions

- **Working dir base**: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-ui-redesign-e3`
- **빌드**: `cd admin-ui && npm run build`
- **각 Task commit 전 codex review** — issue 발견 시 같은 task fix → commit
- **테스트 최소화**: 자동 테스트 0
- commit prefix: `feat(admin-ui): ... (Phase E3.N)`
- 디자인 jsx 마크업/className/inline style 절대 변경 X

## File Structure (Phase E3 종료 후 추가)

```
admin-ui/src/
├── api/
│   ├── activity.ts        # 신규
│   ├── auditChainMonitor.ts  # 신규 (verifyAllTenants + overview)
│   ├── mdsStatus.ts       # 신규
│   ├── adminUsers.ts      # 신규
│   └── funnel.ts          # 신규 (fixture 직접)
├── fixtures/
│   ├── funnel.ts          # 신규
│   ├── activity.ts        # 신규
│   ├── auditChainState.ts # 신규
│   ├── adminMfa.ts        # 신규
│   ├── systemInfo.ts      # 신규
│   └── securityPolicy.ts  # 신규
├── lib/
│   └── search.ts          # 신규 CommandPalette 검색
├── pages/
│   ├── ActivityPage.tsx       # 신규
│   ├── AuditChainPage.tsx     # 신규
│   ├── SettingsPage.tsx       # 신규
│   ├── settings/
│   │   ├── AdminUsersTab.tsx  # 신규
│   │   ├── MdsStatusTab.tsx   # 신규
│   │   ├── SystemInfoTab.tsx  # 신규
│   │   └── SecurityPolicyTab.tsx # 신규
│   └── tenant/
│       └── FunnelTab.tsx      # 실제 구현으로 교체
```

---

## Task 1: fixtures 모듈 (funnel/activity/auditChainState/adminMfa/systemInfo/securityPolicy)

**Files (Create)**:
- `admin-ui/src/fixtures/funnel.ts`
- `admin-ui/src/fixtures/activity.ts`
- `admin-ui/src/fixtures/auditChainState.ts`
- `admin-ui/src/fixtures/adminMfa.ts`
- `admin-ui/src/fixtures/systemInfo.ts`
- `admin-ui/src/fixtures/securityPolicy.ts`

- [ ] **Step 1.1: 디자인 data.js Read 해서 mock 구조 확인**

`docs/design-package/project/src/data.js` 전체 Read. 다음 mock 객체들 위치 확인:
- `MOCK.FUNNELS` 또는 비슷한 funnel 데이터 (tenantId 별 stages/dailyTrends/eventDistribution)
- `MOCK.ACTIVITY_EVENTS` (cross-tenant 이벤트 스트림)
- `MOCK.AUDIT_CHAIN_STATE` (tenant 별 sparkline + tampered 표시)
- `MOCK.ADMIN_USERS` (Settings AdminUsers 테이블용)
- `MOCK.SYSTEM_INFO` / `MOCK.SECURITY_POLICY`

mock 구조를 그대로 .ts 파일로 옮김 (타입 export).

- [ ] **Step 1.2: 각 fixture 파일 작성**

각 파일 패턴 (예: funnel.ts):
```ts
export type FunnelData = {
  stages: { name: string; count: number; rate?: number }[];
  dailyTrends: { date: string; attempts: number; success: number }[];
  eventDistribution: { type: string; count: number }[];
};

export const funnelFixture: Record<string, FunnelData> = {
  // 디자인 data.js 의 mock 값 그대로
  '7f00dead-0000-0000-0000-00000ace0001': { ... },
};

export function getFunnel(tenantId: string, windowDays: 1 | 7 | 30 = 7): FunnelData {
  // windowDays 별로 다른 데이터 또는 단일 데이터 + scale 조정
  return funnelFixture[tenantId] ?? { stages: [], dailyTrends: [], eventDistribution: [] };
}
```

다른 fixture 도 비슷한 패턴. **디자인 data.js 의 정확한 구조 그대로**.

- [ ] **Step 1.3: 빌드 + codex + commit**

```bash
cd admin-ui && npm run build
codex review
git add admin-ui/src/fixtures/
git commit -m "feat(admin-ui): fixtures 모듈 6종 (funnel/activity/auditChain/admin/system/security) (Phase E3.1)"
```

---

## Task 2: ActivityPage + ChipTab

**Files (Create)**:
- `admin-ui/src/api/activity.ts`
- `admin-ui/src/pages/ActivityPage.tsx`

**Files (Modify)**:
- `admin-ui/src/App.tsx` — /activity 라우트의 placeholder 를 실제 ActivityPage 로 교체

- [ ] **Step 2.1: pages-4.jsx Read**

`docs/design-package/project/src/pages-4.jsx` 전체 (156 라인). ActivityPage + ChipTab 함수.

- [ ] **Step 2.2: api/activity.ts 신규**

```ts
import { api } from './client';
import type { ActivityView } from './types';

type ServerActivity = {
  sinceId: string | null;
  events: ActivityView[];
  kpi: {
    events24h: number;
    ops24h: number;
    security24h: number;
    p95Ms: number | null;
  };
  topTenants: { tenantId: string; tenantName: string; count: number }[];
};

export const activityApi = {
  fetch: async (sinceId?: string | null, category?: string): Promise<ServerActivity> => {
    const q = new URLSearchParams();
    if (sinceId) q.set('sinceId', sinceId);
    if (category) q.set('category', category);
    return api.get<ServerActivity>(`/admin/api/activity${q.toString() ? `?${q}` : ''}`);
  },
};
```

**중요**: ActivityView 의 정확한 시그니처 + 서버 응답 envelope 여부는 types.ts / Phase A 의 ActivityController 참고.

- [ ] **Step 2.3: ActivityPage.tsx 신규**

디자인 jsx 의 ActivityPage + ChipTab 1:1 포팅. 5초 폴링 + 카테고리 chip 필터 + Top5 패널.

```tsx
import { useState, useEffect, useRef } from 'react';
import { Icons } from '@/icons/Icons';
import { activityApi } from '@/api/activity';
import { useToast } from '@/shell/ToastHost';
import { useNavigate } from 'react-router-dom';

export default function ActivityPage() {
  // 디자인 jsx 의 state/useEffect 패턴 그대로
  // - events, kpi, topTenants state
  // - 5초 setInterval polling
  // - chip 필터 (전체/운영/보안 실패)
  // - tenant 클릭 → navigate(`/tenants/${id}?tab=overview`)
  return (/* 디자인 마크업 */);
}

function ChipTab({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  // 디자인 그대로
}
```

- [ ] **Step 2.4: App.tsx — /activity 실제 페이지 마운트**

기존 `<Route path="/activity" element={<div>Activity placeholder</div>} />` 를:
```tsx
<Route path="/activity" element={<ActivityPage />} />
```

- [ ] **Step 2.5: 빌드 + codex + commit**

```bash
cd admin-ui && npm run build
codex review
git add admin-ui/src/api/activity.ts admin-ui/src/pages/ActivityPage.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): ActivityPage + ChipTab + 5s polling (Phase E3.2)"
```

---

## Task 3: AuditChainPage + ChainSparkline + MonthlyReportDialog

**Files (Create)**:
- `admin-ui/src/api/auditChainMonitor.ts`
- `admin-ui/src/pages/AuditChainPage.tsx`

**Files (Modify)**:
- `admin-ui/src/App.tsx` — /audit-chain 라우트

- [ ] **Step 3.1: pages-5.jsx Read**

`docs/design-package/project/src/pages-5.jsx` 전체 (190 라인). AuditChainPage + ChainSparkline + MonthlyReportDialog.

- [ ] **Step 3.2: api/auditChainMonitor.ts 신규**

```ts
import { api } from './client';

export type ChainOverview = {
  verifiedAt: string;
  windowHours: number;
  bucketSizeMinutes: number;
  totals: {
    tenantsIntact: number;
    tenantsTotal: number;
    tenantsTampered: number;
    verifiedRows: number;
    verificationMs: number;
  };
  tenants: {
    tenantId: string | null;
    tenantName: string;
    intact: boolean;
    verifiedRows: number;
    buckets: number[];
    tamperedEntryId: string | null;
  }[];
};

export const auditChainMonitorApi = {
  overview: async (windowHours = 24): Promise<ChainOverview> => {
    return api.get<ChainOverview>(`/admin/api/audit/chain/overview?windowHours=${windowHours}`);
  },
  backfill: async (): Promise<{tenantsProcessed: number; rowsUpdated: number; rowsSkipped: number}> => {
    return api.post('/admin/api/audit/chain/backfill', {});
  },
};
```

**중요**: 우리 서버의 overview endpoint envelope 여부 확인 — Phase B 의 AuditChainMonitorController 참고. 만약 raw response 면 getRaw 사용.

- [ ] **Step 3.3: AuditChainPage.tsx 신규**

디자인 jsx 의 3 함수 1:1 포팅. 디자인 데이터 형태 ↔ 우리 서버 응답 형태 어댑팅:
- 디자인의 sparkline 데이터는 `intact` boolean 배열 또는 비슷 — 우리 서버는 `buckets: number[]` (시간별 카운트)
- 어댑팅: 시간별 카운트 + tenant.intact 로 sparkline 색상

```tsx
export default function AuditChainPage() {
  const [overview, setOverview] = useState<ChainOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [showReport, setShowReport] = useState(false);
  // ...
}

function ChainSparkline({ buckets, intact }: { buckets: number[]; intact: boolean }) {
  // 디자인 그대로 — SVG sparkline
}

function MonthlyReportDialog({ open, onClose, chainState }: {
  open: boolean;
  onClose: () => void;
  chainState: ChainOverview;
}) {
  // 디자인 그대로 — 기간 선택 + tenant 선택 + PDF 발급 (window.print() 또는 placeholder)
}
```

- [ ] **Step 3.4: App.tsx /audit-chain 마운트**

- [ ] **Step 3.5: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/auditChainMonitor.ts admin-ui/src/pages/AuditChainPage.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): AuditChainPage + sparkline + MonthlyReportDialog (Phase E3.3)"
```

---

## Task 4: SettingsPage + AdminUsersTab + NewAdminDialog

**Files (Create)**:
- `admin-ui/src/api/adminUsers.ts`
- `admin-ui/src/pages/SettingsPage.tsx`
- `admin-ui/src/pages/settings/AdminUsersTab.tsx`

**Files (Modify)**:
- `admin-ui/src/App.tsx` — /settings 라우트

- [ ] **Step 4.1: pages-6.jsx Read (SettingsPage + AdminUsersTab + NewAdminDialog 영역 line 5-256)**

- [ ] **Step 4.2: api/adminUsers.ts 신규**

```ts
import { api } from './client';

export type AdminUserView = {
  id: string;
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  status: 'ACTIVE' | 'PENDING' | 'SUSPENDED';
  tenantId: string | null;
  createdAt: string;
  lastLoginAt: string | null;
  suspendedAt: string | null;
  createdBy: string | null;
};

export const adminUsersApi = {
  list: async (): Promise<AdminUserView[]> => {
    return api.get<AdminUserView[]>('/admin/api/admin-users');
  },
  invite: async (body: { email: string; role: string; tenantId?: string }) => {
    return api.post<{ user: AdminUserView; invitation: { tokenPrefix: string; plaintextToken: string; acceptUrl: string; expiresAt: string } }>('/admin/api/admin-users', body);
  },
  suspend: async (id: string) => api.post<void>(`/admin/api/admin-users/${id}/suspend`, {}),
  activate: async (id: string) => api.post<void>(`/admin/api/admin-users/${id}/activate`, {}),
  resendInvitation: async (id: string, email: string) =>
    api.post(`/admin/api/admin-users/${id}/invitation/resend?email=${encodeURIComponent(email)}`, {}),
};
```

서버 envelope 여부 확인 후 api.get/getRaw 적절히.

- [ ] **Step 4.3: SettingsPage.tsx 신규 (4 탭 컨테이너)**

디자인 pages-6.jsx 의 SettingsPage 그대로. 4 탭: Admin 사용자 / MDS Status / 시스템 / 보안 정책. 각 탭은 별도 컴포넌트 (Task 5-7).

```tsx
import { useState } from 'react';
import AdminUsersTab from './settings/AdminUsersTab';
import MdsStatusTab from './settings/MdsStatusTab';
import SystemInfoTab from './settings/SystemInfoTab';
import SecurityPolicyTab from './settings/SecurityPolicyTab';

export default function SettingsPage() {
  const [tab, setTab] = useState<'admin' | 'mds' | 'system' | 'security'>('admin');
  // 디자인 마크업 그대로
}
```

- [ ] **Step 4.4: AdminUsersTab.tsx + NewAdminDialog**

디자인 jsx 그대로 + adminMfa fixture (MFA 컬럼). v1.0 read-only 배너 (디자인 자체 노란 배너).

- [ ] **Step 4.5: App.tsx /settings 마운트**

- [ ] **Step 4.6: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/adminUsers.ts admin-ui/src/pages/SettingsPage.tsx admin-ui/src/pages/settings/ admin-ui/src/App.tsx
git commit -m "feat(admin-ui): SettingsPage + AdminUsersTab + NewAdminDialog (Phase E3.4)"
```

---

## Task 5: MdsStatusTab + SystemInfoTab + SecurityPolicyTab

**Files (Create)**:
- `admin-ui/src/api/mdsStatus.ts`
- `admin-ui/src/pages/settings/MdsStatusTab.tsx`
- `admin-ui/src/pages/settings/SystemInfoTab.tsx`
- `admin-ui/src/pages/settings/SecurityPolicyTab.tsx`

- [ ] **Step 5.1: pages-6.jsx Read (line 257-410)**

MdsStatusTab + KvLine + SystemInfoTab + ComponentRow + SecurityPolicyTab.

- [ ] **Step 5.2: api/mdsStatus.ts**

```ts
import { api } from './client';

export type MdsStatus = {
  version: number | null;
  nextUpdate: string | null;
  fetchedAt: string | null;
};

export const mdsStatusApi = {
  get: async (): Promise<MdsStatus> => api.get<MdsStatus>('/admin/api/mds/status'),
  sync: async () => api.post<void>('/admin/api/mds/sync', {}),
};
```

- [ ] **Step 5.3: 3 탭 파일 작성**

디자인 jsx 그대로. SystemInfoTab 과 SecurityPolicyTab 은 fixture 만 (서버에 데이터 없음). MdsStatusTab 은 mdsStatusApi.get 호출.

- [ ] **Step 5.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/mdsStatus.ts admin-ui/src/pages/settings/
git commit -m "feat(admin-ui): MdsStatusTab + SystemInfoTab + SecurityPolicyTab (Phase E3.5)"
```

---

## Task 6: FunnelTab 실제 구현 + BarChart + EventDistribution

**Files (Create)**:
- `admin-ui/src/api/funnel.ts`

**Files (Modify)**:
- `admin-ui/src/pages/tenant/FunnelTab.tsx` — placeholder 를 실제 구현으로 교체

- [ ] **Step 6.1: pages-3.jsx Read (line 546-665)**

FunnelTab + Funnel + BarChart + EventDistribution.

- [ ] **Step 6.2: api/funnel.ts (fixture 기반)**

```ts
import { getFunnel } from '@/fixtures/funnel';
import type { FunnelData } from '@/fixtures/funnel';

export const funnelApi = {
  get: async (tenantId: string, windowDays: 1 | 7 | 30 = 7): Promise<FunnelData> => {
    // 서버 endpoint 없음 — fixture 직접 반환
    return getFunnel(tenantId, windowDays);
  },
};
```

- [ ] **Step 6.3: FunnelTab.tsx 실제 구현으로 교체**

디자인 jsx 의 FunnelTab + Funnel + BarChart + EventDistribution 1:1 포팅.

- [ ] **Step 6.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/funnel.ts admin-ui/src/pages/tenant/FunnelTab.tsx
git commit -m "feat(admin-ui): FunnelTab 실제 구현 + BarChart + EventDistribution (Phase E3.6)"
```

---

## Task 7: CommandPalette 전역 검색 + Sidebar chain status 실시간 + 최종 smoke

**Files (Create)**:
- `admin-ui/src/lib/search.ts`

**Files (Modify)**:
- `admin-ui/src/extras/CommandPalette.tsx` — 검색 결과를 fixture 기반 search 로 연결
- `admin-ui/src/shell/Sidebar.tsx` — chain status carousel 을 auditChainMonitorApi.overview 결과로 실시간

- [ ] **Step 7.1: lib/search.ts 신규**

```ts
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';

export type SearchResult = {
  type: 'tenant' | 'credential' | 'audit';
  id: string;
  label: string;
  sub?: string;
  onSelect: () => void;
};

export async function searchAll(query: string, navigate: (path: string) => void): Promise<SearchResult[]> {
  if (!query.trim()) return [];
  const results: SearchResult[] = [];

  // tenants (실 API)
  try {
    const tenants = await tenantsApi.list();
    const q = query.toLowerCase();
    tenants
      .filter((t) => t.name.toLowerCase().includes(q) || t.slug.toLowerCase().includes(q) || t.id.includes(q))
      .slice(0, 5)
      .forEach((t) => {
        results.push({
          type: 'tenant',
          id: t.id,
          label: t.name,
          sub: t.slug,
          onSelect: () => navigate(`/tenants/${t.id}?tab=overview`),
        });
      });
  } catch {
    /* ignore */
  }

  // credential / audit ID — 서버 검색 없음, fixture (Phase E4 에서 서버 추가)

  return results;
}
```

- [ ] **Step 7.2: CommandPalette.tsx 의 mock tenants 부분을 searchAll 호출로 교체**

기존 빈 배열 처리 부분을 `useEffect([query], () => { searchAll(query, navigate).then(setResults); })` 패턴으로 변경. 디자인 jsx 의 마크업은 그대로 유지.

- [ ] **Step 7.3: Sidebar.tsx 의 chain status 를 실시간으로**

```tsx
// useEffect 로 auditChainMonitorApi.overview() 호출, 30s 주기로 새로고침
// 결과를 carousel 텍스트로 표시:
//   intact: "AUDIT CHAIN OK · 마지막 검증 {N}분 전 · {verifiedRows} 행"
//   tampered: "위변조 의심 — {tampered}/{total} tenant" (빨강)
```

- [ ] **Step 7.4: 최종 빌드 + smoke + commit**

```bash
cd admin-ui && npm run build
codex review
git add admin-ui/src/lib/search.ts admin-ui/src/extras/CommandPalette.tsx admin-ui/src/shell/Sidebar.tsx
git commit -m "feat(admin-ui): CommandPalette 전역 검색 + Sidebar chain status 실시간 (Phase E3.7)"
```

수동 smoke 체크리스트:
- [ ] /activity — 디자인 `01-activity-real.png` 와 시각 일치
- [ ] /audit-chain — 디자인 `audit-chain.png` 와 일치
- [ ] /settings — 디자인 `settings.png` 와 일치, 4 탭 전환
- [ ] /tenants/{id}?tab=funnel — funnel + bar chart + distribution
- [ ] ⌘K 검색 → tenant 결과 + Enter navigate
- [ ] Sidebar 하단 chain status — 실 데이터

---

## Self-Review

### Spec 커버리지
- [x] fixtures 6종 (Task 1)
- [x] ActivityPage (Task 2)
- [x] AuditChainPage (Task 3)
- [x] SettingsPage + AdminUsersTab (Task 4)
- [x] MdsStatusTab + SystemInfoTab + SecurityPolicyTab (Task 5)
- [x] FunnelTab 실제 (Task 6)
- [x] CommandPalette 전역 검색 + Sidebar chain status 실시간 (Task 7)

### Type 일관성
- ChainOverview / ServerActivity / AdminUserView / MdsStatus / FunnelData 모두 명확

### Scope
- 7 task, 각 5~20분
- 자동 테스트 0
- 각 task codex review → fix → commit

---

## 실행 가이드
1. 각 Task step 순서대로
2. 디자인 jsx 마크업 절대 변경 X
3. 서버 응답 envelope 여부에 따라 api.get / getRaw 적절히
4. 빌드 0 에러 + commit
