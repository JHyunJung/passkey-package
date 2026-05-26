# Phase 5 — Admin UI Redesign Design

**Date:** 2026-05-26
**Status:** Design (awaiting plan)
**Predecessor:** Phase 4 (API response standardization) — tag `phase-4-api-response-standard-complete`
**Source design package:** `docs/design-package/` (Claude Design handoff bundle, OKLCH tokens v2)

## 1. Goal

기존 admin-ui 8 페이지를 Linear/Vercel 풍의 OKLCH 디자인 시스템으로 재작성한다. 백엔드 변경 0. 신규 페이지 0. Cmd-K 검색 팔레트 + 30분 idle 세션 만료 모달 추가.

본 Phase는 시각/UX 품질만 끌어올리는 자족적 변경이다. Phase 5 종료 후 admin 콘솔이 "운영 가능한 상태"가 된다 (현재는 brutalist `<table border={1}>` 수준).

## 2. Architecture

```
admin-ui/
├── src/
│   ├── styles/
│   │   └── tokens.css       NEW — 470줄 OKLCH 토큰 시스템 (Light 고정)
│   ├── components/
│   │   ├── Layout.tsx       REWRITE — Sidebar + Header + Outlet
│   │   ├── Sidebar.tsx      NEW — 4 nav items (Tenants/Keys/MDS/Audit)
│   │   ├── Header.tsx       NEW — 검색 입력 + 사용자 메뉴
│   │   ├── Dialog.tsx       NEW — 모달 (Cmd-K, Idle, Confirm)
│   │   ├── Toast.tsx        NEW — 우하단 토스트 + ApiError 통합
│   │   ├── Icons.tsx        NEW — 30개 inline SVG
│   │   ├── CommandPalette.tsx  NEW — ⌘K 페이지 jump
│   │   └── IdleTimeout.tsx     NEW — 30분 idle + 60초 카운트다운 모달
│   ├── pages/
│   │   ├── Login.tsx        REWRITE — split hero + form (디자인 mock 차용)
│   │   ├── TenantList.tsx   REWRITE — KPI 카드 + filter + table
│   │   ├── TenantCreate.tsx REWRITE — 폼 카드
│   │   ├── ApiKeyList.tsx   REWRITE — table + 발급 dialog
│   │   ├── ApiKeyCreateModal.tsx  REWRITE — 일회성 plaintext 노출 dialog
│   │   ├── AuditLog.tsx     REWRITE — filter + table
│   │   ├── MdsStatus.tsx    REWRITE — KPI + force-sync 버튼
│   │   └── KeyManagement.tsx REWRITE — table + rotate dialog (확인 prompt)
│   └── api/
│       ├── client.ts        UNCHANGED (Phase 4 envelope unwrap layer 보존)
│       └── types.ts         UNCHANGED
```

**핵심 설계 결정 (확정)**

| 항목 | 결정 |
|------|------|
| 디자인 시스템 출처 | `docs/design-package/project/src/tokens.css` (470줄, OKLCH v2) |
| 폰트 로딩 | CDN (Pretendard jsdelivr + Geist Google Fonts) — 디자인 의도 보존 |
| Theme | Light 고정 (Tweaks panel 제외, tokens.css의 다크 변형은 보존하되 토글 UI 없음) |
| Density / sidebar / table style | Comfortable + label sidebar + bordered table 고정 (변형 토큰 보존하되 토글 없음) |
| 적용 범위 | 기존 8 페이지 리스킨 + Cmd-K + Idle modal (수령 A) |
| 신규 페이지 | 0 (Activity, Audit Chain Monitor, Settings는 Phase 6+로 미룸) |
| 백엔드 변경 | 0 |
| Cmd-K 범위 | 페이지 이동 6개만 |
| Idle 만료 | 30분 무활동 + 60초 카운트다운 후 자동 logout |
| 기존 ApiError 통합 | client.ts의 ApiError throw를 받아 Toast로 자동 표시 |
| 디자인 패키지 보존 | `docs/design-package/`로 commit (참조용, 영구) |
| State 관리 | 기존 useState 패턴 유지 (Zustand/Redux 도입 안 함) |
| 라이브러리 추가 | 없음 (디자인 mock도 외부 라이브러리 0 — pure React + CSS) |

## 3. Component Inventory

### 3.1 `admin-ui/src/styles/tokens.css` — NEW

