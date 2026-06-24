-- V42 — (의도적 no-op) VPD(DBMS_RLS) 관련 정의는 제거되었습니다.
-- 원래 api_key_lookup_pkg.touch_last_used 를 명시 파라미터(p_tenant_id)로
-- 재정의해 VPD off 에서도 동작하게 했습니다. 패키지는 V52에서 제거됨.
-- ApiKey 룩업/touch 는 native 쿼리(ApiKeyLookupService)가 전담합니다.
-- 기배포 DB의 실제 객체 제거는 V52__drop_vpd.sql 가 수행합니다.
-- 파일/버전은 Flyway 히스토리 연속성을 위해 보존합니다.
SELECT 1 FROM dual;
