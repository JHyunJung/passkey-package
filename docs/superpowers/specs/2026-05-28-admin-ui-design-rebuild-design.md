# Admin UI — 디자인 패키지 1:1 재구현

- **작성일**: 2026-05-28
- **작성자**: jhyun (with Claude)
- **상위 컨텍스트**: 디자인 패키지(`docs/design-package/`) 의 `Passkey Admin Console.html` 과 화면이 계속 어긋남. Phase A~D 가 shadcn/Tailwind 기반으로 새로 그렸기 때문. 디자인이 진짜이고 서버가 거기에 맞춰야 한다는 사용자 원칙 확정.
- **한 줄 정의**: admin-ui 를 완전히 폐기하고 디자인 패키지의 jsx 12 파일을 TypeScript 로 1:1 포팅. 서버 API 는 그대로 두고 UI 의 client 어댑터가 변환. 부족한 데이터는 fixture + "v1.1 예정" 배지.

---

## 1. 배경

### 1.1 어긋남의 원인 (이번 spec 의 출발점)
Phase A~D 가 디자인의 **기능 목록** 만 보고 shadcn 컴포넌트로 새로 그림. 디자인의 `pages-1~6.jsx`/`shell.jsx`/`extras.jsx` 의 raw HTML 구조를 한 번도 안 보았음. 결과: 색/폰트/토큰은 비슷한데 레이아웃·간격·정보 구성·인터랙션 디테일이 모두 어긋남.

### 1.2 사용자 결정 (확정된 7가지)
1. **목표**: D1 — UI/UX 픽셀 일치, 데이터는 더미 허용
2. **전환 전략**: admin-ui 완전 재작성
3. **기존 코드**: `admin-ui/src/` 통째 삭제. `api/client.ts` + `me/MeContext.tsx` 만 재사용 (서버 연결 코드)
4. **서버 API**: 그대로 유지 (PRD 의 `/api/v1/admin/**` 로 안 바꿈). UI client 가 어댑터
5. **빠진 endpoint**: 클라이언트 fixture + "v1.1 예정" 배지
6. **Phase 분할**: 3 phase (E1 셸 / E2 핵심 / E3 보조)
7. **스택**: Vite 5 + React 18 + TypeScript 5. shadcn/Tailwind 제거 (cmdk 옵션). 디자인 패키지의 raw CSS class + inline style + tokens.css 그대로

### 1.3 디자인 패키지 인벤토리
- 위치: `docs/design-package/project/` (이번 spec 시작 시점에 최신 패키지로 교체됨)
- 핵심 JSX (총 12 파일, ~3600 라인):
  - `app.jsx` — 라우팅 + Tweaks state + Main 분기
  - `shell.jsx` — ToastHost / Dialog / Sidebar / Header / Breadcrumb / EmptyState / StatusBadge / CopyBtn
  - `extras.jsx` — IdleSessionModal / CommandPalette
  - `tweaks-panel.jsx` — TweaksPanel + TweakSection/Radio/Color/Toggle
  - `pages-1.jsx` — LoginPage / TenantsListPage / NewTenantDialog / MetricCard / Stat
  - `pages-2.jsx` — TenantDetailPage / TenantHeader / TenantTabs / TenantOverview / WebauthnConfigTab / DiffDialog / AaguidPolicyTab + Field / Segmented / Toggle
  - `pages-3.jsx` — ApiKeysTab / NewKeyDialog / IssuedKeyModal / RevokeKeyDialog / CredentialsTab / RevokeCredentialDialog / AuditTab / EventTypeBadge / ChainVerifyCard / ChainVerifyDialog / PayloadDialog / FunnelTab / Funnel / BarChart / EventDistribution
  - `pages-4.jsx` — ActivityPage + ChipTab
  - `pages-5.jsx` — AuditChainPage / ChainSparkline / MonthlyReportDialog
  - `pages-6.jsx` — SettingsPage / AdminUsersTab / NewAdminDialog / MdsStatusTab / KvLine / SystemInfoTab / ComponentRow / SecurityPolicyTab
  - `icons.jsx` — SVG 아이콘 namespace
  - `data.js` — mock data (fixture 소스)
