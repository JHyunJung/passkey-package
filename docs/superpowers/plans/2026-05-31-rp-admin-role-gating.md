# RP_ADMIN role 게이팅 (admin-ui) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-ui에서 RP_ADMIN(고객사 관리자)이 자기 권한 화면만 보도록 role 게이팅(자기 테넌트 직행 + nav/탭 필터 + route guard)을 추가한다.

**Architecture:** 중앙화 `roles.ts` 헬퍼 + `RequirePlatform` route guard. App.tsx의 PLATFORM 전용 route를 guard로 감싸고 RP_ADMIN을 자기 테넌트로 redirect. Sidebar nav와 SettingsPage 탭을 role로 필터. vitest를 처음 도입해 게이팅 핵심을 단위 테스트. PLATFORM_OPERATOR 경로 무영향. 보안 경계는 여전히 BE.

**Tech Stack:** React 18, TypeScript, react-router-dom 6, Vite(@/ alias), vitest + @testing-library/react + jsdom(신규).

**근거 spec:** `docs/superpowers/specs/2026-05-31-rp-admin-role-gating-design.md`

---

## File Structure

**신규:**
- `admin-ui/src/me/roles.ts` — isPlatform/isRpAdmin/rpTenantId 순수 헬퍼
- `admin-ui/src/me/RequirePlatform.tsx` — route guard(RP→자기 테넌트 redirect)
- `admin-ui/vitest.config.ts` (또는 vite.config에 test) + setup
- `admin-ui/src/me/roles.test.ts`, `RequirePlatform.test.tsx`
- `admin-ui/src/shell/Sidebar.test.tsx`, `admin-ui/src/pages/SettingsPage.test.tsx`

**수정:**
- `admin-ui/package.json` — vitest devDep + test script
- `admin-ui/src/App.tsx` — route를 RequirePlatform로 감싸기 + 진입 redirect role 분기
- `admin-ui/src/shell/Sidebar.tsx` — navItems를 role로 필터
- `admin-ui/src/pages/SettingsPage.tsx` — 탭을 role로 필터

확인된 사실: `@/` = `./src`(vite alias + tsconfig paths). `Me` 타입(`@/api/types`): email/role('PLATFORM_OPERATOR'|'RP_ADMIN')/tenantId(string|null)/mfaEnabled/mfaRequired. App.tsx는 `Navigate`/`useNavigate` 이미 import. SettingsPage는 `me: Me` prop. Sidebar는 `me` prop + `isPlatform` 이미 계산(`me.role === 'PLATFORM_OPERATOR'`), `navItems` useMemo가 NAV_PLATFORM 기반, nav 렌더는 `tenant ? NAV_RP : navItems`.

---

## Task 1: vitest 셋업

admin-ui 첫 테스트 인프라. 의존성 + config + script + smoke test.

**Files:**
- Modify: `admin-ui/package.json`
- Create: `admin-ui/vitest.config.ts`
- Create: `admin-ui/src/test/setup.ts`
- Create: `admin-ui/src/test/smoke.test.ts`

- [ ] **Step 1: devDependency 추가**

`admin-ui/package.json`의 `devDependencies`에 추가(기존 항목 유지):
```json
    "vitest": "^2.1.1",
    "@testing-library/react": "^16.0.1",
    "@testing-library/jest-dom": "^6.5.0",
    "jsdom": "^25.0.0"
```
`scripts`에 추가:
```json
    "test": "vitest run"
```

- [ ] **Step 2: 의존성 설치**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/rp-admin-role-gating/admin-ui && npm install`
Expected: 설치 성공(node_modules에 vitest/testing-library/jsdom).

- [ ] **Step 3: vitest.config.ts 작성**

Create `admin-ui/vitest.config.ts`:
```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
```

- [ ] **Step 4: setup + smoke test**

Create `admin-ui/src/test/setup.ts`:
```ts
import '@testing-library/jest-dom';
```
Create `admin-ui/src/test/smoke.test.ts`:
```ts
import { describe, it, expect } from 'vitest';

