# Admin 콘솔 QA 리포트 — "구현된 듯하나 정상 작동하지 않는" 기능

- **일시**: 2026-06-02
- **대상**: admin-app(8081, dev 프로필) 서빙 admin-ui 정적 빌드
- **계정**: `alice@crosscert.com` (PLATFORM_OPERATOR)
- **방식**: Chrome DevTools 실제 클릭 + admin-ui/src 정적 코드 분석 교차 검증
- **테넌트**: Dev Passkey (`7f00dead-…000de7000001`)

운영자/QA 관점에서 콘솔의 버튼을 하나씩 눌러보며, **UI에 기능이 있어 보이지만 클릭해도 아무 일도 일어나지 않거나 깨지는** 지점을 모았습니다. 각 항목은 실제 클릭 결과(스크린샷·네트워크·콘솔)와 코드 위치로 이중 입증했습니다.

---

## 개선 처리 상태 (2026-06-02 업데이트)

브랜치 `qa-remediation-admin-console` 에서 High+Medium 8건 처리 완료(설계: `docs/superpowers/specs/2026-06-02-admin-qa-remediation-design.md`, 계획: `docs/superpowers/plans/2026-06-02-admin-qa-remediation.md`).

| # | 항목 | 처리 | 브라우저 검증 |
|---|------|------|--------------|
| 1 | deep-link forbidden | ✅ 해결 — 6개 라우트 forward+permitAll 추가 | settings/license/activity/audit-chain 새로고침 SPA 정상, /admin/api 401 유지 |
| 2 | favicon 500 | ✅ 해결 — favicon.png 배치 + NoResourceFoundException→404 | /favicon.ico 404, 콘솔 깨끗 |
| 3 | RP 사이트 열기 | ✅ 연결 — window.open(rpId) | `window.open('https://dev-passkey.crosscert.com','_blank','noopener,noreferrer')` 호출 확인 |
| 4 | Refresh | ✅ 연결 — onReload | tenant refetch 요청 발생 |
| 6 | 필터 | ✅ 구현 — 클라이언트 status 필터 | ALL→ACTIVE→SUSPENDED 토글, SUSPENDED에서 0/1건 |
| 7 | CSV | ✅ 연결 — downloadCsv(filtered) | tenants-*.csv 다운로드, 헤더+데이터 정확 |
| 8 | Incident 생성 | ✅ 숨김 — disabled + 툴팁 | disabled=true, title="향후 지원 예정" |
| 10 | CORS 편집 | ✅ 구현 — add/remove origin | Enter로 칩 추가, x로 삭제 확인 |

**백로그 (미처리, Low)**: #5 테넌트 상세 Dots, #9 Audit Chain 행별 검증, #11 Admin 사용자 Dots, #12 개요 성공률 "Phase E3 연결 예정", #13 시스템 p95·Uptime "—". 백엔드 데이터 연결(E3) 또는 추가 액션 메뉴 설계가 필요한 항목.

---

## 심각도 요약

| # | 발견 | 위치 | 심각도 | 입증 |
|---|------|------|--------|------|
| 1 | **deep-link/새로고침 시 `{"error":"forbidden"}` 노출** (settings·license·activity·audit-chain) | AdminWebMvcConfig + AdminSecurityConfig | 🔴 High | 클릭+코드 |
| 2 | favicon.ico 요청이 404가 아닌 **500 Unhandled** (매 페이지 로드) | GlobalExceptionHandler | 🟠 Medium | 네트워크+서버로그 |
| 3 | 테넌트 상세 헤더 **"RP 사이트 열기"** 죽은 버튼 | TenantDetailPage.tsx:136 | 🟠 Medium | 클릭+코드 |
| 4 | 테넌트 상세 헤더 **"Refresh"** 죽은 버튼 | TenantDetailPage.tsx:137 | 🟠 Medium | 클릭+코드 |
| 5 | 테넌트 상세 헤더 **더보기(Dots)** 죽은 버튼 | TenantDetailPage.tsx:138 | 🟡 Low | 클릭+코드 |
| 6 | 테넌트 목록 **"필터"** 죽은 버튼 | TenantsListPage.tsx:140 | 🟠 Medium | 클릭+코드 |
| 7 | 테넌트 목록 **"CSV"** 죽은 버튼 | TenantsListPage.tsx:144 | 🟠 Medium | 클릭+코드 |
| 8 | Audit Chain **"Incident 생성"** 죽은 버튼 (보안 대응 액션) | AuditChainPage.tsx:251 | 🟠 Medium | 클릭+코드 |
| 9 | Audit Chain 행별 **"검증"** 죽은 버튼 | AuditChainPage.tsx:297 | 🟡 Low | 클릭+코드 |
| 10 | 보안 정책 **CORS Allowlist 추가/삭제 전체 미구현** | SecurityPolicyTab.tsx:150-151 | 🟠 Medium | 클릭+코드 |
| 11 | Admin 사용자 행 **더보기(Dots)** 죽은 버튼 | AdminUsersTab.tsx:254 | 🟡 Low | 클릭+코드 |
| 12 | 개요 탭 **등록/인증 성공률 (7d) = "—, Phase E3 연결 예정"** | TenantDetailPage 개요 | 🟡 Low | 클릭 |
| 13 | 시스템 탭 **API 응답(p95)·Uptime = "—"** (데이터 미연결) | SystemInfoTab | 🟡 Low | 클릭 |

