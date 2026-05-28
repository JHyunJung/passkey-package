# Phase E2 — Admin UI Redesign: 핵심 운영 화면

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** TenantsListPage (PO) + TenantDetailPage 6 탭 (Overview/WebAuthn/AAGUID/ApiKeys/Credentials/Audit) + 모든 Dialog. FunnelTab 은 placeholder (E3 에서 구현). 이 phase 끝나면 F-1 (RP 온보딩), F-2 (API key rotation), F-3 (credential 회수) 사용자 여정이 디자인 화면으로 가능.

**Architecture:** 디자인 `pages-1.jsx`, `pages-2.jsx`, `pages-3.jsx` 를 .tsx 로 1:1 포팅. 서버 응답 형태 (TenantView, ApiKeyView 등) 와 디자인 type 사이 어댑터 (api/*.ts) 가 변환. 부족한 필드 (credentials 카운트, lastEventAt, funnel) 는 fixtures/ 에서 채움.

**Tech Stack:** Vite 5 + React 18 + TypeScript 5 + react-router-dom 6. shadcn 없음. 디자인 raw CSS class.

---

## Conventions

- **Working dir base**: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/admin-ui-redesign-e2`
- **빌드**: `cd admin-ui && npm run build`
- **각 Task 의 commit step 전 `codex review` 실행** — issue 발견 시 같은 task 안에서 fix 후 commit. codex 실행 불가하면 보고에 명시
- **테스트 최소화**: 자동 테스트 0
- commit prefix: `feat(admin-ui): ... (Phase E2.N)`
- 한국어 주석 OK
- 디자인 jsx 의 마크업/className/inline style/함수명/prop 절대 변경 X

## API Adapter 패턴

```ts
// api/tenants.ts
import { api } from './client';
import type { TenantView } from './types';  // server type
import { tenantKpiFixture } from '@/fixtures/tenantKpi';

// 디자인 type (jsx 가 기대)
export type Tenant = {
  id: string;
  name: string;          // server.displayName 매핑
  slug: string;
  rpId: string;
  status: 'ACTIVE' | 'SUSPENDED';
  credentials: number;   // fixture
  apiKeys: number;       // fixture
  lastEventAt: string | null;  // fixture
  createdAt: string;
};

function adaptTenant(s: TenantView): Tenant {
  const kpi = tenantKpiFixture[s.id] ?? { credentials: 0, apiKeys: 0, lastEventAt: null };
  return {
    id: s.id,
    name: s.displayName,
    slug: s.slug,
    rpId: s.rpId,
    status: s.status === 'active' ? 'ACTIVE' : 'SUSPENDED',
    credentials: kpi.credentials,
    apiKeys: kpi.apiKeys,
    lastEventAt: kpi.lastEventAt,
    createdAt: s.createdAt,
  };
}

export const tenantsApi = {
  list: async (): Promise<Tenant[]> => {
    const server = await api.get<TenantView[]>('/admin/api/tenants');
    return server.map(adaptTenant);
  },
  // ...
};
```

## File Structure (Phase E2 종료 후)

```
admin-ui/src/
├── ... (E1 의 파일들)
├── api/
│   ├── client.ts          # 기존
│   ├── types.ts           # 기존 server type
│   ├── designTypes.ts     # 신규 — 디자인 type (Tenant, ApiKey, Credential 등)
│   ├── tenants.ts         # 신규 어댑터
│   ├── apiKeys.ts
│   ├── credentials.ts
│   ├── audit.ts
│   ├── webauthn.ts
│   └── aaguidPolicy.ts
├── fixtures/
│   └── tenantKpi.ts
├── pages/
│   ├── LoginPage.tsx      # 기존
│   ├── TenantsListPage.tsx
│   ├── TenantDetailPage.tsx
│   └── tenant/
│       ├── TenantOverview.tsx
│       ├── WebauthnConfigTab.tsx
│       ├── AaguidPolicyTab.tsx
│       ├── ApiKeysTab.tsx
│       ├── CredentialsTab.tsx
│       ├── AuditTab.tsx
│       └── FunnelTab.tsx (placeholder)
```

---

## Task 1: designTypes.ts + fixtures/tenantKpi.ts

**Files (Create)**:
- `admin-ui/src/api/designTypes.ts`
- `admin-ui/src/fixtures/tenantKpi.ts`

- [ ] **Step 1.1: designTypes.ts**

```ts
// 디자인 패키지의 data.js / jsx 가 기대하는 type
// 서버 type 과 별도 — 어댑터가 변환

export type Tenant = {
  id: string;
  name: string;
  slug: string;
  rpId: string;
  status: 'ACTIVE' | 'SUSPENDED';
  credentials: number;
  apiKeys: number;
  lastEventAt: string | null;
  createdAt: string;
};

export type ApiKey = {
  id: string;
  prefix: string;
  name: string;
  status: 'ACTIVE' | 'REVOKED';
  createdAt: string;
  lastUsedAt: string | null;
};

export type Credential = {
  credentialId: string;
  externalUserId: string;
  nickname: string | null;
  status: 'ACTIVE' | 'REVOKED';
  aaguid: string | null;
  transports: string[];
  signatureCounter: number;
  lastUsedAt: string | null;
  createdAt: string;
};

export type AuditEvent = {
  id: string;
  ts: string;
  eventType: string;
  actorType: string;
  actorId: string | null;
  subjectType: string | null;
  subjectId: string | null;
  payload: Record<string, unknown> | null;
};

export type WebauthnConfig = {
  rpId: string;
  rpName: string;
  origins: string[];
  formats: string[];
  userVerification: 'REQUIRED' | 'PREFERRED' | 'DISCOURAGED';
  attestationConveyance: 'NONE' | 'INDIRECT' | 'DIRECT';
  timeoutMs: number;
};

export type AaguidPolicy = {
  mode: 'ANY' | 'ALLOWLIST' | 'DENYLIST';
  mdsStrict: boolean;
  entries: { aaguid: string; note: string | null; mdsName: string | null }[];
};

export type ChainVerifyResult = {
  intact: boolean;
  verifiedRows: number;
  tamperedEntryIds: string[];
  verifiedAt: string;
};
```

- [ ] **Step 1.2: fixtures/tenantKpi.ts**

```ts
// 서버에 없는 KPI 필드 (credentials 카운트, apiKeys 카운트, lastEventAt) 의 더미값.
// 알려진 tenant slug 들 (acme/foo/bar/demo-rp/sample-rp-demo*) 에 대해 의미있는 값.
// 알려지지 않은 tenant 는 default 0/null.

export type TenantKpi = {
  credentials: number;
  apiKeys: number;
  lastEventAt: string | null;
};

export const tenantKpiFixture: Record<string, TenantKpi> = {
  // slug 'acme-corp' — 결정적 UUID 7F00DEAD00000000000000000ACE0001
  '7f00dead-0000-0000-0000-00000ace0001': { credentials: 14823, apiKeys: 3, lastEventAt: '2026-05-28T03:42:12Z' },
  // 'foo-corp' — 7F00DEAD0000000000000000F00C0001
  '7f00dead-0000-0000-0000-0000f00c0001': { credentials: 92471, apiKeys: 5, lastEventAt: '2026-05-28T03:51:00Z' },
  // 'bar-corp' — 7F00DEAD0000000000000000BA1C0001
  '7f00dead-0000-0000-0000-0000ba1c0001': { credentials: 4108, apiKeys: 2, lastEventAt: '2026-05-28T03:30:00Z' },
  // 'demo-rp' — V11 의 결정적 UUID 00000000-0000-0000-0000-00000000c0de
  '00000000-0000-0000-0000-00000000c0de': { credentials: 1, apiKeys: 1, lastEventAt: '2026-05-27T15:00:00Z' },
};

export function getTenantKpi(id: string): TenantKpi {
  return tenantKpiFixture[id] ?? { credentials: 0, apiKeys: 0, lastEventAt: null };
}
```

- [ ] **Step 1.3: 빌드 확인**

```bash
cd admin-ui && npm run build
```

- [ ] **Step 1.4: codex review**

- [ ] **Step 1.5: Commit**

```bash
git add admin-ui/src/api/designTypes.ts admin-ui/src/fixtures/tenantKpi.ts
git commit -m "feat(admin-ui): 디자인 type + tenantKpi fixture (Phase E2.1)"
```

---

## Task 2: tenants 어댑터 + TenantsListPage + NewTenantDialog

**Files (Create)**:
- `admin-ui/src/api/tenants.ts`
- `admin-ui/src/pages/TenantsListPage.tsx`

**Files (Modify)**:
- `admin-ui/src/App.tsx` — `/tenants` 라우트 추가

- [ ] **Step 2.1: api/tenants.ts**

```ts
import { api } from './client';
import type { TenantView, TenantCreateRequest } from './types';
import type { Tenant } from './designTypes';
import { getTenantKpi } from '@/fixtures/tenantKpi';

function adaptTenant(s: TenantView): Tenant {
  const kpi = getTenantKpi(s.id);
  return {
    id: s.id,
    name: s.displayName,
    slug: s.slug,
    rpId: s.rpId,
    status: s.status?.toUpperCase() === 'ACTIVE' || s.status === 'active' ? 'ACTIVE' : 'SUSPENDED',
    credentials: kpi.credentials,
    apiKeys: kpi.apiKeys,
    lastEventAt: kpi.lastEventAt,
    createdAt: s.createdAt,
  };
}

export const tenantsApi = {
  list: async (): Promise<Tenant[]> => {
    const server = await api.get<TenantView[]>('/admin/api/tenants');
    return server.map(adaptTenant);
  },
  get: async (id: string): Promise<Tenant> => {
    const server = await api.get<TenantView>(`/admin/api/tenants/${id}`);
    return adaptTenant(server);
  },
  create: async (input: { name: string; slug: string }): Promise<Tenant> => {
    const body: TenantCreateRequest = {
      slug: input.slug,
      displayName: input.name,
      rpId: input.slug + '.example.com',
      rpName: input.name,
      allowedOrigins: ['https://' + input.slug + '.example.com'],
      acceptedFormats: ['none', 'packed'],
      requireUserVerification: true,
      mdsRequired: false,
    };
    const server = await api.post<TenantView>('/admin/api/tenants', body);
    return adaptTenant(server);
  },
};
```

- [ ] **Step 2.2: TenantsListPage.tsx + NewTenantDialog 작성**

먼저 `docs/design-package/project/src/pages-1.jsx` 의 line 153~258 영역 (TenantsListPage + Stat + MetricCard + NewTenantDialog) Read.

`admin-ui/src/pages/TenantsListPage.tsx` 신규:
- 디자인 jsx 의 TenantsListPage / MetricCard / NewTenantDialog 1:1 포팅
- props: `{ tenants, onOpen, onCreate }` — 디자인 그대로
- mock data 참조 부분 → `useEffect` 로 `tenantsApi.list()` 호출
- onCreate → `tenantsApi.create({name, slug})` 호출 후 reload
- Stat 같은 보조 컴포넌트는 같은 파일 안 (또는 LoginPage 의 Stat 재사용 — 별도 import)
- 디자인 className / inline style / 마크업 그대로

```tsx
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icons } from '@/icons/Icons';
import { tenantsApi } from '@/api/tenants';
import type { Tenant } from '@/api/designTypes';
import { useToast } from '@/shell/ToastHost';

export default function TenantsListPage() {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [showNew, setShowNew] = useState(false);
  const navigate = useNavigate();
  const toast = useToast();

  async function reload() {
    setLoading(true);
    try {
      const list = await tenantsApi.list();
      setTenants(list);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { reload(); }, []);

  function onOpen(id: string) {
    navigate(`/tenants/${id}?tab=overview`);
  }

  async function handleCreate(input: { name: string; slug: string }) {
    try {
      const t = await tenantsApi.create(input);
      toast({ kind: 'ok', title: 'Tenant 생성 완료', message: t.name });
      setShowNew(false);
      await reload();
    } catch (e: any) {
      toast({ kind: 'err', title: '생성 실패', message: e?.message || '오류' });
    }
  }

  // 여기서부터 디자인 pages-1.jsx 의 TenantsListPage JSX 마크업 그대로 옮김:
  // - KPI 4종 (활성 TENANT, 등록 CREDENTIAL, 유효 API KEY, 24H CEREMONY)
  // - "+ 신규 tenant" 버튼
  // - 검색 / 필터 / CSV (필터/CSV 는 v1.1 — placeholder)
  // - 테이블 (TENANT, SLUG, RP ID, CREDENTIALS, API KEYS, STATUS, 마지막 이벤트, 생성일)
  // - 행 클릭 → onOpen(id)
  // - showNew && <NewTenantDialog ... />

  return (
    // ... 디자인 jsx 마크업
  );
}

function MetricCard({ ... }) { /* 디자인 그대로 */ }
function NewTenantDialog({ open, onClose, onCreate }: { open: boolean; onClose: () => void; onCreate: (i: {name: string; slug: string}) => void }) {
  /* 디자인 그대로 — name + slug 입력, slug 패턴 검증 ^[a-z][a-z0-9-]{1,62}$ */
}
```

**중요**: 디자인 마크업 100% 보존. 디자인 jsx 의 inline style/className/구조 그대로.

- [ ] **Step 2.3: App.tsx — `/tenants` 라우트 추가**

기존 App.tsx 의 Routes 안에 `/tenants` 처리 추가. AuthenticatedApp 내부의 `<main>` 부분을 다음으로 교체:

```tsx
<main style={{ padding: 24 }}>
  <Routes>
    <Route path="/tenants" element={<TenantsListPage />} />
    <Route path="/tenants/:id" element={<TenantDetailPagePlaceholder />} />
    <Route path="*" element={<TenantsListPage />} />
  </Routes>