- `design-canvas.jsx` — Figma 풍 캔버스 wrapper (모든 화면을 한 페이지에 펼침). **포팅 대상 아님** — 디자인 검수용 도구
- 디자인 토큰: `tokens.css` + `tokens.legacy.css` (legacy 는 참고용, 포팅은 tokens.css 만) — OKLCH 색상 + Geist/Pretendard + density/sidebarMode/tableStyle/theme attribute
- 39 스크린샷: `project/screenshots/` (시각 검증 ground truth)
- 보조 문서: `uploads/admin-console-prd.md`, `uploads/admin-console-action-plan.md`

---

## 2. 아키텍처

### 2.1 단일 진실의 원칙
디자인 패키지가 **진짜**. jsx 컴포넌트의 함수명, prop 이름, JSX 구조, className, inline style 모두 변경 없이 .tsx 로 포팅. 서버 응답이 다르면 **어댑터에서 변환** (jsx 절대 수정 안 함).

### 2.2 데이터 흐름
```
디자인 컴포넌트 (.tsx, jsx 그대로)
    │
    └── api/tenants.ts.list()
            │
            └── api.get<Server.TenantView[]>('/admin/api/tenants')
                    │
                    └── adapter: TenantView → Tenant
                        - displayName → name
                        - + credentials/apiKeys/lastEventAt: fixture[id] || 0/null
                        - + status: enabled → "ACTIVE"/"SUSPENDED"
```

### 2.3 fixture 정책
서버에 없는 필드/endpoint:
- Tenant KPI (credential count, apiKey count, lastEventAt, ceremonyCount24h)
- Funnel (등록/인증 단계별 카운트, daily trends, event distribution)
- Activity 의 p95Ms (서버는 placeholder)
- AdminUser MFA 상태
- AuditChain 의 verificationMs 같은 자세한 메트릭
- MonthlyReport PDF
- Settings 의 SystemInfo / SecurityPolicy 일부

처리:
- `admin-ui/src/fixtures/*.ts` 모듈 → 디자인 `data.js` 의 mock 값을 그대로 복사
- 어댑터에서 server response + fixture 합쳐서 design type 으로 반환
- "v1.1 예정" 배지 (디자인 자체가 이미 Settings 탭에 노란 배너로 표시) 가 적용되는 화면은 적용

### 2.4 디렉토리 구조
```
admin-ui/
├── index.html                # 기존 유지 (Vite entry)
├── package.json              # 의존성 정리: shadcn/Tailwind 모두 제거. react/react-dom/react-router-dom 만 + (옵션) cmdk
├── tsconfig.json             # @/* alias 유지
├── vite.config.ts            # 유지
└── src/
    ├── main.tsx              # 재작성
    ├── App.tsx               # 디자인 app.jsx 포팅 + react-router-dom 어댑터
    ├── styles/
    │   ├── tokens.css        # 디자인 패키지 tokens.css 그대로 복사
    │   └── globals.css       # 신규 — @import tokens + body reset + Pretendard
    ├── icons/
    │   └── Icons.tsx         # 디자인 icons.jsx 포팅, Icons namespace
    ├── shell/
    │   ├── Dialog.tsx
    │   ├── ToastHost.tsx
    │   ├── Sidebar.tsx       # 브랜드 헤더 + nav + chain status carousel
    │   ├── Header.tsx        # breadcrumb + ⌘K 검색바 + role badge + 사용자 메뉴
    │   ├── Breadcrumb.tsx
    │   ├── EmptyState.tsx
    │   ├── StatusBadge.tsx
    │   └── CopyBtn.tsx
    ├── extras/
    │   ├── CommandPalette.tsx
    │   └── IdleSessionModal.tsx
    ├── tweaks/
    │   ├── TweaksPanel.tsx   # 디자인 tweaks-panel.jsx 포팅
    │   ├── useTweaks.ts      # localStorage + accent color picker (data-theme/density/tablestyle/sidebarMode + inline --accent)
    │   └── tweaks-utils.ts   # shade / withAlpha
    ├── pages/
    │   ├── LoginPage.tsx
    │   ├── TenantsListPage.tsx
    │   ├── TenantDetailPage.tsx
    │   ├── tenant/
    │   │   ├── TenantOverview.tsx
    │   │   ├── WebauthnConfigTab.tsx
    │   │   ├── AaguidPolicyTab.tsx
    │   │   ├── ApiKeysTab.tsx
    │   │   ├── CredentialsTab.tsx
    │   │   ├── AuditTab.tsx
    │   │   └── FunnelTab.tsx
    │   ├── ActivityPage.tsx
    │   ├── AuditChainPage.tsx
    │   ├── SettingsPage.tsx
    │   └── settings/
    │       ├── AdminUsersTab.tsx
    │       ├── MdsStatusTab.tsx
    │       ├── SystemInfoTab.tsx
    │       └── SecurityPolicyTab.tsx
    ├── api/
    │   ├── client.ts         # 기존 유지 (CSRF + /admin/login + 401 redirect)
    │   ├── types.ts          # 디자인 타입 (Tenant, ApiKey 등) + Server.* 분리
    │   ├── tenants.ts        # 어댑터 + list/get/create
    │   ├── apiKeys.ts
    │   ├── credentials.ts
    │   ├── audit.ts          # from/to → windowHours 어댑팅
    │   ├── activity.ts
    │   ├── auditChain.ts     # 기존 Phase B endpoint 그대로
    │   ├── webauthn.ts       # diff endpoint 사용
    │   ├── aaguidPolicy.ts
    │   ├── adminUsers.ts
    │   ├── mdsStatus.ts
    │   └── funnel.ts         # fixture 직접 반환
    ├── fixtures/
    │   ├── tenantKpi.ts      # tenantId → {credentials, apiKeys, lastEventAt, ceremonyCount24h}
    │   ├── funnel.ts
    │   ├── activity.ts
    │   ├── auditChainState.ts
    │   ├── adminMfa.ts
    │   ├── systemInfo.ts
    │   └── securityPolicy.ts
    ├── me/
    │   └── MeContext.tsx     # 기존 유지
    └── lib/
        ├── formatDateTime.ts # 기존 유지
        ├── cn.ts             # 단순 join (filter Boolean)
        └── search.ts         # CommandPalette 전역 검색 fixture
```