describe('vitest setup', () => {
  it('runs', () => {
    expect(1 + 1).toBe(2);
  });
});
```

- [ ] **Step 5: 테스트 실행 확인**

Run: `npm test`
Expected: smoke test PASS (1 test). vitest 인프라 동작 확인.

- [ ] **Step 6: Commit**

```bash
git add admin-ui/package.json admin-ui/package-lock.json admin-ui/vitest.config.ts admin-ui/src/test/
git commit -m "test(admin-ui): vitest + testing-library 인프라 도입 (RP_ADMIN 게이팅 준비)"
```

---

## Task 2: roles.ts 헬퍼

중앙화 role 판별 순수 함수 + 단위 테스트.

**Files:**
- Create: `admin-ui/src/me/roles.ts`
- Test: `admin-ui/src/me/roles.test.ts`

- [ ] **Step 1: 테스트 작성**

Create `admin-ui/src/me/roles.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { isPlatform, isRpAdmin, rpTenantId } from './roles';
import type { Me } from '@/api/types';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false };

describe('roles', () => {
  it('isPlatform', () => {
    expect(isPlatform(platform)).toBe(true);
    expect(isPlatform(rp)).toBe(false);
  });
  it('isRpAdmin', () => {
    expect(isRpAdmin(rp)).toBe(true);
    expect(isRpAdmin(platform)).toBe(false);
  });
  it('rpTenantId returns RP tenant, null for platform', () => {
    expect(rpTenantId(rp)).toBe('tid-123');
    expect(rpTenantId(platform)).toBeNull();
  });
  it('rpTenantId null when RP_ADMIN has null tenantId (data anomaly)', () => {
    const broken: Me = { ...rp, tenantId: null };
    expect(rpTenantId(broken)).toBeNull();
  });
});
```

Run: `npm test -- roles` → FAIL (roles.ts 없음).

- [ ] **Step 2: roles.ts 구현**

Create `admin-ui/src/me/roles.ts`:
```ts
import type { Me } from '@/api/types';

/** PLATFORM_OPERATOR(플랫폼 운영자, 전체 접근). */
export function isPlatform(me: Me): boolean {
  return me.role === 'PLATFORM_OPERATOR';
}

/** RP_ADMIN(고객사 관리자, 자기 테넌트만). */
export function isRpAdmin(me: Me): boolean {
  return me.role === 'RP_ADMIN';
}

/** RP_ADMIN 의 테넌트 id. PLATFORM 이거나 tenantId 누락(데이터 이상) 시 null. */
export function rpTenantId(me: Me): string | null {
  return me.role === 'RP_ADMIN' ? me.tenantId : null;
}
```

Run: `npm test -- roles` → PASS (4 tests).

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/me/roles.ts admin-ui/src/me/roles.test.ts
git commit -m "feat(admin-ui): 중앙화 role 헬퍼 roles.ts (isPlatform/isRpAdmin/rpTenantId)"
```

---

## Task 3: RequirePlatform route guard

PLATFORM 전용 route를 감싸는 가드. RP→자기 테넌트 redirect, tenantId null→에러.

**Files:**
- Create: `admin-ui/src/me/RequirePlatform.tsx`
- Test: `admin-ui/src/me/RequirePlatform.test.tsx`

- [ ] **Step 1: 테스트 작성**

