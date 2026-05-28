-- ============================================================
-- V29 — Admin User Invitation + status/created_by/suspended_at 컬럼
--
-- 목적: 운영자 초대 플로우 + 정지/활성화 + lockout 방지 audit.
--
-- bcrypt_hash NULL 허용: PENDING 상태 사용자는 비밀번호 없음 (수락 시 설정).
-- status: ACTIVE(default) | PENDING | SUSPENDED.
-- 기존 admin_user 행은 모두 default 'ACTIVE'.
-- ============================================================

-- 1. admin_user 확장
ALTER TABLE admin_user MODIFY (bcrypt_hash VARCHAR2(72) NULL);

ALTER TABLE admin_user ADD (
  status        VARCHAR2(16) DEFAULT 'ACTIVE' NOT NULL,
  created_by    VARCHAR2(255),
  suspended_at  TIMESTAMP WITH TIME ZONE,
  suspended_by  VARCHAR2(255),
  CONSTRAINT ck_admin_user_status CHECK (status IN ('ACTIVE','PENDING','SUSPENDED'))
);

-- 2. admin_user_invitation 테이블
CREATE SEQUENCE admin_user_invitation_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE admin_user_invitation (
  id              NUMBER(19,0)             NOT NULL,
  admin_user_id   RAW(16)                  NOT NULL,
  token_hash      VARCHAR2(64)             NOT NULL,
  token_prefix    VARCHAR2(8)              NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  created_by      VARCHAR2(255)            NOT NULL,
  expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  accepted_at     TIMESTAMP WITH TIME ZONE,
  resent_count    NUMBER(5,0)              DEFAULT 0 NOT NULL,
  resent_at       TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_admin_user_invitation PRIMARY KEY (id),
  CONSTRAINT uq_admin_user_invitation_token UNIQUE (token_hash),
  CONSTRAINT fk_admin_user_invitation_user FOREIGN KEY (admin_user_id) REFERENCES admin_user(id) ON DELETE CASCADE
);

CREATE INDEX ix_admin_user_invitation_user ON admin_user_invitation (admin_user_id);

-- 3. 권한 — APP_ADMIN 에 inline GRANT (V12/V13/V16 패턴)
GRANT SELECT, INSERT, UPDATE ON admin_user_invitation TO APP_ADMIN;
GRANT SELECT ON admin_user_invitation_seq TO APP_ADMIN;
