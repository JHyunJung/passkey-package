# admin-ui V2 리디자인 — 설계

- 날짜: 2026-10-11
- 브랜치: `feat/admin-ui-v2`
- 디자인 원본: Design 캔버스 https://claude.ai/artifact/7Hf6x25iguc9CvU54brtPA — **V2 페이지** (V1 은 비교용 기존 와이어프레임)
- 범위: 프론트엔드(`admin-ui/`)만. 서버·DB 변경 없음.

## 1. 목표와 성공 기준

V2 캔버스의 디자인 시스템·화면 구성·일러스트를 admin-ui 에 **그대로** 적용한다. 기존 기능(호출 API, 전송 값, 권한 분기)은 유지하고 레이아웃·위계·동작만 V2 로 바꾼다.

성공 기준:

1. V2 아트보드 30개(인증 5 · 플랫폼 4 · Tenant 탭 7 · 설정 탭 5 · 다이얼로그 시트 4 · 오버레이 1 · 공용 셸 4)와 실제 화면을 1280px 폭에서 나란히 놓았을 때 레이아웃·색·간격·컴포넌트 상태가 같다.
2. 캔버스의 `[N]`·`[Tenant 이름]` 등 자리표시는 실제 데이터로 채워진다.
3. `npm test` · `npm run lint` · `npm run build` 통과.
4. 빌드 결과물(`dist/`)에 외부 폰트·CDN URL 이 없다(폐쇄망 QA 대응).

## 2. 결정 사항 (사용자 합의)

| 항목 | 결정 |
|---|---|
| 작업 분할 | 이 스펙은 **① 프론트 V2 리디자인**만 다룬다. 서버 기능 ②~⑤ 는 각각 별도 스펙→계획→구현 (§7) |
| 테마 | **라이트 단일.** 다크 모드와 Tweaks(밀도·테이블 스타일·사이드바 모드·강조색) 제거 |
| 클라이언트 동작 변경 | V2 에 그려진 동작 **전부 포함** (§5) |
| 구현 방식 | **A안: 토큰 교체 + `src/ui/` 공용 컴포넌트 계층.** 새 스타일 프레임워크 도입 없음 |

## 3. 토큰 · 폰트 · 에셋

### 3.1 토큰 (`src/styles/tokens.css`)

V2 캔버스 `project/v2/ds.css` 의 값을 정본으로 한다.

- 브랜드(CI 에서 추출): `--brand-900 #0A2E63`, `--brand-700 #0B4EA2`(주 버튼·링크·활성), `--brand-600 #1361BF`, `--brand-500 #1E88D8`(포커스 링·차트 전용, 텍스트 금지), `--brand-300 #7CC3EE`, `--brand-100 #EAF3FB`, `--brand-50 #F5F9FD`
- 뉴트럴: `--ink #16243A`, `--ink-2 #3C4A5E`, `--mute #5B6778`, `--line #DCE3EC`, `--line-soft #EBF0F5`, `--canvas #F6F8FB`, `--sunk #EEF2F7`
- 상태: `--ok #1F7A4D / --ok-soft #E8F5EE`, `--warn #9A5B00 / --warn-soft #FFF4E0`, `--bad #C0362C / --bad-soft #FDECEA`, PLATFORM 역할 `--plat #5B3FB6 / --plat-soft #F1EDFB`
- 반경 6 · 10 · 14 · pill, 그림자 `--sh-1`(카드) · `--sh-2`(다이얼로그)
- 타입: display 28/36·700, h1 22/30·700, h2 16/24·600, body 14/22, small 13/20, caption 12/16, overline 11/16·600·uppercase
- 기존 변수명(`--accent`, `--surface`, `--text` 등)은 전환 기간 동안 V2 값의 **별칭**으로 유지하고, 모든 화면 전환 후 제거한다.
- 제거: `:root[data-theme="dark"]` 블록, `data-density` / `data-table-style` / `data-sidebar` 분기, `src/tweaks/`, `App.tsx` 의 Tweaks 마운트·postMessage 훅.

### 3.2 폰트

