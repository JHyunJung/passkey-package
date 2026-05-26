-- Phase 8: Align all entity tables with the new BaseEntity superclass
-- (UUID PK + created_at + updated_at managed by JPA @PrePersist/@PreUpdate).
--
-- Adds the missing timestamp columns. Tenant already has both; all other
-- tables get the columns added here with DEFAULT SYSTIMESTAMP so existing
-- rows are backfilled at migration runtime (dev/staging premise; no
-- production data backfill).
--
-- DB columns use TIMESTAMP WITH TIME ZONE; JPA maps to java.time.Instant;
-- hibernate.jdbc.time_zone remains UTC. admin-ui converts to Asia/Seoul
-- at render time via the new formatDateTime utility.

-- ── Phase 7 child tables: created_at + updated_at both new ──
ALTER TABLE app_owner.tenant_allowed_origin ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.tenant_accepted_format ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.api_key_scope ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- ── Existing tables: updated_at new (created_at already exists) ──
ALTER TABLE app_owner.admin_user ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.api_key ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.credential ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.audit_log ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.signing_key ADD (
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- ── Special-case tables: created_at + updated_at both new ──
ALTER TABLE app_owner.mds_blob_cache ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE app_owner.scheduler_lease ADD (
  created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

-- Tenant: no change — created_at + updated_at already exist (V19 carries them).
