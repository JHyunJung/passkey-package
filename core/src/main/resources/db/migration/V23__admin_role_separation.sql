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

-- 8. demo tenant 신규 — bob 이 소속될 RP. 결정적 UUID 로 idempotent.
--    NOT EXISTS 가드는 (id, slug) 둘 다 체크 — 운영 환경에 'demo-rp' slug 가
--    다른 id 로 이미 있으면 INSERT skip (unique 위반 방지).
INSERT INTO tenant (
  id, slug, display_name, rp_id, rp_name, status,
  require_user_verification, mds_required,
  created_at, updated_at
)
SELECT
  HEXTORAW('0000000000000000000000000000C0DE'),
  'demo-rp', 'Demo RP', 'localhost', 'Demo RP',
  'active', 'Y', 'N',
  SYSTIMESTAMP, SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM tenant
   WHERE id = HEXTORAW('0000000000000000000000000000C0DE')
      OR slug = 'demo-rp'
);

-- 8a. demo tenant 의 allowed_origin (기존 행 없으면 — demo 신규 또는 운영자가 안 채운 경우)
INSERT INTO tenant_allowed_origin (id, tenant_id, origin, sort_order)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'demo-rp'),
       'http://localhost:9090', 0
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_allowed_origin
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
  );

-- 8b. demo tenant 의 accepted_format — none
INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'demo-rp'),
       'none'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
       AND format = 'none'
  );

-- 8c. demo tenant 의 accepted_format — packed
INSERT INTO tenant_accepted_format (id, tenant_id, format)
SELECT SYS_GUID(),
       (SELECT id FROM tenant WHERE slug = 'demo-rp'),
       'packed'
FROM dual
WHERE EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp')
  AND NOT EXISTS (
    SELECT 1 FROM tenant_accepted_format
     WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
       AND format = 'packed'
  );

-- 9. bob 을 demo tenant 의 RP_ADMIN 으로 재지정.
--    slug 가 이미 있고 다른 id 인 경우에도 그 실제 id 를 가리키도록 SELECT.
UPDATE admin_user
   SET role = 'RP_ADMIN',
       tenant_id = (SELECT id FROM tenant WHERE slug = 'demo-rp')
 WHERE email = 'bob@crosscert.com'
   AND EXISTS (SELECT 1 FROM tenant WHERE slug = 'demo-rp');

COMMIT;
