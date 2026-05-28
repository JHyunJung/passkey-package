# Admin UI ↔ Server Gap Fill — 2026-05-28

> 2026-05-28 의 [Admin UI Feature Gap Audit](./2026-05-28-admin-ui-feature-gap-audit-design.md) 에서 식별된 20건의 gap 을 닫는 구현 spec. **admin-ui 디자인은 절대 수정하지 않는다.**
>
> 5개 Phase(F1~F5)로 나눠 severity 우선순으로 도입. 각 phase 는 self-contained 하며 앞 phase 미적용 상태에서도 동작.

---

## 0. 공통 원칙

이 규칙은 5개 phase 모두에 적용된다. 각 phase 본문에서 반복하지 않는다.

1. **디자인 불변**. `admin-ui/src/pages/`, `shell/`, `extras/` 의 JSX 트리·className·카드/그리드 구조·label·icon 은 **변경 금지**. 수정 허용 범위는:
   - 어댑터 호출 추가 또는 fixture → 실 fetch 로 교체
   - 버튼 `onClick` 핸들러 추가
   - 인라인 fixture 상수 제거 + `useEffect`/state 추가
2. **fixture 처리**. 런타임 데이터 소스로의 fixture 사용은 모두 제거. 타입만 필요하면 `api/*.ts` 로 옮기고 `fixtures/` 파일은 삭제. 단 `fixtures/activity.ts` 는 ActivityPage 의 초기 state default 로 계속 사용 (Gap 보고서 §3 판정에 따라 gap 아님).
3. **백엔드 신규 컨트롤러 패키지 컨벤션**. `admin-app/src/main/java/com/crosscert/passkey/admin/<domain>/<Domain>Controller.java` + `<Domain>Service.java` + `<Domain>View.java`. 응답은 기존 `ApiResponse<T>` envelope.
4. **DB 마이그레이션**. V30 부터 phase 진행 순서대로 순차 할당. Oracle, idempotent, GRANT 분리, CREATE 는 EXCEPTION wrapping (메모리 규칙 `feedback_idempotent_bootstrap.md`). **번호는 phase 가 실제로 마이그레이션을 만들 때 그 시점에 결정** — F2 가 schema 점검 후 V32 를 점거하면 F4 의 mds_sync_history 는 V33 으로 시프트한다. spec 본문의 V32 표기는 "F2 가 마이그레이션을 만들지 않은 경우의 기본값" 이다.
5. **Worktree 정책**. 각 phase 는 `.claude/worktrees/gap-fill-f<N>-<short>` 에서 작업, codex review 후 `main` 으로 `--no-ff` merge (메모리 규칙 `feedback_per_phase_worktree.md`).
6. **Codex review 게이트**. 각 phase 의 staged diff 에 대해 `/codex:review` 통과 후 commit (메모리 규칙 `feedback_codex_review_before_commit.md`).
7. **롤백 단위**. 각 phase 는 self-contained. F2 의 KPI 컬럼이 F3 의 funnel 집계에 의존하면 안 됨 등.

---

## Phase F1 — Critical 2건 (보안 정책 영속화 + MFA 실데이터)

운영자에게 잘못된 보안 상태를 보여주는 두 버그를 닫는다.

### F1.1 SecurityPolicy 저장 영속화 (Gap #20)

**문제**. `SecurityPolicyTab.tsx:113` 의 `저장` 버튼이 `toast` 만 호출. DB·서버 영속 없음.

**스코프**. 정책은 platform 전역 1행. tenant 별 정책 아님.

**Backend**

- `V30__security_policy.sql` — single-row 테이블:
  - `id NUMBER PRIMARY KEY` (always = 1)
  - `session_idle_timeout_minutes NUMBER NOT NULL`
  - `password_min_length NUMBER NOT NULL`
  - `mfa_required CHAR(1) NOT NULL CHECK (mfa_required IN ('Y','N'))`
  - `cors_allowlist CLOB NOT NULL` (JSON array string)
  - `updated_at TIMESTAMP`, `updated_by NUMBER` (admin_user id FK)
  - 기본행 INSERT: idle=30, pwMin=12, mfa='Y', allowlist='[]'
