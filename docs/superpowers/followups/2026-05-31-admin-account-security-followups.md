# Admin 계정 보안 마감 (그룹 A) — Follow-ups

- **작성일**: 2026-05-31
- **브랜치**: `worktree-admin-account-security`
- **spec**: [2026-05-30-admin-account-security-hardening-design.md](../specs/2026-05-30-admin-account-security-hardening-design.md)
- **plan**: [2026-05-30-admin-account-security-hardening.md](../plans/2026-05-30-admin-account-security-hardening.md)

그룹 A(P0 잔여 A·B + P1-6)를 7개 Task로 마감했다. subagent-driven 실행 + Task별 spec/code-quality 2단계 리뷰를 거쳤다. 아래는 리뷰에서 **의도적으로 범위 밖으로 미룬** cross-cutting/minor 항목과 후속 권고다 (deferred-by-design — 누락 아님).

---

## 1. 테스트 헬퍼 중복 (cross-cutting tech debt)

`sha256Hex` + `hex` 바이트→헥스 헬퍼가 이제 4곳에 복제됨: `InvitationService`, `RecoveryCodeService`, `PasswordResetService`, 그리고 `AuditChainVerifier`/`AuditLogService` 등. `maskEmail`도 `InvitationService`·`PasswordResetService`·`AdminSecurityConfig`에 복제(InvitationService는 주석으로 "Mirrors AdminSecurityConfig.maskEmail"). `private static final SecureRandom RNG`도 중복.

- **권고**: `core`에 `TokenHashing`/`AdminTokens` 유틸 추출(`sha256Hex(String)`, `hex(byte[])`, `randomToken(prefix)`, `maskEmail(String)`) 후 호출부 마이그레이션. **다섯 번째 토큰 흐름이 생기기 전에** 정리 권장. 이번 phase는 기존 패턴 일관성을 위해 복제를 따랐다.

## 2. `mfa_secret` 평문→암호문 백필 부재

V37 이전 평문 secret은 `MfaSecretCipher.open()`의 passthrough로 무중단 읽기는 되나, **다음 enroll/confirm 전까지 평문으로 남는다**. seed/소수 운영자라 실害 낮음.

- **권고**: 운영자 수가 늘면 일회성 백필(전 평문 secret을 seal로 재저장) 배치 고려. 현재는 자연 전환에 의존.

## 3. `request()` password-reset 타이밍 사이드채널

존재하는 이메일은 DB save + 동기 `mail.send`를, 없는 이메일은 즉시 return → 응답 시간 차로 계정 존재 추론 가능(낮은 심각도, 미인증·저가치·상위 rate-limit). InvitationService도 동일 posture.

- **권고**: 신경 쓰면 메일 발송을 async dispatch해 양 분기 즉시 반환. 현재 acceptable.

## 4. 예외 타입 불일치 (InvitationService vs PasswordResetService)

`PasswordResetService.confirm()`은 `IllegalArgumentException`(→400, bad client input으로 적절), 평행 흐름인 `InvitationService.accept()`/`lookupValid()`는 `IllegalStateException`("Invalid token"/"Token expired"/"Token already used")을 던짐. 둘 다 400 매핑되나 의도적으로 mirror한 두 흐름의 타입이 다름.

- **권고**: 통일하거나 한 줄 주석으로 의도 명시. trivia-level.

## 5. lockout 동시성 (낮은 위험, 범위 밖)

`adminLoginFailureHandler`의 `findByEmail → recordFailedLogin → save`는 `@Version` 없이 lost-update 가능. 단, **공격자가 race해도 자기 lockout만 가속**(defender-favorable)이고 정상 사용자 더블서브밋은 드물어 무해. optimistic locking은 hot 경로에 `OptimisticLockException` 노이즈를 더하므로 도입 안 함(의도).

- **권고**: 변경 불필요. 인지만.

## 6. password-reset confirm TOCTOU (의도적 용인)

`confirm()`의 `findByTokenHash → isConsumed 체크 → consume`은 동시 confirm race 존재하나, 토큰이 설계상 1회용·operator-initiated·단일 inbox라 최악이 last-writer-wins 재설정(가치 누출 없음). recovery code(`consume`)와 달리 conditional update 미사용 — 코드에 주석으로 명시함.

- **권고**: 변경 불필요. 진짜 belt-and-suspenders 원하면 `markUsed` 패턴의 conditional update(~5줄)로 닫을 수 있음.

---

## 7. codex 독립 리뷰 미실행 (전 Task 공통)

메모리 지침(커밋 전 `/codex review`)을 따르려 했으나 전 Task에서 OpenAI usage limit(resets Jun 1)로 **codex 독립 리뷰 미실행**. 대신 Task별 spec + code-quality subagent 2단계 리뷰로 게이트.

- **권고**: 6/1 quota 리셋 후 누적 diff(`f435cf2..HEAD`)에 `/codex review` 한 번. 특히 V37 idempotency, MfaSecretCipher fallback, recovery `markUsed` 조건부 update, lockout 핸들러, password-reset enumeration.

## 8. admin-ui 후속 (별도 phase)

이번 그룹 A는 백엔드 API만. 운영자 UI가 필요한 것:
- **MFA recovery code 표시 화면**: confirm 응답의 `recoveryCodes` 10개를 1회 표시(인쇄/저장 유도) + verify 화면에서 TOTP 대신 recovery code 입력 허용.
- **password reset 화면**: `/reset-password?token=` 랜딩 + `request`(이메일 입력) 화면.
- **lockout UX**: 잠긴 계정 로그인 시 안내(단, enumeration 방지 위해 일반 실패와 동일 메시지 유지할지 결정 필요).

## 9. 환경 메모

admin-app의 Oracle Testcontainers `*IT`는 이 환경에서 flaky(전 phase followups §5와 동일). 그룹 A 검증은 **core 전체 테스트(VPD IT 포함, V37 Flyway 적용 확인) + admin-app 단위/슬라이스 108개 green**으로 게이트. V37은 core IT의 Flyway 부팅에서 "Successfully applied 37 migrations ... v37"로 clean 적용 확인됨(신규 엔티티 ddl-validate 통과 포함). admin-app IT는 CI/충분한 리소스 환경에서 재실행 권장.