### 2.5 라우터 어댑터
디자인 `app.jsx` 는 자체 `route = {name, tenantId, tab}` state. 우리는 `react-router-dom` 유지:

| 디자인 route | URL |
|---|---|
| `{name:"tenants"}` | `/tenants` |
| `{name:"tenant", tenantId, tab}` | `/tenants/:id?tab=...` |
| `{name:"activity"}` | `/activity` |
| `{name:"audit-chain"}` | `/audit-chain` |
| `{name:"settings"}` | `/settings` |
| (없음 — me 없을 때) | `/login` |

`App.tsx` 가 `useLocation()`/`useNavigate()` 로 양방향 변환. 디자인 컴포넌트는 `setRoute` 같은 prop 을 받음 → 어댑터가 `navigate(...)` 호출.

---

## 3. Phase E1 — 디자인 시스템 + 셸 + Login (9 sub-tasks)

**목표**: 로그인까지 작동. 모든 라우트는 placeholder, 셸만 표시. 디자인 시각 시스템 100% 일치.

| # | 작업 | Commit prefix |
|---|---|---|
| E1.1 | `admin-ui/src/` 통째 삭제 + Tailwind/shadcn 의존성 제거 + main.tsx stub + 빌드 통과 | `chore(admin-ui)` |
| E1.2 | tokens.css + globals.css + icons/Icons.tsx + lib/cn.ts | `feat(admin-ui)` |
| E1.3 | shell primitives (Dialog/ToastHost/Breadcrumb/EmptyState/StatusBadge/CopyBtn) | `feat(admin-ui)` |
| E1.4 | Sidebar + Header (디자인 1:1, chain status carousel 정적) | `feat(admin-ui)` |
| E1.5 | TweaksPanel + useTweaks (accent color picker 포함) | `feat(admin-ui)` |
| E1.6 | CommandPalette + IdleSessionModal | `feat(admin-ui)` |
| E1.7 | LoginPage (좌측 marketing + 우측 form + 데모 role 카드) + me/MeContext 보존 + api/client 보존 | `feat(admin-ui)` |
| E1.8 | App.tsx + main.tsx + react-router-dom 어댑터 | `feat(admin-ui)` |
| E1.9 | 빌드 + 브라우저 smoke 체크리스트 통과 | (없음 — verify only) |