- `policy/SecurityPolicyController.java`:
  - `@RequestMapping("/admin/api/security-policy")`
  - `@GetMapping` → `SecurityPolicyView`
  - `@PutMapping` (PLATFORM_OPERATOR only) → `SecurityPolicyView`
- `policy/SecurityPolicyService.java` + repository
- audit_log 에 `SECURITY_POLICY_UPDATED` 이벤트 기록

**Frontend**

- `api/securityPolicy.ts` 신규
- `SecurityPolicyTab.tsx`:
  - `securityPolicyFixture` import 삭제
  - `useEffect` 로 GET → state
  - `저장` onClick → `securityPolicyApi.update(...)` + 기존 toast 메시지 유지 + 실패 분기
  - `취소` onClick → 다시 GET 으로 reset
- `fixtures/securityPolicy.ts` 삭제

**Acceptance**. 저장 → 새로고침 → 값 유지. RP_ADMIN 의 PUT 은 403. audit 페이지에 `SECURITY_POLICY_UPDATED` 노출.

### F1.2 AdminUser MFA 실데이터 (Gap #15)

**문제**. `AdminUsersTab` 의 MFA 컬럼이 `adminMfaFixture` 사용. 5명 hard-coded, 나머지 항상 OFF.

**Backend**

- `V31__admin_user_mfa.sql`:
  - `admin_user` 에 `mfa_enabled CHAR(1) DEFAULT 'N' NOT NULL CHECK (mfa_enabled IN ('Y','N'))` 추가
  - `mfa_secret VARCHAR2(64)` nullable (TOTP secret; 본 phase 는 컬럼만 추가하고 등록 플로우는 후속 phase)
  - V11 시드 행 UPDATE: `alice@crosscert.com` → 'Y', `bob@crosscert.com` → 'N'
- `AdminUserView` DTO 에 `mfaEnabled: boolean` 추가
- `AdminUserController.list` 응답 자동 포함

**Frontend**

- `api/adminUsers.ts` 의 `AdminUserView` 타입에 `mfaEnabled` 추가
- `AdminUsersTab.tsx:206` 의 `getMfa(u.id)` → `u.mfaEnabled` 로 교체. ON/OFF 뱃지 컴포넌트는 그대로
- `getMfa` / `fixtures/adminMfa` import 삭제
- `fixtures/adminMfa.ts` 삭제

**Acceptance**. DB 실값이 화면에 그대로. alice ON, bob OFF.

### F1 산출물
- 마이그레이션 2개 (V30, V31)
- 신규 컨트롤러 1개 + DTO/Service/Repository (SecurityPolicy)
- 기존 AdminUserController 응답 확장
- 신규 어댑터 1개 (securityPolicy)
- 삭제 fixture 2개 (securityPolicy, adminMfa)
- audit 이벤트 1종 신규

---

## Phase F2 — Tenant KPI 계열 (Gap #1, #2, #4)

TenantsList 행, TenantDetail Overview 카드, WebAuthn 요약 카드가 모두 fixture/하드코딩을 거치지 않고 진짜 DB 값을 표시.

### F2.1 TenantView 응답 확장 — KPI 3필드 (Gap #1, #2)

**문제**. `tenantsApi.list/get` 이 서버 호출 후 `getTenantKpi(s.id)` 로 3필드를 fixture 에서 합성.

**Backend**

- 마이그레이션 불필요 (집계 컬럼 추가 안 함, 호출 시 계산)
- `TenantView` DTO 에 3필드 추가:
  - `credentials: long` — `credential` 테이블 tenant_id 별 COUNT (active + revoked 모두; 디자인 sub 의 "활성 + 회수 포함")
  - `apiKeys: long` — `api_key` 테이블에서 `status='ACTIVE'` AND tenant_id 별 COUNT
  - `lastEventAt: Instant?` — `audit_log` 최신 row 의 `created_at` (tenant_id 기준)
- `TenantAdminService.list()` / `get(id)` 쿼리에 LEFT JOIN 또는 별도 집계. tenant 수가 적으므로(시드 4) `list()` 는 grouped 쿼리 하나로 충분.

