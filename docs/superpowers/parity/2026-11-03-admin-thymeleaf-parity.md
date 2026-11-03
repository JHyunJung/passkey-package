# admin 화면 Thymeleaf 전환 — 동작 동일성(parity) 점검표

원본(React `admin-ui/src`)의 테스트·화면과 전환 결과(Thymeleaf+htmx+Alpine)를 화면별로 대조한다.
결과 칸: `통과`(대응 JUnit 테스트 이름) / `다름`(사유) / `미확인`(해당 화면 task 에서 채움).

## §공통 틀 (Task 3)

### 표시 로직 (vitest → JUnit 1:1)

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| 상태·AAGUID 모드 라벨 | `i18n/labels.test.ts` | 통과 (`LabelsTest`) |
| 조사(을/를, 이/가) | `lib/describeError.test.ts` josa | 통과 (`ErrorMessagesTest`) |
| 오류 문구 분기(5xx·403·404·429·네트워크·ActionBlocked) | `lib/describeError.test.ts` | 통과 (`ErrorMessagesTest`) — ApiError 는 서버 측 상태를 가진 예외로 대응 |
| 일시 오류 판정 | `lib/transientError.test.ts` | 통과 (`ErrorMessagesTest`) |
| KST 포맷·날짜 그룹·직전 달 범위 | `lib/kst.test.ts` | 통과 (`KstFormatTest`) |
| API 키 만료 임박(30일) | `lib/apiKeyExpiry.test.ts` | 통과 (`ApiKeyExpiryTest`) |
| APK 키 해시 변환 | `lib/apkKeyHash.test.ts` | 통과 (`ApkKeyHashTest`) |
| 플랫폼·단축키 표기 | `lib/platform.test.ts` | 통과 (`PlatformLabelsTest`) |
| 보안 정책 검증(origin·timeout) | `lib/policyValidation.test.ts` | 통과 (`PolicyValidationTest`) |
| 복구 코드 파일명 | `lib/recoveryCodeFilename.test.ts` | 통과 (`RecoveryCodeFilenameTest`) |
| CSV 직렬화(BOM·이스케이프·수식 방지) | (원본에 테스트 없음, `csvExport.ts` 규칙) | 통과 (`CsvWriterTest`) |

### 공통 UI 조각 (렌더 검사)

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| Badge/StatusBadge/Button/IconButton/KpiCard/PropChip/Alert/CardHeader/EmptyState | `ui/basics.test.tsx` | 통과 (`UiFragmentsRenderTest`) |
| Switch/Segmented/ChoiceCard/FilterChip/ChipInput/Field | `ui/forms.test.tsx` | 통과 (마크업·속성; 키 입력 동작은 브라우저 확인 필요) |
| Dialog(role·aria-modal·x-trap·Escape·스크림), ConfirmDialog | `ui/Dialog.test.tsx`, `ui/ConfirmDialog.test.tsx` | 마크업 통과, 포커스 트랩·복귀는 브라우저 확인 필요 |
| Tabs(role=tab·aria-selected·로빙 tabindex·배지) | `ui/Tabs.test.tsx` | 마크업 통과, 방향키 이동은 브라우저 확인 필요 |
| Menu(비활성 항목·위험 구획·트리거) | `ui/Menu.test.tsx`, `ui/misc.test.tsx` | 마크업 통과, 위치 계산·키 이동은 브라우저 확인 필요 |
| DataTable/Pagination | `ui/table.test.tsx` | 통과 (링크 기반으로 전환) |
| OtpInput, ToastHost, SaveBar/unsaved | `ui/OtpInput.test.tsx`, `ui/ToastHost.test.tsx`, `ui/unsaved.test.tsx` | 마크업 통과, 입력·타이머 동작은 브라우저 확인 필요 |
| Skeleton, LoadError, StatusStrip, Stepper, SecretBox | `ui/Skeleton.test.tsx`, `ui/LoadError.test.tsx`, `ui/misc.test.tsx` | 통과 (마크업) |

### 공통 틀: 레이아웃·사이드바·상단 바·브레드크럼·라이선스 배너 (Task 4, 소스 수준 점검)

R8: 라이브 React 비교는 Task 17 전에 한 번에 한다. 이 표의 결과는 TSX·테스트 소스와 렌더 결과(JUnit)를 맞춘 것이고, 브라우저 칸은 모두 "미확인"이다.
JUnit 은 `web/shell/ShellControllerTest`(약칭 SCT), `ShellHelpLinksTest`, `AppJsContractTest`.

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| PLATFORM: 플랫폼/관리 그룹, Tenants·활동·감사 체인·설정, 활성 항목 aria-current, 환경 배지 없음 | `Sidebar.test.tsx` V3 1 | source ✅ / 차이: 항목이 `<button>`+이동 대신 `<a href>`(hx-boost 이동). 온프렘이면 라이선스 항목(+"온프렘" 태그)이 같은 그룹에 추가(Sidebar.tsx groups) | SCT `platformSidebar`, `licenseLinkOnlyOnPrem`, `activeNavFollowsPath` | 미확인 |
| 첫 조회 중 "검증 중…" + "N초째 확인 중"(1초마다) | `Sidebar.test.tsx` V3 1 | source ✅ / 차이: 초 갱신은 Alpine `chainCard`(1초 setInterval, 검증 중일 때만 반영) | SCT `chainCardHost`, `AppJsContractTest.components`(등록 문자열만) | 미확인 |
| RP_ADMIN: 설정만, 체인 카드·체인 조회 없음 | `Sidebar.test.tsx` V3 2 | source ✅ / 차이: 없음 | SCT `rpAdminSidebar` | 미확인 |
| 정상: AUDIT CHAIN OK + "마지막 검증 · 방금 · 1,234 행", 메뉴 배지 없음 | `Sidebar.test.tsx` V3 3 | source ✅ / 차이: 없음(경과 표기는 Sidebar.tsx 자체 timeAgo — "방금"/"N분 전"/"N시간 전") | SCT `chainCardOk`, `chainCardTimeAgo` | 미확인 |
| 위변조: "위변조 의심 · N tenant" 빨간 카드, 감사 체인 항목 "위변조 N" 배지, 카드 누르면 감사 체인 | `Sidebar.test.tsx` V3 4 | source ✅ / 차이: 카드가 `<a>`(카드의 hx-target/swap/sync 는 `hx-disinherit` 로 받지 않아 일반 boost 이동). 배지는 응답의 `data-tampered` 를 `Alpine.store('chain')` 로 옮겨 그림(OOB 는 hx-preserve 와 충돌) | SCT `chainCardTampered` | 미확인 |
| 조회 실패 → "검증 상태 확인 불가" + 다시 시도, 성공하면 정상 | `Sidebar.test.tsx` V3 5 | source ✅ / 차이: 실패 판정은 비 2xx(서버 503)·네트워크·시간 초과의 afterRequest(successful=false) | SCT `chainCardFailure`, `chainCardHost` | 미확인 |
| 폴링 실패 시 마지막 확인 시각(KST HH:mm) | `Sidebar.test.tsx` V3 6 | source ✅ / 차이: 없음(`Intl` Asia/Seoul h23) | `AppJsContractTest.components`(등록 문자열만 확인, 동작 검증 아님) | 미확인 |
| 응답이 폴링 주기보다 늦어도 진행 중 조회를 덮지 않음 | `Sidebar.test.tsx` V3 7 | source ✅ / 차이: 폴링은 `hx-sync="#chain-card:drop"` — 진행 중 조회가 있으면 새 폴링 요청을 버림(htmx 2.0.4: drop 이고 xhr 진행 중이면 요청 취소). 다시 시도만 `replace` | SCT `chainCardHostPreserveDisinheritSync` | 미확인 |
| 다시 시도가 진행 중 폴링을 대체하면 옛 응답 무시 | `Sidebar.test.tsx` V3 8 | source ✅ / 차이: 다시 시도만 `hx-sync="#chain-card:replace"` 로 진행 중 조회를 중단·대체한다(htmx 2.0.4 `replace` = 동기화 요소의 진행 중 xhr abort 확인). 폴링은 drop. 중단된 요청의 실패 이벤트는 새 요청이 곧 시작되면 무시(chainCard 의 inflight) | SCT `chainCardHostPreserveDisinheritSync`(속성) | 미확인 |
| 응답 없는 조회는 120초 뒤 중단, 다음 폴링이 회복 | `Sidebar.timeout.test.tsx` | source ✅ / 차이: `hx-request` timeout 120000 + 30초 폴링 | SCT `chainCardHost` | 미확인 |
| 브레드크럼 마지막은 현재 위치, 검색 버튼 문구·팔레트 호출, PLATFORM 배지 | `Header.test.tsx` V3 1 | source ✅ / 차이: 팔레트는 `open-palette` window 이벤트를 올림(소비는 Task 5) | SCT `breadcrumb`, `headerSearch`, `userMenu` | 미확인 |
| 검색 단축키 표기 OS별(⌘K / Ctrl K) | `Header.test.tsx` V3 2 | source ✅ / 차이: 클라이언트(Alpine `platformKey`)가 navigator.platform 으로 결정, 서버 렌더 기본은 Ctrl K | SCT `headerSearch`, `PlatformLabelsTest` | 미확인 |
| 사용자 메뉴는 로그아웃만(역할 전환 없음), 도움말은 별도 메뉴 | `Header.test.tsx` V3 3 | source ✅ / 차이: 로그아웃이 `POST /admin/logout` 폼(CSRF 포함, 서버 302 → 로그인). 표시 이름은 이메일(me 에 displayName 없음 — TSX 의 `displayName ?? email` 과 결과 같음) | SCT `userMenu`, `rpBadge` | 미확인 |
| 도움말: 주소가 비면 키보드 단축키만, 누르면 시트 | `Header.test.tsx` V3 4 | source ✅ / 차이: 시트는 `open-shortcuts` 이벤트(소비는 Task 5) | SCT `helpMenuDefault` | 미확인 |
| 도움말: 주소가 있으면 문서 항목 새 창·noopener, 운영자 문의 | `Header.test.tsx` V3 5 | source ✅ / 차이: 주소는 빌드 상수 대신 설정 `passkey.admin.help.api-docs/rp-guide/support`(기본 빈 값) | `ShellHelpLinksTest` | 미확인 |
| 도움말: Esc 로 닫으면 포커스가 도움말 버튼으로 | `Header.test.tsx` V3 6 | source ✅ / 차이: 없음(shellMenu `close()` 가 포커스를 먼저 돌림) | `AppJsContractTest.components`(등록 문자열만 확인, 동작 검증 아님) | 미확인 |
| 도움말: 단축키를 고르면 시트 열기 전 포커스가 도움말 버튼 | `Header.test.tsx` V3 7 | source ✅ / 차이: 없음(`close(); $dispatch('open-shortcuts')` 순서) | SCT `helpMenuDefault`(속성) | 미확인 |
| RP 전환 실패 시 오류 토스트, 화면 그대로 | `Header.test.tsx` V3 8 | source ✅ / 차이: 토스트는 서버가 `HX-Trigger` 로 보냄(제목 "RP를 전환하지 못했습니다", 본문 "<제목>. 지금 보고 있는 RP(<이름>)는 그대로입니다."). 허용 RP 2개 미만이면 스위처 없음 | SCT `switchTenantFailureToasts`, `switchTenantDenied`, `switcherFragment`, `switcherFallbacksAndEmpty` | 미확인 |
| RP 전환 성공 후 현재 화면 새로고침 | `Header.tsx` onSwitch(`window.location.reload()`) | source ✅ / 차이: htmx 는 `HX-Refresh: true`, 비-htmx 는 같은 출처 `/admin` Referer 로 302(없으면 `/admin/tenants/{id}`) — 규칙 D | SCT `switchTenantRedirects`, `switchTenantPlainRedirect` | 미확인 |
| 테넌트 경로면 id 로 이름 불러옴(Tenants › 이름 › 탭) | `useCrumbTenant.test.tsx` 1 | source ✅ / 차이: 이름 조회는 Tenant 상세 화면 컨트롤러(Task 7)가 하고, 칸 구성은 `Crumbs.tenant` | SCT `crumbsBuilder` | 미확인 |
| 테넌트 경로가 아니면 조회 안 함·null | `useCrumbTenant.test.tsx` 2 | 해당 없음(서버 렌더 — 화면이 필요할 때만 `Crumbs.tenant` 호출) | - | 미확인 |
| 다른 테넌트로 옮기면 이전 이름 안 씀 | `useCrumbTenant.test.tsx` 3 | 해당 없음(페이지마다 서버가 새로 그림) | - | 미확인 |
| 조회 실패는 Tenants 만 | `useCrumbTenant.test.tsx` 4 | source ✅ / 차이: 없음(PLATFORM: Tenants 만, RP: 빈 목록) | SCT `crumbsBuilder` | 미확인 |
| 브레드크럼 칸·탭 이름(개요·WebAuthn·AAGUID 정책·API 키·Credentials·감사 로그·퍼널), 화면별 라벨 | `App.tsx` buildBreadcrumb | source ✅ / 차이: 없음 | SCT `crumbsBuilder`, `breadcrumb` | 미확인 |
| LicenseBanner WARNING: 남은 일수 + "라이선스 보기" → /license | `LicenseBanner.test.tsx` 1 | source ✅ / 차이: 없음 | SCT `bannerWarning` | 미확인 |
| NETWORK_GRACE: 남은 시간 + "조치 방법 보기" | `LicenseBanner.test.tsx` 2 | source ✅ / 차이: 없음(시간 없으면 "곧") | SCT `bannerNetworkGrace` | 미확인 |
| DEAD: role=alert + 영향 문구 + "갱신 방법 보기" | `LicenseBanner.test.tsx` 3 | source ✅ / 차이: 없음 | SCT `bannerDead` | 미확인 |
| showLink 없으면(RP) 링크 없음 | `LicenseBanner.test.tsx` 4 | source ✅ / 차이: 없음 | SCT `bannerLinkHidden` | 미확인 |
| 이미 /license 면 링크 숨김 | `LicenseBanner.test.tsx` 5 | source ✅ / 차이: 배너가 hx-preserve 로 이동 사이에 유지되므로 서버가 아니라 app.js 가 현재 주소(`data-hide-on-path`)로 숨김(htmx:afterSettle·popstate 에서 동기화) — 브라우저 확인 필요 | SCT `bannerLinkHidden` | 미확인 |
| VALID·SaaS 는 아무것도 안 그림(SaaS 는 WARNING·DEAD 도) | `LicenseBanner.test.tsx` 6, 7 | source ✅ / 차이: 없음 | SCT `bannerHidden` | 미확인 |
| 조회 실패는 아무것도 안 그리고 깨지지 않음 / 이후 실패면 마지막 배너 유지 | `LicenseBanner.test.tsx` 8, 9 | source ✅ / 차이: 서버가 503(본문·토스트 없음)을 주고 htmx 가 스왑하지 않아 마지막 배너가 남는다 | SCT `bannerFailureIsQuiet` | 미확인 |
| 폴링 주기 5분 | `LicenseBanner.tsx` | source ✅ / 차이: `hx-trigger="load, every 300s"` | SCT `licenseHost` | 미확인 |
| 레이아웃: 배너 → .app(사이드바, .content: 상단 바 + main), 토스트 호스트 | `App.tsx` AuthenticatedApp | source ✅ / 차이: 체인 카드·배너·RP 전환 자리는 `hx-preserve` 로 boost 이동에도 같은 노드를 유지해 셸이 한 번만 마운트되는 React 와 맞춤(이동마다 `overview(24)` 재실행 없음; Alpine 노드 이동 복원은 브라우저 확인 필요). IdleSessionModal·CommandPalette·ShortcutSheet 는 Task 5 | SCT `structure`, `headOrder`, `bodyBoostAndCsrf` | 미확인 |
| 서버 오류 토스트가 4xx/5xx 에서도 뜸, 422 만 스왑, 204 는 무시 | (신규 규약, 규칙 A) | source ✅ | `AppJsContractTest.responseHandling`, `.csrfRefresh`, `.singleToastImplementation`(설정·등록 문자열이 소스에 있는지만 지킴 — 동작 검증 아님) | 미확인 |

## §팔레트·단축키·유휴 (Task 5, 소스 수준 점검)

R8: 라이브 React 비교는 Task 17 전에 한 번에 한다. 결과는 TSX·테스트 소스와 JUnit 렌더 결과·Node 스텁(커밋 안 함)을 맞춘 것이고, 브라우저 칸은 모두 "미확인"이다.
JUnit: `web/shell/CommandPaletteControllerTest`(약칭 CPT, 서버가 그리는 결과 조각), `ShellExtrasRenderTest`(SER, 레이아웃에 들어간 마크업), `AppJsContractTest`(AJT — 소스에 문자열이 있는지만 지킴, 동작 검증 아님).
"Node 스텁 X#" 은 `app.js`/`ui.js` 를 가짜 window·document·Alpine·가상 시계로 돌려 확인한 시나리오(보고서에 목록). 스텁은 브라우저·실제 Alpine/htmx 가 아니다. **JS 동작은 모두 "JS — 브라우저 확인 필요"**.

### CommandPalette.test.tsx (16)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| 이동/액션 그룹(t-over)·"G T" 표시·결과 없음 문구 | V2 1 | source ✅ / 차이: 필터가 서버 왕복(입력 150ms 뒤 `GET /admin/_/palette?q=`), 서버가 같은 규칙(라벨·힌트·그룹 부분 일치, 대소문자 무시)으로 거른다 | CPT `platformDefaultList`, `filtersByLabelHintGroup`, `emptyState` | ✅ (2026-11-04 live pass) |
| Enter 로 선택 항목 실행(첫 항목 → Tenants 목록) | V2 2 | source ✅ / 차이: 실행이 `adminNavigate('/admin/tenants')`(boost 이동). 선택·실행은 JS | CPT `itemAttributes`(data-href) · 스텁 D1, D4, D12 / JS — 브라우저 확인 필요 | ✅ (2026-11-04 live pass) |
| 동작하지 않는 액션(Hash chain 즉시 검증·Role 전환 데모) 없음, 로그아웃 있음 | V2 3 | source ✅ / 차이: 없음 | CPT `platformDefaultList` | 미확인 |
| 안내 문구가 실제 기능에 맞음(placeholder, credential·감사 ID 안내) | V3 1 | source ✅ / 차이: 없음 | CPT `emptyState`, SER `paletteMarkup` | 미확인 |
| 열리면 검색칸 포커스, 닫히면 열기 전 위치로 | V3 2 | source ✅ / 차이: 포커스는 Alpine `x-trap`(+`autofocus`)이 맡는다(useFocusTrap 대신). 열 때마다 검색칸·이전 결과를 비운다 | SER `paletteMarkup`(autofocus·x-trap 속성은 마크업만), 스텁 D16 / JS — 브라우저 확인 필요 | ❌ → fixed in 브라우저 확인 결과 반영 커밋(show() 에서 입력칸 focus) |
| Tab 은 팔레트 안에서 순환(검색칸이 유일한 탭 정지점), 항목 tabindex=-1 | V3 3 | source ✅ / 차이: TSX 는 Tab 을 막는다. 여기서는 `x-trap` 이 순환시키는데 탭 정지점이 검색칸 하나라 같은 결과여야 함(실제 확인 필요) | CPT `itemAttributes`(tabindex=-1) / JS — 브라우저 확인 필요 | 미확인 |
| PLATFORM: "신규 tenant 생성"은 다이얼로그 열기 동작을 요청하고 닫는다 | V3 4 | source ✅ / 차이: 요청 = `/admin/tenants?new=1` 이동(App.tsx `navigate('/tenants?new=1')` 와 같은 목적지). 목록 화면이 `?new=1` 을 처리하는 것은 Tenant 목록 task 몫 | CPT `platformDefaultList`, `itemAttributes` · 스텁 D12 | 미확인 |
| RP 관리자: 첫 tenant 가 아니라 활성 tenant, 그룹 "현재 Tenant · [이름]", 개요 → 그 tenant 개요 | V3 5 | source ✅ / 차이: 활성 tenant 를 서버가 `ActiveTenantController.current()` 로 읽는다(클라이언트 입력 불신) | CPT `rpUsesActiveTenant`, `rpScopeIsServerSide` | 미확인 |
| 활성 tenant 조회 실패: 첫 tenant 짐작 안 함, 탭 비활성 + 사유, "현재 tenant 다시 확인"으로 회복(열린 채) | V3 6 | source ✅ / 차이: 다시 확인 = 서버 재조회(결과 목록을 비우고 같은 검색어로 다시 받음) | CPT `rpActiveTenantFailure`, `rpNoActiveTenant` · 스텁 D15 | 미확인 |
| 활성 tenant 확인 중: 탭 비활성 + "현재 tenant 확인 중…", Enter 로도 실행 안 됨 | V3 7 | **차이**: 서버 렌더에는 "확인 중" 상태가 없다. 요청 중에는 이전 결과를 비우고 "불러오는 중…"만 보이므로(항목이 없어 Enter 실행 불가) 안전은 같지만 "현재 tenant 확인 중…" 문구·비활성 탭 표시는 없다 | 스텁 D11, D16 / JS — 브라우저 확인 필요 | 미확인 |
| PLATFORM 은 활성 tenant 를 조회하지 않는다 | V3 8 | source ✅ / 차이: 없음 | CPT `platformDefaultList`(`activeTenants.current()` 호출 없음) | 미확인 |
| 화살표 이동·처음 선택은 비활성 항목을 건너뜀, 비활성(신규 tenant 생성) 권한 없음 표시 | V3 9 | source ✅ / 차이: 선택·이동은 JS(결과 조각이 바뀔 때마다 첫 실행 가능 항목 선택). 입력 즉시 현재 결과를 오래된 것으로 보고(결과의 `data-q` ≠ 입력값) 새 결과가 올 때까지 Enter·클릭·화살표를 막는다 — 150ms 디바운스 중 이전 결과(예: 로그아웃) 실행 방지(스텁 S1–S5) | CPT `rpUsesActiveTenant`(비활성+권한 없음) · 스텁 D1–D3, D6, D10 / JS — 브라우저 확인 필요 | 미확인 |
| "키보드 단축키" 항목(도움말 그룹, 힌트 ?)은 shortcuts 동작을 요청하고 닫는다 | 도움말 1 | source ✅ / 차이: 닫힘 포커스 복귀가 끝난 뒤(setTimeout 0) `open-shortcuts` 이벤트 | CPT `platformDefaultList`, `itemAttributes` · 스텁 D14 | 미확인 |
| 닫고 다시 열 때 이전 tenant 라벨·이동이 남지 않음(조회 실패 시) | round1 1 | source ✅ / 차이: 서버가 열 때마다 새로 읽고 클라이언트는 열 때 이전 결과를 비운다 | CPT `rpFilterAndFreshRead` · 스텁 D16 | 미확인 |
| 콤보박스 ARIA: aria-expanded·aria-controls·aria-activedescendant(선택 항목), 비활성은 aria-disabled | round1 2 | source ✅ / 차이: aria-selected·aria-activedescendant 는 JS 가 칠한다 | SER `paletteMarkup`, CPT `rpActiveTenantFailure`(aria-disabled) · 스텁 D1 | 미확인 |
| 항목 버튼에 포커스가 있을 때 Enter 는 선택 인덱스의 다른 명령을 실행하지 않음 | round1 3 | source ✅ / 차이: 없음 | 스텁 D5 / JS — 브라우저 확인 필요 | 미확인 |

