-- ============================================================
-- V51 — drop security_policy.password_min_length
--
-- 목적: "비밀번호 최소 길이" 보안 정책 기능 제거. 정책 검증 컴포넌트
-- (PasswordPolicyValidator)와 함께 폐기되어 더 이상 읽거나 쓰지 않음.
--
-- Idempotent — re-running V51 must succeed:
--   ORA-00904 (invalid identifier) = 컬럼이 이미 없음 → 무시.
-- V31 의 EXCEPTION 래핑 패턴 일관.
-- ============================================================

DECLARE
  e_no_such_column EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_no_such_column, -904);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE security_policy DROP COLUMN password_min_length';
EXCEPTION
  WHEN e_no_such_column THEN NULL;
END;
/