**Frontend**

- `api/tenants.ts`:
  - `getTenantKpi` import 삭제
  - `adaptTenant` 의 `kpi.credentials` → `s.credentials`, 동일 패턴으로 apiKeys/lastEventAt 치환
- `api/types.ts` 의 `TenantView` 에 3필드 추가
- `fixtures/tenantKpi.ts` 삭제

**Acceptance**. TenantsList 의 credentials·apiKeys·최근활동 컬럼이 진짜 DB 값. 시드 환경에서는 대부분 0/0/null.

### F2.2 TenantOverview WebAuthn 요약 카드 (Gap #4)

**문제**. `TenantOverview.tsx:189-191` 의 `userVerification REQUIRED`, `attestation DIRECT`, `timeout 60s` 가 하드코딩.

**스키마 점검 결과**. V21 (tenant_config_normalize) 에서 `webauthn_config` 또는 `tenant` 테이블에 다음 컬럼이 있어야 함:
- `require_user_verification` (BOOLEAN/CHAR)
- `attestation_conveyance` (VARCHAR)
- `webauthn_timeout_ms` (NUMBER)

Plan 단계에서 schema 확인 후 누락 필드가 있다면 V32 또는 V33 으로 보강. 본 spec 작성 시점에는 V21 에 이미 정규화돼 있다고 가정.

**Backend**

- `TenantView` 에 위 3필드 추가
- 누락 시: V32 로 컬럼 추가 + 기본값 마이그레이션

**Frontend**

- `TenantOverview.tsx:189-191`:
  - `<span className="badge badge--accent">REQUIRED</span>` → `<span className="badge badge--accent">{tenant.requireUserVerification ? 'REQUIRED' : 'PREFERRED'}</span>`
  - `<span className="badge">DIRECT</span>` → `<span className="badge">{tenant.attestationConveyance.toUpperCase()}</span>`
  - `"60s"` → `${tenant.webauthnTimeoutMs / 1000}s`
- `api/designTypes.ts` 의 `Tenant` 타입 확장

**Acceptance**. WebauthnConfigTab 에서 저장한 정책 변경이 Overview 카드에 즉시 반영.

### F2 산출물
- 마이그레이션 0~1개 (스키마 점검 후 결정)
- DTO 확장 (TenantView)
- 서비스 쿼리 변경 (집계 + tenant 컬럼 매핑)
- 어댑터 fixture 의존 제거
- 삭제 fixture: tenantKpi.ts

---

## Phase F3 — Funnel + Activity hook (Gap #3, #5, #9)

TenantDetail Funnel 탭 전체와 Overview 의 최근활동/성공률 카드를 진짜 데이터로.

### F3.1 Funnel 백엔드 신규 (Gap #3, #9)

**문제**. `funnelApi.get` 자체가 fixture 직반환. FunnelController 자체가 없음.

**데이터 소스**. `audit_log` 의 ceremony 이벤트 집계. 새 테이블 불필요.

집계 대상 이벤트:
- 등록 시도: `REGISTRATION_BEGIN`
- 등록 성공: `REGISTRATION_FINISH_OK`
- 인증 시도: `AUTHENTICATION_BEGIN`
- 인증 성공: `AUTHENTICATION_FINISH_OK`

**Backend**

- `funnel/FunnelController.java`:
  - `@RequestMapping("/admin/api/tenants/{tenantId}/funnel")`
  - `@GetMapping?windowDays={1|7|30}` → `FunnelView`
  - 권한: PLATFORM_OPERATOR or RP_ADMIN-of-this-tenant
- `funnel/FunnelService.java` — 3개 집계 쿼리:
  - **registration/authentication attempts·success** (windowDays SYSTIMESTAMP 기준)
  - **series**: 일별 attempts/success (GROUP BY TRUNC(created_at), 빈 날도 0 채움)
  - **byEventType**: 액션별 n TOP N
