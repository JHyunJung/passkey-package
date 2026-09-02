-- ============================================================
-- V5 — 어드민 가입 요청·승인 흐름 도입 + 초대 기능 제거.
--
-- 배경: 초대 메일이 가리키는 /accept-invite 프론트 페이지가 admin-ui src 폐기
-- (71ecb005) 때 사라진 뒤 복원되지 않아 초대 흐름이 실제로 동작하지 않았다.
-- 온보딩을 "로그인 페이지 가입 요청 → PLATFORM_OPERATOR 승인" 으로 바꾸고
-- 가입 경로를 하나로 단일화한다.
--
-- admin_signup_request: 승인 전 요청만 보관한다. 승인 시 admin_user 를 ACTIVE 로
-- 생성하고 이 행을 삭제하며, 거절도 삭제다(이력은 audit_log 의
-- ADMIN_SIGNUP_APPROVE / ADMIN_SIGNUP_REJECT 로 남는다). admin_user 에는 승인된
-- 계정만 존재하므로 role NOT NULL 제약과 로그인 조회가 미승인 행을 볼 일이 없다.
--
-- GRANT: PSK_APP_ADMIN 에 SELECT/INSERT/DELETE 만. UPDATE 경로는 없다.
-- PSK_APP_RUNTIME 에는 부여하지 않는다 — passkey-app 의 @EntityScan 은
-- core.entity 패키지를 통째로 스캔하므로 이 엔티티도 ddl-auto: validate 대상이
-- 된다. V4 의 security_incident 와 같은 이유로 SELECT 한 건은 부여한다.
--
-- admin_user_invitation 은 DROP 한다. 미수락 초대가 남아 있어도 어차피 수락할
-- 수 없었으므로 복구 대상이 아니다. admin_user.status CHECK 의 'PENDING' 값은
-- 더 이상 생성되지 않을 뿐이며 제약 자체는 건드리지 않는다.
-- ============================================================

CREATE TABLE admin_signup_request (
    id            RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    email         VARCHAR2(255)               NOT NULL,
    bcrypt_hash   VARCHAR2(72)                NOT NULL,
    reason        VARCHAR2(500),
    requested_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_admin_signup_request       PRIMARY KEY (id),
    CONSTRAINT uq_admin_signup_request_email UNIQUE (email)
);

GRANT SELECT ON admin_signup_request TO PSK_APP_ADMIN;
GRANT INSERT ON admin_signup_request TO PSK_APP_ADMIN;
GRANT DELETE ON admin_signup_request TO PSK_APP_ADMIN;
GRANT SELECT ON admin_signup_request TO PSK_APP_RUNTIME;

DROP TABLE admin_user_invitation;
DROP SEQUENCE admin_user_invitation_seq;