브라우저 smoke 체크 (E1.9):
- [ ] Login 페이지 — 디자인 스크린샷 (`01-login.png`) 픽셀 일치
- [ ] 로그인 → 사이드바 + 헤더 표시
- [ ] ⌘K → CommandPalette 오픈
- [ ] TweaksPanel → 5 옵션 즉시 반영
- [ ] sidebarMode=icons / 다크 모드 / accent color picker 모두 동작
- [ ] Idle 모달 (IDLE_MS 임시 단축으로 테스트)

---

## 4. Phase E2 — 핵심 운영 화면 (11 sub-tasks)

**목표**: F-1 (RP 온보딩), F-2 (API key rotation), F-3 (credential 회수), F-4 (월간 보고서 일부) 사용자 여정 모두 디자인 화면으로 가능. Funnel 은 placeholder.

| # | 작업 |
|---|---|
| E2.1 | api/types.ts — 디자인 타입 + Server.* 분리 |
| E2.2 | fixtures/ — KPI/funnel/activity/chain/adminMfa/systemInfo/securityPolicy 모듈 |
| E2.3 | api/tenants.ts 어댑터 + TenantsListPage + NewTenantDialog |
| E2.4 | TenantDetailPage 셸 (TenantHeader + TenantTabs) |
| E2.5 | TenantOverview + ChainStatusCard |
| E2.6 | WebauthnConfigTab + DiffDialog (서버 diff endpoint 호출) |
| E2.7 | AaguidPolicyTab (mode 카드 + chip + Toggle) |
| E2.8 | ApiKeysTab + NewKeyDialog + IssuedKeyModal + RevokeKeyDialog |
| E2.9 | CredentialsTab + RevokeCredentialDialog |
| E2.10 | AuditTab + EventTypeBadge + ChainVerifyCard + ChainVerifyDialog + PayloadDialog |
| E2.11 | FunnelTab placeholder + 빌드 + 브라우저 smoke |

브라우저 smoke (E2.11):
- [ ] /tenants — `01-02-tenant-list.png` 일치
- [ ] /tenants/{id}?tab=overview
- [ ] tab=webauthn — 편집 → 변경 미리보기 → DiffDialog
- [ ] tab=aaguid — 모드 카드 + chip
- [ ] tab=apikeys — `01-03-apikeys.png` 일치 + 발급 → IssuedKeyModal plaintext
- [ ] tab=credentials — 검색 + 회수
- [ ] tab=audit — `01-04-audit.png` 일치 + 검증 dialog + payload 모달
- [ ] tab=funnel — placeholder ("v1.1 예정")

---

## 5. Phase E3 — 보조 화면 + 마무리 (7 sub-tasks)

**목표**: 디자인 패키지 39 스크린샷 모두 픽셀 일치.

| # | 작업 |
|---|---|
| E3.1 | ActivityPage + ChipTab + 5초 폴링 |
| E3.2 | AuditChainPage + ChainSparkline + MonthlyReportDialog (window.print 또는 placeholder) + 사이드바 chain status 실시간 |
| E3.3 | SettingsPage + 4 탭 (AdminUsers + MdsStatus + SystemInfo + SecurityPolicy) — read-only 배너 포함 |
| E3.4 | FunnelTab 실제 구현 (fixture) + BarChart + EventDistribution |
| E3.5 | CommandPalette 전역 검색 fixture 연결 + Header ⌘K 통합 |
| E3.6 | 데모 role 전환 + 사이드바 chain status 실시간 연결 |
| E3.7 | 최종 빌드 + 전체 39 스크린샷 1:1 비교 smoke |

브라우저 smoke (E3.7) — 전 디자인 스크린샷 비교:
- 모든 화면 (login, tenants, tenant detail 7 탭, activity, audit-chain, settings 4 탭)
- 다크/라이트 + density compact/comfortable + table style 3종 + sidebar labels/icons 조합
- CommandPalette / IdleSessionModal / 모든 Dialog / Toast
- 데모 role 전환 흐름

---

## 6. 서버 변경

**없음.** 모든 서버 endpoint 그대로. UI 어댑터가 흡수.

예외: 만약 어댑터로 해결 불가능한 missing field (예: 디자인이 필수로 가정하는 필드인데 fixture 로 처리하기 곤란) 가 발견되면 별도 mini-task 로 서버에 추가 — 단, **이번 spec 의 1차 목표 (디자인 일치) 후 추가 phase 로 분리** (Phase E4 등).