- 응답 DTO `FunnelView` 는 `fixtures/funnel.ts` 의 `FunnelData` 와 정확히 동일 모양 (UI 무수정 보장):
  ```ts
  { registration: { attempts, success, ratio },
    authentication: { attempts, success, ratio },
    series: [{ day: 'MM-DD', attempts, success }, ...],
    byEventType: [{ type, n }, ...] }
  ```

**Frontend**

- `api/funnel.ts`:
  - runtime fixture import 삭제
  - `funnelApi.get` 을 실 fetch (`api.get<FunnelData>(\`/admin/api/tenants/${tenantId}/funnel?windowDays=${windowDays}\`)`)
- `FunnelTab.tsx` 의 `import type { FunnelData, FunnelSeries, FunnelByEventType } from '@/fixtures/funnel'` → `api/funnel.ts` 의 re-export 로 변경
- `fixtures/funnel.ts` 삭제 (타입은 어댑터로 이동)

**Acceptance**. Funnel 탭이 실 audit_log 집계 표시. windowDays 토글이 실 서버 호출.

### F3.2 TenantOverview funnel KPI 카드 (Gap #3)

**문제**. `TenantOverview.tsx:154-157` 의 `FUNNEL_FIXTURE` 인라인 상수가 항상 attempts=0.

**Frontend**

- `TenantOverview.tsx`:
  - `FUNNEL_FIXTURE` 상수 삭제
  - `useEffect` 에서 `funnelApi.get(tenant.id, 7)` → state
  - 카드 렌더링 로직 그대로; 빈 값일 때 `—` 분기 그대로 (실 데이터 들어오면 자연스럽게 ratio 표시)

**Acceptance**. 활동 있는 tenant 의 Overview 카드가 실 성공률 표시.

### F3.3 TenantOverview 최근활동 카드 (Gap #5)

**문제**. `EMPTY_EVENTS = []` 인라인 상수 → 항상 "최근 활동 없음".

**Frontend**

- `TenantOverview.tsx`:
  - `EMPTY_EVENTS` 삭제
  - `useEffect` 에서 `activityApi.fetch(tenant.id, undefined)` → `view.feed.slice(0,5)` 를 state 에
  - 매핑 로직(EventDot, tail, timeAgo)은 그대로
- `pages/tenant/recentActivityAdapter.ts` 신규 헬퍼:
  - ActivityPage 의 `adaptServerView` 와 동일한 매핑 로직 추출
  - ActivityPage 도 이 헬퍼를 사용하도록 리팩터 (디자인 무변경)

**Acceptance**. Overview 의 "최근 활동" 카드가 해당 tenant 의 실 audit_log 5건 표시.

### F3 산출물
- 신규 컨트롤러 1개 + 서비스 (Funnel)
- 어댑터 1개 fixture 의존 제거 (funnel)
- 컴포넌트 2곳 인라인 상수 제거 + useEffect (TenantOverview)
- 새 헬퍼 1개 (`recentActivityAdapter.ts`)
- 삭제: fixtures/funnel.ts, 인라인 상수 `FUNNEL_FIXTURE` + `EMPTY_EVENTS`

---

## Phase F4 — MDS / SystemInfo / Monthly Report (Gap #14, #16, #17, #18, #19)

Settings 의 MDS·SystemInfo 탭, AuditChainPage 의 월간 PDF.

### F4.1 MDS status 확장 + Sync history (Gap #16, #17, #18)

**문제**.
- `MdsStatusTab.tsx:109` 의 `const trustAnchors = 287` 하드코딩
- `syncHistoryFixture` 인라인
- next 갱신 / 30d 성공률 / 신뢰 모드 응답 일부 누락

**Backend**

- `V32__mds_sync_history.sql` 신규:
  - `id NUMBER PK`, `started_at TIMESTAMP`, `finished_at TIMESTAMP`
  - `version NUMBER`, `status VARCHAR2(16)` (SYNCED/SKIPPED/FAILED)
  - `change_summary VARCHAR2(64)`
  - `duration_ms NUMBER`
  - `error_message VARCHAR2(500)?`
  - INDEX on `started_at DESC`
  - 시드 없음