</main>
```

`TenantDetailPagePlaceholder` 는 Task 3 까지 임시 — "Tenant detail (Phase E2.3 에서 구현)" 표시 정도.

- [ ] **Step 2.4: 빌드 + codex + commit**

```bash
cd admin-ui && npm run build
codex review
git add admin-ui/src/api/tenants.ts admin-ui/src/pages/TenantsListPage.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): tenants 어댑터 + TenantsListPage + NewTenantDialog (Phase E2.2)"
```

---

## Task 3: TenantDetailPage 셸 (TenantHeader + TenantTabs)

**Files (Create)**:
- `admin-ui/src/pages/TenantDetailPage.tsx`

**Files (Modify)**:
- `admin-ui/src/App.tsx` — TenantDetailPagePlaceholder 를 실제 컴포넌트로 교체

- [ ] **Step 3.1: pages-2.jsx Read**

`docs/design-package/project/src/pages-2.jsx` 의 line 5-74 (TenantDetailPage + TenantHeader + TenantTabs) Read.

- [ ] **Step 3.2: TenantDetailPage.tsx**

디자인 jsx 의 TenantDetailPage / TenantHeader / TenantTabs 1:1 포팅. props:
```ts
type TenantDetailPageProps = {
  tenant: Tenant;
  currentTab: string;
  onTabChange: (tab: string) => void;
  me: { role: string; tenantId?: string | null };
};
```

각 탭의 본문은 Task 4-9 에서 채움. 일단 각 탭 자리에 placeholder:
```tsx
{currentTab === 'overview' && <div>Overview — Task 4</div>}
{currentTab === 'webauthn' && <div>WebAuthn — Task 5</div>}
// ...
```

TenantHeader: 디자인의 avatar + name + status badge + RP 사이트 열기 + Refresh + 메뉴 + slug/createdAt 메타. 디자인 그대로.

TenantTabs: 7 탭 (개요/WebAuthn/AAGUID/API Keys/Credentials/Audit/Funnel). currentTab 에 따라 active 표시. onTabChange 콜백.

라우터 wrapper:
```tsx
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';

