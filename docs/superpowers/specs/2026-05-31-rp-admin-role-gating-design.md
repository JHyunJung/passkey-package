# RP_ADMIN role 게이팅 (admin-ui) — Design

- **작성일**: 2026-05-31
- **대상**: Crosscert Passkey Platform — admin-ui (React 18 / TypeScript / react-router-dom 6 / Vite)
- **근거**: [[project-rp-admin-dashboard-crash]] (화이트스크린은 ErrorBoundary로 해결됨, role 게이팅은 별도 phase로 남겨둠)
- **성격**: 구현 설계. 후속 writing-plans의 입력물.

## 1. 목적 / 범위

admin-ui에서 **RP_ADMIN**(고객사/테넌트 관리자)이 자기 권한에 맞는 화면만 보도록 프론트엔드 role 게이팅을 추가한다. 백엔드는 이미 `TenantBoundary`/`@PreAuthorize`로 두 role을 완전 구분한다 — admin-ui는 현재 role 게이팅이 거의 없어 RP_ADMIN에게 PLATFORM 전용 화면(전체 테넌트 목록/전역 activity/audit chain)을 노출하고, 클릭 시 BE 403으로 화면이 깨지거나 빈 상태가 된다(defense-in-depth의 BE만 의존).

**두 role** (`AdminUserDetails`):
- **PLATFORM_OPERATOR**: 플랫폼 운영자, `tenantId=null`, 전체 접근.
- **RP_ADMIN**: 고객사 관리자, `tenantId=<자기 테넌트 UUID>`, 자기 테넌트 1개만.

`me`(`/admin/api/me`)에 `role` + `tenantId`가 모두 온다(`Me` 타입: email/role/tenantId/mfaEnabled/mfaRequired).

**원칙**: 중앙화 헬퍼/가드, 기존 nav 구조(`NAV_PLATFORM`/`NAV_RP`) 재사용, PLATFORM_OPERATOR 경로 무영향(회귀 없음). FE 게이팅은 UX·IA 목적 — 실제 보안 경계는 여전히 BE.

**범위 밖(deferred)**: admin-ui 다른 후속 화면(MFA recovery 표시, password reset 랜딩, API key rotation 버튼, scope UI, alert 설정 — 그룹 A·B·C 백엔드의 UI). P2 전체. 별도 phase.

## 2. 진입 / route guard

### 2.1 로그인 후 진입
현재 App.tsx의 `*` route가 `/tenants`(전역 목록)로 redirect — RP_ADMIN엔 부적절. role 분기:
- PLATFORM_OPERATOR → `/tenants` (전역 목록, 기존)
- RP_ADMIN → `/tenants/{me.tenantId}` (자기 테넌트 상세 직행)

### 2.2 RequirePlatform route guard (신규 `src/me/RequirePlatform.tsx`)
```tsx
// PLATFORM 이면 children, RP_ADMIN 이면 자기 테넌트로 redirect.
<RequirePlatform>{children}</RequirePlatform>
```
- PLATFORM → children 렌더.
- RP_ADMIN → `<Navigate to={`/tenants/${me.tenantId}`} replace />` (자기 테넌트로).
- RP_ADMIN인데 tenantId null(데이터 이상, BE가 non-null 보장하나 방어) → 에러 상태("계정 구성 오류" 안내, redirect loop 방지).

App.tsx의 PLATFORM 전용 route를 감쌈:
- `/tenants`(목록), `/activity`, `/audit-chain`, `/license` → `<RequirePlatform>`
- `/tenants/:id`, `/settings` → 둘 다 접근(guard 없음; `/tenants/:id`는 BE `TenantBoundary`가 cross-tenant 차단, `/settings`는 탭 내부 필터).

`/tenants/:id`를 guard로 감싸지 않으므로 RequirePlatform의 RP redirect 대상이 다시 guard에 걸리지 않음 → **redirect loop 없음**.

## 3. nav 필터 + Settings 탭 게이팅

### 3.1 Sidebar nav 필터 (`Sidebar.tsx`)
현재 메인 화면(tenant context 없음)에서 role 무관 `NAV_PLATFORM`(Tenants/Activity/Audit Chain/설정) 전체 노출. `isPlatform`은 이미 계산됨(`Sidebar.tsx:78`).
- RP_ADMIN: `NAV_PLATFORM`의 Tenants/Activity/Audit Chain 숨김. 설정(Settings)은 노출(내 계정 접근). RP_ADMIN은 로그인 후 자기 테넌트로 직행하므로 자연히 `NAV_RP`(개요/WebAuthn/AAGUID/API Keys/Credentials/Audit/Funnel)를 보게 됨.
- onprem License nav 삽입 로직(`navItems` useMemo)도 PLATFORM 전용 — RP_ADMIN 필터 후 적용.
- 필터는 `isPlatform(me)` 헬퍼로(중앙화).