Create `admin-ui/src/me/RequirePlatform.test.tsx`:
```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { RequirePlatform } from './RequirePlatform';
import type { Me } from '@/api/types';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false };

function renderAt(me: Me) {
  return render(
    <MemoryRouter initialEntries={['/tenants']}>
      <Routes>
        <Route path="/tenants" element={<RequirePlatform me={me}><div>PLATFORM CONTENT</div></RequirePlatform>} />
        <Route path="/tenants/:id" element={<div>TENANT id={`page`}</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('RequirePlatform', () => {
  it('renders children for PLATFORM_OPERATOR', () => {
    renderAt(platform);
    expect(screen.getByText('PLATFORM CONTENT')).toBeInTheDocument();
  });

  it('redirects RP_ADMIN to own tenant', () => {
    renderAt(rp);
    expect(screen.queryByText('PLATFORM CONTENT')).not.toBeInTheDocument();
    expect(screen.getByText(/TENANT id=/)).toBeInTheDocument();
  });

  it('shows error state for RP_ADMIN with null tenantId', () => {
    const broken: Me = { ...rp, tenantId: null };
    render(
      <MemoryRouter initialEntries={['/tenants']}>
        <Routes>
          <Route path="/tenants" element={<RequirePlatform me={broken}><div>PLATFORM CONTENT</div></RequirePlatform>} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.queryByText('PLATFORM CONTENT')).not.toBeInTheDocument();
    expect(screen.getByText(/계정 구성 오류/)).toBeInTheDocument();
  });
});
```

Run: `npm test -- RequirePlatform` → FAIL (없음).

- [ ] **Step 2: RequirePlatform 구현**

Create `admin-ui/src/me/RequirePlatform.tsx`:
```tsx
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import type { Me } from '@/api/types';
import { isPlatform, rpTenantId } from './roles';

/**
 * PLATFORM 전용 route guard. PLATFORM_OPERATOR 면 children, RP_ADMIN 이면
 * 자기 테넌트(/tenants/{tenantId})로 redirect. RP_ADMIN 인데 tenantId 가
 * 없으면(데이터 이상) 에러 상태(redirect loop 방지). 보안 경계는 BE — 이 가드는 IA/UX.
 */
export function RequirePlatform({ me, children }: { me: Me; children: ReactNode }) {
  if (isPlatform(me)) {
    return <>{children}</>;
  }
  const tid = rpTenantId(me);
  if (tid) {
    return <Navigate to={`/tenants/${tid}`} replace />;
  }
  return (
    <div className="page" style={{ padding: 24 }}>
      <h1 className="page__title">계정 구성 오류</h1>
      <div className="page__sub">RP_ADMIN 계정에 테넌트가 지정돼 있지 않습니다. 플랫폼 운영자에게 문의하세요.</div>
    </div>
  );
}
```

Run: `npm test -- RequirePlatform` → PASS (3 tests).

- [ ] **Step 3: Commit**

```bash
git add admin-ui/src/me/RequirePlatform.tsx admin-ui/src/me/RequirePlatform.test.tsx
git commit -m "feat(admin-ui): RequirePlatform route guard — RP_ADMIN 자기 테넌트 redirect (P-게이팅)"
```

---

## Task 4: App.tsx route 감싸기 + 진입 redirect

PLATFORM 전용 route를 RequirePlatform로 감싸고, 진입 redirect를 role 분기.

**Files:**
- Modify: `admin-ui/src/App.tsx`

- [ ] **Step 1: import 추가**

App.tsx 상단 import에 추가:
```tsx
import { RequirePlatform } from '@/me/RequirePlatform';
import { rpTenantId } from '@/me/roles';
```

- [ ] **Step 2: PLATFORM 전용 route를 guard로 감싸기**

