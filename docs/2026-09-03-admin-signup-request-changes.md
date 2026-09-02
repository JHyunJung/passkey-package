# 어드민 가입 방식 전환 — 변경 사항과 후속 조치

어드민 콘솔의 운영자 온보딩을 **초대 메일 방식**에서 **로그인 화면 가입 요청 → PLATFORM_OPERATOR 승인**
방식으로 바꾼 변경(main 머지 `9d81f6f5`, 2026-09-03)의 요약과, 이 변경 때문에 배포·운영에서 해야 하는
일을 정리한 문서입니다.

설계 근거: [`docs/superpowers/specs/2026-09-03-admin-signup-request-approval-design.md`](superpowers/specs/2026-09-03-admin-signup-request-approval-design.md)

## 1. 왜 바꿨나

기존 초대 메일이 가리키던 `/accept-invite` 화면이 admin-ui 재작성(Phase E1.1) 때 사라진 뒤 복원되지 않아,
초대받은 사람이 비밀번호를 설정할 방법이 없었습니다. 즉 신규 운영자 온보딩이 실제로 막혀 있었습니다.
이번 변경으로 가입 경로를 하나로 단일화하고 동작하지 않던 초대 기능은 전부 제거했습니다.

## 2. 무엇이 바뀌었나

### 2.1 사용자 흐름

| 단계 | 이전(초대) | 이후(가입 요청) |
|---|---|---|
| 시작 | 운영자가 설정 › Admin 사용자에서 이메일 입력 후 초대 | 신규 사용자가 로그인 화면의 **"계정이 없으신가요? 가입 요청"** 링크로 `/admin/signup` 진입 |
| 비밀번호 | 초대 링크에서 수락 시 설정(화면 없음 → 불가) | 요청 시 본인이 직접 입력(12~128자) |
| 역할·RP | 초대 시 운영자가 지정 | **승인 시** 운영자가 지정(PLATFORM_OPERATOR 또는 RP_ADMIN + 테넌트) |
| 승인 | 없음 | 설정 › Admin 사용자 탭 상단 **가입 요청** 카드에서 승인/거절 |
| 결과 | – | 승인 즉시 로그인 가능. 승인·거절 결과는 메일로 통지(SMTP 미설정 시 로그만) |

- 가입 요청 폼은 이메일·비밀번호·비밀번호 확인·요청 사유(선택, 500자)입니다.
- 요청 응답은 결과와 무관하게 항상 같은 안내("요청이 접수되었습니다…")입니다. 이미 있는 이메일·이미 대기 중인
  이메일·대기 상한 도달은 조용히 무시되며, 응답 시간으로도 구분되지 않도록 bcrypt 를 항상 먼저 계산합니다.
- 거절은 요청을 삭제합니다. 같은 이메일로 다시 요청할 수 있고, 이력은 감사 로그(`ADMIN_SIGNUP_REJECT`)에 남습니다.
- 두 운영자가 같은 요청을 동시에 처리하면 한쪽만 성공하고 다른 쪽은 "이미 처리된 요청입니다" 를 받습니다.

### 2.2 데이터베이스

- **V5 마이그레이션** `core/src/main/resources/db/migration/V5__admin_signup_request.sql`
  - `admin_signup_request` 테이블 신설: `id`, `email`(UNIQUE), `bcrypt_hash`, `reason`, `requested_at`. FK 없음.
  - GRANT: `PSK_APP_ADMIN` 에 SELECT/INSERT/DELETE, `PSK_APP_RUNTIME` 에 SELECT(스키마 validate 용).
  - `admin_user_invitation` 테이블과 `admin_user_invitation_seq` 시퀀스 **DROP**.
- `admin_user` 에는 승인된 계정만 생성됩니다(status=ACTIVE, enabled=Y). `status` CHECK 의 `PENDING` 값은 남아
  있지만 더 이상 생성되지 않습니다.
- 수동 설치용 `scripts/full-schema-part2-schema.sql` 도 같은 내용으로 동기화했습니다.

### 2.3 백엔드 (admin-app)

| 항목 | 내용 |
|---|---|
| 공개 API | `POST /admin/api/signup-requests` — 비인증, CSRF 면제(이 POST 한 건만), 항상 `202 {"accepted":true}` |
| 관리 API | `GET /admin/api/signup-requests`, `POST /{id}/approve` `{role, tenantIds}`, `POST /{id}/reject` — PLATFORM_OPERATOR 전용 |
| 대기 상한 | 미처리 요청 100건 도달 시 새 요청은 저장하지 않음(WARN 로그) |
| 보존 정리 | 일일 retention 잡이 `passkey.retention.signup-request`(기본 P90D) 지난 미처리 요청 삭제 |
| 감사 로그 | `ADMIN_SIGNUP_APPROVE`(target ADMIN_USER), `ADMIN_SIGNUP_REJECT`(target ADMIN_SIGNUP_REQUEST) |
| 메일 | 승인/거절 결과 메일. `spring.mail.host` 미설정 시 로그 출력으로 대체, 실패해도 처리는 성공 |
| 제거 | `InvitationService`/`InvitationController`, `/admin/api/invitations/**`, `AdminUserService.invite/resendInvitation`, `POST /admin/api/admin-users`, `POST /admin/api/admin-users/{id}/invitation/resend`, `AdminUserInvitation` 엔티티·리포지토리 |
| 유지 | `admin.invite.base-url`(환경변수 `ADMIN_INVITE_BASE_URL`) — 이름은 그대로, 비밀번호 재설정 링크와 승인 메일의 로그인 링크에 사용 |

### 2.4 프론트엔드 (admin-ui)