> 정상 작동을 확인한 기능(참고): 로그인/데모로그인, 신규 tenant 모달, WebAuthn 편집(dirty 추적·저장/되돌리기·rpId readonly), AAGUID 정책, API Key 발급/회전/회수 모달, Credentials 필터·CSV, Audit Logs "검증 실행"·"전체 즉시 검증"·"월간 보고서", Funnel 기간 토글, Activity 필터, MDS "즉시 갱신", 운영자 추가/정지, MFA 켜기, 커맨드 팔레트(⌘K).

---

## 🔴 High

### 1. deep-link / 새로고침 시 `{"error":"forbidden"}` JSON 노출

**증상**: 운영자가 `/admin/settings`, `/admin/license`, `/admin/activity`, `/admin/audit-chain`를 **주소창에 직접 입력하거나 그 페이지에서 새로고침(F5)** 하면, React 콘솔 대신 백엔드 응답 `{"error":"forbidden"}`(JSON 원본)이 그대로 화면에 뜬다. 앱 내부 네비게이션(사이드바·커맨드 팔레트)으로 이동할 때는 정상.

**근본 원인**: `AdminWebMvcConfig.java`의 SPA fallback `addViewController` 화이트리스트에 일부 라우트만 등록됨.
```java
// admin-app/.../config/AdminWebMvcConfig.java:25-33 — 등록된 forward 목록
/admin, /admin/, /admin/login,
/admin/tenants, /admin/tenants/**,
/admin/api-keys, /admin/audit, /admin/mds, /admin/keys
```
실제 React 라우트(`src/App.tsx`)는 `/tenants`, `/activity`, `/audit-chain`, `/settings`, `/license`, `/forgot-password`, `/reset-password`인데, **`/activity`·`/audit-chain`·`/settings`·`/license`·`/forgot-password`·`/reset-password`가 forward 목록과 `AdminSecurityConfig.java:94` permitAll 목록 양쪽에서 누락**됨. 그래서 index.html로 forward되지 못하고 다른 핸들러가 응답을 가로채 forbidden JSON이 노출된다.

**입증**:
- 인증 상태에서 `/admin/settings` 직접 진입 → `{"error":"forbidden"}` (스크린샷 `docs/qa-evidence/bug-deeplink-forbidden-settings.png`)
- `/admin/license`, `/admin/activity` 직접 진입 → 동일
- `/admin/tenants` 직접 진입 → 정상 (forward 목록에 있음)

**권장 조치**: SPA fallback을 개별 경로 나열 대신 `/admin/**`(정적 자산·`/admin/api/**` 제외)로 일괄 forward 처리하고, `AdminSecurityConfig`의 페이지 permitAll도 동일하게 정렬. 라우트 추가 때마다 두 곳을 같이 고쳐야 하는 현재 구조가 누락의 근본 원인.

---

## 🟠 Medium

### 2. favicon.ico → 500 Unhandled (매 페이지 로드마다 ERROR 로그)

**증상**: 모든 페이지 로드 시 브라우저 콘솔에 500 에러 1건. 네트워크 탭에서 `GET /favicon.ico [500]`.

**서버 로그**:
```
ERROR c.c.p.c.api.GlobalExceptionHandler - [Unhandled] GET /favicon.ico
org.springframework.web.servlet.resource.NoResourceFoundException: No static resource favicon.ico.
ERROR c.c.p.core.api.RequestLoggingFilter - method=GET path=/favicon.ico status=500
```
정적 리소스 미존재는 **404**여야 하는데, `GlobalExceptionHandler`가 `NoResourceFoundException`을 처리하지 않아 500으로 떨어지고 스택트레이스가 매 로드마다 쌓인다(노이즈 + 모니터링 5xx 오탐).

**권장 조치**: `GlobalExceptionHandler`에 `NoResourceFoundException` → 404 매핑 추가, 또는 admin static에 favicon.ico 제공.

### 3·4·5. 테넌트 상세 헤더 버튼 3종 (`onClick={() => {}}`)