App.tsx의 `<Routes>` 블록(현재):
```tsx
          <Routes>
            <Route path="/tenants" element={<TenantsListPage />} />
            <Route path="/tenants/:id" element={<TenantDetailRoute me={me} />} />
            <Route path="/activity" element={<ActivityPage />} />
            <Route path="/audit-chain" element={<AuditChainPage />} />
            <Route path="/settings" element={<SettingsPage me={me} onMeChange={onMeChange} />} />
            <Route path="/license" element={<LicensePage />} />
            <Route path="*" element={<Navigate to="/tenants" replace />} />
          </Routes>
```
다음으로 교체:
```tsx
          <Routes>
            <Route path="/tenants" element={<RequirePlatform me={me}><TenantsListPage /></RequirePlatform>} />
            <Route path="/tenants/:id" element={<TenantDetailRoute me={me} />} />
            <Route path="/activity" element={<RequirePlatform me={me}><ActivityPage /></RequirePlatform>} />
            <Route path="/audit-chain" element={<RequirePlatform me={me}><AuditChainPage /></RequirePlatform>} />
            <Route path="/settings" element={<SettingsPage me={me} onMeChange={onMeChange} />} />
            <Route path="/license" element={<RequirePlatform me={me}><LicensePage /></RequirePlatform>} />
            <Route path="*" element={<Navigate to={rpTenantId(me) ? `/tenants/${rpTenantId(me)}` : '/tenants'} replace />} />
          </Routes>
```
(`/tenants/:id`·`/settings`는 guard 없이 둘 다 접근 — `/tenants/:id`는 BE TenantBoundary, `/settings`는 Task 6 탭 필터. `*` redirect는 RP_ADMIN이면 자기 테넌트로, 아니면 `/tenants`.)

- [ ] **Step 3: 타입체크 + 빌드**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/rp-admin-role-gating/admin-ui && npx tsc -b`
Expected: 타입 에러 없음. (`me`가 이 스코프에 있는지 확인 — AuthenticatedApp 컴포넌트가 `me`를 prop으로 받음. 없으면 해당 스코프의 me 변수명 확인.)

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/App.tsx
git commit -m "feat(admin-ui): PLATFORM 전용 route를 RequirePlatform 로 감싸기 + RP 진입 redirect (P-게이팅)"
```

---

## Task 5: Sidebar nav 필터

RP_ADMIN에게 PLATFORM nav 항목(Tenants/Activity/Audit Chain) 숨김.

**Files:**
- Modify: `admin-ui/src/shell/Sidebar.tsx`
- Test: `admin-ui/src/shell/Sidebar.test.tsx`

- [ ] **Step 1: 테스트 작성**

먼저 Sidebar의 props(me, currentRoute, onNavigate, tenant, sidebarMode)와 import를 읽어 테스트 셋업을 맞춘다. Sidebar는 `auditChainMonitorApi`/`licenseApi`를 useEffect에서 호출하므로 테스트에서 이들을 mock해야 한다(vi.mock). Create `admin-ui/src/shell/Sidebar.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Me } from '@/api/types';

// Sidebar 가 마운트 시 호출하는 API 들을 mock (네트워크 차단).
vi.mock('@/api/client', async (orig) => {
  const actual = await (orig() as Promise<Record<string, unknown>>);
  return {
    ...actual,
    auditChainMonitorApi: { overview: vi.fn().mockResolvedValue(null) },
    licenseApi: { get: vi.fn().mockResolvedValue({ deploymentMode: 'saas' }) },
  };
});

import { Sidebar } from './Sidebar';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false };

function renderSidebar(me: Me) {
  return render(
    <Sidebar me={me} currentRoute={{ name: 'tenants' } as any} onNavigate={() => {}} tenant={null as any} />
  );
}

describe('Sidebar nav role filter', () => {
  it('PLATFORM_OPERATOR sees platform nav items', () => {
    renderSidebar(platform);
    expect(screen.getByText('Tenants')).toBeInTheDocument();
    expect(screen.getByText('Activity')).toBeInTheDocument();
    expect(screen.getByText('Audit Chain')).toBeInTheDocument();
  });

  it('RP_ADMIN does NOT see platform-only nav items', () => {
    renderSidebar(rp);
    expect(screen.queryByText('Tenants')).not.toBeInTheDocument();
    expect(screen.queryByText('Activity')).not.toBeInTheDocument();
    expect(screen.queryByText('Audit Chain')).not.toBeInTheDocument();
  });
});
```
(API mock 경로/export 이름은 실제 `@/api/client`를 읽어 정확히 — auditChainMonitorApi/licenseApi가 거기서 export되는지 확인. 다른 모듈이면 그 경로로 mock.)

Run: `npm test -- Sidebar` → FAIL (RP_ADMIN도 nav 보임 — 필터 전).

