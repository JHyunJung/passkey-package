# F01 MFA 필수 정책 강제화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 보안 정책 "MFA 필수"가 켜져 있으면 MFA 미등록 운영자가 로그인 직후 TOTP 등록을 끝내기 전까지 콘솔을 쓰지 못하고, 정책이 켜진 동안 MFA 를 끌 수 없게 한다.

**Architecture:** 로그인 성공 핸들러가 정책을 읽어 새 세션 속성 `MFA_ENROLL_REQUIRED` 를 붙이고, 기존 `MfaPendingFilter` 가 이 상태의 허용 목록을 따로 갖는다. 등록은 `GET /admin/login` 이 그리는 셸 없는 화면에서 기존 `MfaController.enroll/confirm` 을 메서드로 불러 끝내고, confirm 이 속성을 지우고 `MFA_ENROLL` 감사를 남긴다.

**Tech Stack:** Kotlin 2.4 · Spring Boot 4.1 · Spring Security 7 · Thymeleaf + htmx + Alpine · JUnit 5 + MockMvc + Testcontainers(Oracle)

**Spec:** `docs/superpowers/specs/2026-11-13-mfa-required-enforcement-design.md`

## Global Constraints

- 정책 off 일 때 기존 로그인·MFA 동작(세션 속성·리다이렉트·JSON 키 `email/role/mfaRequired` 의 값·HTML 스냅샷)은 그대로. 바뀌는 것은 JSON 로그인 응답과 `/me` 에 `mfaEnrollRequired`(false) 키가 추가되는 것뿐.
- 세션 속성 이름: `MFA_ENROLL_REQUIRED`(값 `java.lang.Boolean.TRUE`). `MFA_PENDING` 과 동시에 붙이지 않는다.
- 정책 조회 실패(`SecurityPolicyService.get()` 예외 — 싱글턴 행 없음 또는 CORS JSON 손상)는 fail-closed: 미등록이면 등록 요구, disable 은 409.
- 오류 코드 문자열: 필터 API 차단 `mfa_enroll_required`(403), disable 거부 `mfa_policy_required`(409).
- 화면 문구(그대로 사용):
  - 등록 화면 안내: "보안 정책에 따라 2단계 인증을 등록해야 콘솔을 쓸 수 있습니다."
  - 끄기 불가 설명: "보안 정책상 모든 운영자에게 2단계 인증이 필수라 끌 수 없습니다."
- 감사: action `MFA_ENROLL`, targetType `ADMIN_USER`, targetId=운영자 id, tenantId null, payload `{"forced": true|false}`; Activity 라벨 "MFA 등록", ops 분류.
- secret·복구 코드: 응답 본문에만, `Cache-Control: no-store`, 로그·세션·헤더·감사 payload 에 없음.
- `/admin/api/mfa/*` 는 등록 필요 세션에 열지 않는다.
- 모든 커밋 메시지 끝에 저장소 규칙의 Co-Authored-By / Claude-Session 줄.

## Review Focus

1. 등록 필요 세션의 htmx 조각 요청(`/admin/_/settings/tab/account`, `HX-Request: true`) → `HX-Redirect: /admin/login`(본문 없음) — Task 2 테스트에 포함.
2. 등록 필요 세션이 `POST /admin/login/mfa`(기존 verify)로 MFA 를 우회해 통과 시도 → 차단 — Task 2 테스트에 포함.
3. 등록 중 이탈(enroll 후 confirm 없이 로그아웃) → 재로그인 시 다시 등록 필요 — Task 7 IT 에 포함.
4. "등록 시작"을 두 번 눌러 secret 이 덮인 뒤 첫 QR 의 코드로 confirm → 401(새 secret 기준) — Task 5 테스트에 포함.
5. 등록 완료 직후 같은 세션의 `GET /admin/login` → `/admin`(등록 화면·MFA 단계 아님) — Task 5 테스트에 포함.

---

### Task 1: 세션 상태 헬퍼 + 로그인 판정

