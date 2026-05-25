-- Platform-scoped 테이블: tenant_id 없음, VPD 미적용
-- APP_OWNER 스키마에 생성됨.
--
-- Timestamp columns use TIMESTAMP WITH TIME ZONE so values are unambiguous
-- across server timezones. JPA entities map these to java.time.Instant
-- (UTC). Hibernate is configured with jdbc.time_zone: UTC in
-- application-common.yml.

CREATE TABLE tenant (
  id              VARCHAR2(64)             NOT NULL,
  display_name    VARCHAR2(256)            NOT NULL,
  status          VARCHAR2(16)             DEFAULT 'active' NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_tenant PRIMARY KEY (id),
  CONSTRAINT ck_tenant_status CHECK (status IN ('active','suspended'))
);

CREATE TABLE scheduler_lease (
  name        VARCHAR2(64)             NOT NULL,
  holder      VARCHAR2(256)            NOT NULL,
  expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_scheduler_lease PRIMARY KEY (name)
);

CREATE SEQUENCE mds_blob_cache_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE mds_blob_cache (
  -- NUMBER(19,0) sized to match Java long via Hibernate BIGINT mapping.
  id           NUMBER(19,0)             NOT NULL,
  version      NUMBER(19,0)             NOT NULL,
  next_update  DATE                     NOT NULL,
  fetched_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  blob_jwt     CLOB                     NOT NULL,
  CONSTRAINT pk_mds_blob_cache PRIMARY KEY (id)
);

-- 런타임/관리자 롤에 권한 부여
GRANT SELECT ON tenant TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant TO APP_ADMIN;

GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler_lease TO APP_ADMIN;

GRANT SELECT ON mds_blob_cache TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON mds_blob_cache TO APP_ADMIN;
GRANT SELECT ON mds_blob_cache_seq TO APP_ADMIN;
