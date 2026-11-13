# F01 — 보안 정책 "MFA 필수" 강제화 설계

- 작성: 2026-11-13
- 대상 모듈: `admin-app` (core 엔티티·스키마 변경 없음)
- 백로그: `docs/backlog.md` §1 F01 (zero-base 재감사 2026-07-04)

## 1. 왜

설정 › 보안 정책의 "모든 운영자에게 MFA 필수" 스위치는 `security_policy.mfa_required` 에 저장만 되고 어디서도 읽히지 않는다.
로그인 성공 처리(`AdminSecurityConfig.adminLoginSuccessHandler`)는 운영자 개인의 `AdminUser.isMfaEnabled` 만 보고
`MFA_PENDING` 을 붙인다. 그래서 정책을 켜도 MFA 미등록 운영자는 비밀번호만으로 계속 로그인한다.
화면 설명("켜면 MFA 를 등록하지 않은 운영자는 다음 로그인 때 등록해야 합니다.")과 실제 동작이 다르고, 관리자가
"필수로 해 뒀다"고 믿게 만드는 **보안 거짓 표시**다.

## 2. 목표 / 비목표 / 실패 기준

**목표**
1. 정책이 켜져 있고 MFA 미등록인 운영자는 로그인 직후 **MFA 등록을 끝내기 전까지 콘솔의 어떤 화면·API 에도 접근하지 못한다.**
2. 등록은 셸 없는 단독 화면에서 기존 3단계(QR 스캔 → 코드 확인 → 복구 코드 저장)로 끝낸다.
3. 정책이 켜져 있는 동안 운영자가 스스로 MFA 를 끌 수 없다.

**비목표**
- 다른 운영자의 MFA 초기화 기능(별도 백로그). 단, 초기화된 운영자가 이 강제 흐름을 그대로 타도록 상태 판정을 운영자 플래그 기준으로 둔다.
- 패스키를 MFA 요소로 쓰기(별도 기능). MFA 요소는 기존 TOTP + 복구 코드 그대로.
- 유예 기간·리마인더·등록 마감일.
- 정책을 켤 때 이미 로그인해 있는 세션 끊기 — 다음 로그인(세션 만료·재로그인)부터 강제한다(사용자 결정).

**실패 기준 (기술적으로 돌아가도 실패)**
- 등록 필요 세션이 허용 목록 밖의 화면·API(특히 `/admin/api/**`, `/admin/_/**` 조각, 다른 POST)에 하나라도 닿는다.
- 정책 조회 실패 시 MFA 미등록 운영자가 그냥 통과한다(fail-open).
- 정책이 꺼져 있을 때 기존 로그인·MFA 동작이 조금이라도 바뀐다.
- 등록 도중 이탈(로그아웃·창 닫기)한 운영자가 다음 로그인 때 등록 없이 통과한다.

## 3. 현재 구조 (구현자가 알아야 할 것)

- `MfaPendingFilter`(admin-app `auth/`): 세션 속성 `MFA_PENDING=TRUE` 이면 허용 목록 외 요청을 막는다.
  API(`/admin/api/**`)는 `403 {"error":"mfa_required"}`, 그 밖 화면 경로는 `/admin/login` 리다이렉트(htmx 면 `HX-Redirect`).
  허용 목록: `/admin/api/mfa/verify`, `/admin/api/me`, `/admin/logout`, `GET /admin/login`, `POST /admin/login/mfa`, 정적 `/admin/{css,js,fonts,img}/**`.
- `AdminSecurityConfig.adminLoginSuccessHandler`: `u.isMfaEnabled` 면 `MFA_PENDING` 세팅, 화면 로그인은 `/admin/login`(MFA 단계) 또는 `/admin` 으로 리다이렉트.
  이 핸들러는 이미 `SecurityPolicyService` 를 주입받아 idle timeout 을 읽는다(실패 시 기본값 + warn).