- 신규 공개 페이지 `/admin/signup`(`SignupRequestPage`), 로그인 화면 링크.
- 설정 › Admin 사용자 탭: 가입 요청 카드(대기 건수 배지, 승인 다이얼로그, 거절 확인 모달) 추가.
  "운영자 추가" 버튼, 초대 링크 모달, 재발송 버튼 제거.
- 승인·거절이 실패하면(409 제외) 다이얼로그를 유지해 재시도할 수 있습니다.

### 2.5 문서

`docs/logging-operations.md`(가입 요청 WARN 4종), `docs/entity-relationship-diagram.md`, `docs/logging-conventions.md`,
`deploy/README.md`(마이그레이션 후속 조치) 갱신.

## 3. 바뀌어서 해야 하는 일

### 3.1 배포 전

1. **환경변수 이름 변경 확인** — `passkey.retention.invitation` 을 override 하고 있었다면
   `passkey.retention.signup-request` 로 바꿉니다(환경변수 형태 `PASSKEY_RETENTION_SIGNUP_REQUEST`). 옛 이름은
   더 이상 읽히지 않으며 기본값 P90D 가 적용됩니다.
2. **`ADMIN_INVITE_BASE_URL` 은 그대로 둡니다.** prod 프로필은 이 값이 localhost 기본값이면 부팅을 거부합니다.
   승인 메일의 로그인 링크가 `<이 값>/admin` 으로 만들어지므로 실제 콘솔 주소여야 합니다.
3. **SMTP** — 승인/거절 통지를 메일로 보내려면 `SPRING_MAIL_HOST` 등 기존 메일 설정이 있어야 합니다. 없으면
   로그에만 남고 요청자는 로그인 화면에서 직접 시도해 확인해야 합니다.
4. **앞단 프록시 레이트리밋** — 공개 엔드포인트 `POST /admin/api/signup-requests` 와 `/admin/signup` 에도
   로그인·비밀번호 찾기와 같은 수준의 레이트리밋을 적용합니다. 앱은 IP 레이트리밋을 하지 않습니다.

### 3.2 DB 마이그레이션

- **Flyway 경로(기본)**: 앱 기동 시 V5 가 자동 적용됩니다. 별도 조치 없음. V5 는 forward-only 이며 초대 테이블은
  복구되지 않습니다(어차피 수락 불가 상태였으므로 데이터 손실로 보지 않습니다).
- **수동 설치 경로(DBeaver, `scripts/full-schema-part*.sql`)**: 갱신된 part2 스크립트를 사용합니다. 이전 버전으로
  만든 DB 에 새 앱을 올리면 `admin_signup_request` 가 없어 `ddl-auto: validate` 에서 부팅이 실패합니다.
- **초대 시절 잔여 계정 정리(1회)**: 초대만 하고 수락되지 않은 계정(`status='PENDING'`, `bcrypt_hash IS NULL`)은
  완료 수단이 없어 운영자 탭에 영구히 남습니다. 로그인은 불가능하므로 보안 문제는 아니지만 혼란을 막기 위해
  PSK_APP_OWNER 로 한 번 실행합니다(`admin_user_tenant` 는 FK cascade 로 함께 삭제됨):

  ```sql
  DELETE FROM admin_user WHERE status = 'PENDING' AND bcrypt_hash IS NULL;
  ```

### 3.3 배포 후 확인

1. 비로그인 상태로 `https://<콘솔>/admin/signup` 이 열리는지(404 가 아닌 가입 폼) 확인합니다.
2. 테스트 이메일로 가입 요청 → PLATFORM_OPERATOR 로 로그인 → 설정 › Admin 사용자 탭에 대기 1건 표시 →
   승인(RP_ADMIN + 테넌트 선택) → 해당 이메일로 로그인 성공을 확인합니다.
3. 감사 로그에 `ADMIN_SIGNUP_APPROVE` 가 남는지, SMTP 를 켰다면 승인 메일이 도착하는지 확인합니다.

### 3.4 운영 안내 변경

- 새 운영자 안내 문구를 "초대 메일을 확인하세요" 에서 **"콘솔 로그인 화면의 가입 요청으로 신청하면 승인해 드립니다"**
  로 바꿉니다.
- 운영 대응 표(`docs/logging-operations.md`): `WARN signup request skipped: … reason=pending-cap max=100` 이 보이면
  대기 요청이 100건 찬 것이므로 운영자 탭에서 승인/거절로 정리합니다.

### 3.5 롤백

- 코드 롤백은 가능하지만 DB 는 forward-only 입니다. 롤백한 이전 앱은 `admin_user_invitation` 테이블을 요구하므로
  `ddl-auto: validate` 에서 부팅이 실패합니다. 롤백이 필요하면 V5 를 되돌리는 별도 마이그레이션(테이블·시퀀스 재생성)
  이 함께 필요합니다.

## 4. 남은 후속 작업(머지 차단 아님)

- `approve`/`reject` 에 CSRF 토큰 미첨부 403 부정 테스트 추가.
- `admin-app` 테스트 `operator` 패키지의 중첩 `@SpringBootConfiguration` 정리(IT 는 현재 `classes=` 명시로 회피).
- `AaguidPolicyCeremonyIT`·`WebauthnConfigSnapshotIT` 가 `AdminUserDetails` 에 null `allowedTenantIds` 를 넘기는 잠재 결함.
- `docs/logging-conventions.md` 의 `LogRedact` 권장 문구를 실제 관례(`CryptoUtils.maskEmail`)와 통일.
- main 에 이미 있던 슬라이스 테스트 컨텍스트 로드 실패(`SecurityPolicyService`/`TenantBoundary`/`securityIncidentRepository`
  MockBean 누락, 13개 클래스)는 이번 변경과 무관하며 별도 정리 대상입니다.