### IdleSessionModal.test.tsx (17)

공통 차이: 활동 이벤트(mousemove·keydown·click·scroll)에 더해 **사용자 동작이 만든 htmx 요청**도 타이머를 리셋한다(브리프; TSX 에는 없음). 판정은 요청의 트리거 이벤트가 실제 입력(`isTrusted`)인지로 한다 — htmx 가 만드는 `load`·`every`(폴링) 트리거는 합성 이벤트라 자동 제외되므로 폴링 요소에 `data-quiet` 을 깜빡해도 안전하다(`data-quiet` 은 이중 안전장치). 로그아웃은 `api.post('/admin/logout')` + 로컬 상태 정리 대신 숨은 폼 `POST /admin/logout`(CSRF 포함) 제출.

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| (idle*60 − 60)초(2분 → 60초) 무활동 뒤 경고 | 1 | source ✅ / 차이: 없음 | SER `idleModalMarkup`(data-minutes), AJT `idleConstants`(상수 문자열) · 스텁 C0, C1 / JS — 브라우저 확인 필요 | 미확인 |
| 본문에 설정된 분(하드코딩 30 아님) | 2 | source ✅ / 차이: `data-minutes` → `x-text="minutes"` | SER `idleModalMarkup` · 스텁 C2 | 미확인 |
| "세션 연장" 클릭이 연장을 호출 | 3 | source ✅ / 차이: 연장 = `fetch GET /admin/api/me`(credentials same-origin) | AJT `idleConstants` · 스텁 C8, C9 / JS — 브라우저 확인 필요 | 미확인 |
| 1분 정책: 경고 10초 + 카운트다운 50초 = 총 60초에 로그아웃 | 4 | source ✅ / 차이: 없음 | 스텁 C3 | 미확인 |
| 만료 시 onLogout 정확히 1번 | 5 | source ✅ / 차이: 없음 | 스텁 C3d, C3e | 미확인 |
| 기본 포커스 "세션 연장", 저장 안 한 변경 손실 경고 문구, 내부 용어 없음 | 6 | source ✅ / 차이: 포커스는 `autofocus` + `x-trap` | SER `idleModalMarkup`(문구·autofocus·내부 용어 없음) / 포커스는 JS — 브라우저 확인 필요 | 미확인 |
| 진행 막대(progressbar) 가 남은 초를 반영(aria-valuemax/valuenow, "N초") | 7 | source ✅ / 차이: 값은 Alpine 바인딩 | SER `idleModalMarkup`(role=progressbar·바인딩 속성) · 스텁 C5, C5g | 미확인 |
| 스크린리더 안내는 30초·10초에만 | 8 | source ✅ / 차이: 없음 | AJT `idleConstants`(문구) · 스텁 C5 | 미확인 |
| "로그아웃" 버튼은 즉시 로그아웃 | 9 | source ✅ / 차이: 폼 제출 | 스텁 C7 | 미확인 |
| 로그아웃 뒤 다시 무장하지 않음·onLogout 한 번 | 10 | source ✅ / 차이: 없음(폼 제출 뒤 페이지가 이동) | 스텁 C7b | 미확인 |
| 만료 로그아웃 뒤에도 다시 무장하지 않음 | 11 | source ✅ / 차이: 없음 | 스텁 C3e | 미확인 |
| 연장 실패는 모달 안 오류, 열린 채 유지, 연타해도 한 번, 포커스가 연장 버튼으로 | 12 | source ✅ / 차이: 오류 문구는 `describeError` 규칙을 JS 에 옮긴 것(HTTP 상태 분기 + 네트워크). 로그인 화면으로의 리다이렉트 응답도 실패 | 스텁 C9, C9f, C9g / JS — 브라우저 확인 필요 | 미확인 |
| 연장 성공하면 모달이 닫힌다 | 13 | source ✅ / 차이: 닫힌 뒤 타이머를 다시 무장 | 스텁 C8 | 미확인 |
| 만료 후 3초에 연장 성공 → 로그아웃 안 함, 모달만 닫음 | 14 | source ✅ / 차이: 없음 | 스텁 C10 | 미확인 |
| 만료 후 3초에 연장 실패 → 로그아웃 | 15 | source ✅ / 차이: 없음 | 스텁 C10g | 미확인 |
| 유예 10초 안에 끝나지 않으면 만료+10초에 로그아웃(이후 완료는 무시) | 16 | source ✅ / 차이: 없음 | 스텁 C10d–f | 미확인 |
| 유예 중 수동 로그아웃 → 한 번만 | 17 | source ✅ / 차이: 없음 | 스텁 C10h, C10i | 미확인 |

### ShortcutSheet.test.tsx (6)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| `?` 로 열리고 닫기 버튼으로 닫힘(제목·행·안내 문구, 기본 포커스는 닫기) | 1 | source ✅ / 차이: 시트 = Task 3 `dialog` 조각, 키는 app.js 가 `open-shortcuts` 이벤트로 | SER `shortcutSheetPlatform`(행·문구·구조) · 스텁 A3 / 포커스·열기·닫기는 JS — 브라우저 확인 필요 | 미확인 |
| 입력란에서 `?` 는 열리지 않음 | 2 | source ✅ / 차이: 없음 | 스텁 A4 | 미확인 |
| alertdialog(비밀값·위험 확인)가 열려 있으면 열리지 않음 | 3 | source ✅ / 차이: 없음(`[aria-modal="true"]` 전체) | 스텁 A5 | 미확인 |
| Ctrl·⌘·Alt 와 함께면 무시 | 4 | source ✅ / 차이: 없음 | 스텁 A4 | 미확인 |
| RP 관리자에게는 G 이동 단축키를 보여 주지 않음 | 5 | source ✅ / 차이: `shortcutRows()` 함수 대신 템플릿 분기(`me.platform`) | SER `shortcutSheetRp` | 미확인 |
| OS 에 맞는 팔레트 단축키를 먼저, 다른 OS 는 보조 표기 | 6 | source ✅ / 차이: `platformKey` 로 클라이언트에서 정함(서버 렌더 = Ctrl K / "Mac · ⌘K") | SER `shortcutSheetPlatform`(마크업) / JS — 브라우저 확인 필요 | 미확인 |

### useGoShortcuts.test.tsx (6)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| g → t 는 tenants 로 | 1 | source ✅ / 차이: 이동 = boost 이동 `/admin/tenants`. 키 매핑 t/a/c/s → tenants/activity/audit-chain/settings | AJT `keyMap`(문자열) · 스텁 A6 | 미확인 |
| 1초가 지나면 체인이 끊긴다 | 2 | source ✅ / 차이: 없음 | AJT `keyMap`, 스텁 A7 | 미확인 |
| 입력란(input/textarea) 에서는 무시 | 3 | source ✅ / 차이: 없음 | 스텁 A8, A8b | 미확인 |
| contenteditable 안에서는 무시 | 4 | source ✅ / 차이: 없음 | 스텁 A8c(g), A5b(`?`) | 미확인 |
| 모달이 열려 있으면 무시 | 5 | source ✅ / 차이: 없음 | 스텁 A9 | 미확인 |
| 수정키 동반·disabled(RP) 면 무시 | 6 | source ✅ / 차이: "enabled" = 시트 조각의 `data-go-shortcuts`(PLATFORM 일 때만 렌더) | SER `shortcutSheetPlatform`, `shortcutSheetRp` · 스텁 A10, A11 | 미확인 |

### usePaletteShortcut.test.tsx (2)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| ⌘K / Ctrl+K 로 팔레트 토글 | 1 | source ✅ / 차이: 토글 = `palette-toggle` 이벤트 → `commandPalette.toggle()` | AJT `keyMap` · 스텁 A1, D16c | 미확인 |
| aria-modal alertdialog 가 열려 있으면 열지 않음 | 2 | source ✅ / 차이: 없음(TSX 처럼 preventDefault 는 먼저 한다) | 스텁 A2 | 미확인 |

### unsaved.test.tsx (6) — R10: 저장 안 한 변경의 단일 출처는 `Alpine.store('dirty')`

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| dirty 가 아니면 바로 이동 | useUnsavedGuard 1 | source ✅ / 차이: 없음 | 스텁 B2a, B11 | 미확인 |
| 검색 파라미터만 바뀌는 이동도 막고 "머무르기"(기본 포커스) | useUnsavedGuard 2 | source ✅ / 차이: 막는 길 둘 — 링크 클릭 capture(`unsavedGuard.onClick`)와 hx-boost GET 의 `htmx:beforeRequest` 취소(`unsaved-ask`). 같은 경로+쿼리면 건너뜀. 뒤로/앞으로 가기(popstate)는 이력마다 찍은 번호(`history.state.idx` — htmx 2.0.4 의 `saveCurrentPageToHistory` 가 `replaceState({htmx:true})` 로 지우므로 `history.pushState/replaceState` 를 감싸 항상 병합·부여)로 이동 폭을 계산해 capture 단계에서 htmx 복원을 막고 `history.go(−폭)` 으로 원위치(pushState 없음 → 스택·앞으로 가기 기록 유지)시킨 뒤 같은 확인창을 띄운다. "머무르기"는 그대로, "이동"은 일회성 통과 허가 + `history.go(폭)` 으로 보류했던 POP 를 진행(TSX useBlocker `proceed` 와 같다; 스텁 H0–H11, R0–R4(새로고침), T1–T2(go 미도착 1초 정리)). 차이: 번호가 없는 이력 항목(번호를 찍기 전의 항목)은 pushState 로 직전 주소를 되살려 막고(앞으로 가기 기록이 잘릴 수 있음) "이동"은 새 boost 이동 | UiFragmentsRenderTest `unsavedGuard`(alertdialog·autofocus), AJT `guardListenersReferenceDirtyStore` · 스텁 B12, B15b, B2b, B2c / JS — 브라우저 확인 필요 | 미확인 |
| "이동" 을 누르면 진행 | useUnsavedGuard 3 | source ✅ / 차이: 일회성 통과 허가(다음 boost 요청/beforeunload 한 번이 소비)를 주고 `adminNavigate`(매번 새 링크를 만들어 `htmx.process` 후 클릭 — htmx 2.0.4 는 처리 시점에 href 를 읽어 두므로)로 같은 주소를 다시 요청 | 스텁 B2e–B2h / JS — 브라우저 확인 필요 | 미확인 |
| dirty 면 beforeunload 를 막는다 | useUnsavedGuard 4 | source ✅ / 차이: 리스너가 `app.js` 에 하나(saveBar 의 것을 없앴다). 저장 중이거나 통과 허가·해제 상태면 건너뜀. 브라우저 기본 확인창 | AJT `guardListenersReferenceDirtyStore`(리스너 하나·스토어 참조 문자열) · 스텁 B5–B9 | 미확인 |
| SaveBar: count 0 이면 렌더하지 않음 | SaveBar 1 | source ✅ / 차이: `x-show="dirty"` 로 숨김(DOM 은 남음) | UiFragmentsRenderTest `saveBar`(Task 3) | 미확인 |
| SaveBar: 건수와 버튼(저장…·되돌리기) | SaveBar 2 | source ✅ / 차이: 건수를 `dirty` 스토어에 보고(`report`) | UiFragmentsRenderTest `saveBar`, AJT `saveBarAndGuardUseStore`(문자열) / JS — 브라우저 확인 필요 | 미확인 |

## §로그인·MFA (Task 6, 소스 수준 점검)

R8: 결과는 TSX·테스트 소스와 JUnit 렌더 결과·Node 스텁(커밋 안 함)을 맞춘 것이고 브라우저 칸은 모두 "미확인"이다.
JUnit: `web/auth/LoginPageControllerTest`(LPC), `web/HomeRedirectControllerTest`(HRC). Node 스텁 = `auth.js`·`ui.js` 를 가짜 window·Alpine 으로 돌린 시나리오(보고서). **JS 동작은 모두 "JS — 브라우저 확인 필요"**.
공통 차이: 로그인 제출은 fetch 가 아니라 `hx-post /admin/login`(보안 체인의 폼 로그인)이고, 성공·실패 모두 `HX-Redirect` 로 전체 페이지를 다시 받는다(실패 = `/admin/login?error`). 화면 이동은 `/me` 재조회 대신 서버가 정한다.

### LoginPage.test.tsx (15)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| 브랜드 패널: 워드마크·헤드라인·장식 일러스트, 가짜 지표 대신 콘솔이 하는 일 | V3 1 | source ✅ / 차이: 워드마크 경로가 `/admin/img/crosscert-logo-transparent.png`(React public 자산 → 정적 자산 폴더) | LPC `anonymousGetsLoginPage` | 미확인 |
| 이메일 칸에 자동 포커스 | V3 2 | source ✅ / 차이: `autofocus` 속성(서버 렌더) | LPC `anonymousGetsLoginPage` | 미확인 |
| 재설정 링크·가입 요청 링크·자동 로그아웃 안내, 로그인 버튼 btn--primary btn--lg | V3 3 | source ✅ / 차이: 없음 | LPC `anonymousGetsLoginPage` | 미확인 |
| 로그인 실패: 폼 맨 위 경고 하나(토스트 없음), 이메일 유지·비밀번호만 비우고 포커스 | V3 4 | source ✅ / 차이: 실패 핸들러가 `/admin/login?error` 로 보내 페이지를 다시 그린다. 경고·비밀번호 칸 `autofocus`·빈 비밀번호는 서버가, 이메일 유지는 `auth.js` 가 제출 직전 sessionStorage 에 둔 이메일을 한 번 되살린다(비밀번호는 저장 안 함, 로그인 성공 이동이면 바로 지움) | LPC `errorParamShowsLoginError` · 스텁(이메일 복원·키 삭제) / JS — 브라우저 확인 필요 | 미확인 |
| 입력을 바꾸면 경고가 사라진다 | V3 5 | source ✅ / 차이: Alpine `x-show="error === 'credentials'"` + 입력 `clearError()` | LPC `errorParamShowsLoginError`(속성) · 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 5xx 는 일시 오류 + 다시 시도(입력 유지, HTTP 상태 표시, 같은 값으로 재요청) | V3 6 | source ✅ / 차이: `htmx:afterRequest` 의 xhr 상태로 판단, 다시 시도 = 폼 `requestSubmit()` | 스텁(503 → transient·status·retry) / JS — 브라우저 확인 필요 | 미확인 |
| 네트워크 오류도 일시 오류 | V3 7 | source ✅ / 차이: `htmx:sendError`·`htmx:timeout` → 일시 오류(HTTP 줄 없음). 셸의 네트워크 토스트는 이 화면에 없다(app.js 미적재) | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 서버 예외(ApiError 503)도 일시 오류 | V3 8 | source ✅ / 차이: 서버 예외는 5xx 응답과 같은 경로(V3 6) | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 로그인 뒤 /me 가 403 이면 쉬운 문구 | V3 9 | 차이: 해당 없음 — 로그인 뒤 `/me` 를 따로 부르지 않는다(성공하면 서버가 `/admin`·MFA 단계로 보낸다). 리다이렉트 없이 온 4xx(예: CSRF 403)는 TSX 의 `!res.ok && <500` 과 같이 자격 오류로 보고 `?error` 로 다시 그린다 | 스텁(403 → `/admin/login?error`) / JS — 브라우저 확인 필요 | 미확인 |
| 성공하면 onLogin(getMe) | V3 10 | source ✅ / 차이: 성공 핸들러의 `HX-Redirect: /admin`(MFA 대기면 `/admin/login` → MFA 단계). 핸들러 분기는 Task 2 | LoginHandlerBranchTest(Task 2), LPC `authenticatedWithoutMfaRedirectsHome`, `mfaPendingGetsMfaPage` | 미확인 |
| 요청 중 입력 읽기 전용, 응답 뒤 다시 편집 | V3 11 | source ✅ / 차이: `x-bind:readonly="submitting"`(`htmx:beforeRequest`~`afterRequest`). 리다이렉트 응답이면 이동 중이라 그대로 둔다. 중복 제출은 `hx-sync="this:drop"`+가드 | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 비밀번호 표시 토글(aria-pressed, 라벨 표시/숨기기) | V3 12 | source ✅ / 차이: Alpine `showPw` | LPC `anonymousGetsLoginPage`(초기 속성) / JS — 브라우저 확인 필요 | 미확인 |
| 문의 주소가 있을 때만 "도움말 · 운영자에게 문의" | V3 13 | source ✅ / 차이: 주소는 `passkey.admin.help.support`(ShellAdvice `helpLinks`, Task 4) | LPC `anonymousGetsLoginPage`(빈 값 → 없음), ShellHelpLinksTest(Task 4, 값 있음) | 미확인 |
| dev/local 이 아니면 DEV 데모 카드 없음 | V3 14 | source ✅ / 차이: 카드와 데모 계정 값이 서버 렌더에서 아예 빠진다(TSX 는 번들에 항상 있음) | LPC `anonymousGetsLoginPage`, `prodAndQaProfilesHaveNoDemoAccount` | 미확인 |
| dev 프로필이면 DEV 데모 카드, 클릭 시 alice 계정 채움 | V3 15 | source ✅ / 차이: 노출 조건은 TSX 와 같음(`active` 에 dev·local 또는 `local=true`, `LoginPageController.showDemoAccount`). 로드 시 미리 채우지 않고 카드 클릭 때 채운다(aria-pressed) | LPC `devProfileShowsDemoCardFilledOnClick`, `localProfileShowsDemoCardFilledOnClick` · 스텁(selectDemo) / JS — 브라우저 확인 필요 | 미확인 |

### MfaChallenge.test.tsx (17)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| 공통 카드 셸: 워드마크·장식 일러스트·OTP 6칸·카드 밖 "← 로그인으로" | V3 1 | source ✅ / 차이: "← 로그인으로" = `POST /admin/logout`(CSRF) 폼 제출(로그아웃 성공 → `/admin/login`) | LPC `mfaPendingGetsMfaPage` | 미확인 |
| 기기 분실 안내 | V3 2 | source ✅ / 차이: 없음 | LPC `mfaPendingGetsMfaPage` | 미확인 |
| TOTP 칸은 숫자만, 붙여넣기는 숫자만 6자리 | V3 3 | source ✅ / 차이: 공통 `otpInput` 조각(Task 3) | UiFragmentsRenderTest(Task 3) / JS — 브라우저 확인 필요 | 미확인 |
| 6자리 입력 시 verify 정확히 한 번(자동 제출, 수동 확인 겹쳐도) | V3 4 | source ✅ / 차이: `otpInput(submit=true)` 의 `requestSubmit()` + `hx-sync="this:drop"` + `onBefore` 가드(진행 중·형식 불일치면 취소) | LPC `mfaSuccessRedirectsHome` · 스텁(이중 제출 차단) / JS — 브라우저 확인 필요 | 미확인 |
| 수동 확인 버튼은 6자리 전 비활성, 실패하면 칸 비움·카드 안 경고(토스트 없음)·시계 안내 | V3 5 | source ✅ / 차이: 401 → 서버가 `auth/mfa :: error` 조각 + 422 를 `#mfa-error` 에 스왑. 칸 비움·시계 안내는 Alpine | LPC `mfaWrongCodeShowsError`(조각·422·HX-Trigger 없음), `mfaPendingGetsMfaPage`(시계 안내 문구) · 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 검증 실패 후 첫 칸에 다시 포커스 | V3 6 | source ✅ / 차이: 칸 재활성 뒤 `otpInput.clear()`(첫 칸 포커스) | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 서버·네트워크 오류는 "확인하지 못했습니다" | V3 7 | source ✅ / 차이: verify 예외 → 본문·토스트 없는 500, 화면이 "확인하지 못했습니다 / 서버가 잠시 응답하지 않습니다. 잠시 후 다시 시도하세요."(네트워크 = "네트워크에 연결할 수 없습니다…") | LPC `mfaUnexpectedErrorIs500WithoutToast` · 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 복구 코드가 틀리면 경고 + 칸 비우고 포커스 | V3 8 | source ✅ / 차이: 제출 값 `mode=recovery` 로 서버가 복구 코드 문구를 고른다 | LPC `mfaWrongCodeShowsError` · 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 모드를 바꾸면 경고가 사라진다 | V3 9 | source ✅ / 차이: `switchMode` 가 `#mfa-error` 를 비운다 | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 복구 코드 모드는 같은 카드 안에서 전환, 대문자·대시 정형 | V3 10 | source ✅ / 차이: Alpine `x-if` 로 같은 form 안에서 전환(지금 모드의 `code` 입력 하나만 제출). 정형은 `formatRecoveryCode` 이식 | LPC `mfaPendingGetsMfaPage`(같은 카드 안 두 템플릿) · 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| TOTP 로 돌아가면 입력이 비워진다 | V3 11 | source ✅ / 차이: x-if 가 OTP 칸을 새로 만든다 | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 복구 코드 부분 입력이면 확인 비활성 | V3 12 | source ✅ / 차이: 없음(`RECOVERY_RE`) | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 복구 코드 완성되면 확인 활성, 그 코드로 verify | V3 13 | source ✅ / 차이: 서버가 `code.trim()` 으로 `MfaController.verify` 호출 | LPC `mfaRecoveryCodeShowsRemainingToast`(인자 `AB3F-2K7M`) · 스텁 | 미확인 |
| 검증 중 입력 읽기 전용(늦은 실패가 새 입력을 못 지움) | V3 14 | source ✅ / 차이: `x-bind:readonly="submitting"` | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| TOTP 검증 중 칸 비활성 | V3 15 | source ✅ / 차이: 칸만 `disabled`(hidden `code` 는 그대로 — 값이 이미 모인 뒤) | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 검증 중 모드 전환 버튼 비활성, 늦은 실패는 복구 칸으로 포커스 | V3 16 | source ✅ / 차이: 없음 | 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| TOTP 검증 중 "복구 코드로 로그인" 비활성 | V3 17 | source ✅ / 차이: 없음 | 스텁 / JS — 브라우저 확인 필요 | 미확인 |

추가(원본 테스트 없음, 브리프·서버 규칙):
| 체크 항목 | 근거 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| 복구 코드로 확인하면 "복구 코드를 사용했습니다." / "남은 복구 코드: N개" 경고 토스트 | MfaChallenge.tsx verify | source ✅ / 차이: `HX-Trigger`(warn) + `HX-Redirect`. 같은 응답에 HX-Redirect 가 있을 때만 `auth.js`(`htmx:beforeOnLoad`)가 토스트를 sessionStorage 로 넘기고 다음 화면 `toastHost.init()` 이 띄운다 | LPC `mfaRecoveryCodeShowsRemainingToast` · 스텁(ui.js toastHost) / JS — 브라우저 확인 필요 | 미확인 |
| 실패로 잠금이 걸려 세션이 끝나면 로그인 화면으로 | 브리프 | 차이: TSX 는 같은 경고를 보이고 다음 요청이 401 로 끝난다. 여기서는 바로 `/admin/login` | LPC `mfaLockoutRedirectsToLogin`, `mfaLockoutWithoutHtmxIs302` | 미확인 |
| MFA 대기가 아니면 verify 를 부르지 않고 `/admin`, CSRF 없으면 403, 미인증은 로그인으로 | 서버 규칙 | source ✅ | LPC `mfaSubmitWhenNotPendingSkipsVerify`, `mfaSubmitRequiresCsrf`, `mfaSubmitAnonymousGoesToLogin` | 미확인 |
| 인증 코드는 로그에 남기지 않는다 | 보안 | source ✅ | LPC `mfaWrongCodeShowsError`, `mfaUnexpectedErrorIs500WithoutToast`(출력 캡처) | — |

