-- ============================================================
-- V1__baseline_schema.sql
--
-- V1~V52 마이그레이션을 단일 baseline으로 squash한 결과.
-- 생성일: 2026-06-27
--
-- 원본: 실DB에 V1~V52 순차 적용 후 DBMS_METADATA.GET_DDL 로 추출한
--       schema-A.txt 를 기준으로 재현.
--       STORAGE/TABLESPACE 절 없음 — Oracle SE2-clean.
--
-- 운영 DB 부재 → Flyway 히스토리 보존 의무 없음.
-- 인프라 시드(scheduler_lease lock / mds_blob_cache singleton /
--   security_policy singleton)만 포함. 테넌트/운영자 시드는 R__ 유지.
--
-- Task 3 에서 A-vs-B diff=0 + IT 그린으로 정확성 검증.
-- ============================================================


-- ============================================================
-- 1. CREATE TABLE (FK 의존성 순서: 부모 → 자식)
-- ============================================================

-- 1-1. TENANT (독립 — 다수 테이블이 참조)
CREATE TABLE tenant (
    id                          RAW(16)                      NOT NULL,
    display_name                VARCHAR2(256)                NOT NULL,
    status                      VARCHAR2(16)  DEFAULT 'active' NOT NULL,
    slug                        VARCHAR2(64)                 NOT NULL,
    rp_id                       VARCHAR2(256)                NOT NULL,
    rp_name                     VARCHAR2(256)                NOT NULL,
    created_at                  TIMESTAMP(6) WITH TIME ZONE  DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at                  TIMESTAMP(6) WITH TIME ZONE  DEFAULT SYSTIMESTAMP NOT NULL,
    require_user_verification   CHAR(1)       DEFAULT 'Y'    NOT NULL,
    mds_required                CHAR(1)       DEFAULT 'N'    NOT NULL,
    attestation_conveyance      VARCHAR2(16)  DEFAULT 'NONE' NOT NULL,
    webauthn_timeout_ms         NUMBER(10,0)  DEFAULT 60000  NOT NULL,
    CONSTRAINT ck_tenant_status       CHECK (status IN ('active','suspended')),
    CONSTRAINT pk_tenant              PRIMARY KEY (id),
    CONSTRAINT uq_tenant_slug         UNIQUE (slug),
    CONSTRAINT ck_tenant_uv           CHECK (require_user_verification IN ('Y','N')),
    CONSTRAINT ck_tenant_mds          CHECK (mds_required IN ('Y','N')),
    CONSTRAINT ck_tenant_attestation  CHECK (attestation_conveyance IN ('NONE','INDIRECT','DIRECT','ENTERPRISE')),
    CONSTRAINT ck_tenant_timeout_range CHECK (webauthn_timeout_ms BETWEEN 1000 AND 600000)
);

-- 1-2. SIGNING_KEY (독립)
CREATE TABLE signing_key (
    id              RAW(16)                     NOT NULL,
    kid             VARCHAR2(64)                NOT NULL,
    alg             VARCHAR2(16)                NOT NULL,
    status          VARCHAR2(16)                NOT NULL,
    public_jwk      CLOB                        NOT NULL,
    private_pkcs8   BLOB                        NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    rotated_at      TIMESTAMP(6) WITH TIME ZONE,
    revoked_at      TIMESTAMP(6) WITH TIME ZONE,
    updated_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ck_signing_key_status        CHECK (status IN ('ACTIVE','ROTATED','REVOKED')),
    CONSTRAINT ck_signing_key_alg           CHECK (alg IN ('RS256')),
    CONSTRAINT ck_signing_key_public_jwk_json CHECK (public_jwk IS JSON),
    CONSTRAINT pk_signing_key               PRIMARY KEY (id),
    CONSTRAINT uq_signing_key_kid           UNIQUE (kid)
);

-- 1-3. MDS_BLOB_CACHE (독립 — id=RAW(16), blob_jwt dropped by V47)
CREATE TABLE mds_blob_cache (
    id          RAW(16)                     NOT NULL,
    version     NUMBER(19,0)                NOT NULL,
    next_update DATE                        NOT NULL,
    fetched_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_mds_blob_cache PRIMARY KEY (id)
);

-- 1-4. MDS_SYNC_HISTORY (독립)
CREATE TABLE mds_sync_history (
    id              NUMBER(19,0)                NOT NULL,
    started_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    finished_at     TIMESTAMP(6) WITH TIME ZONE,
    version         NUMBER(19,0),
    status          VARCHAR2(16)                NOT NULL,
    change_summary  VARCHAR2(128),
    duration_ms     NUMBER(10,0),
    error_message   VARCHAR2(500),
    CONSTRAINT ck_mds_sync_history_status CHECK (status IN ('SYNCED','SKIPPED','FAILED')),
    CONSTRAINT pk_mds_sync_history        PRIMARY KEY (id)
);

-- 1-5. SCHEDULER_LEASE (독립)
CREATE TABLE scheduler_lease (
    id          RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    name        VARCHAR2(64)                NOT NULL,
    holder      VARCHAR2(256)               NOT NULL,
    expires_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_scheduler_lease      PRIMARY KEY (id),
    CONSTRAINT uq_scheduler_lease_name UNIQUE (name)
);