- `index.html` 의 jsdelivr(Pretendard) · Google Fonts(Geist) 링크 삭제.
- `@fontsource/ibm-plex-sans-kr`(400/500/600/700), `@fontsource/ibm-plex-mono`(400/500) 를 의존성으로 추가하고 `main.tsx` 에서 import → woff2 가 `dist/` 에 번들된다. 한글은 unicode-range 분할이라 사용 글자만 로드된다. 라이선스 OFL.
- `--font: 'IBM Plex Sans KR', system-ui, sans-serif`, `--mono: 'IBM Plex Mono', ui-monospace, monospace`. 숫자 열은 `font-variant-numeric: tabular-nums`.

### 3.3 에셋

- CI: `public/crosscert-logo-transparent.png`(워드마크) · `public/favicon.png`(C-키홀 심볼) 원본 그대로. 비율·색·효과 변경 금지, 최소 폭 120px.
- 일러스트 10장(ChatGPT Images, 흰 배경 플랫, 그라데이션·광택·이모지 없음) → `src/assets/illustrations/<name>.webp`. 원본은 캔버스 에셋 저장소에서 내려받는다(`Artifact read` + asset id).

| name | 캔버스 asset id | 사용처 | 출력 크기 |
|---|---|---|---|
| login-hero | 1c9b475083d06467263e848b4603c8d4 | 로그인 브랜드 패널 | 840w |
| empty-tenants | 32f558f3e98a8e781a6dccada76d44a4 | Tenants 빈 상태 | 320 |
| empty-credentials | 1e6ee75f890b60704e9a7f1ecd539f41 | Credentials 빈 상태 | 320 |
| all-clear | 095cd98dc29b5a9926384f2f0444605d | Incident 없음 | 320 |
| mfa | f48c3413453f816afd98b0026b0d768a | MFA 챌린지 · 내 계정 MFA 꺼짐 | 320 |
| recovery-codes | 28c7ea775eb04b48883816539903c8b1 | 복구 코드 다이얼로그 | 320 |
| no-results | 51b64be8419100f7573980e7fcd46fc6 | 검색 결과 없음 · ⌘K 결과 없음 | 320 |
| api-key-issued | e07ad81e6db24148c9626ce80ad59139 | API 키 발급 완료 다이얼로그 | 320 |
| signup-sent | 1cba57052ec79a2ce8db6c5698eebc94 | 가입 요청 접수 | 320 |
| load-error | 7962ac750703541908c0fe368c59f2dd | 불러오기 실패(시스템 등) | 320 |

규칙: 빈 상태·온보딩 전용, 120–160px 로 표시, 한 화면에 하나, 장식이므로 `alt=""`.

## 4. 컴포넌트 계층 (`src/ui/`)

원칙: 컴포넌트당 파일 하나, 스타일은 `src/styles/components.css` 한 곳(BEM 클래스, 예 `.btn--primary`), 데이터 비의존(props 만 렌더).

