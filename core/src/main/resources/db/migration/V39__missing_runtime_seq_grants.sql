-- ============================================================
-- V39 — 누락된 runtime sequence grants
--
-- 목적: passkey-app (APP_RUNTIME) 의 Hibernate ddl-validate 통과.
--   V34(mds_sync_history) 와 V37(admin_password_reset_token) 이 해당 시퀀스의
--   SELECT 를 APP_ADMIN 에만 부여하고 APP_RUNTIME 에 부여하지 않아,
--   passkey-app 부팅 시 "Schema-validation: missing sequence" 로 실패했다.
--   (admin_user_invitation_seq=V30, tenant_webauthn_snapshot_seq=V28 은 정상.)
--
--   passkey-app 은 이 시퀀스로 직접 INSERT 하지 않으나 EntityManager 가 core
--   entity 전체를 스캔하므로 SELECT 접근만 부여한다. 실제 DML 은 V34/V37 에서
--   APP_ADMIN 에 부여됨. Oracle GRANT 는 재실행 idempotent.
--
-- 패턴: V28, V30 의 "runtime_grants" 분리 규칙 일관.
-- ============================================================

GRANT SELECT ON mds_sync_history_seq TO APP_RUNTIME;
GRANT SELECT ON admin_password_reset_token_seq TO APP_RUNTIME;
