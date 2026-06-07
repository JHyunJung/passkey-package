-- ============================================================
-- V42 — api_key_lookup_pkg.touch_last_used 가 VPD off 에서도 동작하고
--        테넌트 격리도 명시적으로 유지하도록 보정
--
-- 문제: 기존 touch_last_used 의 UPDATE WHERE 절이
--   tenant_id = HEXTORAW(SYS_CONTEXT('APP_CTX','TENANT_ID'))
-- 에 의존했다. APP_CTX.TENANT_ID 는 VPD on 일 때만 TenantAwareDataSource 가
-- ctx_pkg.set_tenant 로 채운다. dev/SE2 처럼 VPD off(passkey.vpd.enabled=false)
-- 로 운영하면 SYS_CONTEXT 가 NULL → UPDATE 0행 → api_key.last_used_at 이 영영
-- 갱신되지 않는다(touch 는 best-effort 라 조용히 실패). RP 요청은 정상이지만
-- "마지막 사용" 이 화면에 안 뜬다.
--
-- 결정: 암묵적 세션 컨텍스트(SYS_CONTEXT) 대신, 인증 단계에서 이미 확보한
-- 테넌트 id 를 호출자가 명시적 파라미터(p_tenant_id)로 넘겨 WHERE 에 쓴다.
--   WHERE id = p_id AND tenant_id = p_tenant_id
-- - VPD on/off 무관하게 정확히 동작(컨텍스트 의존 제거).
-- - 테넌트 격리 유지: 이 패키지는 AUTHID DEFINER 라 APP_OWNER 권한으로 돌며
--   VPD 를 우회한다. 컨텍스트 부재를 "인가" 로 취급(tenant_id=tenant_id)하면
--   id 만 알면 타 테넌트 행을 건드릴 수 있으므로, 호출자가 인증한 tenant_id 를
--   강제로 일치시키는 명시 검증이 더 안전하다.
-- 호출자(ApiKeyAuthFilter)는 find_by_prefix 가 돌려준 row.tenantId() 를 그대로
-- 넘긴다(인증된 그 키의 테넌트).
--
-- SPEC(헤더) 시그니처가 바뀌므로 PACKAGE + PACKAGE BODY 를 모두 재정의한다.
-- find_by_prefix 는 변경 없음(V19 정의 그대로 재포함).
-- ============================================================

CREATE OR REPLACE PACKAGE APP_OWNER.api_key_lookup_pkg AUTHID DEFINER AS

  PROCEDURE find_by_prefix(
    p_prefix       IN  VARCHAR2,
    p_found        OUT NUMBER,
    p_id           OUT RAW,
    p_tenant_id    OUT RAW,
    p_key_hash     OUT VARCHAR2,
    p_expires_at   OUT TIMESTAMP WITH TIME ZONE,
    p_revoked_at   OUT TIMESTAMP WITH TIME ZONE);

  PROCEDURE touch_last_used(
    p_id        IN RAW,
    p_tenant_id IN RAW,
    p_now       IN TIMESTAMP WITH TIME ZONE);

END api_key_lookup_pkg;
/

CREATE OR REPLACE PACKAGE BODY APP_OWNER.api_key_lookup_pkg AS

  PROCEDURE find_by_prefix(
    p_prefix       IN  VARCHAR2,
    p_found        OUT NUMBER,
    p_id           OUT RAW,
    p_tenant_id    OUT RAW,
    p_key_hash     OUT VARCHAR2,
    p_expires_at   OUT TIMESTAMP WITH TIME ZONE,
    p_revoked_at   OUT TIMESTAMP WITH TIME ZONE) IS
  BEGIN
    SELECT id, tenant_id, key_hash, expires_at, revoked_at
      INTO p_id, p_tenant_id, p_key_hash, p_expires_at, p_revoked_at
      FROM api_key
     WHERE key_prefix = p_prefix;
    p_found := 1;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      p_found := 0;
      p_id := NULL;
      p_tenant_id := NULL;
      p_key_hash := NULL;
      p_expires_at := NULL;
      p_revoked_at := NULL;
  END find_by_prefix;

  PROCEDURE touch_last_used(
    p_id        IN RAW,
    p_tenant_id IN RAW,
    p_now       IN TIMESTAMP WITH TIME ZONE) IS
  BEGIN
    UPDATE api_key
       SET last_used_at = p_now
     WHERE id = p_id
       AND tenant_id = p_tenant_id;
  END touch_last_used;

END api_key_lookup_pkg;
/
