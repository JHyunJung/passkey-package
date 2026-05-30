# Admin 계정 보안 마감 (그룹 A) — Design

- **작성일**: 2026-05-30
- **대상**: Crosscert Passkey Platform (core / admin-app / admin-ui)
- **근거 spec**: [2026-05-29-saas-readiness-gap-audit-design.md](2026-05-29-saas-readiness-gap-audit-design.md)
- **선행 followups**: [2026-05-30-saas-launch-hardening-followups.md](../followups/2026-05-30-saas-launch-hardening-followups.md) §2.1, §2.2 / spec §4 P1-6
- **성격**: 구현 설계. 후속 writing-plans의 입력물.

## 1. 목적 / 범위

SaaS gap audit의 P0 잔여 보안 항목과 P1-6을 하나의 "Admin 계정 보안 마감" phase로 묶어 닫는다. 네 기능 모두 `admin_user`·MFA·로그인 경로를 건드리므로 단일 worktree·단일 마이그레이션(V37)으로 통합한다.

| ID | 항목 | 현황 |
|---|---|---|
| P0 잔여 A | MFA recovery-code 발급/소비 | V36 테이블만 존재(schema-only). 엔티티·repo·로직 없음 |
| P0 잔여 B | mfa_secret at-rest 암호화 | `admin_user.mfa_secret` 평문 Base32 저장 |
| P1-6 (1) | Admin account lockout | `AdminUserDetails.isAccountNonLocked()` 하드코딩 `true`, 실패 카운터 없음 |
| P1-6 (2) | Admin password reset (self-service) | invitation flow만 존재, 기존 operator 복구 경로 없음 |

**범위 밖(deferred)**: spec §4의 나머지 P1(P1-1, P1-2, P1-3, P1-4, P1-5, P1-7), P2 전체, RP_ADMIN role 게이팅. 별도 phase.

**원칙**: 기존 인프라 최대 재사용 — `KeyEnvelope`(AES-256-GCM), invitation 토큰 패턴, `MailSender`, `DaoAuthenticationProvider` + 성공/실패 핸들러.

## 2. 데이터 모델 — V37 마이그레이션

V37 한 건. 전 DDL을 EXCEPTION으로 감싸 idempotent (V32/V34/V36 패턴).

### 2.1 `admin_user` 컬럼 변경
- `mfa_secret`: `VARCHAR2(64)` → **`VARCHAR2(255)`** (sealed+base64 ~60–80자 수용; ORA-01441/축소 아님 → 단순 MODIFY, 재실행 시 동일 길이는 무시)
- `failed_login_count NUMBER DEFAULT 0 NOT NULL` 추가 (ORA-01430 무시)
- `locked_until TIMESTAMP WITH TIME ZONE` (nullable) 추가 (ORA-01430 무시)

### 2.2 `admin_password_reset_token` (신설 — `admin_user_invitation` 패턴 복제)
```
id            RAW(16)  DEFAULT SYS_GUID() NOT NULL  PK
admin_user_id RAW(16)  NOT NULL  FK → admin_user(id) ON DELETE CASCADE
token_hash    VARCHAR2(64)  NOT NULL          -- sha-256 hex
expires_at    TIMESTAMP WITH TIME ZONE NOT NULL
consumed_at   TIMESTAMP WITH TIME ZONE         -- nullable, one-shot 마킹
created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
INDEX ix_pwd_reset_token_hash (token_hash)
```
GRANT: `APP_ADMIN` SELECT,INSERT,UPDATE,DELETE / `APP_RUNTIME` SELECT (invitation·recovery 패턴 동일 — passkey-app `@EntityScan` ddl-validate 통과용).

### 2.3 `admin_user_recovery_code`
**이미 V36에 존재** → V37에 추가 DDL 없음. (`id`, `admin_user_id` FK CASCADE, `code_hash VARCHAR2(64)`, `used_at`, `created_at`, `ix_recovery_admin_user`)

## 3. 컴포넌트 설계

### 3.1 (A) MFA recovery-code
- **`AdminUserRecoveryCode`** 엔티티 (`core.entity`) + **`RecoveryCodeRepository`** — `findByAdminUserIdAndUsedAtIsNull`, `deleteByAdminUserId`.
- **`RecoveryCodeService`** (admin-app):
  - `generate(adminUser)`: 기존 코드 전량 삭제(재발급 시 폐기) → 10개 평문 코드(`SecureRandom`, `xxxx-xxxx` 형식) 생성 → sha-256 hash만 저장 → **평문 List 반환(이 호출 1회만 평문 노출)**.
  - `consume(adminUser, code)`: 입력 코드 sha-256 → 미사용 코드 중 hash 매칭 1개를 조건부 UPDATE(`used_at IS NULL`)로 `used_at` 마킹. affected-rows==1이면 성공(one-shot).
  - `remaining(adminUser)`: 미사용 개수.
- **`MfaController.confirm()`** 수정: 성공 시 `generate()` 호출 → 응답에 `recoveryCodes: [...]` 추가.
- **`MfaController.verify()`** 수정: TOTP 실패 시 동일 입력으로 `consume()` 재시도. TOTP 또는 recovery code 성공이면 MFA-pending 해제. recovery code 경로 성공 시 `log.warn`(forensic) + 응답에 `remaining` 포함.

### 3.2 (B) mfa_secret at-rest 암호화
- **`MfaSecretCipher`** (admin-app 얇은 래퍼, `KeyEnvelope` 주입):
  - `seal(base32)` → `"enc:v1:" + base64(KeyEnvelope.seal(base32.getBytes(UTF_8)))`
  - `open(stored)`: `enc:v1:` 프리픽스면 base64 디코드 후 `KeyEnvelope.open` → Base32 평문. **프리픽스 없으면 평문으로 간주해 그대로 반환** (기존 평문 secret 무중단 마이그레이션 경로).
