-- ============================================================
-- R__seed_operators.sql — local·dev·qa 공통 운영자 계정 시드 (repeatable)
--
-- ⚠️ prod 는 이 디렉터리를 flyway.locations 에 포함하지 않는다.
--    임시 비번(alice-temp-pw)은 git 공개 plaintext — 비-운영 전용.
--
-- alice 만 시드. PLATFORM_OPERATOR(tenant_id NULL). bob 은 시드하지 않음
-- (RP_ADMIN 테스트는 db/testfix 가 별도 복원).
--
-- Idempotent: email UNIQUE 기준 NOT EXISTS 가드.
-- bcrypt strength 12, plaintext "alice-temp-pw" (기존 V11 해시 재사용).
-- ============================================================

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, created_at)
SELECT admin_user_seq.NEXTVAL, 'alice@crosscert.com',
       '$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS',
       'PLATFORM_OPERATOR', 'Y', SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (
  SELECT 1 FROM admin_user WHERE email = 'alice@crosscert.com'
);

COMMIT;
