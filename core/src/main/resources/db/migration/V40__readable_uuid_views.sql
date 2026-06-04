-- V40__readable_uuid_views.sql
--
-- 운영/디버깅 편의용 조회 VIEW. 모든 PK/FK 가 RAW(16) 바이너리 UUID 로 저장되어
-- DBeaver 등에서 직접 조회 시 hex 덩어리로 보이고 "어느 테넌트인지" 식별이 어렵다.
-- 이 VIEW 들은 (1) RAW UUID 를 표준 문자열(8-4-4-4-12)로 포맷하고 (2) tenant 를 JOIN 해
-- slug/display_name 을 붙여 사람이 읽게 한다. 저장은 base 테이블 그대로(RAW), VIEW 는
-- 데이터를 복제하지 않는 "저장된 SELECT" 일 뿐이다.
--
-- 대상: TENANT_ID FK 를 가진 8개 테이블.
--   credential, api_key, audit_log, admin_user,
--   tenant_aaguid_policy, tenant_accepted_format, tenant_allowed_origin,
--   tenant_webauthn_snapshot
--
-- 주의사항:
--  * 조회 전용. INSERT/UPDATE 는 base 테이블에 한다(JOIN 뷰는 갱신 불가).
--  * VPD: base 테이블의 tenant isolation 정책은 VIEW 를 통해도 그대로 적용된다.
--    APP_ADMIN 은 EXEMPT ACCESS POLICY 라 전 테넌트가 보이고(운영자 조회용으로 적합),
--    APP_RUNTIME 은 VPD 적용을 받아 APP_CTX.TENANT_ID 가 set 된 경우만 행이 보인다.
--    SE2(FGAC 없음)에서는 VPD 가 설치되지 않아 양쪽 다 전 테넌트가 보인다.
--  * 민감 컬럼은 의도적으로 제외: bcrypt_hash, mfa_secret, key_hash.
--  * 큰 바이너리(BLOB public_key)는 제외, 짧은 RAW(aaguid/hash/user_handle 등)는 RAWTOHEX.
--  * RAW(16) UUID 표준 포맷은 REGEXP_REPLACE 로 8-4-4-4-12 하이픈 삽입. NULL 은 그대로 NULL.

