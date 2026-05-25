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
