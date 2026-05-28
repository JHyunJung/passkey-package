-- ============================================================
-- V30 — Admin User Invitation runtime grants
--
-- 목적: passkey-app (APP_RUNTIME) 의 Hibernate ddl-validate 통과.
--   passkey-app 은 admin_user_invitation 을 직접 INSERT/UPDATE 하지 않으나
--   EntityManager 가 entity 전체를 스캔하므로 SELECT 권한 + sequence 접근만
--   부여한다. 실제 DML 은 V29 에서 APP_ADMIN 에 부여됨.
--
--   admin_user 신규 컬럼 (status/created_by/suspended_at/suspended_by) 의
--   SELECT 권한은 기존 V9/V12 grant 가 컬럼 추가 시 자동 상속이라 무관.
--
-- 패턴: V12, V13, V16, V28 의 "runtime_grants" 분리 규칙 일관.
-- ============================================================

GRANT SELECT ON admin_user_invitation TO APP_RUNTIME;
GRANT SELECT ON admin_user_invitation_seq TO APP_RUNTIME;