export default function TenantDetailRoute({ me }: { me: Me }) {
  const { id } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') || 'overview';
  const [tenant, setTenant] = useState<Tenant | null>(null);

  useEffect(() => {
    if (!id) return;
    tenantsApi.get(id).then(setTenant).catch(() => setTenant(null));
  }, [id]);

  if (!tenant) return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;

  return <TenantDetailPage tenant={tenant} currentTab={tab} onTabChange={(t) => setSearchParams({ tab: t })} me={me} />;
}
```

- [ ] **Step 3.3: App.tsx — me prop 전달**

`AuthenticatedApp` 에서 `<Route path="/tenants/:id" element={<TenantDetailRoute me={me} />} />` 로 me 전달.

- [ ] **Step 3.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/pages/TenantDetailPage.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): TenantDetailPage 셸 + 7 탭 nav (Phase E2.3)"
```

---

## Task 4: TenantOverview + ChainStatusCard

**Files (Create)**:
- `admin-ui/src/pages/tenant/TenantOverview.tsx`

- [ ] **Step 4.1: pages-2.jsx Read (line 75-175)**

TenantOverview / KV / EventDot / ChainStatusCard 함수.

- [ ] **Step 4.2: TenantOverview.tsx**

디자인 jsx 의 4 함수 1:1 포팅:
- TenantOverview (KPI 4 + 최근 활동 + ChainStatusCard)
- KV (label/value 한 줄)
- EventDot (이벤트 타입별 색 dot)
- ChainStatusCard (검증 결과 카드)

