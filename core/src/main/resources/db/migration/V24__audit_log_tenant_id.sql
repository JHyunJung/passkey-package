-- ============================================================
-- V24 — audit_log.tenant_id 컬럼 추가
--
-- 목적: PLATFORM_OPERATOR Activity 페이지 + RP_ADMIN audit 격리.
--
-- 결정: hash chain (V10 SHA-256) 의 입력 포맷 변경 없음 — tenant_id 는
-- 순수 metadata. payload 안의 'tenantId' 키가 이미 hash 에 포함되어 tamper
-- evidence 보존. 기존 row 의 tenant_id 는 NULL — backfill 안 함.
--
-- Idempotency: ALTER ADD / CREATE INDEX 는 재실행 시 ORA-01430 / ORA-00955
-- 로 실패. EXCEPTION 으로 감싸 멱등 (Flyway repair 외에도 안전).
-- ============================================================

-- 1. tenant_id 컬럼 추가 (RAW(16) NULL) — ORA-01430 (column already exists) swallow
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE audit_log ADD (tenant_id RAW(16))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1430 THEN NULL; -- column already exists
    ELSE RAISE;
    END IF;
END;
/

-- 2. 인덱스: tenant_id + created_at — ORA-00955 (name already used) swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX audit_log_tenant_ix ON audit_log (tenant_id, created_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/

-- 3. FK 안 둠 (의도) — tenant 가 삭제돼도 audit 은 forensic 으로 남아야 함.
--    tenant 삭제는 admin-role-separation phase 의 fk_admin_user_tenant 가
--    이미 막고 있어 dangling 위험 없음. 단순화 위해 FK 생략.
--
-- 4. 권한 변경 없음 — APP_USER 가 이미 audit_log 에 SELECT/INSERT 갖고 있음
--    (V10 테이블 생성 + V13 runtime grants). 컬럼 추가는 기존 grant 를 그대로 상속.
