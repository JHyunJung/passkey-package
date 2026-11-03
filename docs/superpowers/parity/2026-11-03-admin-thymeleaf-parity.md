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
| 이동/액션 그룹(t-over)·"G T" 표시·결과 없음 문구 | V2 1 | source ✅ / 차이: 필터가 서버 왕복(입력 150ms 뒤 `GET /admin/_/palette?q=`), 서버가 같은 규칙(라벨·힌트·그룹 부분 일치, 대소문자 무시)으로 거른다 | CPT `platformDefaultList`, `filtersByLabelHintGroup`, `emptyState` | 미확인 |
| Enter 로 선택 항목 실행(첫 항목 → Tenants 목록) | V2 2 | source ✅ / 차이: 실행이 `adminNavigate('/admin/tenants')`(boost 이동). 선택·실행은 JS | CPT `itemAttributes`(data-href) · 스텁 D1, D4, D12 / JS — 브라우저 확인 필요 | 미확인 |
| 동작하지 않는 액션(Hash chain 즉시 검증·Role 전환 데모) 없음, 로그아웃 있음 | V2 3 | source ✅ / 차이: 없음 | CPT `platformDefaultList` | 미확인 |
| 안내 문구가 실제 기능에 맞음(placeholder, credential·감사 ID 안내) | V3 1 | source ✅ / 차이: 없음 | CPT `emptyState`, SER `paletteMarkup` | 미확인 |
| 열리면 검색칸 포커스, 닫히면 열기 전 위치로 | V3 2 | source ✅ / 차이: 포커스는 Alpine `x-trap`(+`autofocus`)이 맡는다(useFocusTrap 대신). 열 때마다 검색칸·이전 결과를 비운다 | SER `paletteMarkup`(autofocus·x-trap 속성은 마크업만), 스텁 D16 / JS — 브라우저 확인 필요 | 미확인 |
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

## §Tenant 목록

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

## §Tenant 상세

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

## §Activity

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

## §Audit chain

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

## §설정

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

## §라이선스

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |
