# 어드민 가입 요청·승인 흐름 설계

- 작성일: 2026-09-03
- 범위: admin-app, admin-ui, core (마이그레이션·엔티티)
- 상태: 승인 대기

## 1. 배경과 목표

현재 어드민 계정은 PLATFORM_OPERATOR 가 이메일을 넣어 초대하는 방식만 존재한다. 그러나 초대 메일이 가리키는
`/accept-invite` 프론트 페이지가 Phase E1.1 의 admin-ui src 폐기(71ecb005) 때 사라진 뒤 복원되지 않아,
초대받은 사람이 비밀번호를 설정할 수단이 없다. 즉 신규 운영자 온보딩이 실제로 막혀 있다.

이번 작업은 온보딩 경로를 **"로그인 페이지에서 가입 요청 → 기존 PLATFORM_OPERATOR 가 승인"** 방식으로
바꾸고, 동작하지 않는 초대 기능을 제거해 가입 경로를 하나로 단일화한다.

### 확정된 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 비밀번호 설정 시점 | 요청 시 요청자가 직접 입력 | 메일·수락 페이지 없이 동작. 승인 전 계정은 로그인 게이트를 못 넘음 |
| 역할·테넌트 결정 | 승인하는 관리자가 승인 시 지정 | 공개 폼에 내부 구조 비노출. 권한 결정은 관리자 전담 |
| 기존 초대 기능 | 제거(코드·테이블·UI) | 이미 동작하지 않음. 관리자는 "가입 요청하세요" 안내 후 승인하면 충분 |
| 거절 처리 | 요청 행 삭제 | 감사 로그로 이력 추적. 같은 이메일 재요청 자연스럽게 허용 |
| 결과 알림 | 승인·거절 모두 메일 발송, 실패 무시 | 기존 MailSender(SMTP 미설정 시 로그) 재사용 |
| 남용 방어 | 열거 방지 응답 + 대기 건수 상한 100 | 로그인·비밀번호 찾기와 같은 수준. 레이트리밋은 앞단 프록시 |
| 저장 구조 | 별도 테이블 `ADMIN_SIGNUP_REQUEST` | 계정 테이블에는 승인된 계정만 존재. 역할 NOT NULL 제약 무영향 |

## 2. 데이터 모델

마이그레이션 `core/src/main/resources/db/migration/V5__admin_signup_request.sql` 하나로 처리한다.

### 2.1 신규 테이블 `admin_signup_request`

| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | RAW(16) | PK, DEFAULT SYS_GUID() |
| email | VARCHAR2(255) | NOT NULL, UNIQUE (`uq_admin_signup_request_email`) |
| bcrypt_hash | VARCHAR2(72) | NOT NULL |
| reason | VARCHAR2(500) | NULL 허용 |
| requested_at | TIMESTAMP(6) WITH TIME ZONE | NOT NULL, DEFAULT SYSTIMESTAMP |

GRANT: `PSK_APP_ADMIN` 에 SELECT, INSERT, DELETE. UPDATE 는 부여하지 않는다(갱신 경로 없음).
`PSK_APP_RUNTIME` 에는 아무 권한도 주지 않는다(passkey-app 이 읽을 이유 없음).

### 2.2 초대 테이블 제거

- `DROP TABLE admin_user_invitation` (인덱스 `ix_admin_user_invitation_user` 동반 삭제)
- `DROP SEQUENCE admin_user_invitation_seq`

`admin_user.status` 의 CHECK 제약(`ACTIVE`,`PENDING`,`SUSPENDED`)은 그대로 둔다. PENDING 은 더 이상 생성되지
않을 뿐이며 제약 변경은 이번 목적과 무관하다.

### 2.3 엔티티·리포지토리 (core)

- `AdminSignupRequest` 엔티티: 위 컬럼 매핑. 시각은 프로젝트 관례대로 `OffsetDateTime`.
- `AdminSignupRequestRepository`:
  - `existsByEmail(String)`
  - `long count()` (대기 상한 판정)
  - `List<AdminSignupRequest> findAllByOrderByRequestedAtAsc()`
  - `@Modifying int deleteByIdReturningCount(UUID id)` — 경합 판정용. 0 이면 이미 처리됨
  - `@Modifying int deleteRequestedBefore(OffsetDateTime cutoff, int batch)` — 보존 정리용
- `AdminUserInvitation` 엔티티·`AdminUserInvitationRepository` 삭제.

## 3. 백엔드 (admin-app)

### 3.1 공개 엔드포인트

`POST /admin/api/signup-requests` — 비인증, CSRF 제외.

요청 본문:

```json
{ "email": "a@b.c", "password": "12자 이상 128자 이하", "reason": "선택, 500자 이하" }
```

응답: 입력 검증(형식·길이) 실패를 제외하면 **결과와 무관하게 항상 202** 와 동일 본문
`{"accepted": true}` 를 돌려준다. 이메일 존재 여부를 응답으로 구분할 수 없어야 한다.

내부 동작(`SignupRequestService.request`):
1. `admin_user` 또는 `admin_signup_request` 에 같은 이메일이 있으면 저장하지 않고 마스킹 이메일로 WARN 로그.
2. 대기 건수가 100 이상이면 저장하지 않고 WARN 로그.
3. 그 외에는 bcrypt 해시(기존 `PasswordEncoder`)와 함께 저장.
4. 어느 경우든 예외 없이 반환. 저장 시도 중 UNIQUE 위반(동시 요청)은 잡아서 조용히 무시.

요청 시점에 관리자에게 보내는 알림 메일은 두지 않는다. 운영자 탭의 대기 건수 표시로 대신한다.

### 3.2 관리 엔드포인트

컨트롤러 클래스에 `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")`.

| 메서드 | 경로 | 본문 | 응답 |
|---|---|---|---|
| GET | `/admin/api/signup-requests` | – | `[{id, email, reason, requestedAt}]` 요청 시각 오름차순 |
| POST | `/admin/api/signup-requests/{id}/approve` | `{role, tenantIds}` | 생성된 `AdminUserDto.View` |
| POST | `/admin/api/signup-requests/{id}/reject` | – | 204 |

`approve` 동작(`SignupRequestService.approve`, 단일 트랜잭션):
1. 역할 검증: `PLATFORM_OPERATOR` 또는 `RP_ADMIN` 만 허용. RP_ADMIN 은 tenantIds 1개 이상, PLATFORM_OPERATOR 는
   0개. 위반 시 400 (`IllegalArgumentException`, 기존 초대 로직의 규칙을 그대로 옮김).
2. 요청 행 조회. 없으면 404.
3. `admin_user` 에 같은 이메일이 이미 있으면 409.
4. `AdminUser.create()` 로 계정 생성: email, bcryptHash(요청의 해시 복사), role, status=ACTIVE, enabled=Y,
   createdBy=승인자 이메일. 테넌트 매핑은 `AdminUserTenant.of(...)` 로 `LinkedHashSet` 중복 제거 후 저장.
5. 요청 행을 건수 반환 삭제. **0건이면 다른 관리자가 먼저 처리한 것이므로 409 로 롤백**(계정 이중 생성 방지).
6. 감사 체인 `audit.append(... "ADMIN_SIGNUP_APPROVE", "ADMIN_USER", 생성된 id, {role, tenantCount})`.
7. 결과 메일(제목 "관리자 계정 승인 — Passkey2", 본문에 로그인 URL). try/catch 로 실패 무시.

`reject` 동작:
1. 요청 행 조회. 없으면 404.
2. 건수 반환 삭제. 0건이면 409.
3. 감사 체인 `"ADMIN_SIGNUP_REJECT", "ADMIN_SIGNUP_REQUEST", 요청 id, {}`.
4. 결과 메일(제목 "관리자 계정 요청 거절 — Passkey2"). 실패 무시.

### 3.3 보안 설정 (`AdminSecurityConfig`)

- 추가: `.requestMatchers(HttpMethod.POST, "/admin/api/signup-requests").permitAll()` — 이 한 건만 공개.
  GET 과 `/{id}/...` 하위 경로는 기본 `authenticated()` 에 걸리고 컨트롤러 @PreAuthorize 가 역할을 강제한다.
- 추가: CSRF `ignoringRequestMatchers` 에 같은 POST 매처.
- 제거: `/admin/api/invitations/**` 의 permitAll 과 CSRF 예외.
- `admin.invite.base-url` 속성은 `PasswordResetService` 가 같이 쓰므로 이름을 유지한다. 승인 메일의 로그인 URL
  도 이 속성으로 만든다(`baseUrl + "/admin"`). yml 주석만 "초대" 표현을 "공개 페이지(비밀번호 재설정·승인 메일)"
  로 고친다. `BaseUrlValidation` 의 검증은 그대로 재사용한다.

### 3.4 보존 정리

`RetentionPurgeJob` 의 초대 정리 슬롯을 요청 정리로 교체한다.

- 속성 `passkey.retention.invitation` → `passkey.retention.signup-request` (기본 `P90D`)
- `RetentionPurgeService.purgeInvitations` → `purgeSignupRequests(cutoff)`: `requested_at < cutoff` 인 행을
  배치 삭제. 방치된 요청이 대기 상한 100 을 영구히 점유하지 못하게 한다.
- 잡 payload 키 `invitationsPurged` → `signupRequestsPurged`.

### 3.5 제거 목록

- `InvitationService`, `InvitationController`
- `AdminUserDto` 의 `InviteRequest`, `InviteResponse`, `InvitationInfo`, `InvitationCheck`, `AcceptRequest`
- `AdminUserService.invite`, `resendInvitation` 와 `AdminUserController` 의 `POST /admin-users`,
  `POST /{id}/invitation/resend`
- `PasswordResetService` 주석의 InvitationService 참조 문구
- 테스트: `AdminUserInvitationFlowIT`, `InvitationServiceAcceptTest`, `AdminUserInvitationExpiryTest`,
  `AdminUserServiceTest` 의 invite 케이스, 슬라이스 테스트 14개의 `AdminUserInvitationRepository` MockBean 줄
- 문서·README 에 남은 초대 절차 설명(있으면 가입 요청 절차로 교체)

## 4. 프론트엔드 (admin-ui)

### 4.1 로그인 페이지

"비밀번호를 잊으셨나요?" 링크 옆에 "계정이 없으신가요? 가입 요청" 링크를 추가한다. 대상 `/admin/signup`.

### 4.2 가입 요청 페이지 (`SignupRequestPage.tsx`, 신규 공개 페이지)

- `ForgotPasswordPage` 와 같은 미니멀 레이아웃.
- 필드: 이메일, 비밀번호, 비밀번호 확인, 요청 사유(선택, 500자).
- 클라이언트 검증: 이메일 형식, 비밀번호 12~128자, 확인 일치.
- 제출 후 항상 "요청이 접수되었습니다. 관리자 승인 후 로그인할 수 있습니다." 와 로그인으로 돌아가기 링크를 표시.
- `App.tsx` 의 `isPublicPath` 와 조기 렌더 분기에 `/signup` 추가. `adminFetch` 의 401 리다이렉트 예외 경로에
  `/admin/signup` 추가.

### 4.3 운영자 탭 (`AdminUsersTab.tsx`)

- 상단에 **가입 요청** 섹션: 대기 건수 배지 + 목록(이메일, 사유, 요청 시각 상대 표시, 승인·거절 버튼).
  대기 0건이면 "대기 중인 요청이 없습니다" 한 줄.
- **승인 다이얼로그**: 기존 `NewAdminDialog` 의 역할 선택·테넌트 다중 선택 부분을 재활용해 `ApproveDialog` 로
  개편. 이메일은 읽기 전용 표시. 성공 시 요청 목록과 운영자 목록 모두 갱신, 토스트.