- `LoginPageController`: `GET /admin/login` 이 인증·pending 이면 `admin/auth/mfa` 를 그린다. `POST /admin/login/mfa` 가 `MfaController.verify` 를 메서드로 부른다.
- `MfaController`: `enroll`(secret 생성·봉인, `mfaEnabled=N` 유지) → `confirm`(TOTP 확인 시 `mfaEnabled=Y` + 복구 코드 10개 발급).
  enroll/confirm/disable/recovery-codes 는 `isPending`(=`MFA_PENDING`) 이면 403 — **새 상태 속성은 이 검사에 걸리지 않으므로** enroll/confirm 을 그대로 부를 수 있다.
- 설정 › 내 계정 탭(`AccountTabController` + `templates/admin/settings/account.html` + `static/admin/js/ui.js` 의 `mfaEnroll`·`recoveryCodes`)이
  켜기 3단계 UI(QR data URI = `QrSvg`, secretBox, otpInput, 복구 코드 1회 표시 다이얼로그·복사·다운로드·"저장했습니다" 체크)를 이미 갖고 있다.
- `SecurityPolicyService.get()` 은 싱글턴 행이 없거나 **CORS allowlist JSON 이 깨져 있으면**(`deserialize`) `IllegalStateException`.
  따라서 CORS 데이터 손상도 fail-closed(미등록 운영자 전원 등록 요구·끄기 거부)로 이어진다 — 의도된 동작이며 운영 안내에 적는다.
- 정책 값은 화면 표시용으로만 읽힌다(`SecurityPolicyTabController`, `SecurityPolicyController`) — 강제 지점이 없다.

## 4. 설계

### 4.1 세션 상태

새 세션 속성 `MFA_ENROLL_REQUIRED`(값 `TRUE`). `MFA_PENDING` 과 동시에 붙지 않는다.

로그인 성공 시 판정(`adminLoginSuccessHandler`):

| 운영자 `isMfaEnabled` | 정책 `mfaRequired` | 세션 |
|---|---|---|
| Y | (무관) | `MFA_PENDING` (현행 그대로) |
| N | Y | `MFA_ENROLL_REQUIRED` |
| N | N | 없음 (현행 그대로) |
| N | **조회 실패** | `MFA_ENROLL_REQUIRED` (fail-closed) + warn 로그 |

- 정책 조회는 idle timeout 과 같은 `policy.get()` 한 번으로 둘 다 읽는다(두 번 조회하지 않는다). idle timeout 의 기존 폴백(기본 30분)은 그대로.
- JSON 로그인 응답(`mfaRequired` 키)은 기존 의미(=`MFA_PENDING`) 유지. 새 키 `mfaEnrollRequired` 를 추가한다.
- 화면 로그인 리다이렉트: `MFA_PENDING` 또는 `MFA_ENROLL_REQUIRED` 면 `/admin/login`, 아니면 `/admin`.

### 4.2 차단 필터

`MfaPendingFilter` 를 두 상태를 모두 다루도록 확장한다(필터를 하나 더 만들지 않는다 — 체인 순서·우회 위험을 늘리지 않기 위해).

- `MFA_ENROLL_REQUIRED` 세션의 허용 목록:
  `GET /admin/login`(등록 화면), `POST /admin/login/mfa/enroll`, `POST /admin/login/mfa/confirm`, `/admin/api/me`, `/admin/logout`, 정적 에셋.
  **`/admin/api/mfa/*`(JSON API) 는 허용하지 않는다** — 화면 경로가 컨트롤러 메서드를 직접 부르므로 필요 없고, 허용 면을 최소로 둔다.
- 차단 응답: API 는 `403 {"error":"mfa_enroll_required"}`, 화면은 `/admin/login` 리다이렉트(현행 방식과 동일).
- `MFA_PENDING` 의 허용 목록·응답은 바꾸지 않는다.

### 4.3 등록 화면 (`GET /admin/login`, 셸 없음)

`LoginPageController.login`: 인증 + `MFA_ENROLL_REQUIRED` 면 새 템플릿 `admin/auth/mfa-enroll` 을 그린다.

화면 구성(로그인 화면과 같은 `auth` 레이아웃, 로고, 카드):
1. 안내: "보안 정책에 따라 2단계 인증을 등록해야 콘솔을 쓸 수 있습니다."
2. "등록 시작" → `POST /admin/login/mfa/enroll` → `MfaController.enroll` → QR + 수동 입력 키 + 6자리 OTP 입력 조각.
   (설정 탭 enroll 다이얼로그의 내용 조각을 공유 fragment 로 빼서 재사용하는 것이 바람직 — 구현자 판단, §6.)