### 3.2 Settings 탭 role 필터 (`SettingsPage.tsx`)
현재 탭: `account`/`admins`/`mds`/`system`/`security`(하드코딩 5개 버튼), 기본 `account`.
- RP_ADMIN: `account` 탭만 노출(MFA 등 내 계정). PLATFORM 전용 탭(`admins`/`mds`/`system`/`security`)과 그 탭 컴포넌트(AdminUsersTab 등)는 렌더 안 됨.
- 기본 활성 탭: 그대로 `account`(RP·PLATFORM 모두 첫 탭).
- 탭 목록을 role로 필터 후 렌더. `isPlatform`으로 PLATFORM 전용 버튼 조건부 렌더.

### 3.3 기존 분산 체크 통일
산발적 체크(App.tsx breadcrumb:65, Sidebar:78/134, TenantsListPage:109 TODO, TenantDetailPage:105)를 중앙 헬퍼로. TenantsListPage "신규 tenant" 버튼은 RP_ADMIN이 RequirePlatform로 그 페이지에 못 오므로 자연 해소되나, 방어적으로 `isPlatform` 가드 유지.

## 4. 중앙화 role 헬퍼 (신규 `src/me/roles.ts`)

산발적 `me.role === 'PLATFORM_OPERATOR'`를 한 곳에:
```ts
import type { Me } from '../api/types';

export function isPlatform(me: Me): boolean { return me.role === 'PLATFORM_OPERATOR'; }
export function isRpAdmin(me: Me): boolean { return me.role === 'RP_ADMIN'; }
/** RP_ADMIN 의 테넌트 id(없으면 null — 데이터 이상). PLATFORM 은 null. */
export function rpTenantId(me: Me): string | null {
  return me.role === 'RP_ADMIN' ? me.tenantId : null;
}
```
Sidebar/App/Settings/RequirePlatform이 이 헬퍼를 소비. (`MeContext`의 `useMe`는 그대로, 헬퍼는 순수 함수로 분리해 테스트 용이.)

## 5. 에러 처리 / 경계

- **RP_ADMIN tenantId null**: redirect 대신 에러 상태(무한 loop 방지). BE 불변식상 발생 안 하나 방어.
- **redirect loop 방지**: `/tenants/:id`는 guard 미적용 → RP redirect 대상이 다시 안 걸림.
- **me 로딩/미인증**: guard는 `me` 로드 후 평가. `MeContext` loading 동안 기존 로딩 UI, me=null이면 기존 로그인 흐름.
- **defense-in-depth**: FE 게이팅은 UX·IA. 보안 경계는 BE(`TenantBoundary`/`@PreAuthorize`) — FE 우회해도 403.
- **PLATFORM_OPERATOR 무영향**: 모든 변경은 RP_ADMIN 분기 추가만, PLATFORM 경로 보존.

## 6. 테스트 전략 (vitest 첫 도입 + 핵심 단위)

admin-ui는 현재 테스트 인프라 0(vitest/jest/testing-library 없음, 테스트 파일 없음, `tsc -b && vite build`만). role 판별은 보안 경계라 회귀 방어 가치가 충분 → vitest 도입.

- **셋업**: `vitest` + `@testing-library/react` + `jsdom` devDependency, `vitest.config.ts`(또는 vite config test), `"test": "vitest run"` 스크립트. 첫 테스트 인프라.
- **핵심 단위 테스트**:
  - `roles.ts`: isPlatform/isRpAdmin/rpTenantId — 두 role + tenantId null/non-null 값.
  - `RequirePlatform`: PLATFORM→children, RP_ADMIN→`/tenants/{tenantId}` Navigate(MemoryRouter로 redirect 검증), tenantId null→에러 상태.
  - Sidebar nav 필터: RP_ADMIN me→PLATFORM 항목(Tenants/Activity/Audit Chain) 미노출, PLATFORM me→노출.
  - Settings 탭 필터: RP_ADMIN me→PLATFORM 전용 탭 미노출(account만), PLATFORM me→전체.
- **생략(YAGNI)**: 전체 App 라우팅 통합, 페이지 컴포넌트 렌더, E2E. 브라우저 dogfooding은 선택(로컬 서버+seed 환경 의존 — followups).
- **타입 게이트**: `tsc -b` 빌드가 타입 검증(기존 테스트 0이라 회귀 없음).

## 7. 커밋 전 게이트

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 누적 diff) + code quality subagent. 특히 RequirePlatform redirect loop·tenantId null, nav/탭 필터의 PLATFORM 무영향.

## 8. 구현 순서(권장)

1. vitest 셋업(devDep + config + script).
2. `roles.ts` 헬퍼 + 단위 테스트.
3. `RequirePlatform` + 단위 테스트.
4. App.tsx route 감싸기 + 진입 redirect(role 분기).
5. Sidebar nav 필터 + 테스트.
6. SettingsPage 탭 필터 + 테스트.
7. 빌드 검증(`tsc -b`, `vitest run`) + followups.