| 컴포넌트 | 요구사항 |
|---|---|
| `Button`, `IconButton` | primary · secondary · ghost · danger · danger-solid, sm/md/lg. 아이콘 버튼 `aria-label` 필수 |
| `Badge` | 점+라벨(ok·warn·bad·info·plat·neutral), `noDot`. `StatusBadge` 는 상태코드→Badge 매핑 래퍼로 유지 |
| `Card`, `CardHeader`, `CardFooter` | 헤어라인 + `--sh-1` |
| `KpiCard` | label · value · 보조 문맥, `selected`/`onClick` 시 필터 카드(`aria-pressed`) |
| `PageHeader`, `PropChip` | 제목·설명·액션 줄 / 키·값·복사 칩 |
| `Tabs` | 링크형, 탭별 `dot`(확인 필요) · `badge`(건수) |
| `DataTable` | 툴바 슬롯, 행 클릭, hover, 날짜 그룹 행, `renderExpanded` 펼침 행, 표준 페이지네이션 `1–50 / N건 · 페이지당 · ‹ ›` |
| `Segmented`, `FilterChip`, `RemovableChip`, `ChipInput` | 세그먼트 / 토글 칩 / 적용 필터 칩 / Enter 추가 칩 입력(검증 콜백) |
| `Switch`, `ChoiceCard`, `Field` | 스위치(숨은 checkbox) / 라디오 카드(`role=radio`) / 라벨·도움말·오류 래퍼 |
| `OtpInput` | 6칸, 붙여넣기 분배, 백스페이스 이전 칸 이동, 6자리 채우면 `onComplete` |
| `SaveBar` + `useUnsavedGuard` | 하단 고정 "저장하지 않은 변경 N건 · 되돌리기 · 저장", 라우터 이동·새로고침 시 확인 |
| `Alert` | ok · warn · bad · info 배너 |
| `EmptyState` | 일러스트 · 제목 · 설명 · CTA |
| `Dialog`(확장) | `dismissible={false}` 면 ESC·바깥 클릭 무시, `initialFocus`, 포커스 트랩, `role="alertdialog"` 지원, 회색 푸터 |
| `Stepper` | 세로 단계 표시(done/now/todo) |
| `Menu` | ⋯ 메뉴, 일반·위험 구획, 화살표/Enter/Escape |
| `SecretBox` | 고정폭 시크릿 + 복사 |
| `StatusStrip` | 최근 N회 결과 막대, `role="img"` + 요약 `aria-label` |
| `ConfirmByTyping` | 입력이 기대값과 일치해야 위험 버튼 활성 |

`shell/` 의 `Dialog`·`StatusBadge`·`EmptyState`·`CopyBtn`·`ToastHost` 는 `ui/` 로 이동(사용처 11개 import 경로 변경). `Sidebar`·`Header`·`Breadcrumb` 은 `shell/` 에 남겨 V2 마크업으로 재작성. 아이콘은 `icons/Icons.tsx` 유지, V2 신규 아이콘(복사·더보기·필터·체인·열쇠·지문 등)을 1.5px 스트로크로 추가.

## 5. 화면별 변경

기능·구성 유지. ★ = §6 의 서버 필드가 있을 때만 표시.