- [ ] **Step 2: navItems 필터 추가**

Sidebar.tsx의 `navItems` useMemo(현재 deploymentMode만 분기)를 role로도 필터:
```tsx
  const navItems = useMemo(() => {
    // RP_ADMIN 은 PLATFORM 전용 전역 nav(Tenants/Activity/Audit Chain)를 보지 않는다.
    // 자기 테넌트로 직행해 NAV_RP 를 사용. (설정 nav 는 별도 — 내 계정 접근.)
    if (!isPlatform) {
      return [] as typeof NAV_PLATFORM;
    }
    if (deploymentMode === 'onprem') {
      return [...NAV_PLATFORM.slice(0, -1), NAV_LICENSE, NAV_PLATFORM[NAV_PLATFORM.length - 1]];
    }
    return NAV_PLATFORM;
  }, [deploymentMode, isPlatform]);
```
Note: `isPlatform`은 이미 `Sidebar.tsx:78`에 있음. 만약 spec대로 "설정"(Settings) nav를 RP_ADMIN에게도 노출하려면 `NAV_PLATFORM`에서 settings만 남긴 배열을 반환. 단, RP_ADMIN은 로그인 후 자기 테넌트로 직행해 메인 nav(navItems)를 거의 안 보고 NAV_RP를 보므로, 빈 배열로 두고 "설정"은 NAV_RP 흐름 또는 Header/계정 메뉴로 접근하는 게 자연스럽다. **결정: RP_ADMIN의 navItems는 빈 배열(메인 전역 nav 없음).** 내 계정(Settings)은 RP_ADMIN이 `/settings` 직접 접근 시 account 탭만 보이도록 Task 6에서 처리하고, nav 진입점은 Header의 기존 계정/설정 링크에 의존(있으면). Header에 설정 링크가 없으면 이 Task에서 NAV_RP에 '설정'을 추가하거나 별도 검토 — 구현 시 Header 확인.

- [ ] **Step 3: 테스트 통과 + 빌드**

Run: `npm test -- Sidebar && npx tsc -b`
Expected: PASS. RP_ADMIN nav에 PLATFORM 항목 없음.

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/shell/Sidebar.tsx admin-ui/src/shell/Sidebar.test.tsx
git commit -m "feat(admin-ui): Sidebar nav 를 role 로 필터 — RP_ADMIN 에 PLATFORM 항목 숨김 (P-게이팅)"
```

---

## Task 6: SettingsPage 탭 필터

RP_ADMIN은 account 탭만.

**Files:**
- Modify: `admin-ui/src/pages/SettingsPage.tsx`
- Test: `admin-ui/src/pages/SettingsPage.test.tsx`

- [ ] **Step 1: 테스트 작성**

먼저 SettingsPage가 import하는 탭 컴포넌트(AccountTab 등)가 API를 호출하는지 확인하고, account 탭(AccountTab)이 me prop만으로 렌더되는지 본다. Create `admin-ui/src/pages/SettingsPage.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Me } from '@/api/types';

// 탭 컴포넌트들이 마운트 시 네트워크를 치면 mock. 최소한 PLATFORM 전용 탭은
// 렌더되지 않아야 하므로(RP 케이스), 버튼 라벨 존재 여부만 검증한다.
vi.mock('./settings/AdminUsersTab', () => ({ default: () => <div>ADMIN USERS TAB</div> }));
vi.mock('./settings/MdsStatusTab', () => ({ default: () => <div>MDS TAB</div> }));
vi.mock('./settings/SystemInfoTab', () => ({ default: () => <div>SYSTEM TAB</div> }));
vi.mock('./settings/SecurityPolicyTab', () => ({ default: () => <div>SECURITY TAB</div> }));
vi.mock('./settings/AccountTab', () => ({ default: () => <div>ACCOUNT TAB</div> }));

import SettingsPage from './SettingsPage';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false };

