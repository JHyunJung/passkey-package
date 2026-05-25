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

## From T7 — V3 VPD policy

### Add INDEX statement type for defense-in-depth

V3 attaches the VPD policy with `statement_types => 'SELECT,INSERT,UPDATE,DELETE'`. Phase 0 risk model assumes only APP_ADMIN (with `EXEMPT ACCESS POLICY`) ever has index DDL privileges — and codex review confirmed APP_RUNTIME does not. So Phase 0 has no exposure.

Before any production rollout, add `INDEX` to `statement_types` for defense-in-depth: this protects against future code (or a forgotten grant) that lets a non-admin user create or rebuild indexes on `credential`. With `INDEX` enforcement, an index build cannot read rows of other tenants either.

Implementation: when modifying the policy later, prefer `DBMS_RLS.ALTER_POLICY` over drop-and-recreate, and add `'SELECT,INSERT,UPDATE,DELETE,INDEX'` as `statement_types`.

## From T15 — admin-app application.yml

### Gate `flyway.baseline-on-migrate` by profile/env before prod

`spring.flyway.baseline-on-migrate: true` is enabled unconditionally in `admin-app/src/main/resources/application.yml`. This is correct for Phase 0 because `bootstrap-vpd.sql` populates APP_OWNER with the CTX_PKG package before Flyway ever runs.

In a production deployment, leaving `baseline-on-migrate: true` as an unconditional default would silently auto-baseline ANY non-empty schema without history — masking a real "this DB is already managed by another tool" warning. Before any non-local deploy:

- Move the setting into `application-local.yml` or a profile-specific block.
- Default it to `false` for `prod` / `staging`.
- Document the operational expectation: bootstrap-vpd.sql runs once at provisioning, Flyway picks up cleanly afterwards.

### Boundary debt: SchedulerLease entity in :core

`SchedulerLease` is admin-app's concern (the MDS scheduler in Phase 3). It currently lives in `core/entity` because :core is the only place Spring Data JPA + Hibernate scan from in T8/T9. The side effect: passkey-app must also scan SchedulerLease and APP_RUNTIME must have `SELECT` grant on `scheduler_lease` just to pass ddl-validate (V4 migration).

Cleaner long-term shape (defer until at least Phase 3):

- Move admin-only entities out of `:core/entity` into an `admin-app`-local package.
- Narrow `@EntityScan` in PasskeyApplication to exclude admin entities, or add `@EntityScan({"com.crosscert.passkey.core.entity", "com.crosscert.passkey.app.entity"})`-style allow-lists.
- Roll back the `GRANT SELECT ON scheduler_lease TO APP_RUNTIME` in V4 once passkey-app no longer scans the entity.

Cost-benefit: small. Phase 0 priority is "VPD works", not entity-package hygiene.

## From T16 — Docker API version pin

### Replace `api.version=1.43` pin with a Testcontainers upgrade

`core/build.gradle.kts` pins `systemProperty("api.version", "1.43")` on the test task to work around a known Testcontainers 1.20.4 + Docker Engine v25+ incompatibility (shaded docker-java defaults to API v1.32 which Engine MinAPIVersion 1.40 rejects with HTTP 400). See https://github.com/testcontainers/testcontainers-java/issues/9434.

The pin works for current Docker daemons (e.g. Docker Desktop 4.30+) but breaks on older daemons whose max API < 1.43. Long-term fix: bump the Testcontainers BOM. As of Apr 2026 the latest release is 2.0.5. Validate the suite (Oracle container start, MountableFile, execInContainer) under the new version before removing the pin.

Tracking: `gradle/libs.versions.toml` has `testcontainers = "1.20.4"`. Upgrade target: 2.0.5 (or whatever is current at upgrade time).

## From T9 — note for T16 implementation

### Use saveAndFlush() (not save()) for VPD cross-tenant INSERT assertions

JPA's `repository.save(entity)` is lazy — the actual INSERT happens at the next flush or commit, not at the call site. T16's `assertThatThrownBy(() -> credentialRepository.save(...))` would NOT trigger VPD's `update_check=TRUE` rejection inside the lambda if the INSERT is deferred.

When implementing T16, use one of:
- `credentialRepository.saveAndFlush(entity)` — forces the INSERT immediately.
- Run the assertion outside an `@Transactional` boundary, or wrap explicitly with a programmatic transaction that flushes.
- Use a direct JDBC INSERT via `RuntimeDsHelper.insertCredentialAs(...)` (the T16 plan already wires this for the assertion test case).

The T16 plan's `RuntimeDsHelper.insertCredentialAs` already uses raw JDBC, so the issue is moot for that specific test path. But if T16 implementation drifts and uses JPA `save()` for the cross-tenant assertion, it would silently mask the bug.