- `MdsAdminService.sync()` 에 history insert 한 줄 추가 (기존 동작 무변경)
- `MdsAdminController` 확장:
  - 기존 `GET /admin/api/mds/status` 응답에 4필드 추가:
    - `trustAnchorCount: number` — 현재 blob entries 개수
    - `nextUpdate: LocalDate`
    - `trustMode: string` — `MDS_STRICT_REQUIRED|OPTIONAL`
    - `successRate30d: { ok: number, total: number }` — sync_history 집계
  - `GET /admin/api/mds/history?limit=5` 신규 → `List<MdsSyncHistoryView>`

**Frontend**

- `api/mdsStatus.ts`:
  - `MdsStatus` 타입에 4필드 추가
  - `mdsStatusApi.history(limit)` 함수 추가
- `MdsStatusTab.tsx`:
  - `const trustAnchors = 287` 삭제 → `status?.trustAnchorCount ?? 0`
  - `syncHistoryFixture` 삭제 → `useEffect` 에서 `mdsStatusApi.history(5)` → state
  - KV 3개(next 갱신/30d 성공률/신뢰 모드)를 status 응답 필드로 교체
  - 카드/표 구조 그대로

**Acceptance**. MDS sync 실행 → history 표 즉시 갱신. trust anchors 가 실 blob entry 개수.

### F4.2 SystemInfo 백엔드 신규 (Gap #19)

**문제**. `SystemInfoTab` 전체가 `systemInfoFixture` 직사용. endpoint 없음.

**Backend**

- `system/SystemInfoController.java`:
  - `@RequestMapping("/admin/api/system/info")`
  - `@GetMapping` → `SystemInfoView`
- `system/SystemInfoService.java`:
  - **serverVersion / deployedAt**: Spring Boot `BuildProperties` (build-info.properties)
  - **apiP95Ms / apiAvgMs / apiP99Ms**: Actuator `http.server.requests` summary
  - **uptimePercent / uptimeDays / uptimeIncidentMinutes**: Actuator `process.uptime` + (incident 는 일단 0 으로; 본 phase 스코프 밖)
  - **components**: 정적 + 동적 매트릭스:
    - passkey-app, admin-app, Oracle DB, MDS sync scheduler
    - DB version: `SELECT banner FROM v$version WHERE ROWNUM=1`
    - 모듈 build version: `BuildProperties`
  - **host**: `application.yml` 또는 env (apiHostname, adminConsole, region, environment, deployMethod)
- 마이그레이션 불필요

**Frontend**

- `api/systemInfo.ts` 신규
- `SystemInfoTab.tsx`:
  - `systemInfoFixture` import 삭제
  - `useEffect` GET → state + loading state
  - grid-3 / grid-2 그대로
- `fixtures/systemInfo.ts` 삭제

**Acceptance**. 실 빌드 버전·호스트·uptime·Actuator 메트릭 표시.

### F4.3 월간 무결성 보고서 PDF (Gap #14)

**문제**. `AuditChainPage.tsx:94-97` 의 `handleGenerate` 가 `window.alert('준비 중')`.

**Backend**

- 의존성 추가: `openhtmltopdf-core` + `openhtmltopdf-pdfbox` (Apache 2.0, Spring Boot 호환)
- `audit/MonthlyReportController.java`:
  - `@RequestMapping("/admin/api/audit/chain/monthly-report")`
  - `@GetMapping?from=YYYY-MM-DD&to=YYYY-MM-DD` → `application/pdf` binary
  - 권한: PLATFORM_OPERATOR
- `audit/MonthlyReportService.java`:
  - 기간 내 전체 tenant 의 chain verify 결과 집계 (`AuditChainMonitorService` 재사용)
  - Thymeleaf 또는 string template 로 HTML 리포트 생성 (제목/기간/tenant별 status/합계)
  - openhtmltopdf 로 PDF 변환
  - `Content-Disposition: attachment; filename="audit-chain-monthly-{from}-to-{to}.pdf"`
- audit_log 에 `MONTHLY_REPORT_GENERATED` 이벤트 기록

**Frontend**

- `api/monthlyReport.ts` 신규:
  - `monthlyReportApi.download(from, to): Promise<Blob>`
