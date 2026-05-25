# Deferred Production Hardening Notes

This file collects findings from `/codex:review` runs that are intentionally deferred from Phase 0 (local dev scope) but must be addressed before any non-local deployment.

## From T2 (commit pending) — application-common.yml

**Finding:** `management.endpoint.health.show-details: always` exposes health details unconditionally.

**Risk in prod:** Leaks internal component status (DB/Redis up/down, pool sizes, etc.) to unauthenticated callers.

**Required action before non-local deploy:** Override to `when_authorized` with actuator security, or `never` if internal probes don't need detail. Place override in `application-prod.yml` (not yet created).

**Phase 0 verdict:** Acceptable for local dev. Captured here so it's not forgotten when the prod profile is created.
