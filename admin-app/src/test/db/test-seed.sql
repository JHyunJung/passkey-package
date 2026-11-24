-- ============================================================
-- admin-app 통합 테스트 전용 시드. 루트 build.gradle.kts 가 test-db-init.sql 끝에 이어 붙인다
-- (PSK_APP_OWNER 로 실행). src/test 아래에만 있어 배포물에 들어가지 않는다.
--
--   1) 운영자 alice·bob — 둘 다 PLATFORM_OPERATOR, MFA 꺼짐.
--      alice 로 로그인하는 IT 가 많아, MFA 를 켜 두면 전부 TOTP 확인 단계에서 막힌다.
--      id 는 고정 RAW(alice 0x…10, bob 0x…11). 비밀번호는 alice-temp-pw / bob-temp-pw.
--   2) MFA 필수 정책 끔 — 스키마 기본값은 'Y' 라 그대로 두면 모든 로그인이 TOTP 등록
--      화면으로 간다. 정책 자체를 검증하는 MfaRequiredPolicyIT 는 테스트 안에서 직접 켠다.
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

UPDATE security_policy SET mfa_required = 'N' WHERE id = 1;

COMMIT;