- **거절**: 앱 내 확인 모달(브라우저 `confirm` 사용 금지) 후 호출. 성공 시 목록 갱신, 토스트.
- 409 응답은 "이미 다른 관리자가 처리한 요청입니다" 토스트 후 목록 갱신.
- 제거: "새 운영자 초대" 버튼, `InvitationModal`, 재발송 버튼, 관련 상태.
- `api/adminUsers.ts`: `invite`, `resendInvitation`, 초대 타입 제거. `signupRequests` API(`list`, `approve`,
  `reject`) 추가. `api/types.ts` 의 초대 타입 제거.

## 5. 테스트

### 5.1 서비스 단위 (`SignupRequestServiceTest`, Mockito)

- request: 신규 이메일 → 저장 1회, 해시는 encoder 결과
- request: 계정 테이블에 존재 → 저장 0회, 예외 없음
- request: 요청 테이블에 존재 → 저장 0회
- request: 대기 100건 → 저장 0회
- request: 저장 중 UNIQUE 위반 → 예외 삼킴
- approve: 정상 → 계정 ACTIVE/enabled/createdBy, 매핑 저장, 감사 1회, 메일 1회
- approve: RP_ADMIN 테넌트 없음 → 400
- approve: PLATFORM_OPERATOR 테넌트 있음 → 400
- approve: 계정 이메일 중복 → 409
- approve: 삭제 0건 → 409, 계정 저장 롤백
- approve/reject: 메일 실패 → 처리는 성공
- reject: 정상 → 삭제·감사·메일

### 5.2 컨트롤러 슬라이스 (`SignupRequestControllerSecurityTest`)

- POST 공개: 미인증 202, CSRF 없이 통과
- GET·approve·reject: 미인증 401, RP_ADMIN 403, PLATFORM_OPERATOR 통과
- POST 본문 검증: 11자 비밀번호 400

### 5.3 통합 (`SignupRequestFlowIT`, Testcontainers Oracle)

요청 → PLATFORM_OPERATOR 로 목록 조회 → 승인(RP_ADMIN + 테넌트 1개) → 요청자 로그인 성공 → 요청 목록 비어 있음.
기존 `AdminUserInvitationFlowIT` 를 대체한다. V5 마이그레이션 적용 여부도 이 IT 로 검증된다.

### 5.4 프론트 (vitest)

- `SignupRequestPage`: 비밀번호 불일치 시 제출 차단, 제출 성공 시 접수 문구 렌더
- `AdminUsersTab`: 대기 요청 1건 렌더, 승인 클릭 시 다이얼로그에 이메일 읽기 전용 표시

## 6. 배포 시 주의

- V5 는 초대 테이블을 DROP 한다. 실행 전 미수락 초대가 남아 있어도 복구 대상이 아니다(어차피 수락 불가).
- `passkey.retention.invitation` 을 override 하던 환경변수가 있으면 `passkey.retention.signup-request` 로 바꾼다.
- `ADMIN_INVITE_BASE_URL` 환경변수는 이름 그대로 유지된다.
- V5 적용 후 초대 흐름이 남긴 미완료 계정(`status='PENDING'`, `bcrypt_hash IS NULL`)은 완료 수단이 없으므로 한 번 정리한다 —
  `admin_user_tenant.admin_user_id` FK 가 `ON DELETE CASCADE` 이므로 `admin_user` 만 지우면 된다(PSK_APP_OWNER 로 실행):
  ```sql
  DELETE FROM admin_user WHERE status = 'PENDING' AND bcrypt_hash IS NULL;
  ```

## 7. 범위 밖

- 앱 레벨 IP 레이트리밋
- 요청 시점의 관리자 알림 메일
- 계정 STATUS CHECK 제약 정리
- RP_ADMIN 의 승인 권한(현재 PLATFORM_OPERATOR 전용 유지)