-- 1-6. SECURITY_POLICY (독립 — singleton id=1; password_min_length dropped by V51)
CREATE TABLE security_policy (
    id                          NUMBER(1,0)                  NOT NULL,
    session_idle_timeout_minutes NUMBER(5,0)                 NOT NULL,
    mfa_required                CHAR(1)                      NOT NULL,
    cors_allowlist              CLOB                         NOT NULL,
    updated_at                  TIMESTAMP(6) WITH TIME ZONE  DEFAULT SYSTIMESTAMP NOT NULL,
    updated_by                  VARCHAR2(255),
    CONSTRAINT ck_security_policy_singleton CHECK (id = 1),
    CONSTRAINT ck_security_policy_mfa       CHECK (mfa_required IN ('Y','N')),
    CONSTRAINT pk_security_policy           PRIMARY KEY (id)
);

-- 1-7. SECURITY_INCIDENT (독립 — 실DB에서 tenant FK 없음)
CREATE TABLE security_incident (
    id              RAW(16)                     NOT NULL,
    tenant_id       RAW(16)                     NOT NULL,
    tampered_entry_id RAW(16),
    type            VARCHAR2(64)                NOT NULL,
    severity        VARCHAR2(16)                NOT NULL,
    status          VARCHAR2(16)                NOT NULL,
    detail          VARCHAR2(1024),
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by      RAW(16)                     NOT NULL,
    resolved_at     TIMESTAMP(6) WITH TIME ZONE,
    resolved_by     RAW(16),
    resolution_note VARCHAR2(1024),
    CONSTRAINT ck_security_incident_status     CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT ck_security_incident_severity   CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_security_incident_resolution CHECK (
        (status = 'OPEN'     AND resolved_at IS NULL     AND resolved_by IS NULL     AND resolution_note IS NULL)
        OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND resolution_note IS NOT NULL)
    ),
    CONSTRAINT pk_security_incident PRIMARY KEY (id)
);

