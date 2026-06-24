-- V3 — (의도적 no-op) VPD(DBMS_RLS) 정책은 제거되었습니다.
-- 원래 APP_OWNER.tenant_predicate 함수와 CREDENTIAL_TENANT_ISOLATION 정책을 생성했습니다.
-- 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect)가 전담합니다.
-- 기배포 DB의 실제 객체 제거는 V52__drop_vpd.sql 가 수행합니다.
-- 파일/버전은 Flyway 히스토리 연속성을 위해 보존합니다.
SELECT 1 FROM dual;