`docs/design-package/project/src/tokens.css`를 그대로 복사. 470줄. Light 고정이라 `:root[data-theme="dark"]` 블록은 보존하되 활성화하지 않음. `:root[data-density]`, `:root[data-sidebar]`, `:root[data-tablestyle]` 변형도 동일.

CSS 클래스 제공: `.btn`, `.btn--primary/--danger/--ghost/--outline/--sm/--xs/--lg`, `.input`, `.label`, `.hint`, `.card`, `.card__head/__title/__body`, `.badge`, `.badge--success/--warning/--danger/--info/--violet/--teal/--accent`, `.kbd`, `.divider`, `.chip`, `.table`, `.mono`, `.muted`, `.faint`, `.app`, `.content`, `.page`, `.page__head/__title/__sub`, `.tabs`, `.scrim`, `.dialog`, `.toast`, `.metric-label/-value/-delta`, `.skeleton`, `.banner`, `.empty`, `.row`, `.col`, `.stack-1~8`, `.grid-2/3/4`, `.spacer`.

`main.tsx`에서 `import './styles/tokens.css'` 한 줄로 전역 적용.

### 3.2 `admin-ui/index.html` — MODIFY

`<head>`에 CDN font preconnect + stylesheet 추가:

```html
<link rel="preconnect" href="https://cdn.jsdelivr.net" crossorigin />
<link rel="preconnect" href="https://fonts.googleapis.com" crossorigin />
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css" />
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600;700&family=Geist+Mono:wght@400;500;600&display=swap" />
```

`<html lang="ko">`로 변경 (기존이 `en`이면). 페이지 제목 "Crosscert Passkey Admin"로 설정.

### 3.3 `admin-ui/src/components/Icons.tsx` — NEW

`docs/design-package/project/src/icons.jsx`에서 사용 빈도 높은 ~30개 아이콘을 골라 TypeScript로 포팅. 각 아이콘은 `({size?: number, className?: string}) => JSX.Element` 시그니처. 모두 inline SVG (외부 의존 0).

핵심 아이콘: `BrandMark`, `Search`, `Plus`, `Check`, `X`, `Alert`, `Building`, `Key`, `Receipt`, `Shield`, `Activity`, `Cog`, `User`, `Logout`, `Hash`, `Fingerprint`, `Globe`, `Spinner`, `Copy`, `ChevronDown`, `ChevronRight`, `Filter`, `Download`, `Trash`, `Refresh`, `Lock`, `EyeOff`, `Eye`, `Info`, `ExternalLink`.

### 3.4 `admin-ui/src/components/Layout.tsx` — REWRITE

```tsx
export default function Layout() {
  return (
    <div className="app">
      <Sidebar />
      <div className="content">
        <Header />
        <main><Outlet /></main>
      </div>
    </div>
  );
}
```

기존 `<nav>` 인라인 markup 제거.

### 3.5 `admin-ui/src/components/Sidebar.tsx` — NEW

좌측 232px 고정 폭. 상단 BrandMark + "Passkey Admin" 라벨 + "Crosscert · prod" 부제. 4개 nav 링크 (Tenants/Keys/MDS/Audit). 하단 footer: "AUDIT CHAIN OK · 마지막 검증 2분 전" 상태 인디케이터 (디자인 mock 차용, 실제 데이터는 GET /admin/api/audit/verify 결과 사용).

각 nav item은 `react-router-dom`의 `NavLink`를 사용해 활성 라우트에 강조 스타일.

### 3.6 `admin-ui/src/components/Header.tsx` — NEW

상단 56px 고정 높이. 좌측: 페이지 타이틀 (현재 라우트에서 추론) + breadcrumb. 중앙: 검색 입력 (`⌘K`로 CommandPalette 트리거). 우측: 사용자 이름 (GET /admin/api/me) + role 배지 + 드롭다운 (logout).

### 3.7 `admin-ui/src/components/Dialog.tsx` — NEW

`design-package/project/src/shell.jsx`의 Dialog 컴포넌트 포팅. props: `open`, `onClose`, `title`, `sub?`, `children`, `footer?`, `wide?`. Escape 키 + 스크림 클릭으로 닫힘. CSS는 tokens.css의 `.scrim` `.dialog` `.dialog__head/__title/__sub/__body/__foot`.

### 3.8 `admin-ui/src/components/Toast.tsx` — NEW

`design-package/project/src/shell.jsx`의 ToastHost + useToast hook 포팅. `ToastProvider`로 `<App>`을 감싸고, 페이지에서 `useToast()` 호출해 `push({kind: 'ok'|'err'|'warn', title, message?, traceId?})`. 4.2초 후 자동 dismiss.