서버 호출:
- KPI: tenant prop 에서 (credentials/apiKeys) + fixture 의 ceremony24h
- 최근 활동 5건: `api.get('/admin/api/activity?tenantId=' + tenant.id + '&size=5')` (가능하면) 또는 빈 배열 + "v1.1"
- ChainStatusCard: `auditChainApi.verify(tenantId)` — 우리 서버 `/admin/api/audit/chain/verify?tenantId=` 호출

ChainStatusCard 의 데이터는 디자인 코드의 props 형태에 맞춰 어댑팅:
```ts
type ChainStatus = {
  intact: boolean;
  verifiedRows: number;
  lastVerifiedAt: string;
};
```

api/auditChain.ts 신규:
```ts
import { api } from './client';
export const auditChainApi = {
  verifyTenant: async (tenantId: string) => {
    return api.get<{intact: boolean; tamperedEntryId: string | null; verifiedAt: string}>(
      `/admin/api/audit/chain/verify?tenantId=${tenantId}`
    );
  },
};
```

- [ ] **Step 4.3: TenantDetailPage 의 overview tab 자리에 mount**

`{currentTab === 'overview' && <TenantOverview tenant={tenant} />}`

- [ ] **Step 4.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/pages/tenant/TenantOverview.tsx admin-ui/src/api/auditChain.ts admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): TenantOverview + ChainStatusCard (Phase E2.4)"
```

---

## Task 5: WebauthnConfigTab + DiffDialog

**Files (Create)**:
- `admin-ui/src/api/webauthn.ts`
- `admin-ui/src/pages/tenant/WebauthnConfigTab.tsx`

- [ ] **Step 5.1: pages-2.jsx Read (line 177-387)**

WebauthnConfigTab / Field / Segmented / DiffDialog / DiffRow / DiffLine 함수.

- [ ] **Step 5.2: api/webauthn.ts**

```ts
import { api } from './client';
import type { TenantView } from './types';
import type { WebauthnConfig } from './designTypes';