`admin-ui/src/pages/TenantDetailPage.tsx`
```tsx
136  <button … onClick={() => {}}><Icons.ExternalLink/> RP 사이트 열기</button>  // 새 탭 안 열림
137  <button … onClick={() => {}}><Icons.Refresh/> Refresh</button>              // 데이터 refetch 안 함
138  <button … onClick={() => {}}><Icons.Dots/></button>                         // 메뉴 안 뜸
```
**입증**: "RP 사이트 열기" 클릭 → 새 탭 안 열림(list_pages 1개 유지). "Refresh" 클릭 → tenant/funnel/activity 재요청 없음. Dots 클릭 → 드롭다운 없음(focus만). (스크린샷 `docs/qa-evidence/tenant-detail-header-buttons.png`)

### 6·7. 테넌트 목록 "필터" / "CSV" (onClick 자체가 없음)

`admin-ui/src/pages/TenantsListPage.tsx`
```tsx
140  <button className="btn btn--sm"><Icons.Filter/> 필터</button>     // 핸들러 없음
144  <button className="btn btn--sm"><Icons.Download/> CSV</button>   // 핸들러 없음
```
**입증**: "필터" 클릭 → 패널/드롭다운 없음. "CSV" 클릭 → 다운로드/네트워크 요청 없음. (참고: Activity·Credentials 탭의 동명 버튼은 `downloadCsv()`로 정상 작동 — 목록 페이지만 누락)

### 8. Audit Chain "Incident 생성" (보안 대응 액션 죽음)

`admin-ui/src/pages/AuditChainPage.tsx:251`
```tsx
<button className="btn btn--danger btn--sm"><Icons.Alert/> Incident 생성</button>  // onClick 없음
```
위변조 의심 배너(`[platform] tenant에서 hash 불일치 … DBA+보안팀 알림 필요`) 바로 옆의 핵심 대응 버튼인데 클릭해도 아무 일도 없음. 같은 배너의 "tenant 열기"는 정상(`openTenant` 연결). 보안 사고 대응 UX라 우선순위를 Medium으로.

### 10. 보안 정책 — CORS Allowlist 추가·삭제 전체 미구현

`admin-ui/src/pages/settings/SecurityPolicyTab.tsx`
```tsx
150  <input className="input mono" placeholder="https://… 추가" />   // onChange/onKeyDown 없음 → 추가 불가
151  <button className="chip__x"><Icons.X/></button>                  // onClick 없음 → 삭제 불가
```
**입증**: 입력란에 `https://qa-test.example.com` 입력 후 Enter → 칩 추가 안 됨(값만 남음). `corsAllowlist` state는 서버에서 로드만 하고(line 87) 편집 경로가 완전히 끊김. 저장은 가능하나 항상 로드된 값 그대로라 사실상 읽기 전용. (대조: WebauthnConfigTab의 동일 origin 칩은 `removeOrigin` 연결되어 정상)

---

## 🟡 Low

### 9. Audit Chain 행별 "검증" 버튼
`AuditChainPage.tsx:297` — `<button className="btn btn--xs" style={{marginLeft:4}}>검증</button>` onClick 없음. 같은 행 "열기"는 정상. 상단 "전체 즉시 검증"으로 대체 가능해 영향 낮음.

### 11. Admin 사용자 행 더보기(Dots)
`AdminUsersTab.tsx:254` — onClick 없음. 같은 행의 정지/재전송/활성화는 모두 정상 연결되어 있어 추가 액션 메뉴만 placeholder 상태.

### 12. 개요 탭 성공률 = "—, Phase E3 연결 예정"
테넌트 개요의 "등록/인증 성공률(7d)"이 미연결 안내 텍스트로 노출. **동일 데이터가 Funnel 탭에서는 0%/0건으로 정상 표시**되므로 개요 카드만 연결되면 됨.

### 13. 시스템 탭 API 응답(p95)·Uptime = "—"
SystemInfoTab의 성능 지표 카드가 빈 값. 백엔드 컴포넌트 상태(OK)·버전·호스트 정보는 정상.

---

## 부록 — 위변조 경고 정합성(정상으로 확인됨)

사이드바 "위변조 의심 1/1 tenant" 빨강 표시 vs 테넌트 상세 검증 INTACT가 모순처럼 보였으나, 실제로는 **TAMPERED 대상이 dev-passkey가 아니라 `[platform]` 스코프 체인**임을 Audit Chain Monitor에서 확인. dev seed 데이터 특성(platform audit 체인 일부 불일치)으로, 버그 아님.

---

## 검증 환경 메모
- admin-app/passkey-app/sample-rp 모두 dev 프로필 정상 기동(health 200).
- QA 중 dev 데이터 변경 없음(모든 mutation 모달은 취소/되돌리기 처리).
- 증거 스크린샷: `docs/qa-evidence/`