### App.tsx `*` 경로 · roles.test.ts (4) · RequirePlatform.test.tsx (3)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| `*` → PLATFORM `/tenants`, RP_ADMIN `/tenants/{tenantIds[0]}` (홈·옛 주소 `/admin`, `/admin/`, `/admin/api-keys`, `/admin/audit`, `/admin/mds`, `/admin/keys`) | App.tsx | source ✅ / 차이: 서버 302 | HRC `platformHomeGoesToTenants`, `rpAdminHomeGoesToOwnTenant`, `legacyUrlsRedirect`, `anonymousGoesToLogin` | 미확인 |
| isPlatform / isRpAdmin | roles 1·2 | source ✅ / 차이: `CurrentAdmin.platform`(Task 2) | HRC `homePathMatchesRolesTs` | — |
| rpTenantId: RP 는 자기 tenant, PLATFORM 은 null | roles 3 | source ✅ | HRC `homePathMatchesRolesTs` | — |
| rpTenantId: RP 인데 tenantIds 비면 null → `/tenants`(500 아님) | roles 4 | source ✅ | HRC `rpAdminWithoutTenantGoesToTenantsListNot500` | — |
| RequirePlatform: PLATFORM 은 내용, RP 는 자기 tenant 로, tenant 없는 RP 는 "계정 구성 오류" | RequirePlatform 1–3 | 미확인 — PLATFORM 전용 화면(Tenant 목록·Activity·Audit chain·License) task 몫. 홈 리다이렉트는 tenant 없는 RP 를 `/admin/tenants` 로 보내므로 그 화면이 "계정 구성 오류"를 그려야 한다 | — | 미확인 |

## §비밀번호 찾기·재설정 (Task 7, 소스 수준 점검)

판정: "source ✅" = 템플릿·컨트롤러 소스를 TSX 와 대조. 브라우저: 미확인(전 행).

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| Forgot 공통 셸(워드마크·카드·카드 밖 로그인 링크, 좁은 열) | ForgotPasswordPage V2 1 | source ✅ / 차이: 로고 경로 `/admin/img/…`. JUnit `forgotPageUsesAuthShell` / 브라우저: 미확인 |
| 빈 이메일이면 제출 불가 | V2 2 | source ✅ / 차이: 버튼 비활성은 Alpine `canSubmit()` — JS, 브라우저 확인 필요. 서버는 빈 값이면 422 + "이메일을 입력하세요."(TSX 에 없는 안전망, JUnit `forgotBlankEmailIsInlineErrorAndNotSent`) / 브라우저: 미확인 |
| 제출 후 같은 카드에서 완료 상태 | V2 3 | source ✅ / 차이: htmx 가 `#auth-card` 를 조각으로 교체. JUnit `forgotAlwaysSameMessage`, `forgotHtmxReturnsFragmentOnlyAndPlainPostReturnsFullPage` / 브라우저: 미확인 |
| 요청이 실패해도 완료 카드 내용 같음 | V2 4 | source ✅ / 차이: 서버가 4xx 성격 예외를 던져도 같은 응답(본문 바이트 동일 확인). JUnit `forgotAlwaysSameMessage` / 브라우저: 미확인 |
| 4xx 도 성공과 같은 안내 | V2 5 | source ✅ / JUnit `forgotAlwaysSameMessage` / 브라우저: 미확인 |
| 5xx 면 보내지 못했다고 알리고 입력 유지, "다시 시도" 포커스, 재시도 성공 시 안내 | V2 6 | source ✅ / 차이: 서버는 본문 없는 500, 경고·"다시 시도"·포커스는 auth.js `submitState`(Node 스텁으로 상태 전이 확인). JUnit `forgotServerFailureIsNotShownAsSent` / JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| 네트워크 오류도 보내지 못했다고 알림 | V2 7 | source ✅ / JS(`onNetworkError`) — 브라우저 확인 필요 / 브라우저: 미확인 |
| 요청 중 이메일 읽기 전용·중복 제출 차단·늦은 실패에도 입력 유지 | V2 8 | source ✅ / 차이: `x-bind:readonly`, `hx-sync="this:drop"`, `onBefore` 가드. JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| Reset 토큰 없음 → 무효 링크(경고·다시 받기·로그인으로) | ResetPasswordPage V3 1 | source ✅ / JUnit `resetWithoutTokenShowsInvalid` / 브라우저: 미확인 |
| 입력 상태에도 카드 밖 "← 로그인으로" | V3 2 | source ✅ / JUnit `resetFormCarriesTokenOnlyAsHiddenFieldAndIsNotCacheable` / 브라우저: 미확인 |
| 12자 미만 제출 불가, 12자 이상이면 미터 충족 | V3 3 | source ✅ / 차이: 미터·버튼은 Alpine(`resetForm`, Node 스텁 확인), 서버 안전망 422. JUnit `resetShortOrMismatchedPasswordIsInlineErrorAndNotSent` / JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| 불일치면 오류 + 제출 불가 | V3 4 | source ✅ / 같은 JUnit + Alpine `mismatch()` / JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| 성공하면 같은 카드에서 변경 완료, 로그인 링크 | V3 5 | source ✅ / JUnit `resetSuccessShowsDoneCardAndCallsRestBean` / 브라우저: 미확인 |
| 토큰 관련 400 → 만료 카드, 폼 사라짐 | V3 6 | source ✅ / 차이: 서버 상태는 422(htmx 스왑용). JUnit `resetTokenRejectionShowsExpiredCard` / 브라우저: 미확인 |
| 토큰과 무관한 400 → 서버 메시지 배너, 폼 유지 | V3 7 | source ✅ / JUnit `resetNonTokenRejectionShowsServerMessageAndKeepsForm` / 브라우저: 미확인 |
| 5xx → "비밀번호를 바꾸지 못했습니다" + 다시 시도, 입력 유지 | V3 8 | source ✅ / 서버 500 JUnit `resetServerFailureIsTransientAndDoesNotLogToken`, 경고·재시도 버튼은 JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| 요청 중 읽기 전용·제출 한 번 | V3 9 | source ✅ / `hx-sync`, `onBefore` — JS, 브라우저 확인 필요 / 브라우저: 미확인 |
| (추가) 토큰·비밀번호 비노출 | — | 토큰은 hidden 필드로만, 응답에 되풀이 안 함(done·expired), 로그는 예외 클래스만, no-store·no-referrer. JUnit `resetFormCarries…`, `resetSuccess…`, `resetServerFailure…` / 브라우저: 미확인 |
| (추가) CSRF | — | 두 POST 모두 `_csrf` 필요(없으면 403). `/admin/api/**` 면제 범위 불변. JUnit `postsRequireCsrf` / 브라우저: 미확인 |
| (추가) 422 스왑 시 비밀번호 칸 | — | 차이: 서버가 그린 422 카드는 비밀번호 칸을 비운다(TSX 는 입력 유지; 비밀번호를 응답에 되돌려 쓰지 않기 위해). 이메일·사유는 유지 / 브라우저: 미확인 |
| (추가) 예상 밖 4xx(CSRF 403 등) | — | auth.js `rejected` 배너 "요청이 거부되었습니다" + 새로 고침(Node 스텁). 폼 `method=post action` 으로 htmx 없이도 본문 POST. JUnit `formsPostNativelyWithoutHtmx…` / JS — 브라우저 확인 필요 |

## §가입 요청 (Task 7, 소스 수준 점검)

판정: "source ✅" = 템플릿·컨트롤러 소스를 TSX 와 대조. 브라우저: 미확인(전 행).

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| 공통 셸(워드마크·카드·카드 밖 로그인 링크, 넓은 열) | SignupRequestPage V2 1 | source ✅ / JUnit `signupPageUsesWideAuthShell` / 브라우저: 미확인 |
| 비밀번호가 다르면 제출 불가 | V2 2 | source ✅ / Alpine `canSubmit()`(Node 스텁 확인) + 서버 422 `signupValidationErrorsInline` / JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| 12자 미만 제출 불가 | V2 3 | source ✅ / 같음 / 브라우저: 미확인 |
| 이메일 형식 오류면 제출 불가 | V2 4 | source ✅ / 서버도 TSX EMAIL_RE 와 같은 규칙(점 없는 주소 거부). JUnit `emailWithoutDotIsRejectedLikeTheClientRegex` / 브라우저: 미확인 |
| 제출 후 같은 카드에서 접수 완료(일러스트·제목·돌아가기) | V2 5 | source ✅ / JUnit `pendingCardOnAccepted` / 브라우저: 미확인 |
| 요청이 실패해도 같은 접수 안내 | V2 6 | source ✅ / JUnit `clientErrorFromServiceStillShowsAcceptedNotice` / 브라우저: 미확인 |
| 사유는 trim 해서 보냄 | V2 7 | source ✅ / 차이: 이메일 trim·소문자도 서버에서. JUnit `pendingCardOnAccepted`, `blankReasonIsSentAsNull` / 브라우저: 미확인 |
| 비밀번호 최대 128자, "12–128자" 범위 표시 | V2 8 | source ✅ / `maxlength=128` + 서버 거부. JUnit `signupPageUsesWideAuthShell`, `passwordOver128IsRejected` / 브라우저: 미확인 |
| 이메일 형식 오류는 칸을 벗어날 때 인라인 | V2 9 | source ✅ / Alpine `emailTouched`(blur) — JS, 브라우저 확인 필요 / 브라우저: 미확인 |
| 4xx 는 성공과 같은 접수 안내 | V2 10 | source ✅ / 차이: 브리프대로 입력 검증 오류는 422 + 필드 옆 오류(TSX 는 클라이언트가 먼저 막아 서버 4xx 는 계정 존재 응답뿐). 서비스가 던지는 4xx 성격 예외는 접수 안내. JUnit `clientErrorFromService…`, `signupValidationErrorsInline` / 브라우저: 미확인 |
| 5xx 면 보내지 못했다고 알리고 입력 유지, "다시 시도" 포커스 | V2 11 | source ✅ / 서버 500 JUnit `serverFailureIsNotShownAsAccepted`, 경고·포커스는 JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| 네트워크 오류도 보내지 못했다고 알림 | V2 12 | source ✅ / JS — 브라우저 확인 필요 / 브라우저: 미확인 |
| 요청 중 읽기 전용·중복 제출 차단 | V2 13 | source ✅ / `hx-sync`, `onBefore` — JS, 브라우저 확인 필요 / 브라우저: 미확인 |
| 즉시 가입이 꺼져 있으면 역할 선택지 없음 | V2 14 | source ✅ / JUnit `roleChoicesOnlyWhenSelfSignupEnabled`, `platformOperatorRoleIgnoredWhenSelfSignupIsOff` / 브라우저: 미확인 |
| 즉시 가입 켜짐: Platform Operator 선택 → 보냄, activated 면 바로 로그인 안내 | V2 15 | source ✅ / 차이: 선택지 노출은 서버가 `ProfileController.profile()` 로 판정(GET 때 한 번, TSX 는 마운트 뒤 fetch). JUnit `activatedCardWhenServerActivatedAccount` / 브라우저: 미확인 |
| 즉시 가입 켜짐 + 기본 선택(승인 후 사용) → 역할 없이 보냄, 승인 대기 안내 | V2 16 | source ✅ / JUnit `defaultRoleChoiceSendsNoRoleAndShowsPending` / 브라우저: 미확인 |
| (추가) CSRF·REST 호출 | — | POST `_csrf` 필요, 호출 인자는 `SignupRequestDto.Create`. JUnit `postsRequireCsrf`, `pendingCardOnAccepted` / 브라우저: 미확인 |
| (추가) 422 스왑 시 비밀번호 칸 | — | 차이: 서버가 그린 422 카드는 비밀번호 칸을 비운다(TSX 는 입력 유지; 비밀번호를 응답에 되돌려 쓰지 않기 위해). 이메일·사유는 유지 / 브라우저: 미확인 |
| (추가) 예상 밖 4xx(CSRF 403 등) | — | auth.js `rejected` 배너 "요청이 거부되었습니다" + 새로 고침(Node 스텁). 폼 `method=post action` 으로 htmx 없이도 본문 POST. JUnit `formsPostNativelyWithoutHtmx…` / JS — 브라우저 확인 필요 |

## §Tenant 목록 (Task 8, 소스 수준 점검)

R8: 결과는 TSX·테스트 소스와 JUnit 렌더 결과(`web/tenant/TenantsPageControllerTest` = TPC)를 맞춘 것이고 브라우저 칸은 모두 "미확인"이다. Alpine·htmx 의 실제 교체·포커스·history 는 JUnit 이 속성까지만 본다 → **"JS — 브라우저 확인 필요"**.
공통 차이: 원본은 목록 전체를 한 번 받아 클라이언트에서 검색·필터·페이징했다. 이식은 `GET /admin/_/tenants/list`(조각)가 같은 규칙을 서버에서 적용하고, 응답의 `HX-Push-Url` 로 주소창을 `/admin/tenants?q=&status=&page=&size=` 로 바꾼다. 정렬은 원본에도 없어 `TenantAdminController.list()` 순서를 그대로 둔다(TPC `keepsServiceOrder`). 새 코드: 필터 규칙 `TenantsPageController.filter`(원본 `filtered` 와 같은 식: name 소문자 부분 일치 · `slug.includes(q.toLowerCase())` · q trim 안 함).

### TenantsListPage.test.tsx (17)

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 활성 Tenant KPI 클릭 = ACTIVE 필터 토글(aria-pressed, 칩 "상태: 활성", 세그먼트 "활성 2" 눌림) | V2 1 | source ✅ / 차이: 토글은 클라이언트 상태가 아니라 `hx-get` 주소(켜져 있으면 ALL, 꺼져 있으면 ACTIVE)로, 응답이 KPI·세그먼트·칩을 OOB·본문으로 다시 그린다. 검색어는 유지, 페이지는 1 | TPC `activeKpiToggle`, `segmentAndCounts`, `appliedChips` / JS — 브라우저 확인 필요(OOB 교체) | 미확인 |
| 세그먼트 건수("전체 3 / 활성 2 / 정지 1"), 선택 상태만 남김, KPI 는 눌리지 않음, "1 / 3건" | V2 2 | source ✅ / 차이: 세그먼트 버튼마다 `hx-get`. 정지 선택 시 활성 KPI `aria-pressed=false` | TPC `segmentAndCounts`, `searchAndStatusCombined` | 미확인 |
| 적용 필터 칩 제거(상태·검색)와 모두 지우기 | V2 3 | source ✅ / 차이: 칩 제거 주소가 나머지 필터를 유지. 검색어 칩 제거는 OOB 로 검색 입력 값을 비운다(타이핑 중이면 되돌려 쓰지 않음 — `HX-Trigger: tenant-q`) | TPC `appliedChips`, `typingDoesNotRewriteSearchInput` / JS — 브라우저 확인 필요 | 미확인 |
| tenant 0개: emptyTenants 빈 상태(검색 결과 없음과 구분), 표 없음 | V2 4 | source ✅ / 차이: 없음. 신규 버튼 포함 | TPC `emptyTenants` | 미확인 |
| 검색 결과 없음: noResults 빈 상태(아직 없음과 구분) | V2 5 | source ✅ / 차이: 없음. "필터 모두 지우기" 버튼 | TPC `noResults` | 미확인 |
| 로드 실패: 빈 목록 대신 실패 상태, KPI "—", CSV·KPI 버튼 없음, 오류 토스트 중복 없음(alert 하나) | V2 6 | source ✅ / 차이: 전체 페이지는 200 으로 오류 카드를 그린다(boost 이동이 4xx/5xx 를 스왑하지 않으므로). 조각 요청의 실패는 5xx + 토스트 | TPC `loadFailure`, `fragmentFailureIsToast` | 미확인 |
| 만료 임박 N = ACTIVE 키 30일 이내 | V2 7 | source ✅ / 차이: `ApiKeyAdminController.list(null)` + `ApiKeyExpiry`(Task 3) | TPC `expiringKeys` | 미확인 |
| listAll 실패 시 만료 임박 숨김, 목록 유지 | V2 8 | source ✅ / 차이: 없음 | TPC `expiringHiddenOnKeyFailure` | 미확인 |
| credentialsDelta7d 없으면 7일 증감 미표시, 있으면 합계 | V2 9 | 차이: 서버 `TenantView` 에 `credentialsDelta7d` 가 없어 원본도 항상 미표시. 있을 때의 "· 7일 +5" 는 구현하지 않음(값이 올 수 없음) | TPC `kpis`("7일" 없음) | 미확인 |
| 행 클릭 → tenant 개요(`?tab=overview`) | V2 10 | source ✅ / 차이: 행 안 이름이 `<a href>`(hx-boost 이동)이고, 행의 다른 곳 클릭은 Alpine 이 그 링크를 누른다. 원본의 행 `tabindex` 대신 링크가 키보드 대상 | TPC `rowCells` / JS — 브라우저 확인 필요(행 클릭 위임) | 미확인 |
| 페이지네이션 50건("1–50 / 60건" → 다음 → "51–60 / 60건") | V2 11 | source ✅ / 차이: 서버 페이징(`hx-get`). 페이지 크기 50/100 선택, 범위 밖 page 는 마지막 쪽으로 | TPC `pagination`, `pageSizeAndClamp` | 미확인 |
| 불러오는 동안 Skeleton, KPI "—" | V3 12 | 차이: 해당 없음 — 서버가 완성된 페이지를 준다(로딩 상태 없음). 실패 시 KPI "—" 는 V2 6 | TPC `loadFailure` | 미확인 |
| 로드 실패 → 다시 시도하면 다시 불러옴(서버 문구 "서버가 잠시 응답하지 않습니다", HTTP 503) | V3 13 | source ✅ / 차이: 다시 시도 = 같은 전체 페이지 `hx-get`(body 교체, 주소 유지). HTTP 상태 줄은 loadError 조각의 복사 텍스트에만 있고 본문 메타는 5xx 일 때만 | TPC `loadFailure` / JS — 브라우저 확인 필요 | 미확인 |
| 생성 실패는 다이얼로그 안에만(오류 토스트 중복 없음), 열린 채, 입력값 유지 | V3 14 | source ✅ / 차이: 실패는 422 + 폼 조각(경고 + 입력값). 서버 5xx 도 422 로 돌려 htmx 가 스왑하게 한다(htmx 오류 규칙 A 의 예외 — 원본이 인라인 경고만 보이기 때문). HTTP 상태·trace 는 경고 메타에 | TPC `createServerFailureInline`, `createDuplicateSlug` / JS — 브라우저 확인 필요 | 미확인 |
| 생성 성공 → 새 tenant 의 WebAuthn 탭 | V3 15 | source ✅ / 차이: `HX-Redirect: /admin/tenants/{id}?tab=webauthn`(브리프는 "상세", 원본은 webauthn 탭). 토스트 "Tenant 생성 완료 / <이름> · WebAuthn 설정으로 이동합니다"는 이동 직전 `ui.js` 가 sessionStorage 로 넘겨 다음 화면 toastHost 가 한 번 띄운다 | TPC `createSuccess`, `createNonHtmxRedirects` · 스텁(ui.js 이월) / JS — 브라우저 확인 필요 | 미확인 |
| `?new=1`(팔레트)이면 다이얼로그를 바로 열고 주소에서 new 지움 | 추가 1 | source ✅ / 차이: 서버가 `open:true` 로 그리고 `x-init` 의 `history.replaceState` 가 `new` 만 뺀 주소(검색·필터 유지)로 바꾼다. 비어 있는 목록에서도 열림 | TPC `newQueryOpensDialog` / JS — 브라우저 확인 필요(replaceState·x-if 안 htmx.process) | 미확인 |
| `new` 가 없으면 다이얼로그를 열지 않음 | 추가 2 | source ✅ / 차이: 없음 | TPC `newQueryOpensDialog` | 미확인 |
| RequirePlatform: PLATFORM 은 내용, RP 는 자기 tenant 로, tenant 없는 RP 는 "계정 구성 오류"(Task 6 보류분) | RequirePlatform 1–3 | source ✅ / 차이: RP 리다이렉트는 `HomeRedirectController.homePath` 와 같은 규칙, 조각·CSV·생성은 403(원본은 화면째 막음) | TPC `rpAdminRedirects`, `rpAdminWithoutTenantSeesConfigError`, `rpAdminCannotUseFragments`, `anonymous` | 미확인 |

### NewTenantDialog.test.tsx (9)

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 한글+영문 표시 이름에서 slug·rpId 자동 채움 없음 | 1 | source ✅ / 차이: 이름 입력에 연결된 동작 자체가 없음 | TPC `dialogMarkup`(입력 속성) | 미확인 |
| 영문 표시 이름에서도 자동 채움 없음 | 2 | source ✅ / 차이: 위와 같음 | TPC `dialogMarkup` | 미확인 |
| 숫자로 시작하는 slug 허용(백엔드 규칙) | 3 | source ✅ / 차이: 서버가 같은 정규식으로 검사하고 REST 로 넘긴다 | TPC `createVerbatimAndNormalized` | 미확인 |
| 직접 입력한 name·slug·rpId 를 그대로 제출 | 4 | source ✅ / 차이: REST 요청 본문은 원본 `tenantsApi.create` 와 같은 고정값(rpName=name, origins `https://<rpId>`, 형식 none·packed, UV true, MDS false, NONE, 60000). slug 는 소문자·rpId 는 소문자·trim 으로 정규화(원본 입력 핸들러와 같음 — 입력 칸에서도 Alpine 이 같은 정규화) | TPC `createSuccess`, `createVerbatimAndNormalized` | 미확인 |
| slug 에 잘못된 문자가 있으면 생성하지 않음 | 5 | source ✅ / 차이: 422 + 폼 조각, 필드 오류 `#nt-slug-error`(aria-describedby). 원본은 빈 slug 에는 문구가 없지만 서버는 같은 문구를 보인다(빈 입력도 422 이므로) | TPC `createInvalidSlug` | 미확인 |
| 다음 단계 3개 + RP ID 잠금 안내, RP ID placeholder | V2 | source ✅ / 차이: 없음 | TPC `dialogMarkup` | 미확인 |
| placeholder 도메인 rpId 는 제출 시 오류, 생성하지 않음 | V2 | source ✅ / 차이: 같은 정규식·문구. 오류가 있으면 잠금 안내는 오류로 대체(Field 와 같은 규칙) | TPC `createInvalidRpId` | 미확인 |
| 생성 중 "생성 중…"·비활성(취소 포함)·연타해도 한 번·입력 readonly | V3 | source ✅ / 차이: `busy` 는 dialog 컴포넌트 상태(`htmx:before/after-request`), `x-bind:disabled`·`x-bind:readonly`, 닫기(Esc·스크림)도 막힘, 폼 `hx-sync="this:drop"` | TPC `dialogMarkup`(속성) / JS — 브라우저 확인 필요 | 미확인 |
| 생성 실패는 인라인 경고(제목·쉬운 설명·"입력한 내용은 그대로 있습니다"·HTTP · trace), 입력값 유지, 다시 누를 수 있음 | V3 | source ✅ / 차이: 서버가 같은 문구를 만든다(`ErrorMessages.describe`). 422 로 돌려 폼 안쪽만 교체하므로 다이얼로그는 열린 채 | TPC `createServerFailureInline` / JS — 브라우저 확인 필요 | 미확인 |

### 추가 (원본 테스트 없음)

