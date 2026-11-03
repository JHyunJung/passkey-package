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

## §로그인·MFA

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

## §비밀번호 찾기·재설정

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

## §가입 요청

| 체크 항목 | 원본 테스트 | 결과 |
|---|---|---|
| (해당 화면 task 에서 채움) | | 미확인 |

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
