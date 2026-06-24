-- V35 — (의도적 no-op) VPD(DBMS_RLS) 정책은 제거되었습니다.
-- 원래 테넌트 자식 5개 테이블(TENANT_ALLOWED_ORIGIN/TENANT_ACCEPTED_FORMAT/
-- TENANT_AAGUID_POLICY/TENANT_AAGUID_POLICY_ENTRY/TENANT_WEBAUTHN_SNAPSHOT)에
-- tenant_predicate 기반 row-level isolation 정책을 부착했습니다.
-- 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect)가 전담합니다.
-- 기배포 DB의 실제 객체 제거는 V52__drop_vpd.sql 가 수행합니다.
-- 파일/버전은 Flyway 히스토리 연속성을 위해 보존합니다.
SELECT 1 FROM dual;
