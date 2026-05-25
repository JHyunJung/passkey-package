# Deferred Production Hardening Notes

This file collects findings from `/codex:review` runs that are intentionally deferred from Phase 0 (local dev scope) but must be addressed before any non-local deployment.

## From T2 (commit pending) — application-common.yml

**Finding:** `management.endpoint.health.show-details: always` exposes health details unconditionally.

**Risk in prod:** Leaks internal component status (DB/Redis up/down, pool sizes, etc.) to unauthenticated callers.

**Required action before non-local deploy:** Override to `when_authorized` with actuator security, or `never` if internal probes don't need detail. Place override in `application-prod.yml` (not yet created).

**Phase 0 verdict:** Acceptable for local dev. Captured here so it's not forgotten when the prod profile is created.

## From T3 — docker-compose.yml

### Reset path for fresh DB bootstrap

`gvenzl/oracle-xe` runs `APP_USER` creation on first boot only. If you change `APP_USER`/`APP_USER_PASSWORD` env vars after the volume has been initialized, the new values are ignored. To start from scratch:

```bash
docker compose down -v   # -v removes the named volume oracle-data
docker compose up -d
./scripts/wait-for-oracle.sh
```

The same applies to T4's bootstrap SQL — it should be idempotent, but if you change role/user definitions and want a clean state, `down -v` is the lever.

### Fixed container_name + fixed host ports

`container_name: passkey-oracle` and `1521:1521`, `6379:6379` are hardcoded. Acceptable for one local checkout. If you need multiple worktrees of this repo to run the stack in parallel, parameterize ports and rewrite `wait-for-oracle.sh` to discover the container via `docker compose ps -q oracle` instead of the fixed name.

### Apple Silicon (arm64) notes

`gvenzl/oracle-xe:21-slim-faststart` is amd64-only and runs under Rosetta/QEMU on Apple Silicon. It works, but Flyway migrations and JPA tests may be slower than on a native amd64 host. The arm64-native alternative is `gvenzl/oracle-free:23-slim-faststart`, which would change the PDB name from `XEPDB1` to `FREEPDB1` and require updates in the bootstrap script + JDBC URLs + Testcontainers config. Defer this swap until perf becomes a team-wide tax.

## From T4 — scripts/bootstrap-vpd.sql

### Tighten APP_ADMIN_USER privileges

`GRANT ALL PRIVILEGES TO APP_ADMIN_USER` is broader than required. APP_ADMIN_USER needs:
- Whatever Flyway needs to create/alter APP_OWNER objects (most of `CREATE ANY TABLE`, `CREATE ANY SEQUENCE`, `ALTER ANY TABLE`, `INSERT ANY TABLE` family — confirm exact set against actual migrations once T5/T6/T7 land).
- `EXECUTE ON DBMS_RLS` for VPD policy management.
- The `APP_ADMIN` role grant gives it `CREATE SESSION` + `EXEMPT ACCESS POLICY`.

**Phase 0 verdict:** `GRANT ALL PRIVILEGES` is acceptable for local dev to unblock work. Codex flagged this as P2. Before any non-local deployment, replace with the tight privilege set.

### T16 Testcontainers note

Testcontainers will spawn its own Oracle container for VpdIsolationIT. The init must run this same bootstrap-vpd.sql as a SQL*Plus script (via `docker exec`), NOT via JDBC `withInitScript`, because the script uses SQL*Plus-specific syntax (`/`, `EXIT`, `WHENEVER`). Plan task T16 already calls this out — keep that in mind during T16 execution.

## From T5 — V1 migration

### mds_blob_cache singleton policy

The table schema permits multiple rows; the design intent (per Phase 0 spec) is one current BLOB at a time. Phase 0 leaves the table empty (BLOB population is Phase 3).

When MDS scheduler logic lands in Phase 3, pick one approach:

- **Option A — singleton row** (`id = 1` always, no use of `mds_blob_cache_seq`, UPSERT instead of INSERT). Simple semantics, but no version history.
- **Option B — keep multiple rows + "current" rule** (max `fetched_at` or max `version` wins). Allows comparing against old BLOBs for forensics.

Recommendation: Option A for Phase 3 simplicity. Drop `mds_blob_cache_seq` at that point if it's unused.

**Phase 0 verdict:** Table schema as-is is fine because no rows are written. Pick policy in Phase 3.
