-- ============================================================
-- V38 — api_key.tenant_id 인덱스 추가
--
-- 목적: api_key 에는 PK(id) / UNIQUE(key_prefix) / FK(tenant_id) 만 있고
-- tenant_id 단독 인덱스가 어느 마이그레이션에도 없다. Oracle 은 FK 만으로
-- 자식측 인덱스를 자동 생성하지 않으므로 tenant_id 로 필터하는 쿼리
-- (ApiKeyRepository.countActiveByTenantId — 대시보드 KPI 카드마다 호출,
--  findActiveByTenantId — TenantLifecycleService.suspend 일괄 revoke) 가
--  전부 api_key 풀 테이블 스캔이 된다.
--
-- 결정: active 판정(revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now))
-- 까지 커버하도록 복합 인덱스 (tenant_id, revoked_at, expires_at) 로 만든다.
-- 선두 컬럼 tenant_id 단독 prefix 도 인덱스로 사용 가능하므로 단순 tenant_id
-- 필터(findByTenantId 류 미래 쿼리)도 커버된다.
--
-- Idempotency: CREATE INDEX 재실행 시 ORA-00955 (name already used) 로 실패.
-- V24/V25 와 동일하게 EXCEPTION 으로 감싸 멱등.
--
-- 권한: 인덱스는 별도 GRANT 불요 — api_key 테이블 권한(V13 runtime grants)을
-- 그대로 상속한다. 코드/UI/동작 변화 없음 (순수 스키마 추가).
-- ============================================================

-- 인덱스: (tenant_id, revoked_at, expires_at) — ORA-00955 (name already used) swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_api_key_tenant ON api_key (tenant_id, revoked_at, expires_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/