- `AuditChainPage.tsx`:
  - `handleGenerate` 의 `window.alert` 삭제
  - fetch + `URL.createObjectURL` + 임시 `<a>` click 으로 다운로드 트리거
  - Dialog/footer/button 구조 그대로

**Acceptance**. from/to 선택 후 PDF 생성 클릭 → 파일 다운로드.

### F4 산출물
- 마이그레이션 1개 (V32, mds_sync_history)
- 신규 컨트롤러 2개 (SystemInfo, MonthlyReport) + 기존 MdsAdminController 확장
- 신규 어댑터 2개 (systemInfo, monthlyReport) + 기존 mdsStatus 확장
- openhtmltopdf 의존성 추가
- 삭제: fixtures/systemInfo.ts, MdsStatusTab 인라인 syncHistoryFixture
- audit 이벤트 1종 신규 (`MONTHLY_REPORT_GENERATED`)

---

## Phase F5 — No-op 버튼 7건 + Cleanup (Gap #6, #7, #8, #10, #11, #12, #13)

디자인의 모든 버튼이 클릭 시 의미 있는 동작을 한다. 디자인 요소는 그대로.

### F5.1 TenantOverview 3 버튼 (Gap #6, #7, #8)

- **#6 `편집` 버튼 (WebAuthn 요약 카드 헤더)**
  - onClick → `navigate(\`/tenants/${tenant.id}/webauthn-config\`)`
- **#7 `전체 보기` 버튼 (최근 활동 카드 헤더)**
  - onClick → `navigate(\`/activity?tenantId=${tenant.id}\`)`
  - `ActivityPage.tsx`: `useSearchParams` 로 `?tenantId=` 읽어 `activityApi.fetch(tenantId, …)` 첫 인자에 전달. 필터 chip 디자인 무변경.
- **#8 `수동 검증` / `월간 보고서` 버튼 (chain status 영역)**
  - `수동 검증` → `auditChainApi.verifyTenant(tenant.id)` + 결과 toast
  - `월간 보고서` → `navigate('/audit-chain')`

### F5.2 Audit 탭 액션 (Gap #11)

- **`보고서`** → `navigate('/audit-chain')` (AuditChainPage 의 월간 보고서 dialog 가 거기서 열림). 디자인 무수정 우선, 같은 dialog 를 두 곳에서 띄우려면 dialog 추출이 필요한데 그건 F5 스코프 밖.
- **`최근 24시간 ▾`** — dropdown 메뉴 그리지 않음 (디자인 신규 요소 = 금지). onClick 마다 `24h → 7d → 30d → 24h` 사이클 토글. AuditTab 의 기간 state 가 같이 변경. 라벨도 같은 패턴으로 토글.
- **`내보내기`** — 현재 필터된 audit_log 를 클라이언트에서 CSV blob 생성 후 다운로드. 백엔드 endpoint 신규 추가 없음 (이미 fetch 한 데이터 사용).

### F5.3 Credentials 탭 액션 (Gap #10)

**스키마/UI 점검 결과**. 검색 input + `aaguid · status` 버튼 + 건수 + `CSV` 가 한 row 의 `card__head` 안에 들어가 있고, 추가 행을 그리려면 디자인 신규 요소가 된다.

- **`aaguid · status` 필터** — 검색 input 의 동작 모드를 토글로 사이클: `keyword (default) → aaguid → status`. 버튼 라벨 그대로 두고 입력 placeholder 만 모드에 따라 변경. 디자인 신규 요소 0.
  - 단, **사용자 기대와 다를 가능성**이 있다. UX 만족도가 낮으면 별도 spec 으로 dropdown UI 디자인을 따로 받는 게 정석. 본 phase 에서는 no-op 닫기가 최우선.
- **`CSV` 내보내기** — F5.2 와 동일 패턴. 현재 페이지의 credentials 목록을 CSV blob 다운로드.

### F5.4 ActivityPage 액션 (Gap #12, #13)