function adaptConfig(t: TenantView): WebauthnConfig {
  return {
    rpId: t.rpId,
    rpName: t.rpName,
    origins: t.allowedOrigins,
    formats: t.acceptedFormats,
    userVerification: t.requireUserVerification ? 'REQUIRED' : 'PREFERRED',
    attestationConveyance: 'NONE',     // 서버에 필드 없음 — fixture
    timeoutMs: 60000,                  // 서버에 없음 — fixture default
  };
}

export const webauthnApi = {
  get: async (tenantId: string): Promise<WebauthnConfig> => {
    const t = await api.get<TenantView>(`/admin/api/tenants/${tenantId}`);
    return adaptConfig(t);
  },
  update: async (tenantId: string, body: WebauthnConfig): Promise<WebauthnConfig> => {
    // 우리 서버 PUT은 TenantUpdateRequest 형태. 어댑팅:
    const updateReq = {
      displayName: '',   // 서버가 NotBlank 라 빈 문자열은 reject. 일단 placeholder — 실제로는 호출 전에 tenant.displayName 채워야
      rpId: body.rpId,
      rpName: body.rpName,
      allowedOrigins: body.origins,
      acceptedFormats: body.formats,
      requireUserVerification: body.userVerification === 'REQUIRED',
      mdsRequired: false,
    };
    const t = await api.put<TenantView>(`/admin/api/tenants/${tenantId}`, updateReq);
    return adaptConfig(t);
  },
  diff: async (tenantId: string, proposed: Partial<WebauthnConfig>) => {
    // 우리 서버 diff endpoint 형태 그대로
    return api.post(`/admin/api/tenants/${tenantId}/webauthn-config/diff`, {
      rpId: proposed.rpId,
      rpName: proposed.rpName,
      allowedOrigins: proposed.origins,
      acceptedFormats: proposed.formats,
      requireUserVerification: proposed.userVerification === 'REQUIRED',
      mdsRequired: false,
    });
  },
};
```

**중요**: `update()` 의 displayName 처리 — 우리 서버 PUT 이 TenantUpdateRequest 전체 (displayName 필수) 를 요구하면 호출자가 displayName 을 알아야 함. 어댑터를 다음과 같이 변경:

```ts
update: async (tenantId: string, displayName: string, body: WebauthnConfig): Promise<WebauthnConfig> => { ... }
```

또는 WebauthnConfig 에 displayName 포함 (디자인은 별도지만 우리 서버 제약). 후자가 단순. 실제 호출자가 tenant prop 에서 displayName 가져옴.

- [ ] **Step 5.3: WebauthnConfigTab.tsx 작성**

디자인 jsx 의 5 함수 1:1 포팅. tenant prop 사용.

```ts
export default function WebauthnConfigTab({ tenant }: { tenant: Tenant }) {
  const [cfg, setCfg] = useState<WebauthnConfig | null>(null);
  const [edited, setEdited] = useState<WebauthnConfig | null>(null);
  const [diffOpen, setDiffOpen] = useState(false);
  // ... 디자인 jsx 의 useState/패턴 그대로
}
```

- [ ] **Step 5.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/webauthn.ts admin-ui/src/pages/tenant/WebauthnConfigTab.tsx admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): WebauthnConfigTab + DiffDialog (Phase E2.5)"
```