3. 코드 제출 → `POST /admin/login/mfa/confirm` → `MfaController.confirm`.
   - 성공: 세션에서 `MFA_ENROLL_REQUIRED` 제거 → 복구 코드 1회 표시(기존 `recoveryCodes` 다이얼로그·Alpine 그대로: 복사·다운로드·"저장했습니다" 체크 후 닫기).
     닫기("계속") → `/admin`. 이 시점에 `MFA_PENDING` 을 붙이지 않는다(방금 TOTP 를 확인했다).
   - 401 invalid_code: 입력칸 아래 인라인(설정 탭과 같은 문구 `CODE_MISMATCH`, 422 + `HX-Retarget`).
   - 그 밖 실패·예외: 쉬운 문구 인라인, 원문·secret 비노출.
4. 로그아웃 링크(다른 계정으로 로그인).

보안·표시 규칙(설정 탭과 동일): secret·복구 코드는 응답 본문에만, `Cache-Control: no-store`, 다이얼로그 루트 `hx-history="false"`, 로그에 코드·secret 미기록.

이탈 처리: enroll 은 `mfaEnabled=N` 을 유지하므로 confirm 전에 떠나면 다음 로그인 때 다시 `MFA_ENROLL_REQUIRED` 가 된다(새 secret 으로 다시 시작).

`POST /admin/login/mfa/enroll|confirm` 은 `MFA_ENROLL_REQUIRED` 세션에서만 동작하고, 아니면 `/admin` 으로 리다이렉트(기존 `verifyMfa` 의 "pending 아니면 /admin" 과 같은 패턴). CSRF 유지.

### 4.4 정책이 켜진 동안 MFA 끄기 금지

- `MfaController.disable`: TOTP 검증 **전에** 정책을 확인해 `mfaRequired` 면 `409 {"error":"mfa_policy_required"}`, 정책 조회 실패도 409(fail-closed). secret·플래그 불변.
  (검증 전 확인: 정책상 불가능한 요청에 코드 시도 기회를 주지 않는다.)
- 설정 › 내 계정 탭: 정책이 켜져 있으면 "2단계 인증 끄기" 버튼을 비활성(`disabled`)으로 그리고 위험 영역 설명을
  "보안 정책상 모든 운영자에게 2단계 인증이 필수라 끌 수 없습니다." 로 바꾼다. 끄기 확인창은 그리지 않는다.
  정책 조회 실패 시 화면은 버튼을 비활성으로 그린다(서버도 409 라 일관).
- `AccountTabController.codeErrorText` 에 409 `mfa_policy_required` → 위 문구 매핑(직접 POST 대비).

### 4.5 `/admin/api/me`

`MeView` 에 `mfaEnrollRequired: Boolean`(세션 속성) 추가. 기존 `mfaRequired`(=`MFA_PENDING`) 의미는 유지. 계약 스냅샷 갱신.

### 4.6 감사·로그

- **강제 등록 완료 시 감사 이벤트 `MFA_ENROLL`** (`/admin/login/mfa/confirm` 성공 시, actor=본인, targetType `ADMIN_USER`,
  targetId=운영자 id, tenantId null, payload `{"forced": true}` — secret·코드 없음). Activity 라벨 "MFA 등록", ops 분류.
  설정 탭의 자발적 켜기(`/admin/_/settings/mfa/confirm`)에도 같은 이벤트를 `{"forced": false}` 로 남긴다(탐지 일관성).
  감사 append 실패가 등록 성공을 되돌리지는 않는다 — confirm(=MFA 켜짐·코드 발급) **뒤에** 기록하고, 실패는 error 로그(이 경우 코드 유실 위험이 없으므로
  복구 코드 재발급과 달리 뒤에 둔다).
- 로그인 성공 로그에 `mfaEnrollRequired` 추가(email 은 기존처럼 마스킹).
- 정책 조회 실패로 fail-closed 판정 시 warn.

### 4.7 받아들인 위험 (사용자 결정 2026-11-13)

