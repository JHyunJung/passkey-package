-- ============================================================
-- V50 — security_incident (audit chain 위변조 incident 추적)
--
-- AuditChainPage 의 "Incident 생성"을 정식 incident 로 영속화.
-- OPEN→RESOLVED 워크플로. 테넌트당 OPEN 1건 제한은 함수 기반 부분
-- 유니크 인덱스로 DB 레벨 강제.
--
-- Patterns: CREATE 를 ORA-00955 EXCEPTION 으로 래핑(idempotent),
-- GRANT 는 statement 당 한 줄(V31 패턴).
-- ============================================================

-- 1. Table
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE security_incident (
    id                  RAW(16)                  NOT NULL,
    tenant_id           RAW(16)                  NOT NULL,
    tampered_entry_id   RAW(16),
    type                VARCHAR2(64)             NOT NULL,
    severity            VARCHAR2(16)             NOT NULL,
    status              VARCHAR2(16)             NOT NULL,
    detail              VARCHAR2(1024),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          RAW(16)                  NOT NULL,
    resolved_at         TIMESTAMP WITH TIME ZONE,
    resolved_by         RAW(16),
    resolution_note     VARCHAR2(1024),
    CONSTRAINT pk_security_incident PRIMARY KEY (id),
    CONSTRAINT ck_security_incident_status CHECK (status IN (''OPEN'',''RESOLVED'')),
    CONSTRAINT ck_security_incident_severity CHECK (severity IN (''LOW'',''MEDIUM'',''HIGH'',''CRITICAL'')),
    CONSTRAINT ck_security_incident_resolution CHECK (
      (status = ''OPEN''     AND resolved_at IS NULL     AND resolved_by IS NULL     AND resolution_note IS NULL)
      OR
      (status = ''RESOLVED'' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND resolution_note IS NOT NULL)
    )
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- 2. 테넌트당 OPEN 1건 강제 (함수 기반 부분 유니크 인덱스)
--    ORA-00955(이름 중복=재실행)만 swallow. ORA-01408 은 절대 swallow 하지
--    않는다 — 동일 expression 의 non-unique 인덱스가 먼저 있어 CREATE UNIQUE
--    가 01408 로 실패하면 유니크 강제가 안 걸려 보안 invariant 가 붕괴하므로,
--    그 경우는 마이그레이션이 실패해야 한다.
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955); -- ORA-00955 name already used
BEGIN
  EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX ux_incident_open_per_tenant
    ON security_incident (CASE WHEN status = ''OPEN'' THEN tenant_id END)';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- 3. Grants (one per statement; APP_ADMIN 런타임 계정)
--    UPDATE 는 resolve 워크플로에서 바뀌는 컬럼으로만 제한한다. 생성 시점
--    증거 필드(tenant_id, tampered_entry_id, type, severity, detail,
--    created_at, created_by)는 런타임 계정이 수정 불가 → 감사 추적 불변성 유지.
GRANT SELECT ON security_incident TO APP_ADMIN;
GRANT INSERT ON security_incident TO APP_ADMIN;
GRANT UPDATE (status, resolved_at, resolved_by, resolution_note) ON security_incident TO APP_ADMIN;