---

## Task 6: AaguidPolicyTab

**Files (Create)**:
- `admin-ui/src/api/aaguidPolicy.ts`
- `admin-ui/src/pages/tenant/AaguidPolicyTab.tsx`

- [ ] **Step 6.1: pages-2.jsx Read (line 388-495)**

AaguidPolicyTab / Toggle 함수.

- [ ] **Step 6.2: api/aaguidPolicy.ts**

```ts
import { api } from './client';
import type { AaguidPolicy } from './designTypes';

// 우리 서버 응답 type
type ServerAaguidPolicy = {
  tenantId: string;
  mode: 'ANY' | 'ALLOWLIST' | 'DENYLIST';
  mdsStrict: boolean;
  entries: { aaguid: string; note: string | null; mdsName: string | null }[];
  updatedAt: string;
  updatedBy: string | null;
};

export const aaguidPolicyApi = {
  get: async (tenantId: string): Promise<AaguidPolicy> => {
    const s = await api.get<ServerAaguidPolicy>(`/admin/api/tenants/${tenantId}/aaguid-policy`);
    return { mode: s.mode, mdsStrict: s.mdsStrict, entries: s.entries };
  },
  update: async (tenantId: string, body: AaguidPolicy): Promise<AaguidPolicy> => {
    const updateReq = {
      mode: body.mode,
      mdsStrict: body.mdsStrict,
      entries: body.entries.map((e) => ({ aaguid: e.aaguid, note: e.note })),
    };
    const s = await api.put<ServerAaguidPolicy>(`/admin/api/tenants/${tenantId}/aaguid-policy`, updateReq);
    return { mode: s.mode, mdsStrict: s.mdsStrict, entries: s.entries };
  },
};
```

- [ ] **Step 6.3: AaguidPolicyTab.tsx**

디자인 jsx 의 AaguidPolicyTab + Toggle 1:1 포팅. tenant prop 사용. 모드 카드 3종 + AAGUID chip 입력 + mdsStrict toggle.

- [ ] **Step 6.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/aaguidPolicy.ts admin-ui/src/pages/tenant/AaguidPolicyTab.tsx admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): AaguidPolicyTab (Phase E2.6)"
```

---

## Task 7: ApiKeysTab + 3 다이얼로그

**Files (Create)**:
- `admin-ui/src/api/apiKeys.ts`
- `admin-ui/src/pages/tenant/ApiKeysTab.tsx`

- [ ] **Step 7.1: pages-3.jsx Read (line 5-203)**

ApiKeysTab / NewKeyDialog / IssuedKeyModal / RevokeKeyDialog 함수.

- [ ] **Step 7.2: api/apiKeys.ts**

```ts
import { api } from './client';
import type { ApiKeyView, ApiKeyCreateRequest } from './types';
import type { ApiKey } from './designTypes';

function adapt(s: ApiKeyView): ApiKey {
  return {
    id: s.id,
    prefix: s.keyPrefix,
    name: s.name,
    status: s.revokedAt ? 'REVOKED' : 'ACTIVE',
    createdAt: s.createdAt,
    lastUsedAt: s.lastUsedAt ?? null,
  };
}

export const apiKeysApi = {
  list: async (tenantId: string): Promise<ApiKey[]> => {
    const server = await api.get<ApiKeyView[]>(`/admin/api/api-keys?tenantId=${tenantId}`);
    return server.map(adapt);
  },
  create: async (tenantId: string, name: string): Promise<{ key: ApiKey; plaintext: string }> => {
    const body: ApiKeyCreateRequest = { tenantId, name, scopes: ['ceremony'] };
    const res = await api.post<{ apiKey: ApiKeyView; plaintext: string }>('/admin/api/api-keys', body);
    return { key: adapt(res.apiKey), plaintext: res.plaintext };
  },
  revoke: async (id: string): Promise<void> => {
    await api.delete<void>(`/admin/api/api-keys/${id}`);
  },
};
```

**중요**: 실제 서버 응답 구조 확인 — `POST /admin/api/api-keys` 의 응답이 `{apiKey, plaintext}` 인지 다른 형태인지. 기존 Phase A 의 ApiKeyCreateModal.tsx 가 어떻게 호출했는지 git history 참고 가능.

- [ ] **Step 7.3: ApiKeysTab.tsx**

디자인 jsx 의 4 함수 1:1 포팅. tenant prop. plaintext 1회 노출 모달 + "복사 완료" 체크박스 강제 (디자인 그대로).

- [ ] **Step 7.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/apiKeys.ts admin-ui/src/pages/tenant/ApiKeysTab.tsx admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): ApiKeysTab + 3 dialogs (Phase E2.7)"
```

