-- Platform-scoped operator account table. NO VPD — administration is
-- cross-tenant by design, and admin_user rows belong to the platform
-- itself, not to any tenant.
--
-- APP_RUNTIME never reads or writes this table. APP_ADMIN gets
-- SELECT + INSERT + column-scoped UPDATE on last_login_at. Password
-- changes / role changes are SQL maintenance for Phase 2 (admin
-- self-service password change is a Phase 4 followup).

CREATE SEQUENCE admin_user_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE admin_user (
  id            NUMBER(19,0)             NOT NULL,
  email         VARCHAR2(255)            NOT NULL,
  bcrypt_hash   VARCHAR2(72)             NOT NULL,
  role          VARCHAR2(16)             NOT NULL,
  enabled       CHAR(1)                  DEFAULT 'Y' NOT NULL,
  created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  last_login_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT pk_admin_user PRIMARY KEY (id),
  CONSTRAINT uq_admin_user_email UNIQUE (email),
  CONSTRAINT ck_admin_user_role CHECK (role IN ('ADMIN','VIEWER')),
  CONSTRAINT ck_admin_user_enabled CHECK (enabled IN ('Y','N'))
);

GRANT SELECT, INSERT ON admin_user TO APP_ADMIN;
GRANT UPDATE(last_login_at, bcrypt_hash, role, enabled) ON admin_user TO APP_ADMIN;
GRANT SELECT ON admin_user_seq TO APP_ADMIN;