- **`내보내기`** — 현재 필터된 activity feed 를 CSV blob 다운로드
- **`이전 24시간 더 보기`** — `ActivityController` 에 `?before=<ISO>` optional 파라미터 추가:
  - 응답 모양은 동일, feed 가 before 이전의 24h window
  - 프런트: 가장 오래된 이벤트의 `createdAt` 을 `before` 로 다음 fetch 트리거, 결과를 feed 뒤에 append. 디자인 카드 그대로.
- **이벤트 행 우측 `ChevronRight`** — 디자인에 상세 페이지가 없음. onClick → `toast({ kind: 'info', title: e.type + ' · ' + e.id, message: e.subjectId })` 로 ID 가시화. 사용자가 ID 복사 가능.

### F5.5 Cleanup

- `admin-ui/src/fixtures/auditChainState.ts` 삭제 (참조 없음)
- F1~F4 에서 삭제된 fixture 사후 확인:
  - tenantKpi.ts (F2)
  - funnel.ts (F3)
  - systemInfo.ts (F4)
  - securityPolicy.ts (F1)
  - adminMfa.ts (F1)
- `fixtures/activity.ts` 는 ActivityPage 초기 state default 로 유지

### F5 산출물

- 프런트: onClick 핸들러 7개 추가, useSearchParams 적용 1곳
- 백엔드: ActivityController 에 `before` 파라미터 추가
- CSV export 유틸 1개 (`lib/csvExport.ts`)
- fixture 6개 삭제 (auditChainState + 5개 사후 확인)
- 디자인 영향 0

---

## 종합 산출물 요약

| Phase | DB 마이그레이션 | 신규 컨트롤러 | 기존 컨트롤러 확장 | 신규 어댑터 | 삭제 fixture | audit 이벤트 |
| --- | --- | --- | --- | --- | --- | --- |
| F1 | V30, V31 | SecurityPolicy | AdminUser | securityPolicy | securityPolicy, adminMfa | SECURITY_POLICY_UPDATED |
| F2 | (V32 옵션) | — | TenantAdmin | — | tenantKpi | — |
| F3 | — | Funnel | (Activity 무수정) | (funnel 교체) | funnel | — |
| F4 | V32 (또는 V33) | SystemInfo, MonthlyReport | MdsAdmin | systemInfo, monthlyReport | systemInfo, MdsStatus 인라인 | MONTHLY_REPORT_GENERATED |
| F5 | — | — | Activity (`?before`) | — | auditChainState | — |

총 마이그레이션 3~4개, 신규 컨트롤러 4개, 기존 확장 3개, 신규 어댑터 5개, fixture 삭제 6개, audit 이벤트 2종 신규.

## 디자인 영향 점검 (재확인)

- **18건**: 어댑터 호출 추가/교체 + onClick 추가 + 인라인 상수 제거. **디자인 영향 0**.
- **2건 경계 케이스**:
  - F5.4 `이전 24시간 더 보기` — 백엔드에 `before` 파라미터 추가로 디자인 영향 0
  - F5.3 `aaguid · status` 필터 — 검색 input 모드 토글로 디자인 영향 0, 단 UX 만족도 검증 필요
- **확정**: 본 spec 의 모든 변경은 admin-ui 디자인을 수정하지 않는다.

## Acceptance — 전체 phase 통과 기준

각 phase 본문의 Acceptance 절은 그 phase 의 merge 게이트다. 아래 4개는 모든 phase 가 main 으로 merge 된 뒤 spec 자체의 최종 통과 기준 (regression guard).

1. `admin-ui/src/fixtures/` 디렉토리에 `activity.ts` 한 개만 남는다 (또는 0개).
2. `admin-ui/src/pages/`, `shell/`, `extras/` 에 `fixture` 라는 단어가 import 외에 등장하지 않는다 (grep -n).
3. `admin-ui/src/pages/` 의 모든 `<button` 요소가 `onClick` 또는 `type="submit"` 또는 `disabled` 중 하나는 가진다 (grep -L).
4. 모든 phase 의 Acceptance 절을 manual smoke test (dev DB) 로 통과 — phase 별로 codex review 통과한 시점에 검증되며, 본 절은 그 누적 결과를 가리킨다.

---

*마지막 점검자: Claude (Opus 4.7), 2026-05-28.*
