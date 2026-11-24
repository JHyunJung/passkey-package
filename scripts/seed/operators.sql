-- ============================================================
-- operators.sql — local·dev·qa 공통 운영자 계정 시드
--
-- scripts/apply-schema.sh 가 PSK_APP_OWNER 로 적용한다(앱은 시드를 넣지 않는다).
-- ⚠️ 운영 DB 에는 적용하지 않는다.
--    임시 비번(alice-temp-pw)은 git 공개 plaintext — 비-운영 전용.
--
-- alice 만 시드. PLATFORM_OPERATOR(tenant_id NULL). bob 은 여기서 시드하지 않는다
-- (dev 는 dev-tenant.sql, 통합 테스트는 admin-app/src/test/db/test-seed.sql).
--
-- Idempotent: email UNIQUE 기준 NOT EXISTS 가드.
-- bcrypt strength 12, plaintext "alice-temp-pw".
--
-- id: admin_user.id 는 RAW(16). 시퀀스 없이 결정적 고정 RAW(alice = 0x...0010)를 쓴다.
-- ============================================================

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
SELECT HEXTORAW('00000000000000000000000000000010'), 'alice@crosscert.com',
       '$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS',
       'PLATFORM_OPERATOR', 'Y', SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM admin_user WHERE email = 'alice@crosscert.com'
);

COMMIT;