**ApiError 통합**: client.ts의 ApiError throw를 페이지가 catch했을 때 `toast.push({kind:'err', title: e.serverMessage, traceId: e.traceId})`로 표시.

### 3.9 `admin-ui/src/components/CommandPalette.tsx` — NEW

⌘K (Mac) / Ctrl+K (Win) 단축키로 열림. Dialog wide + 검색 입력 + 필터된 결과 리스트. 결과는 페이지 6개 고정:

```
Tenants       → /tenants
Keys          → /keys
MDS Status    → /mds
Audit Log     → /audit
API Keys      → /api-keys
New Tenant    → /tenants/new
```

키보드: ↑↓로 항목 이동, Enter로 navigate, Esc로 닫기. 검색은 라벨 substring match.

### 3.10 `admin-ui/src/components/IdleTimeout.tsx` — NEW

30분 무활동 감지 (`mousemove`, `keydown`, `scroll`, `click` 리스너로 lastActive timestamp 갱신). 30분 도달 시 Dialog 노출 + 60초 카운트다운. "계속 사용" 버튼 클릭 시 dismiss + 타이머 리셋. 60초 도달 시 자동으로 `/admin/logout` POST → `/admin/login`으로 redirect.

본 Phase는 데모를 위해 production은 30분이지만 dev에서 90초로 줄이는 query param/env var는 추가하지 않는다 (디자인 mock의 데모 모드는 제외).

### 3.11 페이지 8개 — REWRITE

각 페이지는 `docs/design-package/project/src/pages-*.jsx`의 해당 mock을 참조해 재작성. 기존 호출 로직 (`api.get/post`)는 보존. 변경은 markup + className + style만.

| 페이지 | 디자인 mock 위치 | 핵심 변경 |
|--------|-----------------|----------|
| Login | pages-1.jsx (LoginPage) | Split hero (왼쪽 그라데이션 + 카피, 오른쪽 폼). KPI 미리보기는 mock 데이터로 (실제 fetch 안 함) |
| TenantList | pages-2.jsx (TenantsPage) | KPI 카드 4개 + 검색/필터 + table card |
| TenantCreate | pages-2.jsx의 NewTenantDialog | card form (전체 페이지 폼) |
| ApiKeyList | pages-3.jsx (ApiKeysPage) | tenant 선택 + table + 발급 버튼 |
| ApiKeyCreateModal | pages-3.jsx의 NewApiKeyDialog | **일회성 plaintext 노출 강조** (복사 강제, scrim 클릭으로 안 닫힘) |
| AuditLog | pages-3.jsx의 AuditTab | filter chip + table + chain status banner |
| MdsStatus | pages-2.jsx 차용 | KPI (version/nextUpdate/fetchedAt) + Force sync 버튼 + 마지막 결과 표시 |
| KeyManagement | pages-2.jsx 차용 | KPI (ACTIVE/ROTATED 카운트) + table + Rotate 버튼 (Dialog confirm) |

각 페이지는 기존 client.ts 함수 호출 시그니처 보존. ApiError catch → Toast 표시 패턴 적용.

### 3.12 백엔드 변경 없음

admin-app의 어떤 Java 파일도 수정하지 않음. SPA가 build → `processResources`로 admin-app jar에 번들되는 기존 흐름 그대로 사용.

## 4. Wire Format (변경 없음)

Phase 4의 `ApiResponse<T>` envelope 그대로 사용. 신규 엔드포인트 0.

## 5. Migration order

```
T1.  tokens.css + index.html 폰트 + main.tsx import
T2.  Icons.tsx (30 SVG 포팅)
T3.  Dialog.tsx + Toast.tsx (공통 컴포넌트)
T4.  Sidebar.tsx + Header.tsx (Layout shell)
T5.  Layout.tsx REWRITE (T3+T4 통합)
T6.  Login.tsx REWRITE
T7.  TenantList.tsx + TenantCreate.tsx REWRITE
T8.  ApiKeyList.tsx + ApiKeyCreateModal.tsx REWRITE
T9.  AuditLog.tsx REWRITE
T10. MdsStatus.tsx REWRITE
T11. KeyManagement.tsx REWRITE
T12. CommandPalette.tsx
T13. IdleTimeout.tsx
T14. ApiError → Toast 통합 (client.ts 살짝 hook 추가)
T15. design-package commit (참조용 보존)
T16. DoD verify + tag
```

각 단계는 자체적으로 build green 유지. SPA 빌드 + admin-app bootJar이 매번 통과.

