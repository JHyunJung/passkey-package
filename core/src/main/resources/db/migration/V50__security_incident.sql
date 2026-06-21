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
    CONSTRAINT ck_security_incident_status CHECK (status IN (''OPEN'',''RESOLVED''))
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- 2. 테넌트당 OPEN 1건 강제 (함수 기반 부분 유니크 인덱스)
DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955); -- ORA-00955 name already used
  e_index_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_index_exists, -1408);  -- ORA-01408 such column list already indexed
BEGIN
  EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX ux_incident_open_per_tenant
    ON security_incident (CASE WHEN status = ''OPEN'' THEN tenant_id END)';
EXCEPTION
  WHEN e_already_exists THEN NULL;
  WHEN e_index_exists THEN NULL;
END;
/

-- 3. Grants (one per statement; APP_ADMIN 런타임 계정)
GRANT SELECT ON security_incident TO APP_ADMIN;
GRANT INSERT ON security_incident TO APP_ADMIN;
GRANT UPDATE ON security_incident TO APP_ADMIN;