-- 1-8. ADMIN_USER (FK → TENANT)
CREATE TABLE admin_user (
    id                  RAW(16)                     NOT NULL,
    email               VARCHAR2(255)               NOT NULL,
    bcrypt_hash         VARCHAR2(72),
    role                VARCHAR2(20)                NOT NULL,
    enabled             CHAR(1)       DEFAULT 'Y'   NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    last_login_at       TIMESTAMP(6) WITH TIME ZONE,
    updated_at          TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    tenant_id           RAW(16),
    status              VARCHAR2(16)  DEFAULT 'ACTIVE' NOT NULL,
    created_by          VARCHAR2(255),
    suspended_at        TIMESTAMP(6) WITH TIME ZONE,
    suspended_by        VARCHAR2(255),
    mfa_enabled         CHAR(1)       DEFAULT 'N'   NOT NULL,
    mfa_secret          VARCHAR2(255),
    failed_login_count  NUMBER        DEFAULT 0      NOT NULL,
    locked_until        TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT ck_admin_user_enabled      CHECK (enabled IN ('Y','N')),
    CONSTRAINT pk_admin_user              PRIMARY KEY (id),
    CONSTRAINT uq_admin_user_email        UNIQUE (email),
    CONSTRAINT ck_admin_user_role         CHECK (role IN ('PLATFORM_OPERATOR', 'RP_ADMIN')),
    CONSTRAINT ck_admin_user_role_tenant  CHECK (
        (role = 'PLATFORM_OPERATOR' AND tenant_id IS NULL)
        OR
        (role = 'RP_ADMIN' AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT ck_admin_user_status       CHECK (status IN ('ACTIVE','PENDING','SUSPENDED')),
    CONSTRAINT ck_admin_user_mfa_enabled  CHECK (mfa_enabled IN ('Y','N')),
    CONSTRAINT fk_admin_user_tenant       FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

-- 1-9. CREDENTIAL (FK → TENANT)
CREATE TABLE credential (
    id              RAW(16)                     NOT NULL,
    tenant_id       RAW(16)                     NOT NULL,
    user_handle     RAW(64)                     NOT NULL,
    credential_id   RAW(1023)                   NOT NULL,
    sign_count      NUMBER(19,0) DEFAULT 0       NOT NULL,
    aaguid          RAW(16),
    transports      VARCHAR2(128),
    attestation_fmt VARCHAR2(64),
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    last_used_at    TIMESTAMP(6) WITH TIME ZONE,
    updated_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    label           VARCHAR2(128),
    cose_public_key BLOB                        NOT NULL,
    CONSTRAINT pk_credential      PRIMARY KEY (id),
    CONSTRAINT uq_credential_id   UNIQUE (tenant_id, credential_id),
    CONSTRAINT fk_credential_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

-- 1-10. API_KEY (FK → TENANT)
CREATE TABLE api_key (
    id          RAW(16)                     NOT NULL,
    tenant_id   RAW(16)                     NOT NULL,
    key_prefix  VARCHAR2(16)                NOT NULL,
    key_hash    VARCHAR2(255)               NOT NULL,
    name        VARCHAR2(256)               NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    last_used_at TIMESTAMP(6) WITH TIME ZONE,
    expires_at  TIMESTAMP(6) WITH TIME ZONE,
    revoked_at  TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_api_key       PRIMARY KEY (id),
    CONSTRAINT uq_api_key_prefix UNIQUE (key_prefix),
    CONSTRAINT fk_api_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

-- 1-11. AUDIT_LOG (no FK — tenant_id nullable, no FK constraint per schema-A)
CREATE TABLE audit_log (
    id              RAW(16)                     NOT NULL,
    prev_hash       RAW(32),
    hash            RAW(32)                     NOT NULL,
    actor_id        RAW(16),
    actor_email     VARCHAR2(255)               NOT NULL,
    action          VARCHAR2(64)                NOT NULL,
    target_type     VARCHAR2(32),
    target_id       VARCHAR2(64),
    payload         CLOB                        NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    tenant_id       RAW(16),
    tenant_prev_hash RAW(32),
    tenant_hash     RAW(32),
    CONSTRAINT ck_audit_log_payload_json CHECK (payload IS JSON),
    CONSTRAINT pk_audit_log              PRIMARY KEY (id)
);

-- 1-12. CEREMONY_EVENT (FK → TENANT via tenant_id NOT NULL; no FK constraint in schema-A)
CREATE TABLE ceremony_event (
    id          RAW(16)                     DEFAULT SYS_GUID(),
    tenant_id   RAW(16)                     NOT NULL,
    action      VARCHAR2(64)                NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id)
);

-- 1-13. TENANT_AAGUID_POLICY (FK → TENANT)
CREATE TABLE tenant_aaguid_policy (
    tenant_id   RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    policy_mode VARCHAR2(16)                NOT NULL,
    mds_strict  CHAR(1)       DEFAULT 'N'   NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_by  VARCHAR2(255),
    CONSTRAINT ck_tenant_aaguid_policy_mode  CHECK (policy_mode IN ('ANY', 'ALLOWLIST', 'DENYLIST')),
    CONSTRAINT ck_tenant_aaguid_mds_strict   CHECK (mds_strict IN ('Y', 'N')),
    CONSTRAINT pk_tenant_aaguid_policy        PRIMARY KEY (tenant_id),
    CONSTRAINT fk_tenant_aaguid_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE
);

-- 1-14. TENANT_ACCEPTED_FORMAT (FK → TENANT)
CREATE TABLE tenant_accepted_format (
    id          RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    tenant_id   RAW(16)                     NOT NULL,
    format      VARCHAR2(32)                NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ck_taf_format    CHECK (format IN
        ('none','packed','android-key','android-safetynet','fido-u2f','apple','tpm')),
    CONSTRAINT pk_tenant_accepted_format PRIMARY KEY (id),
    CONSTRAINT uq_taf_tenant_format      UNIQUE (tenant_id, format),
    CONSTRAINT fk_taf_tenant             FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE
);

-- 1-15. TENANT_ALLOWED_ORIGIN (FK → TENANT)
CREATE TABLE tenant_allowed_origin (
    id          RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    tenant_id   RAW(16)                     NOT NULL,
    origin      VARCHAR2(512)               NOT NULL,
    sort_order  NUMBER(5,0)   DEFAULT 0     NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_tenant_allowed_origin PRIMARY KEY (id),
    CONSTRAINT uq_tao_tenant_origin     UNIQUE (tenant_id, origin),
    CONSTRAINT ck_tao_origin_format     CHECK (
        REGEXP_LIKE(origin, '^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$')
        OR REGEXP_LIKE(origin, '^android:apk-key-hash:[A-Za-z0-9_-]{43}$')
    ),
    CONSTRAINT fk_tao_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE
);

-- 1-16. TENANT_WEBAUTHN_SNAPSHOT (FK → TENANT)
CREATE TABLE tenant_webauthn_snapshot (
    id                          NUMBER(19,0)                NOT NULL,
    tenant_id                   RAW(16)       DEFAULT SYS_GUID() NOT NULL,
    rp_id                       VARCHAR2(256)               NOT NULL,
    rp_name                     VARCHAR2(256)               NOT NULL,
    allowed_origins             CLOB                        NOT NULL,
    accepted_formats            CLOB                        NOT NULL,
    require_user_verification   CHAR(1)       DEFAULT 'N'   NOT NULL,
    mds_required                CHAR(1)       DEFAULT 'N'   NOT NULL,
    taken_at                    TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    taken_by                    VARCHAR2(255),
    CONSTRAINT ck_tenant_snapshot_uv  CHECK (require_user_verification IN ('Y', 'N')),
    CONSTRAINT ck_tenant_snapshot_mds CHECK (mds_required IN ('Y', 'N')),
    CONSTRAINT pk_tenant_webauthn_snapshot        PRIMARY KEY (id),
    CONSTRAINT fk_tenant_webauthn_snapshot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id) ON DELETE CASCADE
);

-- 1-17. ADMIN_PASSWORD_RESET_TOKEN (FK → ADMIN_USER)
CREATE TABLE admin_password_reset_token (
    id              NUMBER(19,0)                NOT NULL,
    admin_user_id   RAW(16)                     NOT NULL,
    token_hash      VARCHAR2(64)                NOT NULL,
    token_prefix    VARCHAR2(8)                 NOT NULL,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at     TIMESTAMP(6) WITH TIME ZONE,
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_admin_password_reset_token      PRIMARY KEY (id),
    CONSTRAINT uq_admin_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_pwd_reset_admin_user            FOREIGN KEY (admin_user_id) REFERENCES admin_user (id) ON DELETE CASCADE
);

-- 1-18. ADMIN_USER_INVITATION (FK → ADMIN_USER)
CREATE TABLE admin_user_invitation (
    id              NUMBER(19,0)                NOT NULL,
    admin_user_id   RAW(16)                     NOT NULL,
    token_hash      VARCHAR2(64)                NOT NULL,
    token_prefix    VARCHAR2(8)                 NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    created_by      VARCHAR2(255)               NOT NULL,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    accepted_at     TIMESTAMP(6) WITH TIME ZONE,
    resent_count    NUMBER(5,0)   DEFAULT 0     NOT NULL,
    resent_at       TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_admin_user_invitation      PRIMARY KEY (id),
    CONSTRAINT uq_admin_user_invitation_token UNIQUE (token_hash),
    CONSTRAINT fk_admin_user_invitation_user  FOREIGN KEY (admin_user_id) REFERENCES admin_user (id) ON DELETE CASCADE
);

-- 1-19. ADMIN_USER_RECOVERY_CODE (FK → ADMIN_USER)
CREATE TABLE admin_user_recovery_code (
    id              RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    admin_user_id   RAW(16)                     NOT NULL,
    code_hash       VARCHAR2(64)                NOT NULL,
    used_at         TIMESTAMP(6) WITH TIME ZONE,
    created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_admin_user_recovery_code PRIMARY KEY (id),
    CONSTRAINT fk_recovery_admin_user      FOREIGN KEY (admin_user_id) REFERENCES admin_user (id) ON DELETE CASCADE
);

-- 1-20. API_KEY_SCOPE (FK → API_KEY)
CREATE TABLE api_key_scope (
    id          RAW(16)                     DEFAULT SYS_GUID() NOT NULL,
    api_key_id  RAW(16)                     NOT NULL,
    scope       VARCHAR2(32)                NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ck_aks_scope       CHECK (scope IN ('registration','authentication','admin')),
    CONSTRAINT pk_api_key_scope   PRIMARY KEY (id),
    CONSTRAINT uq_aks_api_key_scope UNIQUE (api_key_id, scope),
    CONSTRAINT fk_aks_api_key     FOREIGN KEY (api_key_id) REFERENCES api_key (id) ON DELETE CASCADE
);

-- 1-21. CREDENTIAL_AUTH_EVENT (FK → CREDENTIAL)
CREATE TABLE credential_auth_event (
    id              RAW(16)                     DEFAULT SYS_GUID(),
    credential_id   RAW(16)                     NOT NULL,
    tenant_id       RAW(16)                     NOT NULL,
    result          VARCHAR2(16)                NOT NULL,
    failure_reason  VARCHAR2(64),
    sign_count      NUMBER(19,0)                NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cred_auth_event_credential FOREIGN KEY (credential_id) REFERENCES credential (id) ON DELETE CASCADE
);

-- 1-22. TENANT_AAGUID_POLICY_ENTRY (FK → TENANT_AAGUID_POLICY)
CREATE TABLE tenant_aaguid_policy_entry (
    tenant_id   RAW(16)     DEFAULT SYS_GUID() NOT NULL,
    aaguid      RAW(16)     DEFAULT SYS_GUID() NOT NULL,
    note        VARCHAR2(256),
    CONSTRAINT pk_tenant_aaguid_policy_entry       PRIMARY KEY (tenant_id, aaguid),
    CONSTRAINT fk_tenant_aaguid_policy_entry_policy FOREIGN KEY (tenant_id)
        REFERENCES tenant_aaguid_policy (tenant_id) ON DELETE CASCADE
);


-- ============================================================
-- 2. CREATE SEQUENCE (4개)
-- ============================================================

CREATE SEQUENCE admin_password_reset_token_seq
    MINVALUE 1 MAXVALUE 9999999999999999999999999999
    INCREMENT BY 1 START WITH 1 NOCACHE NOORDER NOCYCLE;

CREATE SEQUENCE admin_user_invitation_seq
    MINVALUE 1 MAXVALUE 9999999999999999999999999999
    INCREMENT BY 1 START WITH 1 NOCACHE NOORDER NOCYCLE;

CREATE SEQUENCE mds_sync_history_seq
    MINVALUE 1 MAXVALUE 9999999999999999999999999999
    INCREMENT BY 1 START WITH 1 NOCACHE NOORDER NOCYCLE;

CREATE SEQUENCE tenant_webauthn_snapshot_seq
    MINVALUE 1 MAXVALUE 9999999999999999999999999999
    INCREMENT BY 1 START WITH 2 NOCACHE NOORDER NOCYCLE;


-- ============================================================
-- 3. CREATE INDEX (보조 인덱스 — PK/UNIQUE 자동생성 제외)
-- ============================================================

-- admin_user
CREATE INDEX ix_admin_user_tenant ON admin_user (tenant_id);
CREATE INDEX ix_admin_user_invitation_user ON admin_user_invitation (admin_user_id);
CREATE INDEX ix_pwd_reset_admin_user ON admin_password_reset_token (admin_user_id);
CREATE INDEX ix_recovery_admin_user ON admin_user_recovery_code (admin_user_id);

-- api_key / api_key_scope
CREATE INDEX ix_api_key_tenant ON api_key (tenant_id, SYS_EXTRACT_UTC(revoked_at), SYS_EXTRACT_UTC(expires_at));
CREATE INDEX ix_aks_api_key ON api_key_scope (api_key_id);

-- audit_log
CREATE INDEX audit_log_actor_ix      ON audit_log (actor_id, SYS_EXTRACT_UTC(created_at));
CREATE INDEX audit_log_created_at_ix ON audit_log (SYS_EXTRACT_UTC(created_at));
CREATE INDEX audit_log_target_ix     ON audit_log (target_type, target_id, SYS_EXTRACT_UTC(created_at));
CREATE INDEX audit_log_tenant_ix     ON audit_log (tenant_id, SYS_EXTRACT_UTC(created_at));
CREATE INDEX audit_log_tenant_seq_ix ON audit_log (tenant_id, id);

-- ceremony_event
CREATE INDEX ix_ceremony_event_created_at          ON ceremony_event (SYS_EXTRACT_UTC(created_at));
CREATE INDEX ix_ceremony_event_tenant_action_time  ON ceremony_event (tenant_id, action, SYS_EXTRACT_UTC(created_at));

-- credential / credential_auth_event
CREATE INDEX ix_credential_tenant_user      ON credential (tenant_id, user_handle);
CREATE INDEX ix_cred_auth_event_created_at  ON credential_auth_event (SYS_EXTRACT_UTC(created_at));
CREATE INDEX ix_cred_auth_event_cred_time   ON credential_auth_event (credential_id, SYS_EXTRACT_UTC(created_at) DESC);

-- mds_sync_history
CREATE INDEX ix_mds_sync_history_started_at ON mds_sync_history (SYS_EXTRACT_UTC(started_at) DESC);

-- signing_key
CREATE UNIQUE INDEX signing_key_one_active_uix ON signing_key (CASE status WHEN 'ACTIVE' THEN 1 END);
CREATE INDEX signing_key_status_ix ON signing_key (status);

-- tenant_accepted_format / tenant_allowed_origin
CREATE INDEX ix_taf_tenant ON tenant_accepted_format (tenant_id);
CREATE INDEX ix_tao_tenant ON tenant_allowed_origin (tenant_id);

-- tenant_webauthn_snapshot
CREATE INDEX tenant_webauthn_snapshot_tenant_taken_ix ON tenant_webauthn_snapshot (tenant_id, SYS_EXTRACT_UTC(taken_at));

-- security_incident
CREATE UNIQUE INDEX ux_incident_open_per_tenant ON security_incident (CASE status WHEN 'OPEN' THEN tenant_id END);


-- ============================================================
-- 4. CREATE VIEW (8개 — v_admin_user 등 V40 UUID 디버깅 뷰)
-- ============================================================

CREATE OR REPLACE FORCE VIEW v_admin_user (
    id, email, role, tenant_slug, tenant_name, tenant_id,
    enabled, status, mfa_enabled, last_login_at, failed_login_count,
    locked_until, created_by, suspended_at, suspended_by, created_at, updated_at
) AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(u.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    u.email,
    u.role,
    t.slug                                                                                         AS tenant_slug,
    t.display_name                                                                                 AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(u.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    u.enabled,
    u.status,
    u.mfa_enabled,
    u.last_login_at,
    u.failed_login_count,
    u.locked_until,
    u.created_by,
    u.suspended_at,
    u.suspended_by,
    u.created_at,
    u.updated_at
FROM admin_user u
LEFT JOIN tenant t ON t.id = u.tenant_id;

CREATE OR REPLACE FORCE VIEW v_api_key (
    id, tenant_slug, tenant_name, tenant_id,
    name, key_prefix, last_used_at, expires_at, revoked_at, created_at, updated_at
) AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(a.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                                                                          AS tenant_slug,
    t.display_name                                                                                  AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(a.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    a.name,
    a.key_prefix,
    a.last_used_at,
    a.expires_at,
    a.revoked_at,
    a.created_at,
    a.updated_at
FROM api_key a
JOIN tenant t ON t.id = a.tenant_id;

CREATE OR REPLACE FORCE VIEW v_audit_log (
    id, tenant_slug, tenant_name, tenant_id,
    actor_email, actor_id, action, target_type, target_id, payload, created_at
) AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(al.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                                                                          AS tenant_slug,
    t.display_name                                                                                  AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(al.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    al.actor_email,
    LOWER(REGEXP_REPLACE(RAWTOHEX(al.actor_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS actor_id,
    al.action,
    al.target_type,
    al.target_id,
    al.payload,
    al.created_at
FROM audit_log al
LEFT JOIN tenant t ON t.id = al.tenant_id;

CREATE OR REPLACE FORCE VIEW v_credential (
    id, tenant_slug, tenant_name, tenant_id,
    label, credential_id_hex, user_handle_hex, aaguid_hex,
    sign_count, transports, attestation_fmt, last_used_at, created_at, updated_at
) AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(c.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                                                                          AS tenant_slug,
    t.display_name                                                                                  AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(c.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    c.label,
    RAWTOHEX(c.credential_id)  AS credential_id_hex,
    RAWTOHEX(c.user_handle)    AS user_handle_hex,
    LOWER(RAWTOHEX(c.aaguid))  AS aaguid_hex,
    c.sign_count,
    c.transports,
    c.attestation_fmt,
    c.last_used_at,
    c.created_at,
    c.updated_at
FROM credential c
JOIN tenant t ON t.id = c.tenant_id;

CREATE OR REPLACE FORCE VIEW v_tenant_aaguid_policy (
    tenant_slug, tenant_name, tenant_id,
    policy_mode, mds_strict, updated_by, created_at, updated_at
) AS
SELECT
    t.slug                                                                                          AS tenant_slug,
    t.display_name                                                                                  AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(p.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    p.policy_mode,
    p.mds_strict,
    p.updated_by,
    p.created_at,
    p.updated_at
FROM tenant_aaguid_policy p
JOIN tenant t ON t.id = p.tenant_id;

CREATE OR REPLACE FORCE VIEW v_tenant_accepted_format (
    id, tenant_slug, tenant_name, tenant_id,
    format, created_at, updated_at
) AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(f.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                                                                          AS tenant_slug,
    t.display_name                                                                                  AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(f.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    f.format,
    f.created_at,
    f.updated_at
FROM tenant_accepted_format f
JOIN tenant t ON t.id = f.tenant_id;

CREATE OR REPLACE FORCE VIEW v_tenant_allowed_origin (
    id, tenant_slug, tenant_name, tenant_id,
    origin, sort_order, created_at, updated_at
) AS
SELECT
    LOWER(REGEXP_REPLACE(RAWTOHEX(o.id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS id,
    t.slug                                                                                          AS tenant_slug,
    t.display_name                                                                                  AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(o.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    o.origin,
    o.sort_order,
    o.created_at,
    o.updated_at
FROM tenant_allowed_origin o
JOIN tenant t ON t.id = o.tenant_id;

CREATE OR REPLACE FORCE VIEW v_tenant_webauthn_snapshot (
    id, tenant_slug, tenant_name, tenant_id,
    rp_id, rp_name, allowed_origins, accepted_formats,
    require_user_verification, mds_required, taken_at, taken_by
) AS
SELECT
    s.id,
    t.slug                                                                                          AS tenant_slug,
    t.display_name                                                                                  AS tenant_name,
    LOWER(REGEXP_REPLACE(RAWTOHEX(s.tenant_id), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\1-\2-\3-\4-\5')) AS tenant_id,
    s.rp_id,
    s.rp_name,
    s.allowed_origins,
    s.accepted_formats,
    s.require_user_verification,
    s.mds_required,
    s.taken_at,
    s.taken_by
FROM tenant_webauthn_snapshot s
JOIN tenant t ON t.id = s.tenant_id;


-- ============================================================
-- 5. CREATE PACKAGE / PACKAGE BODY (signing_key_bootstrap_pkg)
-- ============================================================

CREATE OR REPLACE PACKAGE signing_key_bootstrap_pkg
    AUTHID DEFINER
AS
    PROCEDURE bootstrap_active(
        p_kid           IN  VARCHAR2,
        p_alg           IN  VARCHAR2,
        p_public_jwk    IN  CLOB,
        p_private_pkcs8 IN  BLOB,
        p_inserted      OUT NUMBER);
END signing_key_bootstrap_pkg;
/

CREATE OR REPLACE PACKAGE BODY signing_key_bootstrap_pkg AS

    PROCEDURE bootstrap_active(
        p_kid           IN  VARCHAR2,
        p_alg           IN  VARCHAR2,
        p_public_jwk    IN  CLOB,
        p_private_pkcs8 IN  BLOB,
        p_inserted      OUT NUMBER) IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO v_count FROM signing_key WHERE status = 'ACTIVE';
        IF v_count > 0 THEN
            p_inserted := 0;
            RETURN;
        END IF;
        -- UUID generated by SYS_GUID() for bootstrap row (app not involved here)
        INSERT INTO signing_key (id, kid, alg, status, public_jwk, private_pkcs8, created_at)
            VALUES (SYS_GUID(), p_kid, p_alg, 'ACTIVE',
                    p_public_jwk, p_private_pkcs8, SYSTIMESTAMP);
        p_inserted := 1;
    EXCEPTION
        WHEN DUP_VAL_ON_INDEX THEN
            p_inserted := 0;
    END bootstrap_active;

END signing_key_bootstrap_pkg;
/


-- ============================================================
-- 6. GRANT (123건 — APP_ADMIN / APP_RUNTIME 객체 권한)
-- ============================================================

GRANT DELETE ON admin_password_reset_token TO APP_ADMIN;
GRANT INSERT ON admin_password_reset_token TO APP_ADMIN;
GRANT SELECT ON admin_password_reset_token TO APP_ADMIN;
GRANT UPDATE ON admin_password_reset_token TO APP_ADMIN;
GRANT SELECT ON admin_password_reset_token TO APP_RUNTIME;
GRANT SELECT ON admin_password_reset_token_seq TO APP_ADMIN;
GRANT SELECT ON admin_password_reset_token_seq TO APP_RUNTIME;
GRANT INSERT ON admin_user TO APP_ADMIN;
GRANT SELECT ON admin_user TO APP_ADMIN;
GRANT UPDATE ON admin_user TO APP_ADMIN;
GRANT SELECT ON admin_user TO APP_RUNTIME;
GRANT INSERT ON admin_user_invitation TO APP_ADMIN;
GRANT SELECT ON admin_user_invitation TO APP_ADMIN;
GRANT UPDATE ON admin_user_invitation TO APP_ADMIN;
GRANT SELECT ON admin_user_invitation TO APP_RUNTIME;
GRANT SELECT ON admin_user_invitation_seq TO APP_ADMIN;
GRANT SELECT ON admin_user_invitation_seq TO APP_RUNTIME;
GRANT DELETE ON admin_user_recovery_code TO APP_ADMIN;
GRANT INSERT ON admin_user_recovery_code TO APP_ADMIN;
GRANT SELECT ON admin_user_recovery_code TO APP_ADMIN;
GRANT UPDATE ON admin_user_recovery_code TO APP_ADMIN;
GRANT SELECT ON admin_user_recovery_code TO APP_RUNTIME;
GRANT DELETE ON api_key TO APP_ADMIN;
GRANT INSERT ON api_key TO APP_ADMIN;
GRANT SELECT ON api_key TO APP_ADMIN;
GRANT UPDATE ON api_key TO APP_ADMIN;
GRANT SELECT ON api_key TO APP_RUNTIME;
GRANT DELETE ON api_key_scope TO APP_ADMIN;
GRANT INSERT ON api_key_scope TO APP_ADMIN;
GRANT SELECT ON api_key_scope TO APP_ADMIN;
GRANT UPDATE ON api_key_scope TO APP_ADMIN;
GRANT SELECT ON api_key_scope TO APP_RUNTIME;
GRANT INSERT ON audit_log TO APP_ADMIN;
GRANT SELECT ON audit_log TO APP_ADMIN;
GRANT SELECT ON audit_log TO APP_RUNTIME;
GRANT DELETE ON ceremony_event TO APP_ADMIN;
GRANT INSERT ON ceremony_event TO APP_ADMIN;
GRANT SELECT ON ceremony_event TO APP_ADMIN;
GRANT INSERT ON ceremony_event TO APP_RUNTIME;
GRANT SELECT ON ceremony_event TO APP_RUNTIME;
GRANT DELETE ON credential TO APP_ADMIN;
GRANT INSERT ON credential TO APP_ADMIN;
GRANT SELECT ON credential TO APP_ADMIN;
GRANT UPDATE ON credential TO APP_ADMIN;
GRANT DELETE ON credential TO APP_RUNTIME;
GRANT INSERT ON credential TO APP_RUNTIME;
GRANT SELECT ON credential TO APP_RUNTIME;
GRANT UPDATE ON credential TO APP_RUNTIME;
GRANT DELETE ON credential_auth_event TO APP_ADMIN;
GRANT INSERT ON credential_auth_event TO APP_ADMIN;
GRANT SELECT ON credential_auth_event TO APP_ADMIN;
GRANT INSERT ON credential_auth_event TO APP_RUNTIME;
GRANT SELECT ON credential_auth_event TO APP_RUNTIME;
GRANT DELETE ON mds_blob_cache TO APP_ADMIN;
GRANT INSERT ON mds_blob_cache TO APP_ADMIN;
GRANT SELECT ON mds_blob_cache TO APP_ADMIN;
GRANT UPDATE ON mds_blob_cache TO APP_ADMIN;
GRANT SELECT ON mds_blob_cache TO APP_RUNTIME;
GRANT INSERT ON mds_sync_history TO APP_ADMIN;
GRANT SELECT ON mds_sync_history TO APP_ADMIN;
GRANT SELECT ON mds_sync_history_seq TO APP_ADMIN;
GRANT SELECT ON mds_sync_history_seq TO APP_RUNTIME;
GRANT DELETE ON scheduler_lease TO APP_ADMIN;
GRANT INSERT ON scheduler_lease TO APP_ADMIN;
GRANT SELECT ON scheduler_lease TO APP_ADMIN;
GRANT UPDATE ON scheduler_lease TO APP_ADMIN;
GRANT SELECT ON scheduler_lease TO APP_RUNTIME;
GRANT INSERT ON security_incident TO APP_ADMIN;
GRANT SELECT ON security_incident TO APP_ADMIN;
GRANT SELECT ON security_policy TO APP_ADMIN;
GRANT UPDATE ON security_policy TO APP_ADMIN;
GRANT SELECT ON security_policy TO APP_RUNTIME;
GRANT INSERT ON signing_key TO APP_ADMIN;
GRANT SELECT ON signing_key TO APP_ADMIN;
GRANT SELECT ON signing_key TO APP_RUNTIME;
GRANT EXECUTE ON signing_key_bootstrap_pkg TO APP_ADMIN;
GRANT EXECUTE ON signing_key_bootstrap_pkg TO APP_RUNTIME;
GRANT DELETE ON tenant TO APP_ADMIN;
GRANT INSERT ON tenant TO APP_ADMIN;
GRANT SELECT ON tenant TO APP_ADMIN;
GRANT UPDATE ON tenant TO APP_ADMIN;
GRANT SELECT ON tenant TO APP_RUNTIME;
GRANT DELETE ON tenant_aaguid_policy TO APP_ADMIN;
GRANT INSERT ON tenant_aaguid_policy TO APP_ADMIN;
GRANT SELECT ON tenant_aaguid_policy TO APP_ADMIN;
GRANT UPDATE ON tenant_aaguid_policy TO APP_ADMIN;
GRANT SELECT ON tenant_aaguid_policy TO APP_RUNTIME;
GRANT DELETE ON tenant_aaguid_policy_entry TO APP_ADMIN;
GRANT INSERT ON tenant_aaguid_policy_entry TO APP_ADMIN;
GRANT SELECT ON tenant_aaguid_policy_entry TO APP_ADMIN;
GRANT UPDATE ON tenant_aaguid_policy_entry TO APP_ADMIN;
GRANT SELECT ON tenant_aaguid_policy_entry TO APP_RUNTIME;
GRANT DELETE ON tenant_accepted_format TO APP_ADMIN;
GRANT INSERT ON tenant_accepted_format TO APP_ADMIN;
GRANT SELECT ON tenant_accepted_format TO APP_ADMIN;
GRANT UPDATE ON tenant_accepted_format TO APP_ADMIN;
GRANT SELECT ON tenant_accepted_format TO APP_RUNTIME;
GRANT DELETE ON tenant_allowed_origin TO APP_ADMIN;
GRANT INSERT ON tenant_allowed_origin TO APP_ADMIN;
GRANT SELECT ON tenant_allowed_origin TO APP_ADMIN;
GRANT UPDATE ON tenant_allowed_origin TO APP_ADMIN;
GRANT SELECT ON tenant_allowed_origin TO APP_RUNTIME;
GRANT INSERT ON tenant_webauthn_snapshot TO APP_ADMIN;
GRANT SELECT ON tenant_webauthn_snapshot TO APP_ADMIN;
GRANT SELECT ON tenant_webauthn_snapshot TO APP_RUNTIME;
GRANT SELECT ON tenant_webauthn_snapshot_seq TO APP_ADMIN;
GRANT SELECT ON tenant_webauthn_snapshot_seq TO APP_RUNTIME;
GRANT SELECT ON v_admin_user TO APP_ADMIN;
GRANT SELECT ON v_admin_user TO APP_RUNTIME;
GRANT SELECT ON v_api_key TO APP_ADMIN;
GRANT SELECT ON v_api_key TO APP_RUNTIME;
GRANT SELECT ON v_audit_log TO APP_ADMIN;
GRANT SELECT ON v_audit_log TO APP_RUNTIME;
GRANT SELECT ON v_credential TO APP_ADMIN;
GRANT SELECT ON v_credential TO APP_RUNTIME;
GRANT SELECT ON v_tenant_aaguid_policy TO APP_ADMIN;
GRANT SELECT ON v_tenant_aaguid_policy TO APP_RUNTIME;
GRANT SELECT ON v_tenant_accepted_format TO APP_ADMIN;
GRANT SELECT ON v_tenant_accepted_format TO APP_RUNTIME;
GRANT SELECT ON v_tenant_allowed_origin TO APP_ADMIN;
GRANT SELECT ON v_tenant_allowed_origin TO APP_RUNTIME;
GRANT SELECT ON v_tenant_webauthn_snapshot TO APP_ADMIN;
GRANT SELECT ON v_tenant_webauthn_snapshot TO APP_RUNTIME;


-- ============================================================
-- 7. 인프라 시드 (환경 무관 3개)
-- ============================================================

-- 7-1. scheduler_lease AUDIT_CHAIN_LOCK sentinel (V14 원본)
-- AuditLogService.append 의 직렬화 게이트: SELECT FOR UPDATE 타깃 행.
-- MERGE 로 멱등 보장.
MERGE INTO scheduler_lease tgt
USING (SELECT 'AUDIT_CHAIN_LOCK' AS name FROM dual) src
ON (tgt.name = src.name)
WHEN NOT MATCHED THEN
    INSERT (name, holder, expires_at)
    VALUES ('AUDIT_CHAIN_LOCK', 'audit-system',
            TIMESTAMP '1970-01-01 00:00:00 UTC');

-- 7-2. mds_blob_cache singleton row (V19 UUID 마이그레이션 버전 — id=RAW(16))
-- MdsBlobStore 가 매 스케줄러 실행 시 MERGE 하는 타깃 행.
-- 최초 FIDO MDS3 fetch 성공 시 version/next_update/fetched_at 덮어씀.
MERGE INTO mds_blob_cache USING dual
ON (id = HEXTORAW('00000000000000000000000000000001'))
WHEN NOT MATCHED THEN
    INSERT (id, version, next_update, fetched_at)
    VALUES (HEXTORAW('00000000000000000000000000000001'),
            0, DATE '1970-01-01',
            TIMESTAMP '1970-01-01 00:00:00 +00:00');

-- 7-3. security_policy singleton row id=1 (V31 원본 — password_min_length 제외, V51 삭제됨)
MERGE INTO security_policy t
USING (SELECT 1 AS id FROM dual) s
ON (t.id = s.id)
WHEN NOT MATCHED THEN
    INSERT (id, session_idle_timeout_minutes, mfa_required, cors_allowlist, updated_at, updated_by)
    VALUES (1, 30, 'Y', '[]', SYSTIMESTAMP, 'system');

COMMIT;
