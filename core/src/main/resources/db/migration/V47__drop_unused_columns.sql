-- ============================================================
-- V47 — 미사용 컬럼 정리
--
-- 코드 전수조사 결과 읽는 경로가 없는 컬럼 제거:
--   1. credential.backup_state (CLOB) + ck_credential_backup_state CHECK
--      — Credential.java 에 매핑만 있고 getter 호출 0. webauthn 의
--        backupState(BS 비트)는 무관한 별개.
--   2. mds_blob_cache.blob_jwt (CLOB) — MdsBlobStore 가 raw SQL 로 쓰기만,
--        읽는 코드 없음("감사·재검증용" 의도 미구현).
--
-- DROP COLUMN 은 NOT NULL 과 무관하게 동작 — V36 류 "MODIFY BLOB NOT NULL →
-- ORA-22296" 함정 해당 없음.
--
-- 멱등: 이미 없는 컬럼/제약은 EXCEPTION 으로 무시(재실행·환경차 안전).
--   ORA-00904 = 컬럼 없음, ORA-02443 = 제약 없음.
-- ============================================================

-- 1. credential CHECK 제약 먼저 제거 (컬럼보다 선행)
DECLARE
  e_constraint_missing EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_constraint_missing, -2443);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE credential DROP CONSTRAINT ck_credential_backup_state';
EXCEPTION
  WHEN e_constraint_missing THEN NULL;
END;
/

-- 2. credential.backup_state 컬럼 제거
DECLARE
  e_column_missing EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_missing, -904);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE credential DROP COLUMN backup_state';
EXCEPTION
  WHEN e_column_missing THEN NULL;
END;
/

-- 3. mds_blob_cache.blob_jwt 컬럼 제거
DECLARE
  e_column_missing EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_missing, -904);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE mds_blob_cache DROP COLUMN blob_jwt';
EXCEPTION
  WHEN e_column_missing THEN NULL;
END;
/