| 화면 | V2 변경 | 데이터 |
|---|---|---|
| 앱 셸 | 사이드바: CI 워드마크 + prod 배지, 플랫폼/관리 그룹, 활성 항목 좌측 바, 감사 체인 실패 건수 배지, 하단 체인 상태 카드. 상단 바: 브레드크럼 · ⌘K 검색 · 도움말 · 사용자 pill(역할 배지) | 기존 체인 상태 폴링 |
| 로그인 | 밝은 브랜드 패널(워드마크·헤드라인·히어로·지표 3) + 폼, 서버 오류는 카드 상단 배너, 데모 계정 카드(dev) | 기존 |
| MFA | 공통 카드 셸, `OtpInput` 자동 제출, 같은 카드 안 "복구 코드로 로그인", 카드 밖 로그아웃 | 기존 |
| 비밀번호 재설정 요청·설정, 가입 요청 | 공통 카드 셸, 제출 후 같은 카드에서 완료 상태 전환, 가입 완료 일러스트 | 기존 |
| Tenants | KPI 카드 클릭 = 상태 필터, 전체/활성/정지 세그먼트(건수), 적용 필터 칩 + 모두 지우기, 표준 페이지네이션, 빈 상태 일러스트, KPI "만료 임박 키 N"(30일 이내) | 클라이언트 계산 + `listAllApiKeys()`(tenantId 없이 `GET /admin/api/api-keys`) 추가, ★7일 증감 |
| Activity | 날짜 그룹(오늘/어제/날짜), 유형 색 점·태그, "보안 실패만" 스위치(`category=security`), 우측 상세 패널(언제 KST/UTC · 누가 · 테넌트·대상 · 어떻게 바뀜 · 원본 JSON 복사), Live 표시 | 기존, ★IP, ★전체 diff |
| 감사 체인 모니터 | 전체 상태 헤드라인, 위변조 배너(tenant 열기·Incident 생성), KPI, 전체/위변조만 세그먼트, Incident 빈 상태 일러스트 | 기존, ★최근 14회 스트립, ★다음 검증 |
| License | 상태 헤드라인 + 만료 진행 바, jti 복사, 배너 3상태 | 기존 |
| Tenant 헤더 | 2행: 이름·상태·RP 사이트 열기·⋯(테넌트 정지/해제) / ID·RP ID 복사 칩·slug·생성일. 탭 점 배지: AAGUID(목록에 `mdsName == null` 항목 존재), API 키(30일 내 만료 키 존재) | aaguid-policy · api-keys 조회로 계산 |
| 개요 | KPI 띠, 요약 카드 3(WebAuthn · 최근 활동 · 감사 체인), 각 "자세히" | 기존, ★체인 스트립 |
| WebAuthn / AAGUID / 보안 정책 | 설명 좌·컨트롤 우 2열(`.set` 그리드), `SaveBar` + 이탈 확인, 변경된 필드 강조, AAGUID 행 MDS 확인/미확인 배지 | 기존 |
| API 키 | 이름 + `pk_prefix…`, 스코프 태그 열, 만료 임박 경고 색·아이콘, ⋯ 메뉴(회전 · 접두사 복사 · 위험: 회수), KPI "권장 rotation(90일) 초과 N" | 기존 + 계산, ★suffix |
| Credentials | 검색 + "+ 필터"(aaguid·status) 칩, ID 말줄임+복사, 인증기 아이콘, 표준 페이지네이션, 빈 상태·검색 결과 없음 일러스트. **버그 수정: 검색 파라미터 `search` → `q`** | 기존 |
| 감사 로그 탭 | 체인 검증 카드(PLATFORM), 유형 칩 + 전체 해제, "실패만" 스위치, 기간 세그먼트 → 서버 `from`/`to` 전달, 날짜 그룹 행, 행 펼침(diff + payload + JSON 복사 + 상세 다이얼로그) | 기존(실패 = 현재 실패 액션 목록), ★total |
| 퍼널 | 기간 세그먼트 + 요약 KPI(전체 전환율 · 최대 이탈 단계 · 직전 기간 대비) 먼저, 단계 막대 사이 전환율 칩, 이탈 수, 차트 2열 + "표로 보기"·CSV | 계산. 직전 대비는 30일 `series` 로 시도·성공만, 24h 는 비교 생략 |
| 내 계정 | MFA 꺼짐: 일러스트 + "2단계 인증 켜기"(→ 3단계 다이얼로그). 켜짐: 위험 영역 분리 | ★등록일, ★남은 복구 코드 |
| Admin 사용자 | 가입 요청 대기 섹션(인라인 승인…/거절), 운영자 표 위 역할 세그먼트(전체/PLATFORM/RP_ADMIN/정지) + 검색 | 클라이언트 필터 |
| MDS Status | 상태 헤드라인, KPI, 설정 카드, 이력 표 + 30회 `StatusStrip`(`limit=30`) | 기존 |
| 시스템 | 전체 상태 KPI, 컴포넌트 목록, 호스트 정보, 불러오기 실패 일러스트 상태 | 기존, ★컴포넌트 ms |
| 다이얼로그 | 신규 tenant(다음 단계 3), 테넌트 정지(영향 범위: 회수될 키 수 = `tenant.apiKeys` + `ConfirmByTyping` slug + 취소 기본 포커스), WebAuthn 변경 diff 확인, API 키 발급(만료 6/12/24/36개월·무기한 유지)·발급 완료(`dismissible=false`, 확인 체크 후 닫기)·회수·회전, Credential 상세(섹션 + 이전/다음 + 푸터 좌측 회수)·회수(마지막 8자), Audit Event, Hash chain 검증, Incident 생성·해결, 월간 보고서, MFA 3단계(`Stepper`: 스캔+코드 → 복구 코드), 복구 코드(`dismissible=false`), 가입 승인·거절, MFA 끄기 | 기존 |
| 오버레이 | ⌘K: 이동/현재 Tenant/액션 그룹, 항목 경로·단축키, 선택 강조 바, 하단 키 범례. 이동 단축키 `G T`(Tenants) · `G A`(Activity) · `G C`(감사 체인) · `G S`(설정) — 입력 필드 포커스 중에는 무시. 세션 만료 모달 진행 링. RP 전환·사용자 메뉴, 계정 구성 오류 | 기존 |

