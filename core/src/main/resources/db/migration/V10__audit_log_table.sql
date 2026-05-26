-- Append-only audit log with row-level hash chain.
--
-- Schema rationale:
--   - prev_hash + hash are RAW(32) (SHA-256). NULL prev_hash marks the
--     genesis row.
--   - actor_email is denormalized (not just actor_id) so audit reads
--     remain meaningful after an admin_user row is renamed or deleted.
--   - target_type + target_id are nullable (login events have no target).
--   - payload is CLOB with `IS JSON` check; AuditLogService writes
--     canonical JSON (sorted keys, no whitespace).
--
-- VPD: NONE. Audit is cross-tenant by design — we want one chain that
-- captures every operator action across every tenant.
--
-- Grants: APP_ADMIN gets SELECT + INSERT only. NO UPDATE, NO DELETE,
-- even from APP_ADMIN. Tampering requires DBA-level privilege.

CREATE SEQUENCE audit_log_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE audit_log (
  id           NUMBER(19,0)             NOT NULL,
  prev_hash    RAW(32),
  hash         RAW(32)                  NOT NULL,
  actor_id     NUMBER(19,0)             NOT NULL,
  actor_email  VARCHAR2(255)            NOT NULL,
  action       VARCHAR2(64)             NOT NULL,
  target_type  VARCHAR2(32),
  target_id    VARCHAR2(64),
  payload      CLOB                     NOT NULL,
  created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_audit_log PRIMARY KEY (id),
  CONSTRAINT ck_audit_log_payload_json CHECK (payload IS JSON)
);

CREATE INDEX audit_log_created_at_ix ON audit_log(created_at);
CREATE INDEX audit_log_actor_ix      ON audit_log(actor_id, created_at);
CREATE INDEX audit_log_target_ix     ON audit_log(target_type, target_id, created_at);

GRANT SELECT, INSERT ON audit_log TO APP_ADMIN;
GRANT SELECT ON audit_log_seq TO APP_ADMIN;