describe('SettingsPage tab role filter', () => {
  it('PLATFORM_OPERATOR sees all tabs', () => {
    render(<SettingsPage me={platform} onMeChange={() => {}} />);
    expect(screen.getByText('내 계정')).toBeInTheDocument();
    expect(screen.getByText('Admin 사용자')).toBeInTheDocument();
    expect(screen.getByText('MDS Status')).toBeInTheDocument();
    expect(screen.getByText('시스템')).toBeInTheDocument();
    expect(screen.getByText('보안 정책')).toBeInTheDocument();
  });

  it('RP_ADMIN sees only account tab', () => {
    render(<SettingsPage me={rp} onMeChange={() => {}} />);
    expect(screen.getByText('내 계정')).toBeInTheDocument();
    expect(screen.queryByText('Admin 사용자')).not.toBeInTheDocument();
    expect(screen.queryByText('MDS Status')).not.toBeInTheDocument();
    expect(screen.queryByText('시스템')).not.toBeInTheDocument();
    expect(screen.queryByText('보안 정책')).not.toBeInTheDocument();
  });
});
```

Run: `npm test -- SettingsPage` → FAIL (RP도 모든 탭 보임).

- [ ] **Step 2: 탭 필터 추가**

SettingsPage.tsx에서 `isPlatform` import 후 PLATFORM 전용 탭 버튼·렌더를 조건부로. import 추가:
```tsx
import { isPlatform } from '@/me/roles';
```
컴포넌트 본문 시작에 `const platform = isPlatform(me);` 추가. 5개 탭 버튼 중 account 외 4개(`admins`/`mds`/`system`/`security`) 버튼을 `{platform && (...)}` 로 감싸고, 하단 렌더(`{tab === 'admins' && <AdminUsersTab/>}` 등 4개)도 `{platform && tab === 'admins' && ...}` 로 감싼다. account 버튼·렌더는 그대로(둘 다). 예:
```tsx
        <button className={`tabs__btn ${tab === 'account' ? 'tabs__btn--active' : ''}`} onClick={() => setTab('account')}>내 계정</button>
        {platform && (
          <>
            <button className={`tabs__btn ${tab === 'admins' ? 'tabs__btn--active' : ''}`} onClick={() => setTab('admins')}>Admin 사용자</button>
            <button className={`tabs__btn ${tab === 'mds' ? 'tabs__btn--active' : ''}`} onClick={() => setTab('mds')}>MDS Status</button>
            <button className={`tabs__btn ${tab === 'system' ? 'tabs__btn--active' : ''}`} onClick={() => setTab('system')}>시스템</button>
            <button className={`tabs__btn ${tab === 'security' ? 'tabs__btn--active' : ''}`} onClick={() => setTab('security')}>보안 정책</button>
          </>
        )}
```
그리고 렌더 4개를 `{platform && tab === 'admins' && <AdminUsersTab />}` 식으로. account 렌더는 그대로. (RP_ADMIN은 기본 탭 account라 추가 처리 불요 — PLATFORM 전용 탭은 버튼이 없어 setTab으로 갈 수 없음.)

- [ ] **Step 3: 테스트 통과 + 빌드**

Run: `npm test -- SettingsPage && npx tsc -b`
Expected: PASS. RP_ADMIN은 account 탭만.

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/pages/SettingsPage.tsx admin-ui/src/pages/SettingsPage.test.tsx
git commit -m "feat(admin-ui): Settings 탭을 role 로 필터 — RP_ADMIN 은 account 만 (P-게이팅)"
```

---

## Task 7: 전체 검증 + 분산 체크 정리 + followups

**Files:**
- Modify: `admin-ui/src/pages/TenantsListPage.tsx` (TODO 정리, 방어적 가드)
- Create: `docs/superpowers/followups/2026-05-31-rp-admin-role-gating-followups.md`

- [ ] **Step 1: TenantsListPage TODO 정리**

