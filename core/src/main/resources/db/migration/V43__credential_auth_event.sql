-- ============================================================
-- V43 — credential_auth_event: credential 단위 인증 이벤트(경량 기록)
--
-- 목적: credential 상세의 "인증 기록"(성공/실패 이력). ceremony_event(V41)는
-- tenant+action 집계라 credential 단위 추적이 불가능하다. hash chain 없는 경량
-- 테이블을 따로 둔다(ceremony_event 와 동일 철학).
--
-- 기록자: passkey-app (APP_RUNTIME) — INSERT.  조회자: admin-app (APP_ADMIN) — SELECT.
-- retention purge: admin-app (APP_ADMIN) — DELETE (RetentionPurgeJob).
-- FK: credential(id) ON DELETE CASCADE — credential 회수(DELETE) 시 이벤트 동반 삭제.
-- VPD: ceremony_event 와 동일하게 미적용(앱 레벨 tenant 격리). 단순 기록 지표.
--
-- Idempotency: ORA-00955(name already used)/ORA-00942/ORA-01917 swallow. V41 패턴.
-- ============================================================

-- 테이블 — ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE credential_auth_event ('
    || 'id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY, '
    || 'credential_id RAW(16) NOT NULL, '
    || 'tenant_id RAW(16) NOT NULL, '
    || 'result VARCHAR2(16) NOT NULL, '
    || 'failure_reason VARCHAR2(64), '
    || 'sign_count NUMBER(19) NOT NULL, '
    || 'created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, '
    || 'updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, '
    || 'CONSTRAINT fk_cred_auth_event_credential '
    || '  FOREIGN KEY (credential_id) REFERENCES credential (id) ON DELETE CASCADE)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- table already exists
    ELSE RAISE;
    END IF;
END;
/

-- 조회 인덱스 (credential_id, created_at DESC) — admin 페이지 조회 커버. ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_cred_auth_event_cred_time '
    || 'ON credential_auth_event (credential_id, created_at DESC)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- retention 인덱스 (created_at) — deleteCreatedBefore 단독 술어용. ORA-00955 swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_cred_auth_event_created_at '
    || 'ON credential_auth_event (created_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 런타임 GRANT (각 GRANT 개별 블록 — 멱등·부분 적용 안전)
BEGIN EXECUTE IMMEDIATE 'GRANT INSERT ON credential_auth_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT ON credential_auth_event TO APP_RUNTIME';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
BEGIN EXECUTE IMMEDIATE 'GRANT SELECT, INSERT, DELETE ON credential_auth_event TO APP_ADMIN';
EXCEPTION WHEN OTHERS THEN IF SQLCODE = -1917 OR SQLCODE = -942 THEN NULL; ELSE RAISE; END IF; END;
/
