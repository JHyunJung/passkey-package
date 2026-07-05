-- ============================================================
-- V3 — admin_user.mfa_failed_count: MFA 전용 실패 카운터 분리 (G05).
--
-- 재현감사 G05: 1차 로그인(비밀번호) 성공이 admin_user.failed_login_count 를
-- 0 으로 리셋한다(recordSuccessfulLogin). MFA 검증 실패(MfaController.verify)
-- 도 같은 컬럼을 증가시키므로, 공격자가 이미 알고 있는 올바른 비밀번호로 매번
-- 재로그인해 카운터를 리셋하면 MFA 단계 무차별 대입 잠금 임계치(max-attempts)
-- 에 영원히 도달하지 않는다 — 사실상 무제한 온라인 TOTP 브루트포스.
--
-- 컬럼은 NULLABLE 아님 + DEFAULT 0(FAILED_LOGIN_COUNT 와 동일 패턴) — 기존
-- 행은 0 으로 채워지고 앱 동작 불변. locked_until 은 계속 공유(비밀번호/MFA
-- 어느 쪽이 잠가도 AdminUserDetails.isAccountNonLocked() 가 1차 로그인까지
-- 함께 차단하는 기존 방어심층 속성 유지) — 분리하는 것은 카운터만이다.
-- ============================================================

ALTER TABLE admin_user ADD (mfa_failed_count NUMBER DEFAULT 0 NOT NULL);
