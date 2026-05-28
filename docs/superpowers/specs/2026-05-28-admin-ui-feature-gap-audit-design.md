# Admin UI Feature Gap Audit — 2026-05-28

> 디자인이 노출하는 기능 vs 실제 Passkey 서버/DB가 구현해 둔 기능을 페이지/탭/액션 단위로 대조한 조사 보고서.
> 사용자 결정에 따라 산출물은 "조사 보고서만" (gap 리스트 + 우선순위)이며, 구현은 포함하지 않는다.
> 판정 기준은 **엄격**: fixture, 인라인 상수 mock, no-op 버튼은 모두 gap으로 집계한다.

---

## 1. Executive Summary

| 영향도 | 개수 | 정의 |
| --- | --- | --- |
| **Critical** | 2 | 운영자에게 실제와 다른 보안 상태를 보여준다. ("저장됨" 토스트 vs DB 미반영, MFA OFF 가짜 표시) |
| **Major**    | 10 | 핵심 운영 화면에서 fixture · 인라인 상수 · 하드코딩 · 어댑터 미연결 · alert placeholder 등으로 "데이터 자체가 가짜". (#1, #2, #3, #4, #5, #9, #14, #16, #17, #19) |
| **Minor**    | 8 | onClick 누락된 no-op 버튼, 또는 cosmetic 한 부분 mock. (#6, #7, #8, #10, #11, #12, #13, #18) |
| **합계**     | **20** | §2 표의 20개 항목과 일치 |

영향도 기준은 §4 우선순위 리스트에서 그대로 정렬됨. Critical = "운영자가 잘못된 결정을 내릴 수 있음", Major = "운영자가 가짜 데이터를 진짜로 신뢰함", Minor = "버튼이 없는 것과 사실상 동일 — 표시는 되지만 동작 안 함".

### 한눈에 보는 페이지별 상태

| 페이지 | 진짜 백엔드 연동 | 부분 mock | 완전 mock | no-op 버튼 |
| --- | :-: | :-: | :-: | :-: |
| LoginPage | ✓ | | | |
| TenantsListPage | ✓ | KPI 컬럼(credentials/apiKeys/lastEventAt) | | |
| TenantDetail / Overview | chain status | 동일 KPI + WebAuthn 요약 카드는 하드코딩 | 최근활동(빈배열), 등록/인증 성공률 | 편집, 전체보기, 수동검증, 월간보고서 |
| TenantDetail / WebauthnConfig | ✓ | | | |
| TenantDetail / AaguidPolicy | ✓ | | | |
| TenantDetail / ApiKeys | ✓ | | | |
| TenantDetail / Credentials | ✓ | | | aaguid·status 필터, CSV 내보내기 |
| TenantDetail / Audit | ✓ | | | 보고서, 최근24시간 dropdown, 내보내기 |
| TenantDetail / Funnel | | | **전체** (funnelApi 자체가 fixture 직반환) | |
| ActivityPage | ✓ (fixture는 초기값으로만, 첫 fetch 후 덮어씀) | | | 내보내기, 이전 24시간 더 보기, 상세 |
| AuditChainPage | ✓ | | | 월간 PDF 보고서 (alert placeholder) |
| Settings / AdminUsers | ✓ | MFA 컬럼(fixture에 매핑된 5명만, 나머지 OFF) | | |
| Settings / AdminMfa 탭 (없음 — UI에 별도 탭 없음) | n/a | | | |
| Settings / MdsStatus | status/sync 호출 ✓ | trustAnchors=287 하드, 동기화이력 fixture | | |
| Settings / SystemInfo | | | **전체** | |
| Settings / SecurityPolicy | | | **전체** (저장 클릭시 toast만, 영속 없음) | 저장, 취소 |

---

## 2. Page-by-Page Gap Matrix

판정 컬럼 약어:
- **GAP (Mock)**: 어댑터 자체가 fixture 또는 인라인 상수를 반환
- **GAP (Partial)**: 어댑터는 실 서버를 호출하지만 일부 필드가 fixture에서 합성됨
- **GAP (No-op)**: UI 액션이 있는데 onClick 없음 또는 alert placeholder
- **OK**: 실 컨트롤러 + DB 테이블까지 연결됨

| # | 위치 (page > tab > component) | UI에 보이는 동작 | UI 어댑터 / 호출부 | 어댑터 상태 | 백엔드 컨트롤러 | DB 마이그레이션 | 판정 | 영향도 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | TenantsListPage 행의 `등록 Credential` / `유효 API Key` / `최근 활동` 컬럼 | KPI 값 표시 | `tenantsApi.list` → `getTenantKpi(s.id)` (`src/api/tenants.ts:7,14-16`) | **Partial** — 서버 `TenantView` 받고 KPI 3필드만 fixture merge | TenantAdminController GET / 있음, 그러나 응답에 KPI 없음 | tenant 테이블에 credentials count, api_keys count, lastEventAt 컬럼 없음 (V1, V19) | **GAP (Partial)** | Major |
| 2 | TenantDetail > Overview > 상단 4 MetricCard 중 `등록 Credential`·`유효 API Key` | KPI 카드 | `tenantsApi.get` 동일 경로 | Partial (#1과 동일 원인) | 동일 | 동일 | **GAP (Partial)** | Major |
| 3 | TenantDetail > Overview > 상단 4 MetricCard 중 `등록 성공률 (7d)` / `인증 성공률 (7d)` | 퍼센티지 카드 | `FUNNEL_FIXTURE` 인라인 상수 (`TenantOverview.tsx:154-157`, 항상 ratio=0) | **Mock** — `f.attempts > 0` 이 false 라 `—` + `Phase E3 연결 예정` 노출 | 없음 (FunnelController 없음) | funnel 집계 테이블 없음 | **GAP (Mock)** | Major |
| 4 | TenantDetail > Overview > `WebAuthn 요약` 카드 | rpId/rpName 외 `userVerification`/`attestation`/`timeout` | 컴포넌트 안에서 하드코딩 (`TenantOverview.tsx:189-191`) | **Mock (Hardcoded)** | TenantAdminController GET 이 실제 값 가지고 있음 (tenant 테이블) | tenant 컬럼 존재 | **GAP (Mock)** — 어댑터를 안 부르고 상수 표시 | Major |
| 5 | TenantDetail > Overview > `최근 활동` 카드 | 최근 이벤트 5건 | `EMPTY_EVENTS = []` 인라인 (`TenantOverview.tsx:151`) | **Mock** — 항상 "최근 활동 없음 — Phase E3 에서 연결 예정" | ActivityController 가 tenant 필터 지원 (이미 존재) | audit_log V10 존재 | **GAP (Mock)** — 어댑터 미연결 | Major |
| 6 | TenantDetail > Overview > `편집` 버튼 | rpId 등 편집 진입 | (없음) | n/a | TenantAdminController PUT 존재 | n/a | **GAP (No-op)** — onClick 없음 | Minor |
| 7 | TenantDetail > Overview > `전체 보기` 버튼 (최근 활동) | Activity 페이지로 이동 추정 | (없음) | n/a | n/a | n/a | **GAP (No-op)** | Minor |
| 8 | TenantDetail > Overview > `수동 검증` / `월간 보고서` 버튼 (chain status) | chain 즉시 검증 / 월간 PDF | (없음, AuditChainPage 의 동등 버튼은 동작) | n/a | AuditChainMonitorController 가 verify 지원 | n/a | **GAP (No-op)** | Minor |
| 9 | TenantDetail > Funnel 탭 전체 | conversion funnel / 일별 시도/성공 bar chart / 이벤트 분포 | `funnelApi.get` → `getFunnel()` (`src/api/funnel.ts:7`) | **Mock** — 주석에 "서버 endpoint 없음 — fixture 직접 반환 (Phase E4 에서 서버 추가)" | **없음** | funnel 집계 테이블 없음 | **GAP (Mock)** | Major |
| 10 | TenantDetail > Credentials > `aaguid · status` 필터 / `CSV` 내보내기 | 필터 적용 / 다운로드 | (없음) | n/a | CredentialAdminController list/revoke 존재 | credential 테이블 V2 | **GAP (No-op)** | Minor |
| 11 | TenantDetail > Audit > `보고서` / `최근 24시간 ▾` / `내보내기` | 보고서 발행 / 기간 필터 / export | (없음) | n/a | AuditLogController list 존재, 기간 필터는 서버 query param 활용 가능 | audit_log V10 | **GAP (No-op)** | Minor |
| 12 | ActivityPage 상단 `내보내기` | activity feed export | (없음) | n/a | ActivityController GET 존재 | audit_log V10 | **GAP (No-op)** | Minor |
| 13 | ActivityPage `이전 24시간 더 보기` / 이벤트 행 우측 `ChevronRight` | 페이지네이션 / 상세 진입 | (없음) | n/a | ActivityController 지원 안 함 (페이지네이션 미구현) | n/a | **GAP (No-op)** | Minor |
| 14 | AuditChainPage > `월간 무결성 보고서 발급` Dialog의 `PDF 생성 → 다운로드` 버튼 | PDF 생성 | `handleGenerate` → `window.alert('PDF 생성 기능은 준비 중입니다 (v1.1)…')` (`AuditChainPage.tsx:94-97`) | **Mock (Placeholder Alert)** | 없음 (PDF 발행 endpoint 없음) | n/a | **GAP (Mock)** — alert 만 띄움 | Major |
| 15 | Settings > AdminUsers > `MFA` 컬럼 (각 행) | ON / OFF 뱃지 | `getMfa(u.id)` → `adminMfaFixture` (`src/fixtures/adminMfa.ts`, 5명만 매핑) | **Mock** — fallback false, 모든 서버 admin_user 는 OFF 로 보임 | AdminUserController 응답 DTO 에 mfa 필드 없음 | admin_user 테이블 V9, mfa 컬럼 없음 | **GAP (Mock)** | **Critical** — 보안 상태 오인 |
| 16 | Settings > MdsStatus > 상단 `Trust anchors` 카드 (287) | 활성 authenticator 개수 | 컴포넌트 안 `const trustAnchors = 287` (`MdsStatusTab.tsx:109`) | **Mock (Hardcoded)** | MdsAdminController GET /status 응답에 trustAnchors 필드 없음 | mds_blob_cache V1 | **GAP (Mock)** | Major |
| 17 | Settings > MdsStatus > `최근 동기화 이력` 표 | 최근 5회 sync 로그 | `syncHistoryFixture` 인라인 (`MdsStatusTab.tsx:34-40`) | **Mock** | history endpoint 자체가 없음 | history 테이블 없음 | **GAP (Mock)** | Major |
| 18 | Settings > MdsStatus > `next 갱신 예정` / `동기화 성공률 (30d)` / `신뢰 모드` KV | 메타 표시 | 상수 + `status?.nextUpdate` 일부만 서버 | Partial / Mock | 일부 status endpoint, 일부 없음 | n/a | **GAP (Partial)** | Minor |
| 19 | Settings > SystemInfo 탭 전체 | 서버 버전·p95·uptime·컴포넌트·호스트 | `systemInfoFixture` (`src/fixtures/systemInfo.ts`) | **Mock** — 어댑터/엔드포인트 없음 | 없음 | n/a | **GAP (Mock)** | Major |
| 20 | Settings > SecurityPolicy 탭 전체 | 세션 idle, pw 최소길이, MFA 필수, CORS allowlist `저장` 버튼 | `securityPolicyFixture` 로컬 state, `저장` 클릭 시 `toast({ kind: 'ok', title: '보안 정책 저장됨', traceId: 'tr_sec_001' })` 만 호출 (`SecurityPolicyTab.tsx:113`) | **Mock** — 영속 없음, 새로고침하면 fixture 값으로 복귀 | 없음 (SecurityPolicyController 없음) | security_policy 테이블 없음 | **GAP (Mock)** | **Critical** — 운영자가 "저장됨"을 보고 실제로는 미적용 |

---

## 3. fixtures Cross-Reference (이중 검증)

`admin-ui/src/fixtures/*.ts` 7개 파일이 어디서 import 되는지 역추적. **여기 안 잡힌 인라인 mock(`FUNNEL_FIXTURE`, `EMPTY_EVENTS`, `syncHistoryFixture`, `trustAnchors=287`, WebAuthn 요약 하드코딩)** 은 §2에서 별도 표기.

| fixture 파일 | import 위치 | 분류 |
| --- | --- | --- |
| `fixtures/tenantKpi.ts` | `api/tenants.ts:4` | adapter-level merge — 모든 tenant 목록·상세 KPI 가 fixture 합성 (#1, #2) |
| `fixtures/funnel.ts` | `api/funnel.ts:1-2` + `pages/tenant/FunnelTab.tsx:3` (type-only) | adapter 가 100% fixture 반환 (#9) |
| `fixtures/activity.ts` | `pages/ActivityPage.tsx:5` | 초기 state default 로만 사용. 첫 server fetch 성공 시 즉시 덮어쓰며 실패 시 error UI 로 fallback. **runtime gap 아님** |
| `fixtures/systemInfo.ts` | `pages/settings/SystemInfoTab.tsx:2` | 페이지 전체 fixture 직사용 (#19) |
| `fixtures/securityPolicy.ts` | `pages/settings/SecurityPolicyTab.tsx:3` | 페이지 전체 fixture 직사용 + 저장 미동작 (#20) |
| `fixtures/adminMfa.ts` | `pages/settings/AdminUsersTab.tsx:6` | 행 단위 MFA 컬럼 fixture (#15) |
| `fixtures/auditChainState.ts` | (참조 없음) | **dead fixture** — 실 사용처 없음. cleanup 후보 |

---

## 4. Prioritized Gap List

영향도 + "운영자가 잘못된 결정/행동을 할 수 있는가" 기준 정렬.

### 🔴 Critical (운영자에게 실제 상태를 잘못 알려줌)

1. **#20 — SecurityPolicy 저장이 영속되지 않음.** 저장 버튼 클릭 시 "보안 정책 저장됨" 토스트가 뜨지만 백엔드 controller·DB 모두 없음. 운영자가 정책을 바꿨다고 믿는데 실제 인증·세션 정책은 절대 바뀌지 않는다. → **endpoint + 테이블 신규**
2. **#15 — AdminUsers MFA 컬럼이 fixture.** 5명 hard-coded 외 모든 admin_user 는 항상 "OFF" 로 표시. 실제 MFA 등록 상태와 무관. 운영자가 "이 사람 MFA 안 했네" 라고 오판할 수 있다. → admin_user 테이블에 `mfa_enabled` 컬럼 + 응답 DTO 확장

### 🟠 Major (핵심 운영 화면이 mock — 화면은 그럴듯하지만 데이터 자체가 가짜)

3. **#9 — Funnel 탭 전체가 fixture.** `funnelApi` 자체가 서버 호출 없이 fixture 반환. tenant detail 의 핵심 분석 화면이 완전히 가짜.
4. **#19 — SystemInfo 탭 전체가 fixture.** 서버 버전·uptime·p95·컴포넌트 목록 모두 fixture. SRE/oncall 이 "이 환경 정보다" 라고 신뢰할 수 없다.
5. **#3, #5 — TenantOverview 의 funnel KPI / 최근활동 카드.** 어댑터는 있는데(`activityApi`, `funnelApi`) 페이지가 부르지 않고 인라인 상수를 렌더.
6. **#17, #16 — MDS 동기화 이력 / Trust anchors 287.** MDS sync 자체는 진짜 동작하지만 그 옆에 hard-coded 상수와 fixture 표가 같이 표시되어 신뢰성 떨어짐.
7. **#1, #2 — Tenant KPI(credentials/apiKeys/lastEventAt).** 진짜 서버 응답과 fixture 가 어댑터 안에서 섞여서 합성됨. 본 값과 fake 값 구분이 불가능.
8. **#14 — 월간 무결성 보고서 PDF.** alert("준비 중") 만 띄우고 끝. AuditChainPage 의 hero 버튼인데 동작 안 함.
9. **#4 — WebAuthn 요약 카드의 userVerification/attestation/timeout.** 실 값을 가져올 controller 가 이미 있는데 컴포넌트가 어댑터를 안 부르고 상수 표시.

### 🟡 Minor (no-op 버튼 — 클릭해도 그냥 아무 일도 안 일어남)

10. **#11 — Audit 탭의 `보고서` / `최근 24시간 ▾` / `내보내기`.**
11. **#10 — Credentials 탭의 `aaguid · status` 필터 / `CSV`.**
12. **#12 — ActivityPage 의 `내보내기`.**
13. **#13 — ActivityPage 의 `이전 24시간 더 보기` + 이벤트 행 ChevronRight.**
14. **#6 — TenantOverview 의 `편집` 버튼.**
15. **#7 — TenantOverview 의 `전체 보기` 버튼.**
16. **#8 — TenantOverview 의 `수동 검증` / `월간 보고서` 버튼.**
17. **#18 — MdsStatus 의 `next 갱신 예정` 외 KV (mostly cosmetic).**

### 🧹 Cleanup
- `fixtures/auditChainState.ts` — 참조 없음. 삭제 후보.

---

## 5. 메모 — 조사 방법과 한계

- 본 보고서는 정적 코드 검사 + DB 마이그레이션 파일 grep 으로 작성됨. 실제 런타임 호출 트레이싱은 하지 않음 (필요시 후속 작업).
- "엄격 기준" 적용: fixture import, 인라인 상수 mock, alert placeholder, onClick 누락을 모두 gap 으로 집계.
- 진짜 백엔드 호출은 있지만 응답 필드가 일부만 채워지는 경우는 **Partial** 로 별도 분류.
- ActivityPage 의 fixture import 는 초기 state default 이며 서버 fetch 가 실패해도 fixture 가 노출되지 않게 error UI 분기가 있어 **gap 아님** 으로 판정.
- 백엔드 컨트롤러 매핑은 `admin-app/src/main/java/com/crosscert/passkey/admin/**/*Controller.java` 13 개 전수 검토 결과를 반영.

---

*마지막 점검자: Claude (Opus 4.7), 2026-05-28.*