- secret 저장 지점(`enroll`)에서 `seal`, 검증 지점(`verify`/`confirm`/`disable`의 `validCode`)에서 `open`. 평문→암호문 전환은 다음 enroll/confirm 시 자연 발생(별도 백필 배치 없음 — seed/소수라 무해).
- seal/open 실패는 `KeyEnvelope`처럼 generic `IllegalStateException`(secret 미노출).

### 3.3 (C) account lockout (temporal, 고정 임계치)
- **설정값**: `passkey.admin.lockout.max-attempts` (기본 5), `passkey.admin.lockout.duration` (기본 15m).
- **`AdminUser`** 필드 추가 + 도메인 메서드:
  - `recordFailedLogin(now, maxAttempts, duration)`: 카운트++; 임계 도달 시 `lockedUntil = now + duration`, 카운트 리셋.
  - `recordSuccessfulLogin()`: 카운트=0, `lockedUntil=null`.
  - `isLocked(now)`: `lockedUntil != null && now.isBefore(lockedUntil)`.
- **`AdminUserDetails`**: `lockedUntil` 값을 그대로 보관하고 주입된 `Clock`을 받아 `isAccountNonLocked()` = `lockedUntil == null || clock.instant().isAfter(lockedUntil)`로 평가. (Spring Security가 인증 시점에 호출 → 그 시점 기준으로 temporal 자동 해제가 반영됨. boolean snapshot이 아닌 lockedUntil 보관이라야 정확.) `AdminUserDetailsService`가 user의 `lockedUntil`과 `Clock`을 주입.
- **로그인 핸들러** (기존 `AdminSecurityConfig`):
  - 실패 핸들러: 해당 user 로드 → `recordFailedLogin` 저장(기존 audit 기록 유지).
  - 성공 핸들러: `recordSuccessfulLogin` 저장.
- 잠긴 계정 시도는 기존 실패 응답과 **동일 메시지**(enumeration 단서 금지). `lockedUntil`은 audit/log에만.

### 3.4 (D) password reset (self-service, invitation 패턴)
- **`AdminPasswordResetToken`** 엔티티 + **`PasswordResetTokenRepository`** + **`PasswordResetService`**:
  - `request(email)`: user 존재 시 토큰 생성(1h TTL)·sha-256 hash 저장·`MailSender`로 reset 링크 발송.
  - `confirm(token, newPassword)`: hash 매칭·미소비·미만료 검증 → **password_min_length(P0-6) validator 재사용** → bcrypt 재해시 → `consumed_at` 조건부 UPDATE(one-shot) → 성공 시 해당 user `failedLoginCount`/`lockedUntil` 리셋.
- **`PasswordResetController`** (둘 다 `permitAll`, CSRF는 invitation처럼 `ignoringRequestMatchers` 추가):
  - `POST /admin/api/password-reset/request {email}` → **항상 200·동일 본문**(enumeration 방지).
  - `POST /admin/api/password-reset/confirm {token, newPassword}` → 성공/실패 구분 최소화.

## 4. 보안 경계 / 엣지 케이스

- **Recovery code = 2nd-factor 등가물**: `verify`에서만 소비(로그인 MFA 게이트). 평문은 confirm 응답 1회만, 이후 hash만. 재발급 시 기존 전량 폐기.
- **mfa_secret 마이그레이션 안전성**: `open()`의 평문 fallback으로 기존 운영자 무중단. 전환은 점진적.
- **Lockout enumeration 방지**: 잠긴 계정도 일반 실패와 동일 응답. `lockedUntil` 본문 미노출.
- **Password reset enumeration 방지**: request 항상 200·동일 본문. 토큰 sha-256 hash·1회용·1h TTL. confirm 성공 시 lockout 리셋(정당한 복구 경로).
- **CSRF**: password-reset 두 엔드포인트 `ignoringRequestMatchers` 추가(미인증).
- **Race / double-consume**: recovery `consume`·reset `confirm`은 `used_at IS NULL`/`consumed_at IS NULL` 조건부 UPDATE affected-rows로 one-shot 보장.

## 5. 테스트 전략 (TDD)

- **단위**: `RecoveryCodeService`(생성 개수·hash·재발급 폐기·consume one-shot), `MfaSecretCipher`(seal/open round-trip·평문 fallback), `AdminUser` lockout 상태 전이, `PasswordResetService`(TTL·1회용·hash·validator).
- **슬라이스(`@WebMvcTest`)**: `MfaController` confirm가 recoveryCodes 반환·verify가 recovery code 통과, `PasswordResetController` request 200 항상·confirm validator·CSRF ignore.
- **통합(`*IT`, Testcontainers)**: V37 idempotent 적용, lockout end-to-end(5회 실패→lock→duration 후 해제), 기존 평문 secret open. (Oracle IT 환경 flaky는 followups §5처럼 CI 권장으로 기록)

## 6. 커밋 전 게이트

메모리 지침: 커밋 전 `/codex review`(6/1 quota 리셋 후 가능 시 누적 diff) + code quality subagent. 특히 recovery code 소비 경로, MfaSecretCipher fallback, lockout 핸들러.

## 7. 구현 순서(권장)

1. V37 마이그레이션 + 엔티티/repository (스키마 기반 먼저)
2. (B) MfaSecretCipher — secret 경로 안정화 (이후 기능이 secret을 다룸)
3. (A) RecoveryCodeService + MfaController 연동
4. (C) lockout — AdminUser 도메인 + 핸들러 + AdminUserDetails
5. (D) password reset — service + controller + SecurityConfig CSRF
6. 통합 검증 + 게이트
