-- ============================================================
-- V25 — audit_log 에 per-tenant hash chain 컬럼 추가
--
-- 목적: Phase B — 테넌트 별 독립 hash chain 지원.
--   tenant_prev_hash : 동일 tenant 내 직전 row 의 hash (최초 row = NULL)
--   tenant_hash      : 직전 tenant hash + 현재 row 데이터 로 계산한 SHA-256
--
-- 정책:
--   - 기존 row 의 tenant_prev_hash / tenant_hash 는 NULL (백필 안 함).
--     백필은 별도 admin endpoint 가 처리한다.
--   - FK 없음 — tenant 삭제 후에도 audit 은 forensic 으로 남아야 함.
--   - 권한 변경 없음 — V10/V13 기존 grant 가 컬럼 추가 후 자동 상속.
--
-- Idempotency:
--   ALTER ADD   → ORA-01430 (column already exists) 를 EXCEPTION 으로 swallow.
--   CREATE INDEX → ORA-00955 (name already used) 를 EXCEPTION 으로 swallow.
--   Flyway repair 외에도 재실행 안전.
-- ============================================================

-- 1. tenant_prev_hash 컬럼 추가 (RAW(32) NULL)
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE audit_log ADD (tenant_prev_hash RAW(32))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1430 THEN NULL; -- column already exists
    ELSE RAISE;
    END IF;
END;
/

-- 2. tenant_hash 컬럼 추가 (RAW(32) NULL)
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE audit_log ADD (tenant_hash RAW(32))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1430 THEN NULL; -- column already exists
    ELSE RAISE;
    END IF;
END;
/

-- 3. 인덱스: (tenant_id, id) — per-tenant chain head 조회 (ORDER BY id DESC LIMIT 1) 최적화
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX audit_log_tenant_seq_ix ON audit_log (tenant_id, id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/