## 6. Testing strategy

### admin-ui (frontend)
**현재 admin-ui에는 단위 테스트 0개**. Phase 5에서 Vitest 도입은 OOS — 다음 Phase로 미룸. 본 Phase는:
- **빌드 검증** (`npm run build` + TypeScript strict)
- **시각 검증** (Playwright MCP로 각 페이지 스크린샷 1장씩, 디자인 mock과 대조)
- **수동 클릭 흐름** (DoD에서 사용자가 직접 확인)

### Backend (변경 없음)
기존 134 + Phase 4의 16 = 150 tests 그대로 green 유지. SPA 번들 변경은 admin-app processResources만 영향 → 자동 회귀 테스트 동일.

### Manual smoke (DoD)
- 6 페이지 모두 로드, 디자인 적용 확인
- 신규 tenant 생성 흐름
- API key 발급 + 일회성 plaintext 노출 dialog 동작
- Key rotate confirm dialog → S001 conflict 시 Toast
- MDS Force sync → SyncResult 표시
- ApiError 발생 시 Toast 자동 노출 + traceId 표시
- ⌘K로 페이지 jump
- 30분 idle 시 모달 (dev 검증을 위해 환경변수로 30초 단축 가능?  → 본 Phase 미포함)
- Dark mode 토글 없음 확인 (Light 고정)
- Console 0 error
- 모든 페이지 정확한 wire shape (envelope.success/data 처리)

## 7. Risks & mitigations

| 위험 | 완화 |
|------|------|
| **CDN 폰트 차단 (corporate firewall)** | tokens.css의 fallback chain이 system font로 보완. 차단 시 디자인은 살짝 어색하지만 동작은 정상. Phase 6+에 npm 번들로 마이그레이션 가능 (인터페이스 변경 0) |
| **OKLCH 브라우저 지원** | 모든 메이저 브라우저 2023+ 지원. Safari 16.4+, Chrome 111+, Firefox 113+. Crosscert 운영자 환경은 최신 Chrome 가정 |
| **30분 idle 자동 logout이 사용자 작업 중 발화** | 디자인 mock은 60초 카운트다운으로 사용자가 작업 중단 인지 가능. "계속 사용" 버튼으로 즉시 재시작 |
| **Cmd-K가 OS 시스템 단축키와 충돌** | Cmd-K는 macOS에서 일부 앱이 점유. Chrome 자체는 미사용. preventDefault 처리 |
| **CSS 충돌 (기존 admin-ui inline style)** | 모든 inline style 제거 + className 마이그레이션. T1~T11 한 페이지씩 진행하므로 각 단계에서 격리 |
| **디자인 mock의 React 17/18 vanilla JSX와 우리 TypeScript의 차이** | mock은 type-less. 포팅 시 type 추가. JSX 구조는 동일 |
| **번들 사이즈 증가** | tokens.css 470줄 (~10KB), 신규 컴포넌트 ~15KB. 총 +25KB 미만. 캐싱으로 흡수 가능 |

## 8. Out-of-scope (의도적 제외)

- **Activity feed 페이지** — Phase 6+ (백엔드 신규 필요)
- **Audit Chain Monitor 페이지** — Phase 6+ (백엔드 신규 필요)
- **Settings 4 subtabs** — Phase 6+ (admin user CRUD 등)
- **Role 분리 (PLATFORM_OPERATOR / RP_ADMIN)** — Phase 6+
- **Tweaks panel** — 운영 가치 낮음, 영구 제외
- **Theme 토글** — Light 고정
- **Density / sidebar / table style 토글** — 고정값
- **i18n (영어/한국어 토글)** — 한국어 주력, 영어는 fallback
- **admin-ui Vitest 도입** — Phase 6+
- **OpenAPI client 자동 생성** — Phase 6+
- **font npm 번들** — CDN 유지

## 9. Plan task 예상 수

16 tasks:
- T1 tokens + 폰트 wiring: 1
- T2 Icons: 1
- T3 Dialog + Toast: 1
- T4 Sidebar + Header: 1
- T5 Layout: 1
- T6-T11 페이지 8개 (Tenant/ApiKey 묶음 + 단일들): 6
- T12 CommandPalette: 1
- T13 IdleTimeout: 1
- T14 ApiError → Toast 통합: 1
- T15 design-package commit: 1
- T16 DoD + tag: 1

Phase 4 (13) 동등 사이즈. 백엔드 변경 0이라 IT는 적으나 visual review 비중이 큼.