**Files:**
- Create: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/auth/MfaSessionState.kt`
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/config/AdminSecurityConfig.kt` (`adminLoginSuccessHandler`, 정책 조회·`mfaRequired` 판정 부분 ~278-325)
- Test: `admin-app/src/test/kotlin/com/crosscert/passkey/admin/auth/AdminLoginMfaPolicyTest.kt` (새로 — `AdminLoginCredentialErasureTest` 의 `@WebMvcTest(MeController) + @Import(AdminSecurityConfig)` 실제 formLogin 패턴 복사)

**Interfaces:**
- Produces:
  - `object MfaSessionState { const val ENROLL_REQUIRED_ATTR = "MFA_ENROLL_REQUIRED"; fun isPending(req: HttpServletRequest): Boolean; fun isEnrollRequired(req: HttpServletRequest): Boolean; fun clearEnrollRequired(req: HttpServletRequest) }`
    (`isPending` 은 `MfaPendingFilter.MFA_PENDING_ATTR` 판정 — 기존 사본 3곳은 그대로 두어도 된다)

- [ ] **Step 1: 실패 테스트** — `AdminLoginMfaPolicyTest` (JSON 로그인 = form-urlencoded + `X-XSRF-TOKEN`, 화면 로그인 = `_csrf` 파라미터)
  - `mfaEnabled_pendingRegardlessOfPolicy`: user mfa=Y, policy mfaRequired=true → 세션 `MFA_PENDING=TRUE`, `MFA_ENROLL_REQUIRED` 없음, JSON `mfaRequired=true`, `mfaEnrollRequired=false`
  - `notEnrolled_policyOn_enrollRequired`: mfa=N, policy=true → `MFA_ENROLL_REQUIRED=TRUE`, `MFA_PENDING` 없음, JSON `mfaRequired=false`, `mfaEnrollRequired=true`; 화면 로그인은 `/admin/login` 리다이렉트
  - `notEnrolled_policyOff_unchanged`: mfa=N, policy=false → 두 속성 없음, JSON 키 집합 `email,role,mfaRequired,mfaEnrollRequired`, 화면 로그인 `/admin`
  - `policyMissingRow_failsClosed`: `policy.get()` 이 `IllegalStateException("security_policy singleton row missing")` → `MFA_ENROLL_REQUIRED=TRUE`, 세션 idle = 30분(기본값)
  - `policyCorruptCors_failsClosed`: `IllegalStateException("deserialize cors_allowlist failed")` → 같은 결과
  - `policyOn_idleTimeoutStillApplied`: policy idle=45 → `maxInactiveInterval == 2700`
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :admin-app:test --tests '*AdminLoginMfaPolicyTest'` → FAIL(속성 없음 / 키 없음)
- [ ] **Step 3: 구현** — `policy.get()` 을 한 번만 호출해 idle·mfaRequired 를 같이 얻고, 예외면 idle=기본·`policyRequiresMfa=true` + warn. 판정은 Spec §4.1 표. JSON 에 `mfaEnrollRequired` 추가(기존 3키 유지). 로그인 성공 로그에 `mfaEnrollRequired={}` 추가.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 그리고 `--tests '*AdminLoginCredentialErasureTest' --tests '*LoginPageControllerTest'` 회귀 PASS
- [ ] **Step 5: 커밋** — `feat(admin): 로그인 시 MFA 필수 정책 판정 — MFA_ENROLL_REQUIRED(fail-closed)`

### Task 2: 차단 필터 + 보안 체인 경로

**Files:**
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/auth/MfaPendingFilter.kt`
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/config/AdminSecurityConfig.kt:127-132` (authenticated 매처에 `/admin/login/mfa/enroll`, `/admin/login/mfa/confirm` 추가)
- Test: `admin-app/src/test/kotlin/com/crosscert/passkey/admin/auth/MfaPendingFilterTest.kt`

**Interfaces:**
- Consumes: `MfaSessionState.ENROLL_REQUIRED_ATTR`, `isEnrollRequired`
- Produces: 등록 필요 세션 허용 목록 — `GET /admin/login`, `POST /admin/login/mfa/enroll`, `POST /admin/login/mfa/confirm`, `/admin/api/me`, `/admin/logout`, `/admin/{css,js,fonts,img}/**`

- [ ] **Step 1: 실패 테스트** (기존 `requestWithSession` 헬퍼를 속성 이름을 받게 확장)
  - `enrollRequired_allowList_passesThrough`: 위 허용 목록 각각(메서드 포함) → chain 호출됨
  - `enrollRequired_api_is403_mfaEnrollRequired`: `/admin/api/tenants`, `/admin/api/mfa/enroll`, `/admin/api/mfa/verify`, `/admin/api/mfa/confirm` → 403, 본문 `{"error":"mfa_enroll_required"}`, chain 미호출
  - `enrollRequired_screen_redirectsToLogin`: `GET /admin` → 302 `/admin/login`; `GET /admin/_/settings/tab/account` + `HX-Request: true` → `HX-Redirect: /admin/login`; `POST /admin/login/mfa` → `/admin/login` 리다이렉트(chain 미호출)
  - 기존 `pending_*` 테스트 전부 그대로 통과(회귀); `pending_loginMfaEnrollPath_isRedirected`: `MFA_PENDING` 세션의 `POST /admin/login/mfa/enroll` → 차단
- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*MfaPendingFilterTest'` → FAIL
- [ ] **Step 3: 구현** — `doFilterInternal` 에서 enroll-required 분기를 pending 분기와 나란히 둔다(같은 응답 경로 재사용, 오류 문자열만 다름). KDoc 의 허용 목록 설명에 새 상태 추가.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + `--tests '*PageSecurityTest' --tests '*MfaControllerSecurityTest'` PASS
- [ ] **Step 5: 커밋** — `feat(admin): MFA 등록 필요 세션 차단 — 등록 화면·me·logout·정적만 허용`

### Task 3: 정책 켜짐 동안 MFA 끄기 거부 + `/me` 필드

**Files:**
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/auth/MfaController.kt` (생성자에 `SecurityPolicyService` 추가, `disable`)
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/config/MeView.kt`, `MeController.kt`
- Modify (생성자 변경 반영): `admin-app/src/test/kotlin/com/crosscert/passkey/admin/auth/MfaControllerTest.kt:34`, `MfaControllerRecoveryTest.kt` 슬라이스(이미 `SecurityPolicyService` MockitoBean 있음 — 확인만)
- Test: `MfaControllerRecoveryTest.kt`(슬라이스), `MeControllerSecurityTest.kt`, 계약 `src/test/resources/contract/json/me-get*.shape.json`

**Interfaces:**
- Produces:
  - `MeView(..., val mfaEnrollRequired: Boolean = false)` — **마지막 파라미터, 기본값 false**(기존 호출부 `HomeRedirectControllerTest` 무수정)
  - `MfaController.disable` → 정책 on/조회 실패 시 `409 {"error":"mfa_policy_required"}`

- [ ] **Step 1: 실패 테스트**
  - `disable_policyOn_409_beforeTotp`: policy mfaRequired=true → 409 `mfa_policy_required`, `totp.matchedCounter` 미호출, `admins.save` 미호출
  - `disable_policyReadFails_409`: `securityPolicyService.get()` throws → 409
  - `disable_policyOff_unchanged`: policy=false + 유효 TOTP → 200 `{"disabled":true}`(현행)
  - `me_enrollRequiredSession_reportsFlag`: 세션 `MFA_ENROLL_REQUIRED=TRUE` → `$.data.mfaEnrollRequired == true`, `$.data.mfaRequired == false`
- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*MfaControllerRecoveryTest' --tests '*MeControllerSecurityTest'` → FAIL
- [ ] **Step 3: 구현** — disable 첫머리(pending 검사 다음)에 정책 판정. MeController 는 `MfaSessionState.isEnrollRequired(req)`.
- [ ] **Step 4: 통과 확인** — 위 + `--tests '*contract*'`; JSON 계약은 `me` 에 키 하나 추가된 차이만 확인 후 `CONTRACT_OVERWRITE=true ./gradlew :admin-app:test --tests '*AdminJsonShapeContractTest' -Pcontract.record=true` 로 재기록, `git diff` 로 의도한 차이만인지 검토
- [ ] **Step 5: 커밋** — `feat(admin): MFA 필수 정책 동안 MFA 끄기 거부(409) + me.mfaEnrollRequired`

### Task 4: confirm — 등록 필요 해제 + `MFA_ENROLL` 감사

**Files:**
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/auth/MfaController.kt` (`confirm`)
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/web/format/ActivityLabels.kt`, `activity/ActivityService.kt` (OPS_ACTIONS)
- Test: `MfaControllerRecoveryTest.kt`

**Interfaces:**
- Consumes: `MfaSessionState.isEnrollRequired`, `clearEnrollRequired`; 기존 `audit: AuditLogService`
- Produces: confirm 성공 시 세션의 `MFA_ENROLL_REQUIRED` 제거; 감사 `MFA_ENROLL {"forced": <confirm 시점의 isEnrollRequired>}`

- [ ] **Step 1: 실패 테스트**
  - `confirm_enrollRequiredSession_clearsFlag_auditsForced`: 세션에 `MFA_ENROLL_REQUIRED=TRUE` + 유효 TOTP → 200, 응답 후 세션 속성 없음, `audit.append` 인자 `action=="MFA_ENROLL" && payload["forced"]==true && targetId==u.id.toString() && tenantId==null`
  - `confirm_normalSession_auditsNotForced`: 속성 없음 → `forced==false`
  - `confirm_auditFailure_stillConfirmed`: `audit.append` throws → 200, `recoveryCodes[0]` 존재, `u.isMfaEnabled==true`, 세션 속성 제거됨
  - `confirm_wrongCode_keepsFlag`: 401, 세션 속성 유지, 감사 없음
- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*MfaControllerRecoveryTest'` → FAIL
- [ ] **Step 3: 구현** — 성공 경로: enable·save → 복구 코드 생성 → forced 판정·속성 제거 → 감사(try/catch, 실패 시 `log.error` — 예외 클래스명만). 라벨 `"MFA_ENROLL" to "MFA 등록"`, OPS_ACTIONS 에 추가.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests '*Activity*'` PASS
- [ ] **Step 5: 커밋** — `feat(admin): MFA 등록 완료 시 등록 필요 해제 + MFA_ENROLL 감사(forced 구분)`

### Task 5: 등록 화면

**Files:**
- Create: `admin-app/src/main/resources/templates/admin/auth/mfa-enroll.html`
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/web/auth/LoginPageController.kt` (`login` 분기 + `POST /admin/login/mfa/enroll`, `POST /admin/login/mfa/confirm`)
- Modify (재사용 시): `templates/admin/settings/account.html` 의 enroll 내용·recoveryCodes 조각을 공유 fragment 로 추출할지 구현자 재량(Spec §6) — 추출하면 기존 settings 스냅샷 차이는 공백 수준이어야 함
- Modify (필요 시): `static/admin/js/ui.js`(`mfaEnroll` 재사용), `static/admin/js/auth.js`
- Test: `admin-app/src/test/kotlin/com/crosscert/passkey/admin/web/auth/LoginPageControllerTest.kt`, `contract/RenderedHtmlShellAuthContractTest.kt` + 새 스냅샷 `contract/html/auth/mfa-enroll.*.html`

**Interfaces:**
- Consumes: `MfaController.enroll(auth, req)`, `MfaController.confirm(VerifyRequest, auth, req)`(Task 4 가 속성 제거), `MfaSessionState.isEnrollRequired`, `QrSvg.render(uri, 152)`, `RecoveryCodeFilename.of`, `AccountTabController.codeErrorText(status, error)`(internal → 같은 모듈이라 호출 가능)
- Produces: 경로 `POST /admin/login/mfa/enroll`(성공: QR·secret·OTP 폼 조각 → `#mfa-enroll-step`), `POST /admin/login/mfa/confirm`(성공: 복구 코드 다이얼로그 조각; 닫기 → `/admin`; 실패: 422 + `HX-Retarget: #mfa-enroll-error`)

- [ ] **Step 1: 실패 테스트** (`LoginPageControllerTest`)
  - `login_enrollRequired_rendersEnrollPage`: 인증 + `MFA_ENROLL_REQUIRED` → 뷰 `admin/auth/mfa-enroll`, 본문에 Global Constraints 의 안내 문구, "등록 시작" 버튼 `hx-post=/admin/login/mfa/enroll`, 로그아웃 폼, 셸(aside/nav.sidebar) 없음
  - `enrollStart_rendersQr`: enroll 200 → `img[src^=data:image/svg+xml]`, secret 텍스트, OTP 입력 `name=code`, 폼 `hx-post=/admin/login/mfa/confirm`, `Cache-Control: no-store`
  - `enrollConfirm_ok_showsCodesOnce`: confirm 200(recoveryCodes 10개) → 복구 코드 다이얼로그 `[data-rc-code]` 10개, 닫기 후 이동 대상 `/admin`(속성 `data-done-href="/admin"` 또는 구현이 정한 방식 — 테스트에 명시), 헤더·세션·플래시에 코드 없음, no-store
  - `enrollConfirm_invalid_inline422`: 401 invalid_code → 422, `HX-Retarget: #mfa-enroll-error`, 문구 = `AccountTabController.codeErrorText(401,"invalid_code")`
  - `enrollConfirm_secondEnrollReplacesSecret`(Review Focus 4): enroll 두 번 → `MfaController.enroll` 2회 호출, 두 번째 응답의 secret 이 화면에 있음(첫 secret 없음)
  - `enrollEndpoints_notEnrollRequired_redirectHome`: 속성 없는 세션의 두 POST → `HX-Redirect: /admin`, `mfa.enroll/confirm` 미호출
  - `login_afterEnrollCompleted_redirectsHome`(Review Focus 5): 속성 제거된 세션의 `GET /admin/login` → `redirect:/admin`
  - 계약: `RenderedHtmlShellAuthContractTest` 에 `auth/mfa-enroll.page.html`, `auth/mfa-enroll.start.html`, `auth/mfa-enroll.confirm.ok.html`, `auth/mfa-enroll.confirm.form-error.html` snap 추가
- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*LoginPageControllerTest' --tests '*RenderedHtmlShellAuthContractTest'` → FAIL
- [ ] **Step 3: 구현** — 템플릿은 `admin/auth/mfa.html` 의 레이아웃(head·logo·auth-card)을 따르고, QR/secret/OTP/복구 코드 UI 는 설정 탭과 같은 조각·Alpine 컴포넌트를 쓴다. secret·코드 응답은 `noStore`.
- [ ] **Step 4: 통과 확인** — 새 스냅샷을 `-Pcontract.record=true` 로 기록·검토, 기존 스냅샷(settings/·auth/) 차이가 의도한 것뿐인지 `git diff` 확인, 위 명령 PASS
- [ ] **Step 5: 커밋** — `feat(admin): MFA 필수 정책 등록 화면(셸 없음, QR → 코드 확인 → 복구 코드)`

### Task 6: 내 계정 탭 — 정책 켜짐이면 끄기 비활성

**Files:**
- Modify: `admin-app/src/main/kotlin/com/crosscert/passkey/admin/web/settings/AccountTabController.kt` (생성자에 `SecurityPolicyService`, `AccountView` 에 `disableLocked: Boolean`, `codeErrorText` 409 매핑)
- Modify: `templates/admin/settings/account.html` (위험 영역)
- Test: `AccountTabControllerTest.kt`, `contract/RenderedHtmlSettingsContractTest.kt` + 스냅샷

**Interfaces:**
- Produces: `AccountView(email, mfaEnabled, enrolledAt, disableLocked: Boolean = false)`; `codeErrorText(409, "mfa_policy_required")` == Global Constraints 의 끄기 불가 문구

- [ ] **Step 1: 실패 테스트**
  - `on_policyRequired_disableLocked`: mfa=Y + policy=true → "2단계 인증 끄기" 버튼 `disabled`, `x-on:click` 없음, 위험 영역에 끄기 불가 문구, `#mfa-disable-form` 없음; "복구 코드 다시 받기"는 그대로
  - `on_policyReadFails_disableLocked`: policy get throws → 같은 결과
  - `on_policyOff_unchanged`: 기존 `onShowsDangerZone` 그대로 통과
  - `codeErrorText_policyRequired`: `codeErrorText(409, "mfa_policy_required")` == 끄기 불가 문구
- [ ] **Step 2: 실패 확인** — `./gradlew :admin-app:test --tests '*AccountTabControllerTest'` → FAIL
- [ ] **Step 3: 구현** — `populate` 에서 정책 조회(예외 → locked=true). 슬라이스 테스트에 `SecurityPolicyService` 빈이 없으면 MockitoBean 추가(contract 슬라이스 `HtmlContractSlice` 포함).
- [ ] **Step 4: 통과 확인** — 위 + `--tests '*RenderedHtmlSettingsContractTest'`; 정책 off 스냅샷 차이 0, 정책 on 스냅샷 `settings/account.tab.frag.mfa-on.policy-required.html` 새로 기록
- [ ] **Step 5: 커밋** — `feat(admin): MFA 필수 정책 동안 내 계정 탭 끄기 비활성 + 안내`

### Task 7: 통합 테스트 + 문서

**Files:**
- Create: `admin-app/src/test/kotlin/com/crosscert/passkey/admin/auth/MfaRequiredPolicyIT.kt` (`RpAdminBoundaryIT` 의 Testcontainers + `loginAs` + 쿠키 jar 패턴 복사, `classes = [AdminApplication::class]` 명시)
- Modify: `rp-app/scripts/bootstrap-rp-app.sh` (상단 주석), `docs/backlog.md` (F01 완료 처리), `docs/deploy` 또는 `qa-bundle/README.md` 의 운영 안내에 fail-closed 한 줄(정책 조회 실패·CORS 데이터 손상 시 미등록 운영자 전원 등록 요구)

**Interfaces:**
- Consumes: 실제 `TotpService`(secret 으로 현재 코드 계산), 정책 on 은 owner JDBC 로 `security_policy.mfa_required='Y'` 갱신

- [ ] **Step 1: 실패하는 IT** — `MfaRequiredPolicyIT`
  - `policyOn_unenrolledOperator_mustEnrollBeforeUsingConsole`: 정책 on → MFA 미등록 운영자 JSON 로그인 → 응답 `mfaEnrollRequired=true` → `GET /admin/api/tenants` 403 `mfa_enroll_required` → 화면 경로 `POST /admin/login/mfa/enroll`(`HX-Request`) → 응답의 secret 으로 TOTP 계산 → `POST /admin/login/mfa/confirm` 200 → `GET /admin/api/tenants` 200 → audit_log 에 `MFA_ENROLL` 1행(payload forced true)
  - `policyOn_abandonedEnrollment_requiredAgainOnNextLogin`(Review Focus 3): enroll 만 하고 로그아웃 → 재로그인 → `mfaEnrollRequired=true`
  - `policyOn_disableRejected`: MFA 등록된 운영자(위 흐름 후) → `POST /admin/api/mfa/disable` 409 `mfa_policy_required`
  - `policyOff_unenrolledOperator_unchanged`: 정책 off → 로그인 직후 `GET /admin/api/tenants` 200
- [ ] **Step 2: 실패 확인 후 통과까지** — 앞 Task 들이 끝났으면 바로 PASS 여야 한다. 실패하면 원인을 Task 로 되돌려 고친다. `./gradlew :admin-app:test --tests '*MfaRequiredPolicyIT'`
- [ ] **Step 3: 문서** — 스크립트 주석 "MFA 필수 정책 off · MFA 미등록 계정 전제", backlog F01 [x] + 받아들인 위험 한 줄, 운영 안내 fail-closed 한 줄
- [ ] **Step 4: 전체 회귀** — `./gradlew :admin-app:test --continue`; 실패가 있으면 해당 클래스만 단독 재실행해 Oracle 컨테이너 경합(ORA-01917 등 컨텍스트 로드 실패)인지 구분하고, 경합이 아니면 고친다
- [ ] **Step 5: 커밋** — `test(admin): MFA 필수 정책 통합 테스트 + 문서(backlog·운영 안내·bootstrap 전제)`