---

## Task 8: CredentialsTab + RevokeCredentialDialog

**Files (Create)**:
- `admin-ui/src/api/credentials.ts`
- `admin-ui/src/pages/tenant/CredentialsTab.tsx`

- [ ] **Step 8.1: pages-3.jsx Read (line 204-330)**

CredentialsTab / RevokeCredentialDialog 함수.

- [ ] **Step 8.2: api/credentials.ts**

```ts
import { api } from './client';
import type { CredentialView, PageView } from './types';
import type { Credential } from './designTypes';

function adapt(s: CredentialView): Credential {
  return {
    credentialId: s.credentialId,
    externalUserId: s.userHandle ?? '',
    nickname: null,
    status: 'ACTIVE',  // 서버에 명시 status 없으면 ACTIVE
    aaguid: s.aaguid ?? null,
    transports: s.transports ?? [],
    signatureCounter: s.signCount ?? 0,
    lastUsedAt: s.lastUsedAt ?? null,
    createdAt: s.createdAt,
  };
}

export const credentialsApi = {
  list: async (tenantId: string, page = 0, size = 50, search?: string): Promise<{ items: Credential[]; total: number }> => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (search) q.set('search', search);
    const res = await api.get<PageView<CredentialView>>(`/admin/api/tenants/${tenantId}/credentials?${q}`);
    return { items: res.content.map(adapt), total: res.totalElements };
  },
  revoke: async (tenantId: string, credentialId: string): Promise<void> => {
    await api.delete<void>(`/admin/api/tenants/${tenantId}/credentials/${credentialId}`);
  },
};
```

PageView 가 우리 types.ts 에 있는지 확인. 없으면 type 정의.

- [ ] **Step 8.3: CredentialsTab.tsx**

디자인 jsx 의 2 함수 1:1 포팅.

- [ ] **Step 8.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/credentials.ts admin-ui/src/pages/tenant/CredentialsTab.tsx admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): CredentialsTab + RevokeCredentialDialog (Phase E2.8)"
```

---

## Task 9: AuditTab + ChainVerifyCard + ChainVerifyDialog + PayloadDialog + EventTypeBadge

**Files (Create)**:
- `admin-ui/src/api/audit.ts`
- `admin-ui/src/pages/tenant/AuditTab.tsx`

- [ ] **Step 9.1: pages-3.jsx Read (line 331-544)**

AuditTab / EventTypeBadge / ChainVerifyCard / ChainVerifyDialog / PayloadDialog 함수.

- [ ] **Step 9.2: api/audit.ts**

```ts
import { api } from './client';
import type { AuditLogView, PageView } from './types';
import type { AuditEvent, ChainVerifyResult } from './designTypes';

function adapt(s: AuditLogView): AuditEvent {
  return {
    id: s.id,
    ts: s.createdAt,
    eventType: s.action,
    actorType: s.actorEmail ? 'ADMIN' : 'SYSTEM',
    actorId: s.actorEmail ?? null,
    subjectType: s.targetType ?? null,
    subjectId: s.targetId ?? null,
    payload: s.payload ?? null,
  };
}

export const auditApi = {
  list: async (tenantId: string, page = 0, size = 50): Promise<{ items: AuditEvent[]; total: number }> => {
    const q = new URLSearchParams({ tenantId, page: String(page), size: String(size) });
    const res = await api.get<PageView<AuditLogView>>(`/admin/api/audit?${q}`);
    return { items: res.content.map(adapt), total: res.totalElements };
  },
  verify: async (tenantId: string, from?: string, to?: string): Promise<ChainVerifyResult> => {
    // 디자인은 from/to 지만 우리 서버는 windowHours.
    // 어댑팅: from/to 가 주어지면 시간 차이 → windowHours 변환.
    let windowHours = 24;
    if (from && to) {
      windowHours = Math.max(1, Math.round((new Date(to).getTime() - new Date(from).getTime()) / 3_600_000));
    }
    const s = await api.get<{tenantId: string; intact: boolean; tamperedEntryId: string | null; verifiedAt: string}>(
      `/admin/api/audit/chain/verify?tenantId=${tenantId}&windowHours=${windowHours}`
    );
    return {
      intact: s.intact,
      verifiedRows: 0,  // 우리 서버 응답에 없음 — 0 또는 fixture
      tamperedEntryIds: s.tamperedEntryId ? [s.tamperedEntryId] : [],
      verifiedAt: s.verifiedAt,
    };
  },
};
```

- [ ] **Step 9.3: AuditTab.tsx**

디자인 jsx 의 5 함수 1:1 포팅.

- [ ] **Step 9.4: 빌드 + codex + commit**

```bash
git add admin-ui/src/api/audit.ts admin-ui/src/pages/tenant/AuditTab.tsx admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): AuditTab + chain verify + payload (Phase E2.9)"
```

---

## Task 10: FunnelTab placeholder

**Files (Create)**:
- `admin-ui/src/pages/tenant/FunnelTab.tsx`

- [ ] **Step 10.1: FunnelTab placeholder**

```tsx
import { EmptyState } from '@/shell/EmptyState';