| 체크 항목 | 결과 | JUnit | 브라우저 |
|---|---|---|---|
| 서버 검색: name 대소문자 무시·slug·trim 안 함·한글·이스케이프 | source ✅ / 차이: 없음(원본 `filtered` 를 그대로 이식) | TPC `searchRules`, `filterUnit` | 미확인 |
| 서버 순서 유지(정렬 없음), 행 셀(이니셜·id 끝 8자·천 단위·배지·"3분 전"·생성일·—) | source ✅ / 차이: 없음 | TPC `keepsServiceOrder`, `rowCells`, `rowDashes` | 미확인 |
| 조각 응답의 `HX-Push-Url`(기본값 생략·UTF-8 인코딩·보정된 page) | 새 동작(주소창 갱신은 원본의 state 대신 URL) | TPC `pushUrlCanonical`, `pageSizeAndClamp` / JS — 브라우저 확인 필요 | 미확인 |
| CSV: 필터 적용 전체 행(페이지 무시), 열·BOM·`tenants-<KST 오늘>.csv`·수식 주입 방지 | source ✅ / 차이: 클라이언트 Blob 이 아니라 `GET /admin/_/tenants/export`(attachment) | TPC `csvExport`, `csvLink` | 미확인 |
| 생성 CSRF·권한·검증 순서(REST 호출 전 검증) | source ✅ | TPC `createRequiresCsrf`, `rpAdminCannotUseFragments`, `createInvalidName` | 미확인 |

## §Tenant 상세 (Task 9, 소스 수준 점검)