`TenantsListPage.tsx:109` 근처의 "RP_ADMIN role 숨기기 필요" TODO를 확인. RP_ADMIN은 RequirePlatform로 이 페이지에 못 오므로 자연 해소되나, "신규 tenant" 버튼 등에 방어적 `isPlatform(me)` 가드가 있으면 유지하고 TODO 주석 제거. me prop이 이 컴포넌트에 없으면(TenantsListPage가 me를 안 받으면) RequirePlatform 보호로 충분하니 TODO만 제거. 실제 코드 읽어 판단.

- [ ] **Step 2: 전체 빌드 + 테스트**

Run: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/rp-admin-role-gating/admin-ui && npx tsc -b && npm test`
Expected: 타입체크 통과 + 전체 테스트 green(smoke 1 + roles 4 + RequirePlatform 3 + Sidebar 2 + SettingsPage 2 = 12). admin-app 백엔드 빌드는 무관(admin-ui만 변경).

- [ ] **Step 3: followups 갱신**

Create `docs/superpowers/followups/2026-05-31-rp-admin-role-gating-followups.md`: RP_ADMIN 게이팅 완료. deferred: admin-ui 다른 후속 화면(MFA recovery 표시/password reset 랜딩/API key rotation 버튼/scope UI/alert 설정), 브라우저 dogfooding(로컬 서버+seed 계정 bob/alice — 환경 의존), Header에 RP_ADMIN '설정/내 계정' 진입점 검토(nav에서 PLATFORM 항목 숨긴 후 RP가 설정 가는 동선), RP_ADMIN 전용 nav 라벨(현재 NAV_RP 재사용). 그리고 `project_rp_admin_dashboard_crash` 메모리의 "role 게이팅 미해결" 갱신 권고.

- [ ] **Step 4: 커밋 전 게이트 — codex review (가능 시) + code quality**

메모리 지침: 누적 diff(`3728f04..HEAD`)에 `/codex review`(6/1 리셋 후). 특히 RequirePlatform redirect loop·tenantId null, PLATFORM 무영향.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/TenantsListPage.tsx docs/superpowers/followups/
git commit -m "docs(followups): RP_ADMIN 게이팅 완료 + TenantsListPage TODO 정리"
```

---

## Self-Review

**Spec coverage:**
- 진입 redirect role 분기 (§2.1): Task 4 (`*` route) ✅
- RequirePlatform guard (§2.2): Task 3 + Task 4(감싸기) ✅
- Sidebar nav 필터 (§3.1): Task 5 ✅
- Settings 탭 필터 (§3.2): Task 6 ✅
- 분산 체크 통일 (§3.3): Task 5/6(헬퍼 사용) + Task 7(TenantsListPage TODO) ✅
- roles.ts 헬퍼 (§4): Task 2 ✅
- 에러처리/redirect loop/tenantId null (§5): Task 3(에러 상태) + Task 4(/tenants/:id guard 미적용) ✅
- 테스트 vitest 도입 + 핵심 단위 (§6): Task 1(셋업) + Task 2/3/5/6(테스트) ✅
- followups: Task 7 ✅

**Placeholder scan:** 모든 step에 실제 코드/명령. "실제 코드 읽어 판단"은 Header 설정 진입점·API mock 경로·TenantsListPage me 유무가 프로젝트 실제에 의존하는 실행 지시(읽을 파일 명시).

**Type consistency:**
- `isPlatform(me)/isRpAdmin(me)/rpTenantId(me)` — Task 2 정의, Task 3/4/5/6 사용 일치 ✅
- `RequirePlatform({ me, children })` — Task 3 정의, Task 4 사용 일치 ✅
- `Me` 타입(email/role/tenantId/mfaEnabled/mfaRequired) — 전 Task 일치 ✅
- 테스트 import 경로 `@/api/types`, `./roles`, `./RequirePlatform` — alias 일치 ✅

**의존성 버전 주의(Task 1)**: vitest 2.x + @testing-library/react 16 + jsdom 25는 React 18 호환. npm install 시 peer 충돌 나면 호환 버전으로 조정(구현 시 install 출력 확인).
