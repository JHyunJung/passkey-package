-- ============================================================
-- V23 — admin_user role 모델 확장 (ADMIN/VIEWER → PLATFORM_OPERATOR/RP_ADMIN)
-- + tenant_id FK 추가 (RP_ADMIN 의 자기 tenant 매핑)
--
-- 모든 statement 는 plain SQL — PL/SQL block 없이 Flyway 가 직접 파싱.
-- demo tenant id 는 결정적 RAW(16) HEXTORAW('...C0DE') 로 inline.
-- ============================================================

-- 1. tenant_id 컬럼 추가 (RAW(16) NULL) — RP_ADMIN 만 채움
ALTER TABLE admin_user ADD (
  tenant_id RAW(16)
);

-- 2. FK to tenant (cascade restrict — tenant 삭제 전 admin 정리 필요)
ALTER TABLE admin_user ADD CONSTRAINT fk_admin_user_tenant
  FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- 3. 인덱스: RP_ADMIN 의 같은 tenant 조회 효율화
CREATE INDEX ix_admin_user_tenant ON admin_user (tenant_id);

-- 4a. 기존 CHECK 제약 제거 — UPDATE 전에 먼저 DROP 해야 PLATFORM_OPERATOR 값 삽입 가능
ALTER TABLE admin_user DROP CONSTRAINT ck_admin_user_role;

-- 4b. role 컬럼 확장 — PLATFORM_OPERATOR(17자)가 기존 VARCHAR2(16) 에 안 들어가므로 확장
ALTER TABLE admin_user MODIFY (role VARCHAR2(20));

-- 4. 기존 ADMIN/VIEWER → PLATFORM_OPERATOR 일괄 UPDATE
UPDATE admin_user SET role = 'PLATFORM_OPERATOR' WHERE role IN ('ADMIN', 'VIEWER');

-- 6. 신규 CHECK — role enum 2 종
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role
  CHECK (role IN ('PLATFORM_OPERATOR', 'RP_ADMIN'));

-- 7. role <-> tenant_id invariant
ALTER TABLE admin_user ADD CONSTRAINT ck_admin_user_role_tenant
  CHECK (
    (role = 'PLATFORM_OPERATOR' AND tenant_id IS NULL)
    OR
    (role = 'RP_ADMIN' AND tenant_id IS NOT NULL)
  );

-- (구) 로컬 테넌트 시드 + RP_ADMIN 지정은 db/local/R__seed_local_tenant.sql
-- 로 이관됨. test 프로필은 AdminFlowIT.resetState() 가 인라인 재시드한다.

COMMIT;