R8: 결과는 TSX·테스트 소스와 JUnit 렌더 결과(`web/tenant/TenantDetailPageControllerTest` = TDPT)를 맞춘 것이고 브라우저 칸은 모두 "미확인"이다. 약어: TDP=TenantDetailPage.test.tsx, TO=TenantOverview.test.tsx, TS=tenantSignals.test.ts, TF=tabFormat.test.ts.
공통 차이: 전체 페이지는 헤더·탭 줄·개요 탭을 서버가 그린다(R3'). 다른 탭은 `#tenant-tab` 이 `hx-trigger=load` 로 `GET /admin/_/tenants/{id}/tab/{tab}` 를 불러온다. 일반 탭 매핑은 개요(모르는 키 포함)를 돌려주고 Task 10–13 의 리터럴 경로가 우선한다.

### TenantDetailPage.test.tsx · useCrumbTenant

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| fmtDateKst(KST YYYY-MM-DD, 없으면 —) | TDP 1 | source ✅ / 차이: `KstFormat.kstDate`(JUnit 별도 `KstFormatTest`) 사용 | TDPT `headerAndTabs`(2026-03-02) | 미확인 |
| 이름·상태 배지·RP 사이트 링크(새 창)·ID/RP ID 복사·slug·생성일·탭 7개(순서) | TDP 2 | source ✅ / 차이: 없음(탭은 role=tab 링크) | TDPT `headerAndTabs` | 미확인 |
| 탭 클릭은 onTabChange | TDP 3 | source ✅ / 차이: `hx-get` 조각 + `hx-push-url`(주소 `?tab=`), 활성 표시·마지막 브레드크럼은 `ui.js`(htmx:afterRequest). 탭 키는 TSX 값(`apikeys`) — 브리프의 `api-keys` 는 TSX 가 이긴다 | TDPT `tabLinkAttributes`, `everyTabKeyIsSelectable` / JS — 브라우저 확인 필요 | 미확인 |
| 감사 로그 탭에 PLATFORM 여부 전달 | TDP 4 | 미확인 — 감사 탭 task 몫(이 task 는 `platform` 모델 값을 제공) | — | 미확인 |
| AAGUID 배지: mode≠ANY 이고 mdsName 없는 항목 수, 사유 툴팁 | TDP 5 | source ✅ / 차이: 전체 페이지 렌더 때 한 번 조회(탭 조각 교체로는 갱신 안 됨 — AAGUID 저장 후 갱신은 AAGUID 탭 task 몫) | TDPT `tabBadges` | 미확인 |
| API 키 배지: 30일 안 만료 ACTIVE 키 수, 개요에 개수 전달 | TDP 6 | source ✅ / 차이: 판정은 `ApiKeyExpiry.isExpiringSoon`(회수·만료 제외, 경계 포함) | TDPT `kpis`, `tabBadges` | 미확인 |
| 조건 없으면 배지 없음 | TDP 7 | source ✅ / 차이: 없음 | TDPT `tabBadges`(ANY 0) | 미확인 |
| 신호 조회 실패는 조용히 무시 | TDP 8 | source ✅ / 차이: 없음 | TDPT `tabBadges`, `noExpiringHintWhenLookupFails` | 미확인 |
| AAGUID 저장 후 onSaved 로 배지 갱신 | TDP 9 | 미확인 — AAGUID 탭 task 몫 | — | 미확인 |
| PLATFORM ⋯ 트리거는 테두리 버튼 | TDP 10 | source ✅ / 차이: 없음(`btn btn--dots`) | TDPT `suspendDialogForPlatform` | 미확인 |
| RP_ADMIN 에게는 ⋯ 메뉴 없음(서버 PLATFORM 전용) | TDP 11 | source ✅ / 차이: 서버도 `POST …/suspend|activate` 를 PLATFORM 만 허용(403, REST 미호출) | TDPT `suspendHiddenForRpAdmin` | 미확인 |
| 정지 L3: 제목 조사·영향 범위·취소 기본 포커스·slug 일치 전 비활성·성공 토스트 "회수됨" | TDP 12 | source ✅ / 차이: 확인 라벨의 slug 를 mono 칩으로 강조하지 않음(라벨은 문자열). 성공 = 경고 토스트 + `HX-Redirect`(같은 탭 재조회, 원본 onReload) 후 이월 표시. 다이얼로그 비활성·일치 판정은 Task 3 JS | TDPT `suspendDialogForPlatform`, `suspendSuccess` / JS — 브라우저 확인 필요 | 미확인 |
| 정지 실패는 인라인 오류, 닫히지 않음, 토스트 없음 | TDP 13 | **차이**: 인라인 오류("정지하지 못했습니다")는 Task 3 confirmDialog 가 그리지만 서버 오류 토스트(`HX-Trigger`)가 같이 뜬다(다이얼로그가 쉬운 문구를 그 헤더에서 읽는 계약) — 토스트 중복 | TDPT `suspendFailure`(5xx + 토스트, 리다이렉트 없음) / JS — 브라우저 확인 필요 | 미확인 |
| 취소하면 다시 열 때 입력 초기화 | TDP 14 | source ✅ / 차이: `x-if` 로 다시 그려 비워짐(Task 3) | JS — 브라우저 확인 필요 | 미확인 |
| 정지 해제: SUSPENDED 면 메뉴 "정지 해제" → L1 확인 → activate | TDP 15 | source ✅ / 차이: 성공 시 `HX-Redirect` | TDPT `activateDialogForSuspended`, `activateSuccess` | 미확인 |
| 정지 해제 취소는 호출하지 않는다 | TDP 16 | source ✅ / 차이: 요청은 확인 버튼에만 있다 | JS — 브라우저 확인 필요 | 미확인 |
| WebAuthn 저장·API 키 변경 후 onReload(개요 요약·배지 갱신) | TDP 17–18 | 미확인 — 해당 탭 task 몫(저장 후 갱신 규약은 그쪽이 정한다) | — | 미확인 |
| A→B 빠른 이동에서 늦은 A 응답을 B 주소에 그리지 않음 | TDP 19 | source ✅ / 차이: 서버 렌더라 해당 없음(이동마다 전체 페이지·조각을 새로 받음). 탭 조각은 `hx-sync=#tenant-tab:replace` 로 이전 요청을 취소 | JS — 브라우저 확인 필요 | 미확인 |
| 로딩 중 Skeleton("Tenant 정보 불러오는 중"), "Loading…" 없음 | TDP 20 | **차이**: 전체 페이지는 서버가 완성해 보내므로 로딩 스켈레톤이 없다. 다른 탭(`?tab=…`)은 탭 컨테이너가 스켈레톤("탭 불러오는 중") + load | TDPT `nonOverviewTabLoadsLazily` | 미확인 |
| 404 면 "찾을 수 없습니다" + Tenants로 돌아가기(다시 시도 없음) | TDP 21 | source ✅ / 차이: 직접 접근은 404 상태, boost 이동은 4xx 를 스왑하지 않아 200 으로 같은 화면 | TDPT `notFound` | 미확인 |
| 5xx 면 LoadError(다시 시도) + ‹ Tenant 목록으로, 성공 시 상세 | TDP 22 | source ✅ / 차이: 다시 시도 = 같은 전체 페이지를 다시 받아 body 교체. 200 + 카드(boost 가 5xx 를 스왑하지 않으므로, Task 8 규칙) | TDPT `loadFailure` | 미확인 |
| 로드 실패를 토스트로 띄우지 않는다(인라인 하나) | TDP 23 | source ✅ / 차이: 없음 | TDPT `loadFailure`(HX-Trigger 없음, role=alert 1개) | 미확인 |
| 열린 뒤 재조회 실패 → 상세 유지 + 경고 + 다시 시도 / 404 로 바뀌면 not-found | TDP 24–25 | **차이(해당 없음)**: 서버 렌더에는 "이전 내용 유지" 상태가 없다. 새로 받은 화면이 곧 최신이다 | — | 미확인 |
| 재조회 실패 시 정지 메뉴 비활성(stale) / 다이얼로그 연 뒤 실패하면 정지 안 함 | TDP 26–27 | **차이(해당 없음)**: stale 상태가 없다. 대신 서버가 정지 요청마다 `tenants.get` 으로 테넌트를 다시 읽고 경계를 확인한다 | TDPT `suspendSuccess` | 미확인 |
| (Review Focus 1) RP_ADMIN 이 다른 tenant 를 열면 | —(App.tsx: 403 LoadError) | source ✅ / 차이: 전체 페이지·조각 모두 403(`WebExceptionAdvice`: 직접 접근은 오류 화면, boost/조각은 토스트 + 본문 없음), 데이터를 읽는 REST 호출 0 | TDPT `rpAdminOtherTenantDenied` | 미확인 |
| 모르는 탭 키 | —(TSX: 빈 화면) | **차이(R3')**: 개요로 떨어진다(전체 페이지·조각) | TDPT `unknownTabFallsBackToOverview`, `tabFragmentHasNoLayout` | 미확인 |
| 이후 탭 task 의 리터럴 경로가 일반 매핑에 막히지 않음 | — | source ✅ | TDPT `literalTabMappingWinsOverGeneric` | 미확인 |
| 브레드크럼 이름(useCrumbTenant): PLATFORM Tenants › 이름 › 탭, RP 이름 › 탭 | useCrumbTenant | source ✅ / 차이: 클라이언트 조회 대신 서버 조회 결과를 `breadcrumbs` 모델로 | TDPT `breadcrumbTenantName` | 미확인 |

### TenantOverview.test.tsx

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| KPI 4개(등록 Credential 천 단위·유효 API Key·성공률 %·n / m 시도)와 요약 카드 3개 | TO 1 | source ✅ / 차이: 숫자 형식은 `NumberFormat(US)`(toLocaleString 기본과 같은 쉼표), 소수 1자리 `%.1f` | TDPT `headerAndTabs`, `kpis` | 미확인 |
| WebAuthn 요약 origins 는 실제 allowedOrigins, 없으면 행 없음 | TO 2 | source ✅ / 차이: 없음(`origins` null 일 때만 행 생략) | TDPT `webauthnSummary` | 미확인 |
| credentialsDelta7d 는 있을 때만 "7일 +N" | TO 3 | **차이**: 서버 `TenantView` 에 필드가 없어 원본도 항상 미표시 → 미구현 | TDPT `kpis`("7일 +" 없음) | 미확인 |
| expiringKeyCount 없으면 만료 임박 줄 없음 | TO 4 | source ✅ | TDPT `noExpiringHintWhenLookupFails` | 미확인 |
| PLATFORM: 최근 활동은 활동 피드에서 이 tenant 만 | TO 5 | source ✅ / 차이: 원본은 전체 피드를 받아 클라이언트에서 걸렀으나 REST 의 `tenantId` 필터로 서버에서 건다(이 tenant 이벤트가 전체 최근 N건 밖이어도 보임) + 같은 tenant 재검사, 최대 5건 | TDPT `platformRecentActivity` | 미확인 |
| 감사 체인 무결 / 위변조(bad 배너, …끝 8자) | TO 6–7 | source ✅ / 차이: 첫 화면 "확인 중" 카드가 `hx-trigger=load` 로 `…/overview/chain` 을 받아 교체 | TDPT `chainCardIntact`, `chainCardTampered`, `chainCardInitialChecking` / JS — 브라우저 확인 필요 | 미확인 |
| RP_ADMIN: PLATFORM 전용 API 미호출, 체인 카드 없음, 전체 보기는 audit 탭 | TO 8 | source ✅ / 차이: 체인 카드 엔드포인트도 PLATFORM 만(403) | TDPT `rpAdminOwnTenant`, `chainRequiresPlatform` | 미확인 |
| PLATFORM 전체 보기는 활동 화면(tenant 필터) | TO 9 | source ✅ / 차이: `/admin/activity?tenantId=` | TDPT `platformRecentActivity` | 미확인 |
| 전 tenant 체인 검증(overview)을 개요에서 부르지 않음, recentRuns 스트립 없음 | TO 10 | source ✅ / 차이: 없음(`verifyTenant` 만) | TDPT `chainCardIntact` | 미확인 |
| 수동 검증=verifyTenant 재호출, 월간 보고서=감사 체인 화면, 자세히=감사 로그 탭 | TO 11 | source ✅ / 차이: 링크는 일반 `<a>`(boost) | TDPT `chainCardInitialChecking`, `chainCardIntact`, `chainManual` | 미확인 |
| 퍼널 실패: 성공률 "—" + "불러오지 못했습니다", "시도 없음" 아님, 다시 시도 | TO 12 | source ✅ / 차이: 다시 시도 = 개요 조각 재요청 | TDPT `funnelFailureAndNoAttempts` | 미확인 |
| 퍼널 로딩 중에는 "시도 없음"을 보이지 않는다 | TO 13 | **차이(해당 없음)**: 서버가 퍼널을 조회한 뒤 그리므로 로딩 상태가 없다 | — | 미확인 |
| 체인 검증 실패: "확인 중"에 멈추지 않고 확인 불가 + 다시 시도 | TO 14 | source ✅ / 차이: 서버 실패는 200 + "확인 불가" 카드(토스트 없음) | TDPT `chainAutoFailureIsUnavailable` | 미확인 |
| 체인 응답이 제한 시간(15초) 안에 없으면 확인 불가, "감사 로그로"는 audit 탭 | TO 15 | source ✅ / 차이: `hx-request` timeout 15000 + 카드 Alpine(`htmx:timeout`)이 "확인 불가"로 바꾼다 | TDPT `chainCardInitialChecking`, `chainCardAlpineIsConstant` / JS — 브라우저 확인 필요 | 미확인 |
| 수동 검증 중 "검증 중…" 비활성, 연타해도 한 번만 | TO 16 | source ✅ / 차이: `verifying` 플래그 + `hx-sync=this:drop`, 버튼 `x-bind:disabled` | JS — 브라우저 확인 필요 | 미확인 |
| 수동 검증이 시간 안에 응답 없으면 버튼 해제·이전 결과 유지, 늦은 응답 무시 | TO 17 | source ✅ / 차이: 시간 초과 시 `htmx:after-request` 로 verifying 해제(전역 시간 초과 토스트), 응답이 교체되지 않아 이전 결과 유지. 토스트 제목은 "서버가 잠시 응답하지 않습니다"(원본 "수동 검증 실패") | JS — 브라우저 확인 필요 | 미확인 |
| 수동 검증 실패도 버튼 해제·이전 결과 유지, 토스트 "수동 검증 실패" | TO 18 | source ✅ / 차이: 서버가 오류 상태 + 제목 있는 오류 토스트(본문 없음) | TDPT `chainManual` | 미확인 |
| 수동 검증 완료 토스트(무결 ok / 위변조 warn) | TO(코드) | source ✅ | TDPT `chainManual` | 미확인 |

### tenantSignals.test.ts · tabFormat.test.ts

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| tenantSignals: 미확인 AAGUID 수(ANY 0·mdsName null·빈 aaguid 제외) | TS 1–4 | source ✅ / 차이: `TenantDetailPageController.unverifiedAaguid` | TDPT `tabBadges` | 미확인 |
| tenantSignals: 30일 이내 ACTIVE 키만(경계 포함)·기간 변경·recentRuns 선택·요약 라벨 | TS 5–9 | source ✅ / 차이: 만료 판정은 기존 `ApiKeyExpiry`(Task 3). recentRuns 는 서버에 없어 미사용(원본도 미사용) | TDPT `kpis` | 미확인 |
| tabFormat: KST 날짜·시각, 빈 값 —, timeAgo, 천 단위 | TF 1–4 | source ✅ / 차이: 기존 `KstFormat`(Task 3 `KstFormatTest`)을 재사용 | TDPT `platformRecentActivity`(상대 시간) | 미확인 |
| 이벤트 점 색 eventColor | TO(코드) | source ✅ | TDPT `eventColor` | 미확인 |

## §Tenant WebAuthn 설정·AAGUID 정책 탭 (Task 10, 소스 수준 점검)

약어: WTC = `WebauthnTabControllerTest`, ATC = `AaguidTabControllerTest`, OVT = `OriginValidationTest`. 브라우저 확인 필요 항목(JS)은 Node 스텁(미커밋)으로 로직만 확인했다.

### WebauthnConfigTab.test.ts — validateOrigin (15)

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| rpId 자기 자신 허용·https 정규화 / 스킴 없으면 https 가정 / 서브도메인 / 여러 단계 | WT 1–4 | source ✅ / 차이: `OriginValidation`(Java 이식) | OVT `self`,`noScheme`,`sub`,`deepSub` | 미확인 |
| port 보존 / 경로·쿼리 제거 / 대소문자 무시 | WT 5·6·12 | source ✅ / 차이: 기본 포트(443·80)는 정규화에서 뺀다(JS URL 과 같음) | OVT `port`,`stripsPathAndQuery`,`caseInsensitive`,`extras` | 미확인 |
| 비슷한 host·상위 도메인·접미사 위장·다른 도메인의 서브 위장 거부 | WT 7–10 | source ✅ | OVT `similar`,`parent`,`suffixDisguise`,`attackerSuffix` | 미확인 |
| 빈 입력 거부 | WT 11 | source ✅ | OVT `blank` | 미확인 |
| android:apk-key-hash 통과 / 43자 아님 / base64url 아님 거부 | WT 13–15 | source ✅ | OVT `apk`,`apkShort`,`apkBadChar` | 미확인 |

### WebauthnConfigTab.view.test.tsx (19)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| changedFieldNames 화면 순서 | WV 1 | source ✅ | WTC `mergeAndOrder` | 미확인 |
| 설명 좌/컨트롤 우 행, rpId 읽기 전용 + 변경 불가, 라벨 title(원시 키), 필수 aria-pressed, 변경 전 저장 바 없음 | WV 2 | source ✅ / 차이: 저장 바는 서버가 항상 그리고 변경이 없으면 Alpine(`x-show=dirty`)이 숨김. "변경됨" 배지는 행 라벨 옆(원본은 rpName·대기 시간은 입력 옆) | WTC `rendersRowsWithTsxStrings` / JS — 브라우저 확인 필요 | 미확인 |
| dirty → 저장 바 건수·요약("대기 시간 · 허용 origin")·변경 필드 표시·실시간 timeout 안내·되돌리기 | WV 3 | source ✅ / 차이: 요약은 폼 `data-savebar-labels` 라벨표(화면 순서)로 saveBar 가 만든다. 변경 표시는 saveBar 가 `[data-field]` 에 `data-changed` 를 달아 CSS 가 켠다(행 단위). timeout 안내는 `timeoutHint` 컴포넌트(처음 값은 서버가 같은 문구로) | WTC `timeoutGuideRenderedByServer`,`formWiring` / 스텁(timeoutHint·chipInput reset) / JS — 브라우저 확인 필요 | 미확인 |
| origin 검증: 범위 밖·정규화 후 중복은 추가하지 않고 오류 | WV 4 | source ✅ / 차이: 클라이언트 함수 대신 서버 검증(GET `…/webauthn/origin-check`, 본문=오류, `X-Normalized-Value`=정규화 값). 검증 실패·통신 실패는 통과로 보지 않음 | WTC `originCheck` / 스텁(chipInput 원격 검증 5경우) | 미확인 |
| Android 앱 키: 64 hex → apk-key-hash origin, 잘못된 길이 오류 | WV 5 | source ✅ / 차이: 서버 변환(GET `…/webauthn/apk-key`) 후 `chip-add` 이벤트로 칩 입력에 넣는다(`chipAdder`) | WTC `apkKeyCheck` / 스텁(chipAdder) | 미확인 |
| dirty 가 아니면 탭 이동이 바로 / dirty 면 이동이 막히고 확인 다이얼로그 | WV 6·7 | source ✅ / 차이: 탭 링크 `data-guard-dirty` + `$store.dirty`(Task 5·9). saveBar 가 form 변경 수를 store 에 보고 | Task 9 `TDPT tabLinksCarryGuard…` / JS — 브라우저 확인 필요 | 미확인 |
| 저장… → L2 변경 비교(라벨·초 단위) → 같은 payload 로 update | WV 8 | source ✅ / 차이: 폼 제출 = 미리보기 POST(`data-preview`) → `#wa-confirm-slot` 에 확인 다이얼로그(비교한 값을 hidden 으로) → 확인 = PUT. 원본 `diff(tenantId, draft, mdsRequired)`·`update(…)` 인자와 같은 REST 본문 | WTC `diffShowsChangedFieldsOnly`,`diffRequestBody`,`dialogCarriesComparedValues`,`hiddenMdsRequiredPreservedOnSave` | 미확인 |
| 서버 diff 가 비어도 로컬 변경이 있으면 저장을 막지 않고 확인 목록에 | WV 9 | source ✅ | WTC `diffShowsChangedFieldsOnly` | 미확인 |
| 서버 diff(requireUserVerification 불리언)+로컬(증명) 병합, 한국어, raw 값 비노출 | WV 10 | source ✅ | WTC `mergedServerAndLocalChanges`,`mergeAndOrder` | 미확인 |
| 저장 실패는 다이얼로그 안 인라인 오류, 입력·저장 바 유지 | WV 11 | source ✅ / 차이: 확인 요청(X-Confirm)의 실패는 `confirmError` 만(토스트 없음), 4xx/5xx 라 탭이 교체되지 않음 | WTC `saveFailureIsInlineOnly`,`saveValidationFailureIsInline` / JS — 브라우저 확인 필요 | 미확인 |
| 저장 중 입력 잠금·두 번 나가지 않음 | WV 12 | **부분 차이**: 미리보기 요청 중에는 form 이 inert + 저장 바 '저장 중…'(saveBar `previewing`). 저장(PUT) 중에는 잠금 없음. 연타는 `hx-sync=this:drop`(미리보기)·confirmDialog 의 busy(저장)로 막는다. 저장은 다이얼로그가 들고 있는 값이라 그 사이 편집이 보내지지 않으며, 성공하면 탭이 새 값으로 다시 그려진다 | JS — 브라우저 확인 필요 | 미확인 |
| 변경 비교 중·다이얼로그가 열린 동안 입력 잠금, 저장은 비교한 값으로 | WV 13 | source ✅(저장 값) / **차이**(잠금): 입력 잠금 없음. 저장 값은 비교한 hidden 값 | WTC `dialogCarriesComparedValues` | 미확인 |
| 다이얼로그 취소하면 입력 잠금이 풀린다 | WV 14 | **차이(해당 없음)**: 잠그지 않으므로 풀 것이 없다. 취소해도 입력·저장 바는 그대로 | — | 미확인 |
| 사용자 검증은 필수·권장만("요구 안 함" 숨김) | WV 15 | source ✅ / 차이: 서버도 DISCOURAGED 를 422 로 거부 | WTC `rendersRowsWithTsxStrings`,`fieldErrors` | 미확인 |
| 로드 실패: LoadError + 다시 시도 | WV 16 | source ✅ / 차이: 조회 실패는 인라인 LoadError(다시 시도 = `TenantTabs.requesterAttrs`). 403·404 는 Task 9 처럼 토스트 + 본문 없음 | WTC `loadFailureIsInlineLoadError`,`rpAdminOtherTenantDenied` | 미확인 |
| 로딩 중 Skeleton | WV 17 | **차이(해당 없음)**: 탭 컨테이너의 스켈레톤(Task 9)이 대신한다 | Task 9 | 미확인 |
| "자세히" 링크·RP 연동 가이드 — 주소가 비면 숨김 | WV 18 | source ✅ / 차이: `helpLinks.rpGuide`(Task 4 ShellAdvice) 사용 | WTC `helpLinksHiddenWhenEmpty`(빈 주소만; 주소가 있는 렌더는 템플릿 소스 확인) | 미확인 |
| 문구에 ceremony 없음 | WV 19 | source ✅ | WTC `rendersRowsWithTsxStrings` | 미확인 |

### 추가 (원본 테스트 없음, 브리프·계약)

| 체크 항목 | 결과 | JUnit | 브라우저 |
|---|---|---|---|
| 숨은 값(mdsRequired·acceptedFormats·rpId·displayName)은 서버에서 읽은 현재 값을 REST 에 되돌려 보낸다(요청에 실려 와도 무시) | source ✅ / 차이: 원본은 불러온 시점 값을 클라이언트가 들고 있다가 보냄(오래된 값·위조 가능) | WTC `hiddenMdsRequiredPreservedOnSave`,`mdsFalsePreservedAndUvMapping` | — |
| 서버 `AllowedOriginFormat`·범위·길이 위반 → 422 + 필드 옆 오류(OOB), 입력·변경 상태 유지, REST 미호출 | 신규(ruling G) | WTC `invalidOriginInlineError`,`fieldErrors` | 미확인 |
| 저장 성공 `HX-Trigger: saved`(+토스트) → `$store.dirty` 해제, 탭 새 값으로 | 신규 | WTC `saveTriggersSavedEvent` | 미확인 |
| 접근 경계(`TenantTabs.load` 먼저, RP 다른 tenant 403) | 신규 | WTC `rpAdminOtherTenantDenied`,`rpAdminOwnTenantAllowed` | — |

### AaguidPolicyTab.test.ts (17)

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| formatAaguid 11케이스(32 hex 변환·8자까지 하이픈 없음·대문자·기존 하이픈·비 hex·절단·빈 값·하이픈만·공백·세그먼트 경계) | AT 1–11 | source ✅ / 차이: 서버 `entry-check` 가 같은 규칙으로 맞춘다(입력 중 자동 포맷은 없고 Enter 때 맞춤) | ATC `formatAaguid`,`entryCheck` | 미확인 |
| saveConfirmKind 3 | AT 12–14 | source ✅ | ATC `saveConfirmKind` | 미확인 |
| policyChanges 3 | AT 15–17 | source ✅ | ATC `policyChanges` | 미확인 |

### AaguidPolicyTab.view.test.tsx (19)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| aaguidChangedFields | AV 1 | source ✅ / 차이: 키는 폼 필드 이름(mode·aaguid·mdsStrict), 라벨은 `data-savebar-labels` | ATC `changedFields` | 미확인 |
| MDS 엄격 설명: MDS 등록 여부만, REVOKED/폐기 없음 | AV 2 | source ✅ | ATC `strictHint` | 미확인 |
| mode radiogroup·MDS 확인/미확인 배지·경고 제목·항목 보기·스위치 | AV 3 | source ✅ / 차이: 행 목록은 Alpine x-for(칩 입력 meta). 모드 라벨 title 은 원시 enum. 배지 클래스는 `badge--success/warning` | ATC `rendersTsxStrings`,`listWiring` / JS — 브라우저 확인 필요 | 미확인 |
| 미확인 항목이 없으면 Alert 없음 | AV 4 | source ✅ | ATC `noAlertWhenAllVerified` | 미확인 |
| 추가: 자동 포맷 → 추가 → 저장… → L2 변경 비교 → 같은 payload, onSaved(배지 갱신) | AV 5 | source ✅ / 차이: 입력 중 자동 포맷·추가 버튼 비활성 없음(Enter/추가 때 서버 검증 후 정규화). onSaved = 탭 줄 배지 OOB 갱신 | ATC `previewDiff`,`saveCallsRestAndTriggersSaved`,`badgeRefreshedAfterSave` / 스텁(chipInput) | 미확인 |
| 중복 AAGUID 는 추가되지 않는다 | AV 6 | source ✅ / 차이: 입력칸은 비워진다(원본은 그대로) | 스텁(chipInput addValue 중복 무시) | 미확인 |
| 삭제 버튼 → 목록 변경, 되돌리기로 복구 | AV 7 | source ✅ / JS — 브라우저 확인 필요(chipInput reset → chips-change) | 스텁(reset) | 미확인 |
| 전체 허용 선택 시 목록 행이 숨고 요약은 mode | AV 8 | source ✅ / JS — 브라우저 확인 필요(`policyForm.mode`) | ATC `strictHint`(x-show/x-cloak) / 스텁 | 미확인 |
| 빈 허용 목록: 빨간 경고 + 추가/되돌리기, 차단 목록은 안내만 | AV 9 | source ✅ / JS(개수·모드에 따라 x-show) | ATC `listWiring` / 스텁(policyForm) | 미확인 |
| MDS strict 스위치 → 요약, 라벨 켜짐/꺼짐 | AV 10 | source ✅ / 차이: 스위치 마크업을 조각 대신 직접 그려 라벨이 상태를 따른다 | ATC `rendersTsxStrings` / JS | 미확인 |
| dirty 면 탭 이동이 막힌다 | AV 11 | source ✅ (Task 9 가드) / JS | — | 미확인 |
| 허용 목록 비우고 저장 → L3 이해 체크 전엔 저장 불가 | AV 12 | source ✅ | ATC `previewBlockAll` | 미확인 |
| MDS 엄격을 켜는 저장 → L2 영향 요약 "켜고 저장" | AV 13 | source ✅ | ATC `previewMdsStrict` | 미확인 |
| 저장 실패는 다이얼로그 안 인라인 오류, 입력 유지 | AV 14 | source ✅ | ATC `saveFailureInline` | 미확인 |
| 로드 실패: LoadError + 다시 시도 | AV 15 | source ✅ | ATC `loadFailure` | 미확인 |
| 저장 중 입력 잠금·저장은 한 번 | AV 16 | **차이**: 입력 잠금 없음(WebAuthn 탭 WV 12 와 같은 이유). 저장은 확인 다이얼로그 busy 로 한 번 | JS — 브라우저 확인 필요 | 미확인 |
| 모드 원문은 title, 확인 목록은 실제로 바뀐 항목만 | AV 17 | source ✅ / 차이: 확인 목록의 모드는 라벨 텍스트(title 없음) | ATC `previewListsOnlyChanged`,`rendersTsxStrings` | 미확인 |
| 삭제 후 같은 항목 재추가 = 변경 없음 | AV 18 | source ✅ / 차이: 재추가하면 순서가 바뀌므로 폼 `data-savebar-unordered=aaguid` 로 saveBar 가 목록을 정렬한 집합으로 비교(서버 `policyChanges` 와 같은 기준) | ATC `policyChanges`, `formWiring` / 스텁(saveBar normField) / JS — 브라우저 확인 필요 | 미확인 |
| MDS 엄격 영향은 목록과 무관하게 "MDS 미등록 모든 인증기 거부" | AV 19 | source ✅ | ATC `previewMdsStrict` | 미확인 |

### 추가 (원본 테스트 없음, 브리프·계약)

| 체크 항목 | 결과 | JUnit | 브라우저 |
|---|---|---|---|
| 저장은 `AaguidPolicyController.update`, 항목 메모는 서버의 현재 값을 항목별로 되돌림(새 항목 null) | source ✅ / 차이: 원본은 불러온 항목의 note 를 그대로 보냄 | ATC `saveCallsRestAndTriggersSaved` | — |
| 저장 뒤 탭 줄 AAGUID 배지 OOB 갱신(`#tenant-tab-aaguid-badge`) | 신규(Task 9 이월) | ATC `badgeRefreshedAfterSave` | 미확인 |
| 검증 실패 422 + 필드 옆 오류, 변경 없음은 경고 토스트 | 신규(ruling G) | ATC `previewNoChangeAndInvalid` | 미확인 |
| 접근 경계 | 신규 | ATC `rpAdminOtherTenantDenied`,`rpAdminOwnTenantAllowed` | — |

## §Tenant API 키 탭 (Task 11, 소스 수준 점검)

약어: AKT = `ApiKeysTab.test.tsx` 순번, AKC = `ApiKeysTabControllerTest`. 브라우저 쪽(Alpine)은 Node 스텁(미커밋: apiKeyForm 토글·미리보기·canSubmit·요청 중 잠금, apiKeyIssued 보고·체크 전 닫기 불가·닫으면 자리 비움·destroy 해제)으로만 확인.

### ApiKeysTab.test.tsx (24)

| 체크 항목 | 원본 테스트 | 결과 | JUnit / 스텁 | 브라우저 |
|---|---|---|---|---|
| ⋯ 메뉴는 ACTIVE 키에만, 회전·접두사 복사 + 위험 구획 회수, 회수 다이얼로그 기본 포커스 취소·danger 버튼 | AKT 1 | source ✅ | AKC `rendersKpisRowsAndMenuForActiveOnly`,`revokeIsL2WithoutTypedConfirm` | ✅ (2026-11-04 live pass) |
| 접두사 뒤 keySuffix 는 값이 있을 때만 | AKT 2 | source ✅ / 차이: 서버 ApiKeyView 에 keySuffix 가 없어 항상 `prefix…` (원본도 실제 서버 응답에서는 같다) | AKC `rendersKpisRowsAndMenuForActiveOnly` | 미확인 |
| 30일 이내 만료 경고 일수, KPI 초과 키 수(warn), 활성/만료/회수 | AKT 3 | source ✅ | AKC `rendersKpisRowsAndMenuForActiveOnly`,`expiryBadgeMatchesHelper` | 미확인 |
| 발급 완료: Escape·스크림 무시, 체크 전 닫기 비활성, 체크 후 '닫기 (영구 소실)' | AKT 4 | source ✅ / JS(dismissible=false, closeOnScrim=false, x-bind:disabled=!checked) | AKC `issueCallsRestAndRendersIssuedDialog` / 스텁(finish) | ✅ (2026-11-04 live pass) |
| 회전: 24시간 겹침 안내 → 새 키 + 이전 키 만료 시간 | AKT 5 | source ✅ | AKC `rotateDialogIsL2Primary`,`rotateShowsNewKeyAndOldKeyExpiry` | 미확인 |
| 발급 다이얼로그가 열린 동안 이동·새로고침 막기('키를 아직 저장하지 않았습니다' / 머무르기) | AKT 6 | source ✅ / JS: `$store.dirty.report('apikey-issued',1)` + `notice` 로 unsavedGuard 문구 교체(app.js·unsavedGuard.html 공유 변경) | 스텁(apiKeyIssued init/destroy) / JS — 브라우저 확인 필요 | 미확인 |
| 회전 다이얼로그는 새 키 id, 만료는 다시 읽은 새 키 값 | AKT 7 | source ✅ / 차이: 원본은 열린 뒤 목록 재조회로 채우고, 여기서는 응답 전에 서버가 목록을 다시 읽어 바로 그린다(못 찾으면 만료 줄 숨김) | AKC `rotateShowsNewKeyAndOldKeyExpiry`,`rotateHidesExpiryWhenNewKeyMissing` | 미확인 |
| 발급 버튼 연타는 한 번만 | AKT 8 | source ✅ / JS: form `hx-sync=#ak-root:drop` + busy 동안 버튼 disabled | 스텁(onBefore → canSubmit false) | 미확인 |
| 회수·회전 확인 버튼 처리 중 비활성 '회수 중…' | AKT 9 | source ✅ (Task 3 confirmDialog busy) | AKC `revokeIsL2WithoutTypedConfirm`(라벨) | 미확인 |
| 로드 실패: LoadError + 다시 시도(빈 상태 아님) | AKT 10 | source ✅ | AKC `loadErrorNotEmpty` | 미확인 |
| 로딩 중 Skeleton | AKT 11 | source ✅ / 차이: 탭 컨테이너의 공통 스켈레톤(Task 9) | — | 미확인 |
| 빈 상태: 첫 API 키 발급 + RP 연동 가이드 | AKT 12 | source ✅ | AKC `emptyState` | 미확인 |
| 발급 실패: 다이얼로그 안 경고, 입력 유지, 토스트 없음 → 재발급 | AKT 13 | source ✅ (422 + `#ak-new-error` OOB, 폼은 교체 안 함. 폼 상태는 x-if 밖이라 취소 후 다시 열어도 유지, 발급 성공만 초기화 — Fix 1) | AKC `issueFailureIsInlineAndKeepsInput`,`issueValidationRejectsWithoutCallingRest` | 미확인 |
| 발급 중 '발급 중…' 비활성, 입력 잠금 | AKT 14 | source ✅ / JS(busy → input·fieldset·칩 disabled) | AKC `newKeyDialogMarkup` / 스텁 | 미확인 |
| 만료 열 글 배지: 정상·만료 임박·무기한·만료됨 | AKT 15 | source ✅ | AKC `expiryBadgeMatchesHelper` | 미확인 |
| 권한 범위 한국어 라벨 + 원문 title | AKT 16 | source ✅ | AKC `scopeLabelsKoreanWithRawTitle` | 미확인 |
| RP 가이드 링크가 비면 렌더하지 않음 | AKT 17 | source ✅ (`helpLinks.rpGuide`) | AKC `emptyState` | 미확인 |
| 회수: L2 영향 요약(입력 확인 없음), 성공 토스트 "회수했습니다" | AKT 18 | source ✅ / **브리프와 다름**: 브리프는 ConfirmByTyping 을 요구했지만 원본(답지)은 L2·입력 없음(`queryByRole('textbox')` null) — 원본을 따름 | AKC `revokeIsL2WithoutTypedConfirm`,`revokeSucceedsWithToastAndRefresh` | 미확인 |
| 회수 실패는 다이얼로그 안 인라인 | AKT 19 | source ✅ (confirmError) | AKC `revokeFailureIsInline` | 미확인 |
| 회전: L2 primary, 실패 인라인 | AKT 20 | source ✅ | AKC `rotateDialogIsL2Primary`,`rotateFailureIsInline` | 미확인 |
| 발급 실패 경고 마침표 겹침 없음 | AKT 21 | source ✅ | AKC `issueFailureIsInlineAndKeepsInput` | — |
| 발급 진행 중이면 회수·회전 동시 실행 안 됨(공용 가드) | AKT 22 | source ✅ / 차이: 같은 `hx-sync=#ak-root:drop` 그룹이라 뒤 요청은 조용히 버려진다('다른 작업이 진행 중' 문구 없음). 모달이라 화면에서는 겹치기 어렵다 | JS — 브라우저 확인 필요 | 미확인 |
| KPI 라벨 '권장 회전 주기' | AKT 23 | source ✅ | AKC `rendersKpisRowsAndMenuForActiveOnly` | — |
| 서버 설명이 비어도 마침표 겹침 없음 | AKT 24 | source ✅ | AKC `issueFailureIsInlineAndKeepsInput`(issueErrorText) | — |

### apiKeyHelpers.test.ts · apiKeyExpiry.test.ts

| 체크 항목 | 원본 테스트 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| rotationOverdueCount(90일 초과만), expiryState/daysLeft(올림), latestCreatedAt·keyStatusCounts, previewExpiry(말일 보정), expiryBadge 6경우 | apiKeyHelpers 1–6 | source ✅ / 차이: previewExpiry 는 서버가 KST 로 계산해 프리셋별로 넘긴다 | AKC `expiryBadgeMatchesHelper`,`newKeyDialogMarkup` | — |
| isExpiringSoon(30일, 회수·만료·무기한 false) | apiKeyExpiry 1–3 | source ✅ (Task 9 `ApiKeyExpiry`) — 탭 배지 계산에 사용 | AKC `issueCallsRestAndRendersIssuedDialog`(배지) | — |

### 추가 (원본 테스트 없음, 브리프·계약)

| 체크 항목 | 결과 | JUnit | 브라우저 |
|---|---|---|---|
| 평문 키는 응답 본문에만: 세션·플래시·헤더(토스트 포함)·리다이렉트 없음, `Cache-Control: no-store`, `hx-history=false`, 닫으면 자리 비움 | 신규 | AKC `issuedSecretOnlyInResponse` / 스텁(finish) | 미확인 |
| 회전·회수 주소(키 id)는 `?tenantId=` 를 `TenantTabs.load` 로 확인하고 그 tenant 목록에 키가 있어야 REST 호출 | 신규 | AKC `rpAdminOtherTenantDenied`,`keyOfAnotherTenantViaOwnTenantIdNotMutated` | — |
| 회전·회수는 `X-Confirm` 없으면 400 | 신규 | AKC `mutationsRequireConfirmHeader` | — |
| 발급·회전·회수 뒤 탭 줄 API 키 배지 OOB(`#tenant-tab-apikeys-badge`) | 신규(Task 9 이월) | AKC `issueCallsRestAndRendersIssuedDialog`,`rotateShowsNewKeyAndOldKeyExpiry`,`revokeSucceedsWithToastAndRefresh` | 미확인 |
| 접두사 복사 → 토스트(상수 JS + data-prefix) | source ✅ | AKC `copyPrefixMenuItem` | 미확인 |
| 정지 다이얼로그의 '유효한 API 키 N개' 는 키 변경 뒤 갱신(원본 onChanged → tenant 재조회): 변경 응답이 tenant 를 다시 읽어 `#tenant-apikey-count` OOB, 확인창은 열 때 그 값을 읽음 | source ✅ (Fix 1) | AKC `mutationsRefreshSuspendKeyCount`,`keyCountRefreshFailureIsSilent`, TDPT `suspendKeyCountReadsLiveCountElement` | 미확인 |
| 발급 중 Escape·스크림·취소로 닫히지 않음(원본 onClose={busy ? noop : onClose}) | source ✅ (Fix 1, 바깥 dialog busy) | AKC `issueDialogBlocksDismissWhileIssuingAndKeepsState` / JS — 브라우저 확인 필요 | 미확인 |
| 발급의 네트워크 오류·권한 거부는 다이얼로그 안 인라인(전역 토스트 없음) | source ✅ (Fix 1: data-quiet + X-Confirm → confirmError, netErr) | AKC `issueNetworkAndDeniedErrorsAreInline` / 스텁 | 미확인 |
| 키 이름 이스케이프(목록·확인창·발급 완료) | source ✅ | AKC `keyNameIsEscaped` | — |

## §Activity (Task 14, 소스 수준 점검)

약어: AP = `ActivityPage.test.tsx` 순번, AD = `activityDiff.test.ts`, AL = `activityLabels.test.ts`, APC = `ActivityPageControllerTest`, ADT = `ActivityDiffTest`, ALT = `ActivityLabelsTest`. 브라우저: 미확인 — 폴링 타이머·hx-sync 의 실제 중단/드롭·OOB afterbegin/beforeend 삽입·Alpine 상태 전환·포커스는 JS(Node 스텁으로 상태 전이만 확인, 커밋 안 함) — 브라우저 확인 필요.
공통 차이: 목록 상태는 서버가 아니라 화면(DOM)이 가진다(가장 새 행 id·아래쪽 커서·맨 위/맨 아래 KST 날짜 키가 숨은 입력). 날짜 그룹은 원본처럼 `role=group aria-label=라벨`(키 = KST yyyy-MM-dd, `id=day-KEY` + `day-KEY-rows`)이고 폴링·더 보기는 날짜 키로 기존 그룹에 이어 붙는다(자정을 넘겨 라벨이 바뀌어도 이어짐; 이미 그려진 그룹의 라벨 글자("오늘")는 새로고침 전까지 그대로). 액션 검색(q)은 원본처럼 읽어 온 행을 클라이언트(Alpine `applyFilter`)에서 거른다(서버 왕복 없음, 더 보기 행 유지, 지우면 전부 복원, 보이는 행 없는 그룹은 머리글째 숨김). **초기 24건 창(visibleCount 24, 더 보기마다 max(n,24) 증가)은 구현하지 않았다** — 서버가 준 한 쪽(50건)을 전부 그린다(원본은 24건만 보이고 더 보기가 창을 넓힘). 필터 전환이 실패하면 목록을 stale 로 표시해(`#activity-loaded=0`) 다음 성공 요청이 병합 대신 교체한다.

| 체크 항목 | 원본 테스트 | 결과 | 테스트 | 브라우저 |
|---|---|---|---|---|
| 피드를 KST 날짜(오늘/어제/YYYY-MM-DD)로 묶는다 | AP 1 | source ✅ (원본과 같은 role=group 날짜 그룹, KST 날짜 키) | APC `groupsByKstDay`,`pollPrependsIntoSameDayGroupAndNewGroups` | 미확인 |
| 상세 패널 5개 섹션 + TENANT_UPDATE before/after diff, 선택 행 강조 | AP 2 | source ✅ / 차이: 선택 강조는 Alpine `selectedId`(행 class·aria-current), 서버가 행을 다시 그려도 유지 | APC `diffRendersSameAsOriginal`,`detailButtonsAndSelection` | 미확인 |
| actorIp 없으면 IP 줄 없음 / 있으면 표시 | AP 3, 4 | source ✅ / 차이: `ActivityDetailView` 에 actorIp 필드가 없어 줄은 항상 없다(원본도 서버가 안 줌) | — | — |
| `diff` 필드가 있으면 payload before/after 대신 그것을 쓴다 | AP 5 | source ✅ / 차이: 서버 상세에 diff 필드가 없어 화면은 항상 payload 경로(`ActivityDiff.buildDiffRows(null, payload)`); diff 우선 규칙은 단위 테스트로 | ADT `serverDiffWins` | — |
| "보안 실패만" 스위치 없음, 보안 세그먼트가 category=security 로 조회 | AP 6 | source ✅ | APC `categoryNormalizedAndForwarded`,`controlsCarryOtherFilters` | 미확인 |
| 카테고리·tenant 필터는 URL 에 남고 URL 로 열면 적용 | AP 7 | source ✅ (rows 응답이 `HX-Replace-Url`, 검색어는 주소에 두지 않음 — 원본 동일) | APC `filtersFromUrl`,`rowsFragment` | 미확인 |
| 알 수 없는 category 는 전체 | AP 8 | source ✅ | APC `categoryNormalizedAndForwarded` | — |
| 첫 로드 실패: LoadError, KPI "—", 내보내기 비활성 | AP 9 | source ✅ (전체 페이지 200 + 오류 카드, CSV 는 Alpine `loaded` 로 막음) | APC `firstLoadFailure` | 미확인 |
| 첫 로드 실패 → 다시 시도 성공하면 목록 | AP 10 | source ✅ (다시 시도 = rows 요청, 폴링이 먼저 성공하면 `loaded=0` 이라 목록 영역을 교체) | APC `firstLoadFailure`,`pollRecoversFromLoadError` | 미확인 |
| 상세: A 를 연 뒤 B 를 열면 늦게 온 A 응답은 무시 | AP 11 | source ✅ (`hx-sync #activity-detail:replace`) / JS — 브라우저 확인 필요 | APC `detailButtonsAndSelection` | 미확인 |
| 상세: 불러오는 중 닫으면 응답이 와도 다시 열리지 않음 | AP 12 | source ✅ (closeDetail 이 `htmx:abort`) / JS | 스텁(`closeDetail` abort) | 미확인 |
| 필터를 바꾼 뒤 이전 필터의 늦은 응답은 버림 | AP 13 | source ✅ (필터 요청 `hx-sync #activity-root:replace` 가 폴링 중단, 폴링은 drop, 더 보기는 `htmx:abort`) | APC `pollingElementAttributes` | 미확인 |
| 폴링은 새 행만 앞에 붙이고 더 보기 행을 지우지 않는다 | AP 14 | source ✅ (OOB afterbegin, `top` 앞의 행만) | APC `pollPrependsOnlyNewRows`,`pollNoNewRowsAndDayHeader`,`pollTopNotFoundAndEmptyList` | 미확인 |
| p95 없으면 KPI 3개(kpis--3) / 있으면 4개 | AP 15, 16 | source ✅ / 차이: 폴링에서 p95 가 null 로 오면 이전 값을 유지하던 규칙은 없다(서버가 현재 항상 null) | APC `groupsByKstDay`,`p95Kpi` | — |
| 불러오는 동안 Skeleton, "로딩 중…" 텍스트 없음 | AP 17 | source ✅ / 차이: 첫 화면은 서버가 완성해서 그린다 — 필터 교체 중에는 목록 영역이 반투명(`htmx-request`), 상세는 Skeleton | APC `detailButtonsAndSelection` | 미확인 |
| 폴링 실패 → "연결 끊김 · N초 전 갱신" + 목록 유지 + 경고 배너, 다시 연결되면 "실시간" | AP 18 | source ✅ (Alpine 상태: responseError/sendError/timeout 로 disconnected, 성공 시 해제; 폴링 요소 `data-quiet` + 503 빈 본문 — 오류 토스트·LoadError 없음) | APC `pollFailureIsQuiet`,`liveStatusMarkup` + 스텁 | 미확인 |
| 이전 폴링이 진행 중이면 다음 폴링을 시작하지 않는다 | AP 19 | source ✅ (`hx-sync #activity-root:drop`) | APC `pollingElementAttributes` | 미확인 |
| 더 늦게 시작한 요청이 먼저 끝나면 먼저 시작한 요청의 늦은 응답은 버림 | AP 20 | source ✅ / 차이: 늦은 응답을 버리는 대신 hx-sync 로 앞선 요청을 중단(폴링=drop, 새로고침=queue last, 필터=replace) | APC `pollingElementAttributes` | 미확인 |
| "N초 전 갱신" 카운터는 aria-live 영역 밖 | AP 21 | source ✅ (공유 1초 틱 하나 — 요소가 사라지면 destroy 가 구독 해제) | APC `liveStatusMarkup` + 스텁(틱 구독·해제) | 미확인 |
| 상세 로드 실패는 패널 안 LoadError, 다시 시도로 재요청 | AP 22 | source ✅ (서버 오류 422 + 패널 안 오류, 네트워크 실패는 Alpine 패널 `netError`) | APC `detailFailureInline` + 스텁 | 미확인 |
| 새로고침이 폴링에 추월당해도 새로고침 응답이 끝나면 버튼이 다시 활성화 | AP 23 | source ✅ / 차이: `hx-disabled-elt` + `queue last`(폴링이 새로고침을 중단하지 않는다) | APC `pollingElementAttributes` | 미확인 |
| 새로고침 중 필터를 바꿔도 새로고침 응답이 끝나면 버튼이 활성화 | AP 24 | source ✅ / 차이: 새로고침은 진행 중인 필터 교체 뒤에 이어서 실행 | APC `pollingElementAttributes` | 미확인 |
| 필터를 바꾼 조회가 실패하면 이전 필터의 이벤트·KPI 를 남기지 않고 LoadError(KPI "—") | AP 25 | source ✅ (rows 422 + 오류 카드 + OOB KPI "—", 필터는 그대로) | APC `rowsFailure` | 미확인 |
| tenant 를 바꾼 조회가 실패해도 이전 tenant 이벤트를 보이지 않는다 | AP 26 | source ✅ (같은 경로) | APC `rowsFailure` | 미확인 |
| 카테고리·tenant 를 바꿔도 컨트롤이 다시 마운트되지 않아 포커스가 남는다 | AP 27, 28 | source ✅ (세그먼트·선택 상자·검색 입력은 응답이 다시 그리지 않음 — `reset` 요청만 OOB) | APC `rowsFragment`,`rowsResetControls` | 미확인 |
| 적용된 필터 칩 + 모두 지우기 + 검색어 칩 | (소스) | source ✅ | APC `rowsResetControls`,`rowsQueryFilters`,`controlsCarryOtherFilters` | 미확인 |
| 더 보기: (시각, id) 튜플 커서로 이전 쪽을 아래에 붙임, 실패는 토스트 | (소스) | source ✅ | APC `loadMore`,`loadMoreNoopCases`,`loadMoreFailureToasts`,`moreButtonAttributes` | 미확인 |
| CSV(activity-KST오늘.csv, 6열, 액션 검색 무시), 읽은 행이 없으면 경고 토스트 | (소스) | source ✅ / 차이: 원본은 화면에 읽은 행 전부 — 서버가 최신 쪽부터 가장 오래된 행의 커서까지 이어 읽는다(부분 파일 금지, 최대 40쪽) | APC `csvExport`,`csvFailure`,`csvLinkAttributes` + 스텁 | 미확인 |
| 활발한 Tenant 클릭 = tenant 필터, 폴링은 목록이 바뀔 때만 이 카드를 다시 그림(포커스 보존) | (소스) | source ✅ (원본은 React 가 포커스 유지 → 서명 비교로 대체) | APC `tenantsCardOnlyWhenChanged`,`filtersFromUrl` | 미확인 |
| PLATFORM 만: RP_ADMIN 은 홈, 조각·상세·CSV 는 403 | (App.tsx RequirePlatform) | source ✅ | APC `rpAdminRedirectedHome`,`fragmentsRejectRpAdmin`,`unauthenticated` | — |
| 사용자 값(행위자·tenant 이름·diff 값)은 HTML 로 해석되지 않음 | 신규 | — | APC `userValuesAreEscaped`,`detailEscapes` | — |
| 유휴 타이머: 폴링(`every`)은 합성 이벤트라 리셋하지 않음, 행 클릭·필터는 사용자 동작이라 리셋 | 신규 | source ✅ (Task 5 isTrusted 규칙, 폴링 요소 `data-quiet`) | APC `pollingElementAttributes` | 미확인 |
| activityDiff: diff 우선 / changedFields 순서 / 값 다른 키만 / before·after 없거나 JSON 아님 → [] / arrayChange | AD 1–6 | source ✅ | ADT 전부 | — |
| activityLabels: 라벨 매핑·fallback·문장·system | AL 1–4 | source ✅ | ALT 전부 | — |

## §Audit chain (Task 15, 소스 수준 점검)

TC = `AuditChainPageControllerTest`. 브라우저: 전부 미확인(R8) — 아래 JS 항목은 Alpine/htmx 동작이라 속성·소스까지만 본다. PLATFORM 전용(App.tsx RequirePlatform): 페이지는 RP 를 홈으로 보내고 모든 조각·동작은 403(TC `platformOnly`).

### AuditChainPage.test.tsx (39) · backfillGuard (2)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 카드 헤더 캡션은 제목 옆 한 줄, 플랫폼 행 아바타 첫 글자 | AC 1 | source ✅ | TC `overviewIntact` | 미확인 |
| recentRuns 없으면 스트립 열 숨김 / 있으면 14회 요약 / nextRunAt 유무 | AC 2·3·4 | 차이: 서버 DTO(AuditChainOverview)에 recentRuns·nextRunAt 이 없다 — 스트립·"다음 검증"은 그리지 않는다(원본의 "없을 때" 분기와 같다) | TC `overviewIntact`("요청 시") | — |
| 60초 자동 검증을 주장하지 않는다 | AC 5 | source ✅ (검증 방식 "요청 시") | TC `overviewIntact` | — |
| incident 0건이면 all-clear 빈 상태 | AC 6 | source ✅ | TC `overviewIntact` | 미확인 |
| incident 목록 실패는 빈 상태로 오인시키지 않는다 | AC 7 | source ✅ / 차이: "마지막으로 조회된 목록" 은 서버가 이전 목록을 몰라 보이지 않는다(오류 알림만) | TC `incidentListFailureIsNotEmpty` | — |
| overview 실패 시 오류 상태 + 다시 시도 | AC 8 | source ✅ (422 + LoadError, 원문 없음) | TC `firstLoadFailure` | 미확인 |
| 위변조면 bad 배너 → 생성 다이얼로그 → createIncident(tenantId) | AC 9 | source ✅ | TC `tamperedBannerAndBackfillDisabled`,`createDialogMarkup`,`createIncidentOk` | 미확인 |
| Incident 해결은 메모가 필수 | AC 10 | source ✅ (버튼 비활성은 JS, 서버도 공백·1025자 거절) | TC `incidentTable`,`resolveRequiresNote`,`resolveOk` | 미확인(Alpine) |
| 위변조만 세그먼트 | AC 11 | source ✅ / JS: 행 x-show | TC `viewFilterSurvivesReload` | 미확인 |
| 월간 보고서 다이얼로그: tenant 체크박스 없이 포함 목록 | AC 12 | source ✅ | TC `monthlyReportLinkPointsToExistingApi` | 미확인 |
| 지금 검증은 읽기 전용(overview 만, backfill 안 부름) | AC 13 | source ✅ | TC `verifyRequestsAreSyncedAndTimeboxed`(GET overview) | — |
| 체인 백필은 더 보기 메뉴 안 L3 확인(취소 기본 포커스·이해 체크) 후에만 | AC 14 | source ✅ (confirmDialog L3 ack, X-Confirm 필수) | TC `backfillDialogMarkup`,`backfillGuardBlocksSecondRun` | 미확인 |
| 백필 실패는 다이얼로그 안 인라인, 열린 채 | AC 15 | source ✅ (confirmError) | TC `backfillFailureIsInline` | 미확인 |
| 위변조면 백필 메뉴 비활성 + 사유, 눌러도 안 열림 | AC 16 | source ✅ (다이얼로그 자체를 그리지 않음) | TC `tamperedBannerAndBackfillDisabled` | — |
| 다이얼로그가 열린 사이 위변조가 나타나면 닫힘 / 해소돼도 다시 안 열림 | AC 17·18 | 차이: 응답이 루트를 통째로 교체해 다이얼로그가 사라진다(재검증 응답이 곧 새 상태). 위변조면 다이얼로그를 그리지 않으므로 해소 뒤에도 자동으로 열리지 않는다 | TC `backfillDialogMarkup` | 미확인 |
| 월간 보고서: 시작일>종료일이면 인라인 오류 + 생성 비활성, 고치면 해제 | AC 19 | source ✅ / JS: Alpine rangeError(문구 포함) | TC `monthlyReportLinkPointsToExistingApi` | 미확인 |
| 검증 버튼은 "지금 검증" 하나 | AC 20 | source ✅ | TC `overviewIntact` | — |
| 재검증 중에도 표 유지, "검증 중…"(비활성, 가짜 진행률 없음) | AC 21 | source ✅ / JS: busy | TC `verifyRequestsAreSyncedAndTimeboxed`(data-chain-verify·hx-disabled-elt) | 미확인 |
| 다시 검증이 실패해도 이전 결과 유지 + 경고 | AC 22 | source ✅ (HX-Retarget #chain-warn) | TC `reverifyFailureKeepsPreviousResult` | 미확인 |
| 위변조 tenant 가 여럿이면 모두 나열, 행마다 생성 | AC 23 | source ✅ | TC `createButtonRules`,`tamperedBannerAndBackfillDisabled` | — |
| OPEN incident 가 있는 tenant 는 "Incident 처리 중" | AC 24 | source ✅ | TC `createButtonRules` | — |
| 배너의 "감사 로그 열기" → 그 tenant 감사 로그 탭 | AC 25 | source ✅ | TC `tamperedBannerAndBackfillDisabled` | — |
| 처음 불러오는 동안 Skeleton | AC 26 | source ✅ | TC `pageIsSkeleton` | 미확인 |
| incident 목록이 오기 전에는 빈 상태·활성 생성 버튼 없음 | AC 27 | 차이: 서버는 두 호출이 끝난 뒤 한 번에 그린다(목록 대기 상태 없음). 목록 실패면 생성 비활성 | TC `incidentListFailureIsNotEmpty` | — |
| Incident 생성 더블 클릭은 한 번만 요청 | AC 28 | source ✅ (hx-sync drop + busy 비활성) / JS | TC `createDialogMarkup` | 미확인 |
| Incident 생성 실패는 알리고 버튼을 다시 쓸 수 있다 | AC 29 | source ✅ (422 인라인, busy 해제) | TC `createFailureInlineNoRaw` | 미확인 |
| 백필 가드(A1): 정상 → 재검증 실패 / 검증 중 / 다이얼로그가 열린 사이 실패 | AC 30–32 | source ✅ (`blocked` = busy ∥ failed → 다이얼로그 x-if 접힘, 메뉴 클릭은 사유 토스트) | TC `backfillDialogMarkup` | 미확인 |
| 해결 실패는 인라인(다이얼로그·메모 유지) | AC 33 | source ✅ | TC `resolveFailureInline` | 미확인 |
| 생성 실패 원문([CODE] message)은 보이지 않는다 | AC 34 | source ✅ | TC `createFailureInlineNoRaw` | — |
| 생성 422(위변조 아님)는 다이얼로그 안, 토스트 없음 | AC 35 | source ✅ | TC `createNotTamperedInline` | — |
| 해결 404: 메모 유지·해결 비활성·닫기·목록 새로고침 | AC 36 | source ✅ (resolve-gone 이벤트, OOB 카드) | TC `resolveNotFoundInline` | 미확인 |
| tenant 검증 실패 토스트는 쉬운 문구, 원문은 "자세히"만 | AC 37 | source ✅ (detail 은 HTTP 상태만) | TC `verifyTenantFailure` | — |
| PDF 생성 실패 토스트에 원문이 본문으로 나오지 않는다 | AC 38 | source ✅ (공통 다운로드 도우미 + `data-download-error`="PDF 생성 실패") | TC `monthlyReportLinkPointsToExistingApi` | 미확인(JS) |
| 위변조가 있는 상태에서 onConfirm 호출돼도 backfill 안 부름 | BG 1 | source ✅ (서버 가드: 요청마다 개요 재검증) | TC `backfillGuardBlocksTampered` | — |
| 정상이던 체인 재검증 실패 후 onConfirm → backfill 안 부름 | BG 2 | source ✅ | TC `backfillGuardBlocksWhenVerifyFails` | — |
| (추가) 중복 실행·확인 헤더 없는 호출 거절 | 브리프 | source ✅ (X-Confirm 없으면 400, 위변조가 생기면 두 번째 실행 거절) | TC `backfillGuardBlocksSecondRun` | — |
| (추가) 생성·해결 409 → 경고 토스트(이미 진행 중/이미 해결됨) | AC 9 | source ✅ | TC `incidentConflictShowsToast`,`resolveConflictToast` | — |
| (추가) tenant 검증 토스트(검증 완료 / 위변조 의심) | handleVerifyTenant | source ✅ | TC `verifyTenantIntact`,`verifyTenantTampered` | — |
| (추가) 이름·이메일·tenant 이름 이스케이프 | 계약 | source ✅ | TC `escapesUserValues` | — |
| (추가) 월간 보고서 링크는 기존 API, 기본 기간 KST 직전 달 | 브리프 | source ✅ | TC `monthlyReportLinkPointsToExistingApi` | 미확인 |

### Task 15 메모 (TSX 와의 차이)

- 상태가 서버에 없어 사건 목록 재조회(409 외의 실패 경로)는 사건 카드만 OOB 로 새로 그리고, 위변조 배너는 다음 개요 때 맞춰진다. 409 와 성공은 개요 전체를 다시 검증해 교체한다(원본의 reverify 와 같음; 409 는 원본이 목록만 다시 읽던 곳 — 서버가 위변조 행 목록을 모르므로 전체 재검증).
- Incident 목록 "다시 시도"는 개요 전체를 다시 읽는다(배너의 생성 버튼이 목록 결과에 달려 있다).
- 백필 가드는 TSX 에서는 클라이언트 전용(`backfillAllowed`)이다. 서버에서도 지키려고 요청마다 `overview(24)` 로 재검증한다(위변조·실패면 거절). 동시 실행 직렬화는 서비스의 DB 락이 한다 — 컨트롤러에 별도 락을 만들지 않았다.
- 월간 보고서 PDF 는 공통 다운로드 도우미(`data-csv-download`)를 재사용한다(실패 토스트 제목은 링크의 `data-download-error`, 진행은 `csv-download` 이벤트). 도우미에는 타임아웃이 없다(원본은 120초).
- 다이얼로그 요청 요소(래퍼)는 다이얼로그 밖이지만 래퍼의 `reqBusy` 를 dialog 의 `busy` 로 비춰(x-effect) 진행 중에는 Escape·스크림·취소가 막힌다(원본 onClose={busy ? noop : onClose}). 성공(2xx)이면 래퍼가 닫는다.
- `verify-result.html` 은 만들지 않았다 — 한 tenant 검증은 토스트 + 루트 교체라 별도 조각이 없다.

## §설정 (Task 16a, 소스 수준 점검 — 틀·내 계정·시스템. Admin 사용자·보안 정책·MDS 는 16b)

SPC = `SettingsPageControllerTest`, ATC = `AccountTabControllerTest`, SIT = `SystemInfoTabControllerTest`, QST = `QrSvgTest`. 브라우저: 전부 미확인(R8).

### SettingsPage.test.tsx (6)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 루트 .page, 제목·설명 | SP 1 | source ✅ | SPC `platformSeesAllTabs` | 미확인 |
| PLATFORM 탭 5개, '설정 탭', 내 계정 선택 | SP 2 | source ✅ | SPC `platformSeesAllTabs` | 미확인 |
| RP 는 내 계정 탭만, 가입 요청 조회 안 함 | SP 3 | source ✅ | SPC `rpAdminSeesOnlyAccountTab` | — |
| Admin 사용자 탭 가입 요청 배지(tabs__count) | SP 4 | source ✅ / 차이: 실패 시 숨김(원본과 같음), 탭 안 변경 뒤 갱신은 16b | SPC `pendingBadge` | — |
| 탭 클릭으로 내용 교체 | SP 5 | source ✅ / 차이: hx-get 조각 + push-url, 활성 표시는 ui.js — JS 브라우저 확인 필요 | SPC `tabLinksAreFragmentRequests` | 미확인 |
| ?tab= 진입, RP 는 무시하고 내 계정 | SP 6 | source ✅ (resolveTab 그대로) | SPC `settingsTabResolveMatchesOriginal`, `platformTabQueryLoadsLazily`, `rpAdminSeesOnlyAccountTab` | — |
| (추가) 리터럴 탭 매핑 우선, 미구현 PLATFORM 탭은 404 | 계약 | source ✅ | SPC `literalTabMappingWinsOverGeneric`, `genericTabFragment` | — |

### AccountTab.test.tsx (15) · RecoveryCodesModal.test.tsx (7)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 꺼짐: 일러스트(장식) + 켜기 카드 | AT 1 | source ✅ | ATC `offShowsEnrollCard` | 미확인 |
| 진행 표시 QR 스캔 → 코드 확인 → 복구 코드 다이얼로그, 켜기 창 닫힘, 카드 켜짐 | AT 2 | source ✅ / 진행 표시·6자리 활성은 Alpine(mfaEnroll) — JS 브라우저 확인 필요. 카드는 응답 OOB | ATC `enrollRendersDialog`, `confirmShowsRecoveryCodes` | 미확인 |
| confirm 후 getMe 실패해도 복구 코드 표시 | AT 3 | source ✅ / 차이: 토스트가 응답당 하나라 성공 토스트 대신 새로고침 실패 경고만 | ATC `confirmMeReloadFailureStillShowsCodes` | — |
| 잘못된 코드: 인라인 + 코드 지움 + 첫 칸 포커스, 토스트 없음, 다시 입력하면 지움 | AT 4 | source ✅ (422 + HX-Retarget) / 지움·포커스·재입력 해제는 JS | ATC `confirmInvalidCodeInline` | 미확인 |
| 네트워크 오류는 쉬운 문구 인라인 | AT 5 | JS — 브라우저 확인 필요(status 0 → netErr) | — | 미확인 |
| 403 은 권한 문구 인라인 | AT 6 | source ✅ | ATC `confirmOtherFailuresInline` | — |
| 429(끄기)는 요청 과다 문구 인라인 | AT 7 | source ✅ | ATC `disableFailureInline` | — |
| 취소하면 닫히고 confirm 안 부름 | AT 8 | JS — 브라우저 확인 필요(닫으면 래퍼째 제거) | — | 미확인 |
| 켜짐: badge--success, 선택 줄 숨김, 위험 영역 | AT 9 | source ✅ | ATC `onShowsDangerZone` | — |
| 등록일은 서버 값이 있을 때만, 보관 안내 | AT 10 | source ✅ / 차이: MeView 에 mfaEnrolledAt 없음 → 늘 숨김 | ATC `onShowsDangerZone` | — |
| 끄기 alertdialog, 6자리 sanitize 후 disable(code), 꺼짐 | AT 11 | source ✅ / sanitize·버튼 활성은 JS(mfaDisable) | ATC `onShowsDangerZone`, `disableSuccess` | 미확인 |
| 끄기 코드 틀림: 인라인, 값 유지·전체 선택·aria-invalid, 토스트 없음 | AT 12 | source ✅ (422 #mfa-disable-err) / 선택·포커스는 JS | ATC `disableFailureInline` | 미확인 |
| 끄기 요청 중 읽기 전용, 늦은 실패가 값 덮지 않음, 취소 비활성 | AT 13 | JS — x-bind:readonly·busy 미러 | ATC `onShowsDangerZone`(속성) | 미확인 |
| 복구 코드 표시 중 이동·새로고침 막기(문구) | AT 14 | JS — $store.dirty report + notice(recoveryCodes) | — | 미확인 |
| 복사: 코드 줄바꿈 연결 / 실패 시 수동 안내 | RC 1·2 | JS — 화면의 코드로 copyToClipboard | ATC `confirmShowsRecoveryCodes`(문구) | 미확인 |
| 코드 10개, 01–10, 목록 이름 | RC 3 | source ✅ | ATC `confirmShowsRecoveryCodes` | — |
| 체크 전 닫기 비활성 '닫기 · 체크 필요' | RC 4 | source ✅ / 활성 전환 JS | ATC `confirmShowsRecoveryCodes` | 미확인 |
| alertdialog, Escape·스크림으로 안 닫힘 | RC 5 | source ✅ (dismissible=false, closeOnScrim=false) | ATC `confirmShowsRecoveryCodes` | 미확인 |
| 일러스트 장식 | RC 6 | source ✅ | ATC `confirmShowsRecoveryCodes` | — |
| 다운로드 파일명 sanitize | RC 7 | source ✅ (RecoveryCodeFilename, KST 날짜) / Blob 다운로드는 JS | ATC `confirmShowsRecoveryCodes` | 미확인 |
| (추가) 코드는 응답 본문에만(세션·플래시·헤더·토스트 없음, no-store) | 브리프 | source ✅ | ATC `recoveryCodesNotStoredInSession` | — |
| (추가) QR 은 인라인 data URI, 외부 참조 없음 | 브리프 R4 | source ✅ | ATC/QST `qrIsInlineDataUriNoExternal`, QST `decodesBack` | — |
| (추가) enroll 실패 토스트 '2단계 인증을 시작하지 못했습니다' | AccountTab.tsx | source ✅ | ATC `enrollFailureToast` | — |

### SystemInfoTab.test.tsx (13)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 버전·KST 배포·환경·메모·가동 일수 | SI 1 | source ✅ | SIT `rendersInfo` | — |
| 긴 버전 말줄임(title), kv, KST | SI 2 | source ✅ | SIT `longVersionTruncates` | — |
| deployedAt null → 정보 없음 | SI 3 | source ✅ | SIT `deployedMissingOrInvalid` | — |
| 실패 시 오류 표시 / LoadError(role=alert, 복사) / 일러스트 + 다시 시도 | SI 4·10·12 | source ✅ / 차이: 원문 없음(공유 계약) | SIT `loadFailure` | 미확인 |
| 상태별 배지 클래스 | SI 5 | source ✅ | SIT `statusBadgesAndOverall` | — |
| 전체 상태 = 가장 나쁜 상태 | SI 6 | source ✅ | SIT `statusBadgesAndOverall` | — |
| latency 없으면 숨김 | SI 7 | 차이: 서버 DTO 에 latencyMs 없음 — 늘 숨김 | SIT `statusBadgesAndOverall` | — |
| KPI 라벨 한국어 | SI 8 | source ✅ | SIT `rendersInfo` | — |
| 로딩 중 Skeleton | SI 9 | source ✅ (탭 영역 공통 스켈레톤) | SPC `platformTabQueryLoadsLazily` | 미확인 |
| 가동 일수 없음 → — | SI 11 | source ✅ | SIT `uptimeMissing` | — |
| 깨진 배포 시각 → — | SI 13 | source ✅ | SIT `deployedMissingOrInvalid` | — |

## §라이선스 (Task 16a, 소스 수준 점검)

LPC = `LicensePageControllerTest`. PLATFORM 전용: RP 는 자기 tenant 로 302, 다시 확인 조각 403(LPC `licenseRpAdminRedirected`).

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 상태 배지(원문 툴팁) + 상대 시간 | LP 1 | source ✅ | LPC `headline` | — |
| 만료까지 일수, 진행 바 없음 | LP 2 | source ✅ | LPC `daysOnly` | — |
| 상세 항목 순서, ID 복사, 기능 태그, 유예 숨김 | LP 3 | source ✅ | LPC `detailCard` | — |
| stack-5, kv--rows | LP 4 | source ✅ | LPC `detailCard` | — |
| NETWORK_GRACE | LP 5 | source ✅ | LPC `networkGrace` | — |
| null 값 —, DEAD 카드 | LP 6 | source ✅ | LPC `deadWithNulls` | — |
| WARNING 카드 / VALID 카드 없음 | LP 7·8 | source ✅ | LPC `warningAndValid` | — |
| SaaS 안내 | LP 9 | source ✅ | LPC `saas` | — |
| 조회 실패 LoadError + 다시 시도 | LP 10 | source ✅ / 차이: 원문('boom') 은 보이지 않음(공유 계약) | LPC `loadFailure` | 미확인 |
| 다시 확인 중 기존 정보 유지, '확인 중…' 비활성, 중복 없음 | LP 11 | source ✅ (hx-disabled-elt, hx-sync drop, htmx-request 클래스로 문구 전환) | LPC `recheckSuccess` | 미확인 |
| 다시 확인 실패: 정보 유지 + 인라인 경고 | LP 12 | source ✅ (HX-Retarget #license-recheck-alert) | LPC `recheckFailureKeepsPrevious` | 미확인 |
| 처음 로딩 Skeleton | LP 13 | 차이: 서버가 그려서 보내므로 로딩 상태 없음 | — | — |

## §설정 (Task 16b, 소스 수준 점검 — Admin 사용자·보안 정책·MDS)

AUT = `AdminUsersTabControllerTest`, SEC = `SecurityPolicyTabControllerTest`, MDT = `MdsTabControllerTest`. 브라우저: 전부 미확인(R8). 키 관리(KeyMgmtController)는 원본 설정 화면에 없어 이 탭들에 두지 않았다. `addTenant`/`removeTenant` 도 원본 AdminUsersTab 이 부르지 않아 화면이 없다.
RP_ADMIN 은 세 탭의 모든 엔드포인트(조각·변경·검증)에서 403, REST 미호출: AUT `rpAdminForbiddenEverywhere`, SEC `rpAdminForbidden`, MDT `rpAdminForbidden`. 리터럴 탭 매핑이 16a 일반 매핑보다 우선: AUT/SEC/MDT `literalWinsOverGeneric`.

비교·읽기 차이 메모: (1) 보안 정책 CORS 목록은 저장 바가 순서 없이 비교한다(TSX sameList 는 순서 비교) — 삭제 되돌리기가 목록 끝에 다시 넣어도 변경 0건이 되게 한 것이고, 순서만 바꾸는 UI 는 없다. 저장 때는 화면 순서 그대로 보낸다. (2) 승인 대화상자 GET 이 `SignupRequestController.list()` 를 한 번 더 읽어 요청을 찾는다(원본은 행 데이터를 들고 있음). 없어진 요청이면 404.

### AdminUsersTab.test.tsx (40)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 대기 요청 카드: 건수 배지·이메일·사유 | AU 71 | source ✅ | AUT `signupRequestsCard` | 미확인 |
| 사유 없으면 '요청 사유 없음' | AU 80 | source ✅ | AUT `signupRequestsCard` | 미확인 |
| 대기 요청 0건 안내 | AU 86 | source ✅ | AUT `signupEmptyAndFailure` | 미확인 |
| 대기 건수를 탭 줄 배지에 알림 | AU 93 | source ✅ / 차이: 상위 상태 대신 승인·거절 응답의 OOB `#settings-tab-admins-count`(탭 줄 개수 자리, tabs.html 에 추가). 전체 화면 렌더 때는 SPC 가 계산 | AUT `approveRp`, `reject`, `tabStripCountSlot` | 미확인 |
| 승인 대화상자: 요청자 표시 + 역할·tenant 제출 | AU 99 | source ✅ / 차이: 대화상자를 GET 으로 받아 슬롯에 연다(열 때 tenant 를 지금 읽음) | AUT `approveDialog`, `approveRp` | 미확인 |
| RP Admin 인데 tenant 0개 → 오류, API 안 부름 | AU 118 | source ✅ / 차이: 서버도 같은 규칙으로 422 + 대화상자 안 오류(HX-Retarget) | AUT `approveValidation` · 스텁(signupApprove 제출 막기) / JS — 브라우저 확인 필요 | 미확인 |
| tenant 검색으로 목록 좁힘 | AU 129 | source ✅ / 차이: 이미 그려진 목록을 Alpine 이 거름 | 스텁(signupApprove.matches·noMatch) / JS — 브라우저 확인 필요 | 미확인 |
| Platform Operator → tenant 없이 승인 | AU 142 | source ✅ / 차이: 서버가 tenant 를 무시하고 빈 목록으로 호출 | AUT `approvePlatformIgnoresTenants` | 미확인 |
| 거절은 L1 확인(기본 포커스 취소), 확인 라벨 '거절', 확인 시 호출 | AU 155 | source ✅ | AUT `rejectDialog`, `reject` | 미확인 |
| 초대 버튼 없음 | AU 172 | source ✅ | AUT `signupRequestsCard` | 미확인 |
| 승인 실패(409 외)는 대화상자 유지, 목록 다시 읽지 않음 | AU 179 | source ✅ / 차이: 오류 상태 + 토스트, 본문 없음 → htmx 가 스왑하지 않아 대화상자 유지 | AUT `approveConflictAndFailure` | 미확인 |
| 승인 409 → 닫고 목록 다시 읽기(경고 토스트) | AU 192 | source ✅ / 차이: 200 + 목록 교체 + 자리 비움 OOB | AUT `approveConflictAndFailure` | 미확인 |
| 거절 실패(409 외)는 확인창 안 인라인 오류, 유지 | AU 205 | source ✅ / 차이: confirmError(5xx 문구) | AUT `rejectConflictAndFailure` | 미확인 |
| 거절 409 → 안내 후 닫고 요청 다시 읽기 | AU 218 | source ✅ | AUT `rejectConflictAndFailure` | 미확인 |
| 승인 연타해도 한 번만 | AU 229 | source ✅ / 차이: hx-sync=this:drop + 요청 중 submitting(Alpine) | AUT `approveDialog`(hx-sync) · 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 승인 실패 토스트는 서버 원문 대신 쉬운 문구 | AU 245 | source ✅ / 차이: 제목 '승인 실패'(원본 errorToast) | AUT `approveConflictAndFailure` | 미확인 |
| 헤더 요약('N명 · 활성 M명')과 표 열 | AU 265 | source ✅ | AUT `operatorsTable` | 미확인 |
| 세그먼트 PLATFORM/RP_ADMIN/정지/전체 필터 | AU 281 | source ✅ / 차이: 서버가 행에 data-role·data-status 를 달고 Alpine 이 거름 | AUT `operatorsTable`(속성) · 스텁(operatorFilter) / JS — 브라우저 확인 필요 | 미확인 |
| 검색(이메일·ID, 대소문자 무시) + 결과 없음 | AU 303 | source ✅ / 차이: 결과 없음 행은 x-show | 스텁(operatorFilter.match·none) / JS — 브라우저 확인 필요 | 미확인 |
| 세그먼트와 검색 함께 적용 | AU 316 | source ✅ | 스텁(operatorFilter) / JS — 브라우저 확인 필요 | 미확인 |
| 정지·활성화 액션 버튼 | AU 325 | source ✅ | AUT `selfProtectionAndActions`, `suspendCallsRest`, `activate` | 미확인 |
| 로딩 중 Skeleton | AU 343 | 차이: 서버 렌더라 목록 스켈레톤 없음(탭 로딩 자체는 컨테이너 스켈레톤, SPC `platformTabQueryLoadsLazily`) | — | 미확인 |
| 운영자 목록 실패 → LoadError, 건수 '—', 검색 숨김, 다시 시도 | AU 353 | source ✅ / 차이: 검색·세그먼트는 비활성이 아니라 그리지 않음, 다시 시도 = 탭 다시 받기 | AUT `usersFailure` | 미확인 |
| 가입 요청 실패 → LoadError, 대기 '—' | AU 365 | source ✅ | AUT `signupEmptyAndFailure` | 미확인 |
| Tenant 열: 이름 조인·외 N개·전체 tenant | AU 373 | source ✅ | AUT `operatorsTable` | 미확인 |
| tenants 못 읽으면 id 꼬리, 전체 id title | AU 382 | source ✅ | AUT `tenantNamesUnavailable` | 미확인 |
| 다시 읽기 실패 → 데이터 유지 + 경고 | AU 391 | source ✅ / 차이: 변경 뒤 다시 읽기에 실패하면 화면을 스왑하지 않고(HX-Reswap none) 경고 자리(#au-users-warn / #au-signups-warn)만 OOB. 행 액션은 비활성이 되지 않는다(정지는 확인 때 서버가 목록을 다시 검증) | AUT `suspendThenReloadFailsKeepsDom`, `rejectThenReloadFailsKeepsDom`, `approveThenPartialReloadFailure` | 미확인 |
| 낡은 운영자 목록 → 행 액션 비활성·다시 시도 | AU 403 | 차이: 해당 없음(항상 방금 읽은 목록). 정지는 확인 때 서버가 목록을 다시 읽어 상태를 검증 | AUT `suspendBlockedNoRest` | 미확인 |
| 낡은 가입 요청 → 승인·거절 비활성·다시 시도 | AU 421 | 차이: 해당 없음(항상 방금 읽은 목록). 이미 처리됐으면 409 경고 + 갱신 | AUT `approveConflictAndFailure`, `rejectConflictAndFailure` | 미확인 |
| 정지… → L1 확인 → 정지 → 되돌리기 토스트 → 활성화 API | AU 447 | source ✅ / 차이: 토스트 '되돌리기'는 window 이벤트 `au-undo-suspend`(toastHost 확장, 기존 href 액션 호환)를 운영자 카드가 받아 `POST …/activate?undo=1` 을 보낸다(정지 상태일 때만 호출 — 아니면 '되돌릴 수 없습니다' 경고) | AUT `suspendDialog`, `suspendCallsRest`, `undoSuspend` · 스텁(toastHost.run·operatorFilter.onUndo) / JS — 브라우저 확인 필요 | 미확인 |
| 정지 실패는 다이얼로그 안 인라인, 유지, 되돌리기 없음 | AU 467 | source ✅ | AUT `suspendRestFailure` | 미확인 |
| 본인·마지막 활성 PLATFORM 은 정지 비활성 + 사유 | AU 479 | source ✅ / 차이: 서버도 확인 때 같은 규칙을 한 번 더 본다(REST 안 부름, 409) | AUT `selfProtectionAndActions`, `lastPlatformBlocked`, `suspendBlockedNoRest`, `units` | 미확인 |
| 활성 PLATFORM 이 둘 이상이면 PLATFORM 도 정지 가능 | AU 490 | source ✅ | AUT `selfProtectionAndActions` | 미확인 |
| 활성화 처리 중 '활성화 중…' 비활성, 연타 한 번 | AU 497 | source ✅ / 차이: 라벨은 안 바뀐다(hx-disabled-elt 로 비활성 + hx-sync=this:drop) | AUT `selfProtectionAndActions`(속성) / JS — 브라우저 확인 필요 | 미확인 |
| 되돌리기 연타·실패·stale(511·528·611·625) | AU 511/528/611/625 | source ✅ / 차이: 실패·stale 은 서버가 원본 문구로 처리. 연타 가드는 서버 직렬화가 없다(같은 활성화 요청이 두 번 가면 두 번째는 REST 가 거절할 수 있음) | AUT `undoSuspend` / JS — 브라우저 확인 필요 | 미확인 |
| 활성화 실패 토스트도 쉬운 문구 | AU 543 | source ✅ / 차이: 제목 '활성화하지 못했습니다' | AUT `activate` | 미확인 |
| 다른 운영자의 처리가 겹쳐도 행 표시는 독립 | AU 553 | source ✅ / 차이: hx-sync 는 버튼별이지만 성공 응답은 #au-users 전체를 교체 | AUT `selfProtectionAndActions`(속성) / JS — 브라우저 확인 필요 | 미확인 |
| 열려 있던 정지 다이얼로그는 목록이 낡아지면 정지하지 않고 안내 | AU 571 | source ✅ / 차이: 서버가 확인 때 목록을 읽어 판정 | AUT `suspendBlockedNoRest` | 미확인 |
| 이미 처리 중인 운영자는 정지하지 않고 성공 토스트 없음 | AU 587 | source ✅ / 차이: 확인 버튼 hx-sync=this:drop(서버 측 직렬화는 없음) | AUT `suspendDialog`(속성) / JS — 브라우저 확인 필요 | 미확인 |
| 정지 다이얼로그는 목록 다시 읽기를 기다리지 않고 닫힘 | AU 641 | 차이: 응답이 갱신된 목록을 함께 돌려주고 #au-users 교체로 다이얼로그가 사라진다 | AUT `suspendCallsRest` | 미확인 |

### SecurityPolicyTab.test.tsx (17)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 카드 헤더 캡션 '모든 콘솔 운영자에게 적용' | SP 33 | source ✅ | SEC `rendersPolicy` | 미확인 |
| .set 3행 + 변경 없으면 SaveBar 없음 | SP 38 | source ✅ / 차이: SaveBar 는 변경 0건이면 x-show 로 숨김 | SEC `rendersPolicy` / JS — 브라우저 확인 필요 | 미확인 |
| dirty → SaveBar(건수·요약), 저장하면 update 후 사라짐 | SP 46 | source ✅ / 차이: 저장 = 폼 PUT, 성공 응답이 탭 전체를 새 값으로 다시 그림 | SEC `savesAndRerendersTab`, `rendersPolicy` / JS — 브라우저 확인 필요 | 미확인 |
| CORS: Enter 추가(새 칩 강조), 중복 안내, 취소 = 서버 값 | SP 65 | source ✅ / 차이: 중복·형식 검증은 서버 왕복(origin-check). 취소 = 서버 재조회(`?revert=1`, 실패하면 입력 유지 + '되돌리지 못했습니다') | SEC `originCheck`, `revertRefetch` · 스텁(saveBar.revert) / JS — 브라우저 확인 필요 (Task 3 chipInput 스텁) | 미확인 |
| dirty 상태 탭 이동은 이탈 확인 | SP 83 | source ✅ | SPC 탭 링크 data-guard-dirty + `$store.dirty` / JS — 브라우저 확인 필요 | 미확인 |
| 로드 실패 → 기본값 대신 LoadError, 입력칸·저장 숨김, 다시 시도 | SP 93 | source ✅ | SEC `loadFailure` | 미확인 |
| timeout 1–1440 인라인 오류, 저장해도 API 안 부름, 입력칸 포커스 | SP 104 | source ✅ / 차이: 서버도 422 + 필드 오류 | SEC `idleValidation` · 스텁(securityPolicy.timeoutErr·onSubmit) / JS — 브라우저 확인 필요 | 미확인 |
| origin 형식 오류면 추가하지 않고 입력값 남김 | SP 123 | source ✅ / 차이: origin-check 가 오류 문구 | SEC `originCheck`, `corsValidation` / JS — 브라우저 확인 필요 | 미확인 |
| origin 소문자 저장, 대소문자 중복 안내 | SP 134 | source ✅ | SEC `originCheck` | 미확인 |
| 콘솔 origin 지우면 경고 + 삭제 되돌리기, 저장하면 L2 확인 | SP 145 | source ✅ / 차이: L2 확인은 422 + 다이얼로그(저장 바가 2xx 를 저장됨으로 세지 않게), 확인 = confirmLockout=1 PUT | SEC `removingConsoleAsksConfirmation`, `confirmedAndCaseInsensitive`, `consoleWarningConfig` · 스텁 / JS — 브라우저 확인 필요 | 미확인 |
| 콘솔 origin 경고 문구는 실제 영향을 말한다 | SP 170 | source ✅ | SEC `consoleWarningConfig` | 미확인 |
| 저장 중 입력 잠금, 저장은 한 번만 | SP 184 | source ✅ / 차이: 저장 요청 중 폼을 inert 로 잠그고 모든 결과에서 푼다(확인 PUT 은 다이얼로그 스크림이 막는다). 중복 전송은 hx-sync=this:drop | SEC `formHooks` · 스텁(securityPolicy.saving) / JS — 브라우저 확인 필요 | 미확인 |
| 저장 실패는 쉬운 문구 토스트, 입력 그대로 | SP 201 | source ✅ | SEC `saveFailure` | 미확인 |
| 삭제 되돌리기는 64개면 거부·안내 | SP 210 | source ✅ | 스텁(securityPolicy.restore) / JS — 브라우저 확인 필요 | 미확인 |
| 저장 전 목록 전체 검증(64개 초과) | SP 224 | source ✅ / 차이: 서버 422 + 인라인 오류(확인창·REST 없음) | SEC `corsValidation` | 미확인 |
| 대문자로 저장된 콘솔 origin 도 지우면 경고 | SP 235 | source ✅ | SEC `consoleWarningConfig`, `removesConsoleUnit`, `confirmedAndCaseInsensitive` | 미확인 |
| 서버에 1440 초과가 저장돼 있어도 손대지 않으면 막지 않음 | SP 245 | source ✅ | SEC `idleBoundariesAndUntouched` · 스텁 | 미확인 |

### MdsStatusTab.test.tsx · api/mdsStatus.test.ts (26)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 실패 행 errorMessage 한 줄 평문(길면 줄임), 전체 title | MD 43 | source ✅ | MDT `failureReasonIsPlain`, `units` | 미확인 |
| 이력 limit=30, 스트립 막대 수 = 이력 수 | MD 59 | source ✅ | MDT `historyTableAndStrip` | 미확인 |
| 헤드라인·KPI·설정 | MD 71 | source ✅ | MDT `headlineKpisSettings` | 미확인 |
| 이력 표 최근 6행, 상태 라벨 한국어 | MD 83 | source ✅ | MDT `historyTableAndStrip`, `rowLabelsAndKst` | 미확인 |
| 최신 실행이 실패면 실패 헤드라인 | MD 94 | source ✅ | MDT `failedHeadlineAndEmpty` | 미확인 |
| 즉시 갱신 → sync 후 상태·이력 다시 읽기 | MD 100 | source ✅ / 차이: 응답이 #mds-root 를 통째로 교체 | MDT `syncSynced` | 미확인 |
| 한 번도 동기화 안 된 설치(version 0, 1970 시드) → 중립 | MD 109 | source ✅ | MDT `neverSynced` | 미확인 |
| fetchedAt 2000년 이전이면 이력 없음 | MD 124 | source ✅ | MDT `neverSynced`, `units` | 미확인 |
| 이력이 오기 전에는 '정상' 헤드라인을 먼저 그리지 않음 | MD 130 | source ✅ / 차이: 서버가 상태·이력을 모두 읽은 뒤 한 번에 렌더(원천적으로 발생 안 함) | MDT `historyOnlyFailure` | 미확인 |
| 상태 조회 실패는 오류 상태 | MD 142 | source ✅ | MDT `statusFailure` | 미확인 |
| 이력 시각은 KST | MD 153 | source ✅ | MDT `rowLabelsAndKst` | 미확인 |
| 신뢰 모드 라벨 + 설명, 원문은 툴팁 | MD 162 | source ✅ | MDT `headlineKpisSettings` | 미확인 |
| 모르는 신뢰 모드는 원문 | MD 170 | source ✅ | MDT `trustModes` | 미확인 |
| 서버가 값을 안 주면 '—' | MD 178 | source ✅ | MDT `trustModes`, `neverSynced` | 미확인 |
| 영어 잔재 'next 갱신 예정' 없음 | MD 189 | source ✅ | MDT `headlineKpisSettings` | 미확인 |
| 로드 실패 LoadError(다시 시도 + 오류 정보 복사), 토스트 없음 | MD 196 | source ✅ | MDT `statusFailure` | 미확인 |
| 로딩 중 Skeleton | MD 205 | 차이: 탭 컨테이너 스켈레톤(SPC `platformTabQueryLoadsLazily`) | — | 미확인 |
| 갱신 뒤 재조회 실패 → 데이터 유지 + 경고 | MD 215 | source ✅ / 차이: 화면 유지(HX-Reswap none) + #mds-warn 경고(다시 시도). 상태·이력 중 하나라도 못 읽으면 같은 처리 | MDT `syncThenReloadFailsKeepsDom`, `syncThenHistoryFailsKeepsDom` | 미확인 |
| 즉시 갱신은 진행 중 재클릭해도 한 번만, 끝나면 다시 가능 | MD 228 | source ✅ / 차이: hx-sync=this:drop + hx-disabled-elt + 120초 제한. 라벨 전환은 .htmx-request CSS | MDT `syncButtonAttrs` / JS — 브라우저 확인 필요 | 미확인 |
| 갱신 예외는 쉬운 문구(원문 없음), 버튼 복구 | MD 241 | source ✅ | MDT `syncException` | 미확인 |
| 갱신 결과가 FAILED 여도 서버 원문 error 를 싣지 않음 | MD 255 | source ✅ | MDT `syncSkippedAndFailed` | 미확인 |
| 이력만 실패 → 이력 영역 LoadError, '정상'·'이력 없음' 아님 | MD 264 | source ✅ | MDT `historyOnlyFailure` | 미확인 |
| 재조회에서 이력만 실패하면 이전 이력 유지 + 경고 | MD 277 | source ✅ / 차이: 갱신 뒤 재조회의 이력 실패는 화면 유지 + 경고. 처음 렌더에서 이력만 실패하면 이력 영역 LoadError | MDT `historyOnlyFailure` | 미확인 |
| 겹친 재조회 순서가 바뀌어도 채택된 최신 이력 유지 | MD 289 | 차이: 해당 없음 — 응답 하나가 화면을 통째로 바꾸고 갱신 버튼은 hx-sync=this:drop | — | 미확인 |
| Object.prototype 이름 같은 신뢰 모드도 원문 | MD 315 | source ✅ | MDT `trustModes` | 미확인 |
| limit 쿼리·스트립 상수 30 (api/mdsStatus.test.ts) | MA 9·13 | source ✅ / 차이: 직접 메서드 호출 history(30) | MDT `historyTableAndStrip` | 미확인 |

## §Tenant 자격 증명 탭 (Task 12, 소스 수준 점검)

약어: CT = `CredentialsTab.test.tsx` 순번, CD = `CredentialDetailDialog.test.tsx` 순번, CH = `credentialHelpers.test.ts`, CC = `CredentialsTabControllerTest`. 브라우저: 미확인(행 클릭 cred-open 이벤트, 다이얼로그 열기·닫기, 확인 입력 게이트, 디바운스는 JS — 브라우저 확인 필요).
공통 차이: 필터(aaguid·상태)·페이징·검색은 원본처럼 클라이언트 상태가 아니라 요청 파라미터 + 서버 렌더(필터는 여전히 "현재 페이지 안"). 로딩 중 건수 "—"·스켈레톤은 목록 교체 방식이라 없음.

### CredentialsTab.test.tsx (21)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 검색어 디바운스 → 서버 q(앞뒤 공백 제거, 첫 페이지) | CT 1 | source ✅ / JS: hx-trigger `input changed delay:300ms`, 새 검색은 page 를 싣지 않아 1페이지 | CC `searchInputContract` | 미확인 |
| 사용자 열 말줄임, 전체 값은 title | CT 2 | source ✅ | CC `rendersColumnsAndCells` | 미확인 |
| credential 이 없으면 빈 상태 일러스트 | CT 3 | source ✅ | CC `emptyStates` | 미확인 |
| 검색 결과 없음 + 필터 지우기 | CT 4 | source ✅ / 차이: 서버가 입력·패널을 OOB 로 비움(`reset=all`) | CC `clientFilterAndChips`,`listResponseOob` | 미확인 |
| + 필터: 토글, 상태 선택 시 칩·현재 페이지 안에서 거름 | CT 5 | source ✅ / 차이: 거르는 일을 서버가 함(요청 1회) | CC `clientFilterAndChips`,`countAndCsvLabels` | 미확인 |
| 상세 이전/다음: 페이지 안·경계에서 이웃 페이지로 | CT 6 | source ✅ / 차이: 서버가 이웃 항목을 미리 계산해 링크로 줌(경계에서만 이웃 페이지 1회 조회), 넘어가면 `list=1` 로 그 페이지 목록도 OOB | CC `detailNavWithinPage`,`detailNavAcrossPages` | 미확인 |
| 회수 L3 — 마지막 8자 입력해야 활성, 상세 푸터에서도 진입 | CT 7 | source ✅ / 차이: 입력 라벨의 mono 강조 없음(조각 라벨은 문자열) | CC `revokeConfirmDialog` / JS(confirmByTyping 게이트) — 브라우저 확인 필요 | 미확인 |
| 회수 처리 중 "회수 중…" 비활성, 연타 한 번 | CT 8 | source ✅ / JS(confirmDialog busy) + hx-sync this:drop | CC `revokeConfirmDialog` | 미확인 |
| 회수 실패는 다이얼로그 안 인라인, 닫히지 않고 토스트 없음 | CT 9 | source ✅ | CC `revokeFailureIsInline` | 미확인 |
| 회수 성공 직후 다이얼로그는 닫히고 새로고침을 기다리지 않음 | CT 10 | source ✅ / 차이: 서버가 새로고침한 목록을 같은 응답에 싣는다 | CC `revokeCallsRestAndRefreshes` | 미확인 |
| 회수 뒤 새로고침 실패 → 목록 자리 오류(+다시 시도) | CT 11 | source ✅ / 차이: 기존 행을 남기지 않고 LoadError | CC `revokeSucceedsEvenIfRefreshFails` | 미확인 |
| 새로고침 실패 시 행 단위 회수 비활성 + 사유 | CT 12 | 해당 없음 / 차이: 서버는 상태를 안 갖는다(오래된 목록이 남지 않음) | — | — |
| 확인 연 뒤 새로고침 실패해도 회수하지 않음 | CT 13 | 해당 없음(위와 같음). 대신 회수 직전 서버가 목록에서 credential 을 다시 확인(없으면 404, REST 호출 없음) | CC `foreignCredentialViaOwnTenantNotTouched` | — |
| 다른 페이지 로딩 중 건수 "—"·페이지 표시 숨김 | CT 14 | 차이: 목록 교체 방식이라 로딩 표시 없음 | — | — |
| 다른 페이지 요청 실패 → 이전 행 없이 전체 오류 + 다시 시도 | CT 15 | source ✅ | CC `loadError` | 미확인 |
| 휴지통은 ACTIVE 행에만 | CT 16 | source ✅ / 서버 뷰에 status 가 없어 항상 ACTIVE(원본 adapt 와 같음) | CC `rendersColumnsAndCells` | 미확인 |
| 검색 placeholder 는 실제 범위, 별칭은 안 된다고 알림 | CT 17 | source ✅ | CC `searchInputContract` | 미확인 |
| 필터 없으면 서버 총 건수, CSV 라벨에 현재 페이지 건수 | CT 18 | source ✅ | CC `countAndCsvLabels`,`csvExport` | 미확인 |
| 로드 실패: LoadError, 건수 —, CSV 비활성, 페이지네이션 없음, 다시 시도 | CT 19 | source ✅ / 차이: 검색 입력은 비활성화하지 않음(입력 유지) | CC `loadError` | 미확인 |
| 로딩 중 Skeleton | CT 20 | 차이: 탭 컨테이너 공통 스켈레톤만(목록 교체 중 없음) | — | — |
| 전송 방식은 한국어 라벨, 원문 title | CT 21 | source ✅ | CC `rendersColumnsAndCells` | 미확인 |

### CredentialDetailDialog.test.tsx (11)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| props 로 즉시 상세 렌더 | CD 1 | source ✅ | CC `detailRenders` | 미확인 |
| 섹션 3개 dlg-sec | CD 2 | source ✅ | CC `detailRenders` | 미확인 |
| 인증 기록 표시 | CD 3 | source ✅ | CC `detailRenders` | 미확인 |
| 이력 없음 안내 | CD 4 | source ✅ | CC `detailEmptyValuesAndEvents` | 미확인 |
| 로드 실패 오류 + 다시 시도 | CD 5 | source ✅ | CC `detailEventsFailureIsLocal` | 미확인 |
| 다시 시도 시 재조회 | CD 6 | source ✅ / 차이: 다시 시도가 상세 전체를 다시 불러옴 | CC `detailEventsFailureIsLocal` | 미확인 |
| position 이 있으면 위치·이전/다음, 핸들러 없으면 비활성 | CD 7 | source ✅ | CC `detailNavWithinPage` | 미확인 |
| position 없으면 이동 UI 없음 | CD 8 | source ✅ | CC `detailLookupFallbackAndNotFound` | 미확인 |
| c 가 바뀌면 그 credential 의 기록 재조회 | CD 9 | source ✅(이동 = 새 상세 요청) | CC `detailNavWithinPage` | 미확인 |
| 섹션 3개와 푸터 좌측 회수(ACTIVE) | CD 10 | source ✅ | CC `detailRenders` | 미확인 |
| 인증 기록 로딩 Skeleton | CD 11 | 차이: 상세 요청에 기록이 함께 와 로딩 단계가 없음 | — | — |

### credentialHelpers.test.ts (3)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| hasClientFilter | CH 1 | source ✅ | CC `countAndCsvLabels`(필터 유무로 건수 문구 분기) | — |
| aaguid 하이픈·대소문자 무시 부분 일치, status 정확히 | CH 2 | source ✅ | CC `countAndCsvLabels`,`clientFilterAndChips` | — |
| authenticatorLabel: MDS 이름 → aaguid 끝 8자 → unknown | CH 3 | source ✅ | CC `rendersColumnsAndCells` | — |

## §Tenant 감사 로그·퍼널 탭 (Task 13, 소스 수준 점검)

약어: AT = `AuditTab.test.tsx` 순번, AH = `auditHelpers.test.ts`, FT = `FunnelTab.test.tsx`, FH = `funnelHelpers.test.ts`, TC = `AuditTabControllerTest`, FC = `FunnelTabControllerTest`, AHT = `AuditHelpersTest`, FHT = `FunnelHelpersTest`. 브라우저: 미확인(행 펼침·다이얼로그 열기, 체인 검증 경과 타이머·verify-run 이벤트, 표로 보기 토글은 JS — 브라우저 확인 필요).
공통 차이: (1) **원본에는 자동 갱신(폴링)이 없다** — 브리프의 `hx-trigger="every …"` 는 두지 않았다(원본이 이긴다). (2) 필터·기간·더 보기는 상태가 아니라 요청 파라미터(서버가 다음 상태 주소를 만든다)이고 카드(`#audit-card`)/루트(`#funnel-root`)만 교체한다. (3) 로딩 중 스켈레톤은 서버 렌더라 없음. (4) 감사 목록 필터 파라미터는 브리프(`action=&from=&to=`)가 아니라 TSX(기간·이벤트 종류 그룹·실패만)를 따른다 — 그룹·실패만은 원본처럼 읽어 온 행 안에서 거른다.

### AuditTab.test.tsx (16)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 기간 세그먼트를 서버 from/to 로 보냄(24시간 → 7일) | AT 1 | source ✅ | TC `windowSentAsRange`,`windowSegmentUrls` | 미확인 |
| 실패만: result 우선, 없으면 실패 목록·이름 규칙 | AT 2 | source ✅ / 차이: REST 에 result 가 없어 이름 규칙만 실제로 쓰임(코드는 result 도 지원) | TC `failuresOnly`, AHT `isFailure` | 미확인 |
| 그룹 칩(원시 코드는 title)·필터 지우기·건수 | AT 3 | source ✅ | TC `groupChips`,`countText` | 미확인 |
| 어느 그룹에도 없는 이벤트는 "기타" | AT 4 | source ✅ | TC `groupChips`, AHT `eventGroup` | 미확인 |
| 날짜 그룹 행 + 행 펼침(diff·payload·JSON 복사·상세 다이얼로그) | AT 5 | source ✅ / 차이: JSON 복사는 토스트 대신 버튼의 "복사됨" 표시(copyBtn 공용), 다이얼로그는 행마다 x-if 로 준비 | TC `rendersTable`,`expandedRow`,`eventDialog` | 미확인 |
| 건수: total 없으면 "최근 N건" | AT 6 | source ✅ / 차이: REST 가 total 을 주지 않아 "기간 · 총 건수" 분기는 도달하지 않음 | TC `countText` | — |
| 더 보기: 50건이면 같은 기간으로 다음 페이지를 붙임 | AT 7 | source ✅ / 차이: stateless 서버라 1..N 쪽을 같은 구간(to)으로 다시 읽음 | TC `loadMoreSameRange` | 미확인 |
| 체인 검증 카드 PLATFORM 전용, 검증 실행 → 결과 배지 | AT 8 | source ✅ | TC `chainCardPlatformOnly`,`verifyIntact`,`verifyTampered` | 미확인 |
| 더 보기 진행 중 기간 변경 → 이전 응답 폐기 | AT 9 | source ✅ / JS 아님: 카드의 `hx-sync="this:replace"` 가 앞 요청을 취소 | TC `pollingKeepsFilters`(hx-sync 속성) | 미확인 |
| 체인 검증 중: 퍼센트 없이 "검증 중…" + 경과, 닫아도 계속, 연타 한 번 | AT 10 | source ✅ / JS: 래퍼 hx-sync `this:drop`, verify-run 이벤트가 요청 요소(래퍼)에서 계속 | TC `chainCardPlatformOnly`(마크업·hx 속성) | 미확인 |
| 체인 검증 실패: 토스트 + 버튼 다시 활성 | AT 11 | source ✅ / 차이: 오류 상태 + 토스트(본문 없음)라 카드 교체 없음 | TC `verifyFailureAndRole` | 미확인 |
| 로드 실패: LoadError, 건수 —, CSV 비활성, 다시 시도 | AT 12 | source ✅ | TC `loadFailure` | — |
| 기간 변경 후 로드 실패 → 이전 행 남기지 않음 | AT 13 | source ✅ (카드 전체 교체) | TC `loadFailure` | — |
| 로딩 중 Skeleton | AT 14 | 차이: 서버 렌더라 없음 | — | — |
| 체인 검증 중 tenant 변경 → 이전 결과 폐기 | AT 15 | 해당 없음: tenant 마다 별도 탭 조각(래퍼가 교체되며 타이머는 isConnected 로 멈춤) | — | 미확인 |
| 경과 시간이 실제로 늘어남 | AT 16 | JS — 브라우저 확인 필요 (Alpine begin()/setInterval) | TC `chainCardPlatformOnly`(경과 표시 마크업) | 미확인 |

### auditHelpers.test.ts (8) · 추가

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| windowRange | AH 1 | source ✅ | AHT `windowRange` | — |
| isFailure | AH 2 | source ✅ | AHT `isFailure` | — |
| eventTone | AH 3 | source ✅ | AHT `eventTone` | — |
| payloadDiff | AH 4 | source ✅ | AHT `payloadDiff` | — |
| diffValue | AH 5 | source ✅ | AHT `diffValue` | — |
| dayLabel (KST 오늘/어제/날짜) | AH 6 | source ✅ (공용 KstFormat) | AHT `dayLabel` | — |
| eventGroup | AH 7 | source ✅ | AHT `eventGroup` | — |
| (추가) JSON.stringify(v, null, 2) 모양 | — | source ✅ | AHT `pretty` | — |
| (추가) CSV: BOM·헤더·이스케이프·수식 주입·파일명 | csvExport.ts | source ✅ / 차이: 서버 `CsvWriter`, 현재 화면과 같은 필터·구간의 행 | TC `csvMatchesOriginalFormat`,`csvNeutralizesFormulas` | — |
| (추가) RP_ADMIN 다른 tenant → 탭·카드·검증·CSV 403 | 계약 | source ✅ | TC `crossTenantForbidden` | — |
| (추가) 사용자 값(action·행위자·대상·payload) 이스케이프 | 계약 | source ✅ | TC `escapesUserControlledValues` | — |

### FunnelTab.test.tsx (7) · funnelHelpers.test.ts (7)

| 체크 항목 | 원본 | 결과 | JUnit | 브라우저 |
|---|---|---|---|---|
| 7d KPI: 전체 전환율, 직전 7일 대비(30일 series), 최대 이탈, 전환율 칩 | FT 1 | source ✅ | FC `funnelPercentagesMatchHelper` | — |
| 이벤트 타입 범례는 본문 글꼴 | FT 2 | source ✅ | FC `distributionCard` | — |
| 24h·30d 는 직전 기간 대비·30일 비교 조회 없음 | FT 3 | source ✅ | FC `noComparisonOutside7d` | — |
| 기간 전환 중 이전 숫자 숨김 | FT 4 | 차이: 서버 렌더 교체(이전 숫자는 응답 전까지 남음, hx-sync replace 로 늦은 응답이 덮지 않음) | FC `windowSegment`(hx 속성) | 미확인 |
| 표로 보기 + 일별 시리즈 CSV | FT 5 | source ✅ / JS: x-show 토글 / 차이: CSV 는 서버(kind=series) | FC `seriesCard`,`csvExports` | 미확인 |
| 조회 실패 LoadError·내보내기 비활성·다시 시도 | FT 6 | source ✅ | FC `loadFailure` | — |
| 기간 전환 후 실패 → 이전 숫자 대신 LoadError | FT 7 | source ✅ (루트 전체 교체) | FC `loadFailure` | — |
| funnelSummary 3 · priorPeriodDelta 3 · format 1 | FH 1–7 | source ✅ (toFixed 와 같은 반올림) | FHT `summary`,`noDrop`,`allZero`,`delta`,`deltaNegative`,`deltaNull`,`format` | — |
| (추가) 막대 높이·너비는 숫자만 th:style, 색은 클래스 | 계약 | source ✅ | FC `barsUseNumbersOnly` | — |
| (추가) CSV 3종·수식 무력화 | csvExport.ts | source ✅ | FC `csvExports`,`csvNeutralizesFormula` | — |
| (추가) 403·리터럴 매핑·이벤트 타입 이스케이프 | 계약 | source ✅ | FC `crossTenantForbidden`,`literalMappingWinsAndRpOwnTenant`,`escapesEventTypes` | — |

### Task 13 Fix round 1 메모

- 더 보기 중 **첫 쪽(page 0) 조회가 실패하면** 카드 전체가 LoadError 로 바뀐다(TSX 는 기존 행을 유지하고 토스트) — stateless 재조회의 결과. 이후 쪽 실패는 읽은 행 유지 + 토스트.
- 원본에는 쪽 상한이 없지만 stateless 재조회 비용 때문에 40쪽(2,000건)에서 멈추고 "더 불러올 수 없습니다 — 기간이나 필터를 좁혀 주세요." 를 보인다(TC `loadMoreCapShown`). CSV 도 같은 범위, 쪽 실패 시 부분 파일 대신 오류(TC `csvRejectsPartialFailure`).
- 퍼널 기간 전환 중: 루트 Alpine `pending` 으로 이전 결과 숨김·스켈레톤·내보내기/CSV 링크 비활성(FT 4 차이 해소, 브라우저 미확인). 요청이 실패하면 결과는 돌아오지만 세그먼트 선택 표시는 새 값에 남는다(알려진 한계).

### Task 15 Fix round 1 메모

- tenant 한 곳 검증 중에는 "지금 검증"과 모든 행의 검증 버튼이 함께 잠긴다(루트 하나를 교체하는 단일 동기화 그룹) — TSX 는 행마다 `verifyingId` 라 다른 행은 눌렀다. 중복 요청 방지를 위한 의도된 차이.
- 해결 다이얼로그는 하나를 공유한다(대상은 버튼의 data-*, 폼의 id 값). 사건 목록만 갱신(409·다시 시도·오류 경로)해도 다이얼로그 정의를 다시 그릴 필요가 없다. 배너의 "Incident 처리 중"/생성 버튼은 `#chain-act-{tid}` OOB 로 갱신.
- 백필 메뉴 항목은 검증 중·재검증 실패에 Alpine 이 aria-disabled + 사유(title)로 바꾼다. 메뉴 항목에 별도 힌트 줄(.menu__reason)은 서버 렌더 비활성(위변조)일 때만 보인다.
- 재검증이 타임아웃·네트워크·비 2xx 로 끝나면(data-chain-reverify 요청) `failed` → 백필 차단(TSX status !== ok).

## Live browser pass (2026-11-04)

설정: 로컬 Oracle Free 23(arm64, FREEPDB1, gvenzl/oracle-free:23-slim-faststart — 로컬 전용, compose 변경 없음) + Redis 7, admin-app(브랜치 HEAD d31cca53)을 `--spring.profiles.active=local` 로 :8081, React admin-ui(메인 체크아웃, 변경 없음)를 vite :5173 에서 `/admin/api` 를 :8081 로 프록시. 뷰포트 1280x860, ego-browser.

시각 쌍비교(픽셀 차이, 임계 24): login ≈ 동일 레이아웃; tenants 0.00%; tenant overview 0.01%; webauthn 0.02%; aaguid 0.01%; apikeys 0.01%; credentials 1.89%(스크롤바·헤더 줄바꿈, 같은 레이아웃); audit 0.02%; funnel 0.58%; activity 1.48%; audit-chain 3.12%; settings account 0.00%; admins 0.03%; security 0.00%; mds 0.81%; system 0.00%; license 0.00%.

Thymeleaf 앱에서 확인한 흐름(콘솔 오류 없음 — 아래 결함 제외): 로그인(폼·DEV 데모) · 명령 팔레트 열기·목록 · 새 tenant 대화상자(422 인라인 오류, 성공 시 HX-Redirect + 토스트) · WebAuthn 저장(saveBar → diff 확인 → 저장, dirty 0) · API 키 발급(1회 시크릿, 체크 후 닫기)·행 메뉴·회수 L2 확인 · 계정 MFA 등록(QR)·확인(복구 코드)·해제 · Activity 5초 폴링·검색·상세 패널 · 단축키 시트(?)·G T 이동 · tenant 탭 전환·뒤로 가기.

발견한 결함과 처리(모두 이 커밋에서 수정, 동작은 컨트롤러가 브라우저로 재확인):
1. 팔레트 검색칸 미포커스 — `show()` 가 열린 뒤 입력칸에 focus(다음 틱 + rAF 재시도).
2. Activity·audit-chain 첫 카드 위 여백 — 빈 `#activity-detail`·`#chain-warn` 이 flex gap 에 끼던 것을 `:empty { display: none }` 으로.
3. MDS "동기화 설정" endpoint 줄바꿈 — `.kv dd` 의 overflow-wrap:anywhere 를 `kv__nowrap` 으로 해제.
4. 퍼널 화살표·단계 높이 — 단계의 이탈 자리 `<span class="hint">` 가 보통 공백(접혀 높이 0)이라 TSX `&nbsp;`(16px)와 단계 높이가 달랐다(254 vs 270). NBSP 로 수정(후속 커밋).
6. Activity 필터 카드 높이(80px vs 68px) — 적용된 필터 래퍼 `#activity-applied`(높이 0 빈 블록)가 카드 flex gap 을 차지. `display: contents` 로 수정(후속 커밋).
5. audit-chain 콘솔 경고(hx-include 가 없는 `[name=view]` 를 가리킴) — 골격·불러오기 실패 요청에는 hx-include 를 붙이지 않음.