export default function FunnelTab() {
  return (
    <div style={{ padding: 24 }}>
      <EmptyState
        icon="Activity"
        title="Funnel 시각화 — 준비 중"
        description="등록/인증 funnel, 일별/타입별 차트는 Phase E3 에서 구현됩니다."
      />
    </div>
  );
}
```

- [ ] **Step 10.2: TenantDetailPage 에 mount**

`{currentTab === 'funnel' && <FunnelTab />}`

- [ ] **Step 10.3: 빌드 + commit**

```bash
git add admin-ui/src/pages/tenant/FunnelTab.tsx admin-ui/src/pages/TenantDetailPage.tsx
git commit -m "feat(admin-ui): FunnelTab placeholder (Phase E2.10)"
```

---

## Task 11: 최종 빌드 + smoke

- [ ] **Step 11.1: 빌드 + 시각 확인 가이드 작성**

```bash
cd admin-ui && npm run build
```
0 에러.

수동 smoke 체크리스트 (사용자가 admin-app 띄운 후 확인):
- [ ] /tenants — 디자인 `01-02-tenant-list.png` 와 시각 일치
- [ ] /tenants/{id}?tab=overview — KPI + 활동 + ChainStatus
- [ ] tab=webauthn — 편집 + 변경 미리보기 → DiffDialog
- [ ] tab=aaguid — 모드 카드 + chip
- [ ] tab=apikeys — `01-03-apikeys.png` 와 시각 일치 + plaintext 모달
- [ ] tab=credentials — 검색 + 회수
- [ ] tab=audit — `01-04-audit.png` 와 시각 일치
- [ ] tab=funnel — placeholder

- [ ] **Step 11.2: Commit (smoke 정리)**

```bash
git commit --allow-empty -m "chore(admin-ui): Phase E2 smoke 통과 + main merge 준비 (Phase E2.11)"
```

---

## Self-Review

### Spec 커버리지
- [x] designTypes + tenantKpi fixture (Task 1)
- [x] tenants 어댑터 + TenantsListPage (Task 2)
- [x] TenantDetailPage 셸 (Task 3)
- [x] TenantOverview + ChainStatusCard (Task 4)
- [x] WebauthnConfigTab + DiffDialog (Task 5)
- [x] AaguidPolicyTab (Task 6)
- [x] ApiKeysTab + 3 dialogs (Task 7)
- [x] CredentialsTab + RevokeDialog (Task 8)
- [x] AuditTab + ChainVerify + Payload (Task 9)
- [x] FunnelTab placeholder (Task 10)
- [x] 최종 smoke (Task 11)

### Type 일관성
- `Tenant`, `ApiKey`, `Credential`, `AuditEvent`, `WebauthnConfig`, `AaguidPolicy`, `ChainVerifyResult` 모두 designTypes.ts 에서 정의
- 각 api 모듈에서 server type → design type 어댑팅
- 어댑터 함수명: `adaptTenant`, `adapt` (각 모듈에서 default name)

### Scope
- 11 task, 각 5~15분 분량
- 자동 테스트 0
- 각 task 끝 codex review → fix → commit

---

## 실행 가이드
1. 각 Task 의 step 순서대로
2. 디자인 jsx 의 마크업/className/inline style/함수명/prop 절대 변경 X
3. 각 task 끝 codex review → issue 발견 시 같은 task 안에서 fix → commit
4. 빌드 실패 시 task 끝나기 전 fix
5. 서버 응답 시그니처가 plan 의 가정과 다르면 어댑터에서 조정 (jsx 는 절대 수정 X)