---

## 7. 의존성 정리

`admin-ui/package.json` 변경:

**제거**:
- `tailwindcss`, `autoprefixer`, `postcss`, `@types/node` (devDeps)
- `clsx`, `tailwind-merge`, `class-variance-authority`, `lucide-react`, `sonner`, `cmdk` (옵션 — cmdk 는 CommandPalette 에서 디자인이 자체 구현이므로 사실상 불필요)
- `@radix-ui/react-dialog`, `@radix-ui/react-dropdown-menu`, `@radix-ui/react-label`, `@radix-ui/react-popover`, `@radix-ui/react-slot`, `@radix-ui/react-switch`, `@radix-ui/react-tabs`, `@radix-ui/react-tooltip`

**유지**:
- `react`, `react-dom`, `react-router-dom`
- devDeps: `vite`, `@vitejs/plugin-react`, `typescript`, `@types/react`, `@types/react-dom`

빌드 결과 크기 감소 예상: 현 ~470KB → 약 ~200KB (Radix/lucide/sonner 제거).

---

## 8. OUT-OF-SCOPE (다음 phase 후보)

| 항목 | 비고 |
|---|---|
| Funnel 서버 endpoint 구현 | UI 는 fixture, 운영 시 실 데이터 필요해지면 별도 phase |
| MonthlyReport PDF 발급 (서버) | UI 는 window.print(), Jasper/iText 도입은 별도 phase |
| AdminUser MFA enforcement | UI 는 read-only fixture, 실제 MFA 도입 별도 spec |
| 전역 검색 endpoint (tenant/credential/audit ID) | UI 는 fixture, 실 검색 도입 별도 phase |
| 보안 정책 동적 설정 (idle timeout / 비밀번호 길이 / CORS) | UI 는 read-only, 저장소 별도 phase |
| 시스템 컴포넌트 상태 (실시간 health) | UI 는 fixture + actuator 일부, 별도 phase |
| Tenant 의 ceremonyCount24h, p95 응답 서버 집계 | Micrometer + DB 집계 별도 phase |
| Phase A~D 의 shadcn shim 정리 (Dialog/Switch wrapper) | 통째 삭제로 자연스럽게 해결 |

---

## 9. 위험 / 트레이드오프

| 위험 | 완화 |
|---|---|
| 디자인 jsx 의 ESLint/TypeScript 호환 문제 (디자인은 JS, 우리는 TS strict) | TypeScript strict 약간 완화 (`noImplicitAny: false` 등) + 점진 타입 보강 |
| 디자인이 가정하는 missing field (예: lastEventAt) 가 너무 많아 fixture 가 비대해짐 | fixture 정리: 의미 있는 값만 (예: 3 tenant 만 fixture, 나머지는 0/null fallback) |
| 어댑터 계층이 데이터 변환 오류로 화면이 비어 보임 | 어댑터 함수당 fallback (null-safe) + 디자인 jsx 의 EmptyState 활용 |
| Phase A~D 의 학습된 디테일 (Codex review fix 들) 잃을 위험 | Phase A~D 코드는 git history 에 보존, 필요 시 참고 가능 |
| dev profile 의 데모 role 전환 동작 깨짐 | 기존 `/admin/api/profile` 엔드포인트 활용, LOCAL_PREFILL 동등 동작 보장 |
| accent color picker 가 OKLCH 토큰 시스템과 충돌 | 디자인 자체 처리 패턴 (inline `--accent`/`--accent-hover`/`--accent-soft` style.setProperty) 그대로 따름 |

---

## 10. 실행 가이드

각 phase 는:
1. 별도 worktree (`worktree-admin-ui-redesign-e1` 등)
2. spec 의 sub-task 순서대로 commit
3. 각 commit 전 codex review (실행 불가하면 skip + 보고)
4. Phase 끝 brower smoke 통과 후 `git merge --no-ff` 로 main 에
5. 사용자 자동 진행 정책: 중간 결정은 추천안으로 자율, 큰 갈림길만 사용자 확인

다음 단계: 이 spec 승인 후 `writing-plans` skill 로 Phase E1 상세 plan 작성 → `subagent-driven-development` 로 실행.
