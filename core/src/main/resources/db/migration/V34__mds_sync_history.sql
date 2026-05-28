-- ============================================================
-- V34 — mds_sync_history (MDS 동기화 이력 테이블)
--
-- 목적: MdsStatusTab "최근 동기화 이력" 카드 실데이터화 (Gap #17).
-- MdsSchedulerService.runOnce() 호출 시마다 한 row append.
-- Idempotent: CREATE wrapped in EXCEPTION (ORA-00955).
-- ============================================================

DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE TABLE mds_sync_history (
    id              NUMBER(19,0)             NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at     TIMESTAMP WITH TIME ZONE,
    version         NUMBER(19,0),
    status          VARCHAR2(16) NOT NULL,
    change_summary  VARCHAR2(128),
    duration_ms     NUMBER(10,0),
    error_message   VARCHAR2(500),
    CONSTRAINT pk_mds_sync_history PRIMARY KEY (id),
    CONSTRAINT ck_mds_sync_history_status CHECK (status IN (''SYNCED'',''SKIPPED'',''FAILED''))
  )';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

DECLARE
  e_already_exists EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_already_exists, -955);
BEGIN
  EXECUTE IMMEDIATE 'CREATE SEQUENCE mds_sync_history_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE';
EXCEPTION
  WHEN e_already_exists THEN NULL;
END;
/

-- CREATE INDEX idempotency: re-run can raise either
--   ORA-00955 (name already used by another object — same index name)  OR
--   ORA-01408 (such column list already indexed — different name, same column set).
-- Catch both via SQLCODE so the migration is safe to replay (matches V25 pattern).
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_mds_sync_history_started_at ON mds_sync_history (started_at DESC)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 OR SQLCODE = -1408 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

GRANT SELECT, INSERT ON mds_sync_history TO APP_ADMIN;
GRANT SELECT ON mds_sync_history_seq TO APP_ADMIN;