Credential 상세 이전/다음은 현재 로드된 페이지 안에서 이동하고, 페이지 경계에서는 다음/이전 페이지를 불러와 이어간다.

감사 체인 헤더 문구: 서버에 정기 검증 스케줄러가 없으므로 ②가 배포되기 전까지 "60초마다 자동 검증" 대신 실제 동작(수동·요청 시 검증)으로 표기한다.

## 6. 서버 연결 지점 (후속 작업용 계약 초안)

후속 서버 작업에서 추가될 필드를 TS 타입에 **optional** 로 선언한다. 필드가 없으면 해당 요소를 **렌더링하지 않는다**(자리표시·"준비 중" 문구 없음, 기능 플래그 없음). 이름·형태는 각 서버 스펙에서 최종 확정한다.

| 후속 | 필드(초안) | 없을 때 |
|---|---|---|
| ② 감사 체인 이력 | `ChainOverview.tenants[].recentRuns?: { at: string; ok: boolean }[]`, `ChainOverview.nextRunAt?: string` | 스트립 열·카드 숨김, "다음 검증" 숨김 |
| ③ 로그 보강 | `ActivityEvent.actorIp?: string`, `ActivityEvent.diff?: { field: string; from: unknown; to: unknown }[]`, 감사 로그 응답 `total?: number`, `AuditRow.result?: 'SUCCESS' \| 'FAILURE'` | IP 줄 숨김, diff 는 `TENANT_UPDATE` before/after 만, 건수 "최근 N건", 실패 판정은 현재 실패 액션 목록 |
| ④ MFA 정보 | `Me.mfaEnrolledAt?: string`, `Me.recoveryCodesRemaining?: number` | 켜짐 카드 두 줄 숨김 |
| ⑤ 지표 보강 | `TenantView.credentialsDelta7d?: number`, `SystemComponent.latencyMs?: number`, `ApiKey.keySuffix?: string` | 보조 줄·ms 숨김, `pk_prefix…` 만 |

## 7. 범위 밖

- 서버·DB 변경(②~⑤ 는 별도 스펙). 위 계약 필드의 서버 구현.
- 다크 모드, 사용자 설정형 밀도·강조색.
- 테넌트 컨텍스트 사이드바(코드상 미연결 상태 유지).
- V2 에 없는 신규 화면.

## 8. 테스트 · 검증

**자동 테스트(vitest)**

- 문구·클래스 의존 7개 갱신: Sidebar, SettingsPage, CredentialDetailDialog, AdminUsersTab, SystemInfoTab(`badge--*`), RequirePlatform, IdleSessionModal — 검증 동작은 유지, 쿼리만 교체.
- 신규 컴포넌트(동작 있는 것): `OtpInput`(붙여넣기·백스페이스·완료), `Dialog`(`dismissible=false` ESC·스크림 무시, `initialFocus`, 포커스 트랩), `useUnsavedGuard`, `ConfirmByTyping`, `Menu`(키보드), `ChipInput`(검증), `DataTable`(펼침·페이지네이션).
- 신규 화면 동작: Tenants KPI→필터, 감사 로그 기간→`from`/`to`, Credentials `q` 회귀, Credential 상세 이전/다음, §6 필드 유/무 렌더.
- 완료 조건: `npm test` · `npm run lint` · `npm run build` 통과.

**시각 비교**

- 로컬 스택(`docs/dev-setup.md`, dev 프로필 · 데모 계정)에서 admin-ui dev 서버 실행.
- Playwright 를 scratch 에서 일회성 실행(프로젝트 의존성 추가 없음), 1280px 로 V2 아트보드 30개 대응 화면·상태(빈 상태·다이얼로그 포함) 캡처.
- 캔버스 아트보드와 나란히 비교 → 차이 목록 → 수정 → 최종 비교 결과를 사용자에게 제시.
- `dist/` 에 `fonts.googleapis`·`fonts.gstatic`·`jsdelivr` 문자열이 없는지 검사.

**배포 영향**: admin-app 이 `/admin/` 으로 서빙하는 정적 파일만 바뀐다. API·DB 변경 없음.
