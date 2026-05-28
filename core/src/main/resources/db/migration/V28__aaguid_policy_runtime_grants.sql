-- ============================================================
-- V28 — AAGUID Policy + WebAuthn Snapshot runtime grants
--
-- 목적: passkey-app (APP_RUNTIME) 이 부팅 시 Hibernate ddl-validate 를
--   통과하고, 등록 ceremony 에서 정책을 조회할 수 있도록 SELECT 부여.
--
-- 패턴: V12 (admin_user), V13 (audit_log), V16 (signing_key) 와 동일한
--   "runtime_grants" 파일 분리 규칙.
--
-- 권한 결정:
--   tenant_aaguid_policy, tenant_aaguid_policy_entry
--     → passkey-app 등록 ceremony 에서 정책 SELECT 필요. DML 은 admin-app 전용.
--   tenant_webauthn_snapshot, tenant_webauthn_snapshot_seq
--     → passkey-app 은 snapshot 을 직접 INSERT/UPDATE 하지 않음.
--       Hibernate ddl-validate 를 위한 SELECT + sequence 접근만 부여.
--       DML 은 V27 에서 APP_ADMIN 에 부여됨.
-- ============================================================

GRANT SELECT ON tenant_aaguid_policy TO APP_RUNTIME;
GRANT SELECT ON tenant_aaguid_policy_entry TO APP_RUNTIME;
GRANT SELECT ON tenant_webauthn_snapshot TO APP_RUNTIME;
GRANT SELECT ON tenant_webauthn_snapshot_seq TO APP_RUNTIME;
