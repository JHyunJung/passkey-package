-- ============================================================
-- V9001 — test 프로필 전용 운영자 계정 복원.
--
-- 프로덕션 V11/V19 가 시드를 들어냈으므로(프로필 분리), IT 가 의존하는
-- alice(PLATFORM_OPERATOR) / bob(RP_ADMIN 후보) 를 test 에서만 복원한다.
-- bob 의 tenant 매핑은 AdminFlowIT.resetState() 가 demo-rp 재시드 후 수행.
--
-- mfa_enabled='N' 으로 시드 — V32 가 alice 를 'Y' 로 켜는 UPDATE 는 이제
-- 대상이 없어(V9001 보다 먼저 실행) 무효이므로, 여기서 명시적으로 N 을 박아
-- IT 로그인이 MfaPendingFilter 에 걸리지 않게 한다. (V9000 의 mfa 끄기 UPDATE
-- 에 의존하지 않음 — V9000 이 V9001 보다 먼저 돌아 0 rows 일 수 있으므로.)
--
-- plaintext: alice-temp-pw / bob-temp-pw (기존 V11 해시 재사용).
-- id: V19 이후 admin_user.id 는 RAW(16) — 고정 RAW(alice 0x...0010, bob 0x...0011)
--     를 재사용(admin_user_seq 안 씀).
-- Idempotent: email NOT EXISTS 가드.
-- ============================================================

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, mfa_enabled, created_at)
SELECT HEXTORAW('00000000000000000000000000000010'), 'alice@crosscert.com',
       '$2a$12$jpftll2M2sOc8XRs99Zw0ODgKWBiRKQcIieK/UqUBbizW7xKI8awS',
       'PLATFORM_OPERATOR', 'Y', 'N', SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE email = 'alice@crosscert.com');

INSERT INTO admin_user (id, email, bcrypt_hash, role, enabled, mfa_enabled, created_at)
SELECT HEXTORAW('00000000000000000000000000000011'), 'bob@crosscert.com',
       '$2a$12$gvD5tGra6vKnSn/9cxqfQOKZOzlzp4LCg276Ddfkpwl8Kk24Zbb1G',
       'PLATFORM_OPERATOR', 'Y', 'N', SYSTIMESTAMP
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE email = 'bob@crosscert.com');

COMMIT;