비밀번호만 탈취된 미등록 계정은 공격자가 먼저 자기 TOTP 를 등록할 수 있다(이후 진짜 운영자는 MFA 단계에서 막힘).
업계 표준(로그인 후 등록 강제)과 같은 위험으로 **받아들인다**. 완화: `MFA_ENROLL` 감사로 Activity 에서 탐지,
복구는 "다른 운영자의 MFA 초기화"(백로그)로. 등록 전 추가 본인 확인(이메일 OTP·승인)은 하지 않는다.

### 4.8 JSON 로그인 클라이언트

저장소 안 JSON 로그인 사용처는 개발용 `rp-app/scripts/bootstrap-rp-app.sh` 뿐이며, 이 스크립트는 지금도 MFA 켠 계정으로는
동작하지 않는다. 정책이 켜지면 MFA 미등록 계정으로도 동작하지 않게 된다(등록 필요 세션에 `/admin/api/mfa/*` 미개방 유지 — 내 판단).
스크립트 상단 주석에 "MFA 필수 정책 off·MFA 미등록 계정 전제"를 적는다.

## 5. 테스트 요구

- 로그인 성공 핸들러: 4개 판정 행(§4.1 표) 각각. 정책 조회 예외 → `MFA_ENROLL_REQUIRED`. 정책 off 회귀(세션 속성·리다이렉트·JSON 이 현행과 동일).
- 필터: `MFA_ENROLL_REQUIRED` 세션으로 허용 목록 각 경로 통과 / 대표 차단 경로(`/admin`, `/admin/_/settings/tab/account`, `/admin/api/tenants`, `/admin/api/mfa/enroll`, `/admin/api/mfa/verify`, `POST /admin/login/mfa`) 차단·응답 형식.
  `MFA_PENDING` 경로 회귀.
- 등록 화면: `GET /admin/login` 렌더, enroll → QR 조각, confirm 성공 → 속성 제거 + 복구 코드(no-store, 헤더·세션에 코드 없음), 실패 인라인, 비대상 세션이면 `/admin`.
- 통합(IT, 실제 보안 체인): 정책 on + 미등록 운영자 로그인 → 업무 API 403 → 등록 완료 → 업무 API 200. 등록 중 로그아웃 → 재로그인 시 다시 등록 필요.
- disable: 정책 on → 409(검증 전, 플래그 불변), 정책 조회 실패 → 409, 정책 off → 현행.
- fail-closed 원인 두 가지(싱글턴 행 없음, CORS JSON 손상) 각각으로 로그인 판정이 `MFA_ENROLL_REQUIRED` 가 되는지.
- `MFA_ENROLL` 감사: 강제 등록(`forced:true`)·설정 탭 켜기(`forced:false`) 각각 기록, 감사 실패 시에도 등록 성공 응답·복구 코드 표시 유지.
- 내 계정 탭: 정책 on 이면 끄기 비활성 + 문구, off 면 현행 스냅샷 동일.
- HTML 계약 스냅샷: 새 화면 기록, 기존 스냅샷은 의도한 차이만.

## 6. 구현자 재량 (결과가 같으면 자유)

- 설정 탭 enroll/recoveryCodes 조각을 공유 fragment 로 추출할지, 단독 화면용으로 따로 둘지(중복 최소화 권장, 기존 스냅샷 차이는 공백 수준이어야 함).
- 상태 판정 헬퍼의 위치(예: `MfaSessionState` 같은 작은 객체로 두 속성 판정을 모으기).
- 등록 화면 Alpine 컴포넌트를 `mfaEnroll` 재사용할지 새로 둘지.

## 7. 내가 정한 것 (사용자 확인 대상)

- fail-closed: 정책 조회 실패 시 등록 요구·끄기 거부.
- JSON API `/admin/api/mfa/*` 는 등록 필요 세션에 열지 않음(화면 경로만).
- 등록 완료 직후 `MFA_PENDING` 미부여(같은 세션에서 방금 TOTP 확인).
- 끄기 금지는 TOTP 검증 전에 판정.
- `MFA_ENROLL` 감사는 등록 성공 뒤 기록(실패해도 등록 유지), 설정 탭 자발적 켜기에도 기록.
- bootstrap 스크립트는 고치지 않고 전제만 주석으로.