-- credential — 어느 테넌트의 패스키인지 + credential 식별
CREATE OR REPLACE VIEW v_credential AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(c.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(c.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    c.label,
    RAWTOHEX(c.credential_id)               AS credential_id_hex,
    RAWTOHEX(c.user_handle)                  AS user_handle_hex,
    LOWER(RAWTOHEX(c.aaguid))                AS aaguid_hex,
    c.sign_count,
    c.transports,
    c.attestation_fmt,
    c.last_used_at,
    c.created_at,
    c.updated_at
FROM credential c
JOIN tenant t ON t.id = c.tenant_id;

-- api_key — 어느 테넌트의 키인지 (key_hash 제외)
CREATE OR REPLACE VIEW v_api_key AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(a.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(a.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    a.name,
    a.key_prefix,
    a.last_used_at,
    a.expires_at,
    a.revoked_at,
    a.created_at,
    a.updated_at
FROM api_key a
JOIN tenant t ON t.id = a.tenant_id;

-- audit_log — 어느 테넌트·누가 한 행동인지 (tenant_id/actor_id nullable: PLATFORM 행은 NULL)
-- tenant 는 LEFT JOIN (글로벌 체인 행은 tenant_id 가 NULL).
CREATE OR REPLACE VIEW v_audit_log AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(al.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(al.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    al.actor_email,
    LOWER(REGEXP_REPLACE(RAWTOHEX(al.actor_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS actor_id,
    al.action,
    al.target_type,
    al.target_id,
    al.payload,
    al.created_at
FROM audit_log al
LEFT JOIN tenant t ON t.id = al.tenant_id;

-- admin_user — RP_ADMIN 이 어느 테넌트 소속인지 (bcrypt_hash, mfa_secret 제외)
-- tenant_id nullable (PLATFORM_OPERATOR 는 NULL) → LEFT JOIN.
CREATE OR REPLACE VIEW v_admin_user AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(u.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    u.email,
    u.role,
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(u.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    u.enabled,
    u.status,
    u.mfa_enabled,
    u.last_login_at,
    u.failed_login_count,
    u.locked_until,
    u.created_by,
    u.suspended_at,
    u.suspended_by,
    u.created_at,
    u.updated_at
FROM admin_user u
LEFT JOIN tenant t ON t.id = u.tenant_id;

-- tenant_aaguid_policy — 테넌트별 AAGUID 정책 (PK 가 곧 tenant_id)
CREATE OR REPLACE VIEW v_tenant_aaguid_policy AS
SELECT
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(p.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    p.policy_mode,
    p.mds_strict,
    p.updated_by,
    p.created_at,
    p.updated_at
FROM tenant_aaguid_policy p
JOIN tenant t ON t.id = p.tenant_id;

-- tenant_accepted_format
CREATE OR REPLACE VIEW v_tenant_accepted_format AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(f.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(f.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    f.format,
    f.created_at,
    f.updated_at
FROM tenant_accepted_format f
JOIN tenant t ON t.id = f.tenant_id;

-- tenant_allowed_origin
CREATE OR REPLACE VIEW v_tenant_allowed_origin AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(o.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(o.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    o.origin,
    o.sort_order,
    o.created_at,
    o.updated_at
FROM tenant_allowed_origin o
JOIN tenant t ON t.id = o.tenant_id;

-- tenant_webauthn_snapshot (ID 는 NUMBER 시퀀스 PK, tenant_id 만 RAW)
CREATE OR REPLACE VIEW v_tenant_webauthn_snapshot AS
SELECT
    s.id,
    t.slug                                  AS tenant_slug,
    t.display_name                          AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(s.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    s.rp_id,
    s.rp_name,
    s.allowed_origins,
    s.accepted_formats,
    s.require_user_verification,
    s.mds_required,
    s.taken_at,
    s.taken_by
FROM tenant_webauthn_snapshot s
JOIN tenant t ON t.id = s.tenant_id;

-- GRANT: 운영자(APP_ADMIN, VPD EXEMPT — 전 테넌트 조회) + 런타임(APP_RUNTIME, VPD 적용).
-- base 테이블 GRANT 패턴(최근 *runtime_grants* 마이그레이션)과 동일하게 SELECT 만 부여.
GRANT SELECT ON v_credential               TO APP_ADMIN;
GRANT SELECT ON v_credential               TO APP_RUNTIME;
GRANT SELECT ON v_api_key                  TO APP_ADMIN;
GRANT SELECT ON v_api_key                  TO APP_RUNTIME;
GRANT SELECT ON v_audit_log                TO APP_ADMIN;
GRANT SELECT ON v_audit_log                TO APP_RUNTIME;
GRANT SELECT ON v_admin_user               TO APP_ADMIN;
GRANT SELECT ON v_admin_user               TO APP_RUNTIME;
GRANT SELECT ON v_tenant_aaguid_policy     TO APP_ADMIN;
GRANT SELECT ON v_tenant_aaguid_policy     TO APP_RUNTIME;
GRANT SELECT ON v_tenant_accepted_format   TO APP_ADMIN;
GRANT SELECT ON v_tenant_accepted_format   TO APP_RUNTIME;
GRANT SELECT ON v_tenant_allowed_origin    TO APP_ADMIN;
GRANT SELECT ON v_tenant_allowed_origin    TO APP_RUNTIME;
GRANT SELECT ON v_tenant_webauthn_snapshot TO APP_ADMIN;
GRANT SELECT ON v_tenant_webauthn_snapshot TO APP_RUNTIME;
