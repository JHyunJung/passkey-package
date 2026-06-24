-- V8 — (의도적 no-op) VPD(DBMS_RLS) 정책은 제거되었습니다.
-- 원래 API_KEY_TENANT_ISOLATION 정책과 definer-rights api_key_lookup_pkg 를 생성했습니다.
-- api_key VPD 정책과 definer-rights api_key_lookup_pkg 제거. ApiKey 룩업은 native 쿼리(ApiKeyLookupService).
-- 테넌트 격리는 앱 레벨 Hibernate @Filter(TenantFilterAspect)가 전담합니다.
-- 기배포 DB의 실제 객체 제거는 V52__drop_vpd.sql 가 수행합니다.
-- 파일/버전은 Flyway 히스토리 연속성을 위해 보존합니다.
SELECT 1 FROM dual;
