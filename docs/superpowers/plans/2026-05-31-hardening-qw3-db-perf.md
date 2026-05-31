# QW-3 — DB/JPA 성능 + 의존성 검증 Implementation Plan

REQUIRED SUB-SKILL: superpowers:subagent-driven-development — execute each Task as an isolated subagent dispatch with the verification gate inline. Do not batch Tasks; complete and commit one before starting the next.

## Goal

Phase QW-3 closes 5 `risk=none` performance/dependency-hygiene findings on the Crosscert Passkey platform **without changing any wire response, UI display value, hash output, or signing-key format**:

- `perf-apikey-no-tenant-index` — `api_key.tenant_id` has no standalone index (only PK, UNIQUE(key_prefix), FK). Every `countActiveByTenantId` / `findActiveByTenantId` filter does a full table scan. Add a Flyway V38 index.
- `perf-tenant-view-nplus1` — `TenantAdminService.list()` fires `1 + 3N` queries (per-tenant credentials/apiKeys/lastEventAt). Replace with 3 `GROUP BY tenant_id` batch aggregations + `Map` lookup. **Returned `TenantView` numbers must be byte-identical.**
- `perf-tenant-eager-collections-touch` — preventive: add `hibernate.default_batch_fetch_size` so future LAZY collection touches batch.
- `sec-nimbus-jose-jwt-940` / `sec-vite-esbuild-devserver` — **no blind bump.** Verify whether a real resolved advisory exists for nimbus 9.40 → 9.x and vite 5.4.21 / esbuild 0.21.5; bump to a patch only if an advisory is confirmed, otherwise record the keep-rationale in followups.

## Architecture

- **Flyway**: V1–V37 exist, no gaps. New file = `V38__api_key_tenant_index.sql` in `core/src/main/resources/db/migration/`. Idempotent pattern reused verbatim from `V24__audit_log_tenant_id.sql:25-34` (`EXECUTE IMMEDIATE 'CREATE INDEX …'` wrapped in `BEGIN/EXCEPTION WHEN OTHERS THEN IF SQLCODE = -955 THEN NULL; ELSE RAISE; END IF; END;` + slash terminator). No GRANT needed (index inherits table grants).
- **N+1 fix**: Add 3 aggregate `@Query` methods (one per existing per-tenant query) returning `List<Object[]>` keyed by `tenant_id`, drained into `Map<UUID, Long>` / `Map<UUID, Instant>`. `TenantAdminService.toView` becomes `toView(Tenant, credentials, apiKeys, lastEventAt)` fed from the maps in `list()`. Single-row `get()`/`create()`/`update()` keep calling the existing per-tenant repo methods (unchanged path). **The new aggregate JPQL must encode the exact same predicates** as `countByTenantId` / `countActiveByTenantId` (revokedAt-null + expiry) / `findFirstByTenantIdOrderByCreatedAtDesc` (max createdAt).
- **Hibernate batch**: flat dot-keys under `spring.jpa.properties` in `core/src/main/resources/application-common.yml` (6-space indent, after `hibernate.default_schema` line 18). `hibernate.jdbc.batch_size: 30` + `hibernate.default_batch_fetch_size: 16`. **Deliberately omit `order_inserts`/`order_updates`** — see Task 3 rationale.
- **Dependency verification**: nimbus pinned literally in `gradle/libs.versions.toml:8` (`nimbus-jose-jwt = "9.40"`), consumed by `core/build.gradle.kts:26` + `sdk-java/build.gradle.kts:20`. vite/esbuild are admin-ui devDeps (prod serves static `vite build` output, no runtime exposure). Verification gates on `SigningKeyProviderTest`/`IdTokenIssuerTest` (plain unit tests — run in this env).

## Tech Stack

- Java 17, Spring Boot 3.5.14, Spring Data JPA / Hibernate (OracleDialect), Flyway 10.20.1, Oracle 23.
- Build/compile gate: `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q` (EXIT 0).
- Unit/slice tests: `./gradlew :<module>:test --tests '<pattern>'`. Oracle `*IT` (e.g. `VpdIsolationIT`) boots real Testcontainers Oracle and is **flaky in this env** — run opportunistically, record env failure separately, do not block on it.
- admin-ui (only touched if vite bump is warranted): `cd .../admin-ui && npx tsc -b && npm test && npm run build`.

---

## Task 1 — Flyway V38: `api_key(tenant_id)` index (`perf-apikey-no-tenant-index`)

Pure schema addition. No code/UI/behavior change. GRANT not needed.

### Files
- CREATE `core/src/main/resources/db/migration/V38__api_key_tenant_index.sql`

### Steps

**Step 1.1 — Confirm no name collision (2 min).**
Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
grep -rin "ix_api_key_tenant" core/src/main/resources/db/migration/ ; \
ls core/src/main/resources/db/migration/ | sort -V | tail -3
```
Expected output: no match for `ix_api_key_tenant` (index name is free) and `V37__admin_account_security.sql` as the last existing file (so `V38` is the correct next number). If either expectation fails, STOP and re-plan.

**Step 1.2 — Write V38 migration (3 min).**
Use the exact idempotent pattern from `V24__audit_log_tenant_id.sql:25-34` (single `EXECUTE IMMEDIATE`, `WHEN OTHERS` + `SQLCODE = -955` swallow, slash terminator). Index covers the active-filter predicate (`tenant_id, revoked_at, expires_at`) so `countActiveByTenantId` / `findActiveByTenantId` are fully covered, not just `tenant_id`.

Write `core/src/main/resources/db/migration/V38__api_key_tenant_index.sql`:
```sql
-- ============================================================
-- V38 — api_key.tenant_id 인덱스 추가
--
-- 목적: api_key 에는 PK(id) / UNIQUE(key_prefix) / FK(tenant_id) 만 있고
-- tenant_id 단독 인덱스가 어느 마이그레이션에도 없다. Oracle 은 FK 만으로
-- 자식측 인덱스를 자동 생성하지 않으므로 tenant_id 로 필터하는 쿼리
-- (ApiKeyRepository.countActiveByTenantId — 대시보드 KPI 카드마다 호출,
--  findActiveByTenantId — TenantLifecycleService.suspend 일괄 revoke) 가
-- 전부 api_key 풀 테이블 스캔이 된다.
--
-- 결정: active 판정(revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now))
-- 까지 커버하도록 복합 인덱스 (tenant_id, revoked_at, expires_at) 로 만든다.
-- 선두 컬럼 tenant_id 단독 prefix 도 인덱스로 사용 가능하므로 단순 tenant_id
-- 필터(findByTenantId 류 미래 쿼리)도 커버된다.
--
-- Idempotency: CREATE INDEX 재실행 시 ORA-00955 (name already used) 로 실패.
-- V24/V25 와 동일하게 EXCEPTION 으로 감싸 멱등.
--
-- 권한: 인덱스는 별도 GRANT 불요 — api_key 테이블 권한(V13 runtime grants)을
-- 그대로 상속한다. 코드/UI/동작 변화 없음 (순수 스키마 추가).
-- ============================================================

-- 인덱스: (tenant_id, revoked_at, expires_at) — ORA-00955 (name already used) swallow
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX ix_api_key_tenant ON api_key (tenant_id, revoked_at, expires_at)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL; -- index already exists
    ELSE RAISE;
    END IF;
END;
/
```

**Step 1.3 — Invariant verification: compile + Flyway syntax sanity (3 min).**
The migration is SQL-only, so the compile gate proves nothing about it. Confirm:
1. Backend compile unaffected:
   ```bash
   cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q
   ```
   Expected: EXIT 0.
2. Structural sanity of the new file (exactly one `EXECUTE IMMEDIATE`, one slash terminator, the `-955` swallow present):
   ```bash
   grep -c "EXECUTE IMMEDIATE" core/src/main/resources/db/migration/V38__api_key_tenant_index.sql ; \
   grep -c "SQLCODE = -955" core/src/main/resources/db/migration/V38__api_key_tenant_index.sql ; \
   tail -1 core/src/main/resources/db/migration/V38__api_key_tenant_index.sql
   ```
   Expected: `1`, `1`, and a line containing only `/`.

**Step 1.4 — Flyway boot verification via core IT (env-conditional) (4 min).**
`VpdIsolationIT` (`core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java`) is `@SpringBootTest @Testcontainers` and lets Flyway migrate the full V1–V38 chain against a real Oracle XE container — this is the authoritative "V38 applies cleanly" check.
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :core:test --tests 'com.crosscert.passkey.core.vpd.VpdIsolationIT'
```
- If GREEN: V38 confirmed to apply (and re-apply idempotently, since the IT image may already carry a prior schema). Record "Flyway V38 applied, IT green".
- If it FAILS on Docker/Testcontainers/Oracle env (timeout, container pull, sqlplus) rather than a migration error: this is the known-flaky env path. Record verbatim as **"VpdIsolationIT env-skip: <first error line>"** and rely on Step 1.3 structural sanity. Do **not** edit V38 to chase an env failure. Only a genuine `FlywayException`/`ORA-` from the migration itself blocks the Task.

**Step 1.5 — Commit (2 min).**
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
git add core/src/main/resources/db/migration/V38__api_key_tenant_index.sql && \
git commit -m "perf(core): V38 — api_key(tenant_id,revoked_at,expires_at) 인덱스 추가

api_key 는 PK/UNIQUE(key_prefix)/FK 만 있어 tenant_id 필터 쿼리가 풀스캔.
countActiveByTenantId/findActiveByTenantId active 판정까지 커버하는 복합
인덱스를 V24/V25 멱등 패턴(ORA-00955 swallow)으로 추가. 순수 스키마 추가
— 코드/UI/동작/응답 무변경.

finding: perf-apikey-no-tenant-index

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2 — `TenantAdminService.list()` N+1 → GROUP BY batch aggregation (`perf-tenant-view-nplus1`)

`list()` currently streams `findAll()` through `toView`, firing 3 queries per tenant. Replace with 3 aggregate queries (one round-trip each, independent of N) drained into maps. **Critical invariant: the aggregate JPQL must produce identical numbers to the existing per-tenant methods** — especially `countActive`'s revoked/expiry predicate. Single-row paths (`get`/`create`/`update`) stay on the existing per-tenant methods.

### Files
- `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java`
- `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`

### Steps

**Step 2.1 — TDD: failing test asserting aggregate-fed `list()` equals per-tenant values (5 min).**
This test pins the invariant *before* implementation: with 2 tenants, `list()` must return the same `credentials`/`apiKeys`/`lastEventAt` numbers regardless of whether they came from per-tenant or batch queries, and `toView` must consume the new aggregate methods (verified by stubbing only the aggregate methods, not the per-tenant ones).

Append to `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java` (the existing `setUp()` already mocks all 9 collaborators + `ObjectMapper`; reuse them). Add imports `java.time.Instant`, `org.mockito.Mockito.never`, and use the existing `mock`/`when`/`verify`:
```java
    @Test
    void listUsesBatchAggregatesNotPerTenantQueries() {
        // two ACTIVE tenants
        Tenant a = new Tenant("T_A", "Tenant A", "a.example", "Tenant A");
        Tenant b = new Tenant("T_B", "Tenant B", "b.example", "Tenant B");
        UUID idA = a.getId();
        UUID idB = b.getId();
        Instant eventA = Instant.parse("2026-05-01T00:00:00Z");

        when(boundary.currentTenantScope()).thenReturn(java.util.Optional.empty());
        when(repo.findAll()).thenReturn(List.of(a, b));

        // batch aggregate stubs — Object[]{tenantId(UUID), count/maxCreatedAt}
        when(credentialRepository.countGroupedByTenantId())
                .thenReturn(List.<Object[]>of(new Object[]{idA, 5L}, new Object[]{idB, 0L}));
        when(apiKeyRepository.countActiveGroupedByTenantId(any()))
                .thenReturn(List.<Object[]>of(new Object[]{idA, 2L})); // idB absent => 0
        when(auditLogRepository.findLatestCreatedAtGroupedByTenantId())
                .thenReturn(List.<Object[]>of(new Object[]{idA, eventA})); // idB absent => null

        List<TenantAdminDto.TenantView> views = service.list();

        assertThat(views).hasSize(2);
        TenantAdminDto.TenantView va = views.stream().filter(v -> v.id().equals(idA)).findFirst().orElseThrow();
        TenantAdminDto.TenantView vb = views.stream().filter(v -> v.id().equals(idB)).findFirst().orElseThrow();
        assertThat(va.credentials()).isEqualTo(5L);
        assertThat(va.apiKeys()).isEqualTo(2L);
        assertThat(va.lastEventAt()).isEqualTo(eventA);
        assertThat(vb.credentials()).isEqualTo(0L);
        assertThat(vb.apiKeys()).isEqualTo(0L); // tenant absent from active-key aggregate => 0
        assertThat(vb.lastEventAt()).isNull();   // tenant absent from event aggregate => null

        // invariant: list() must NOT use the per-tenant N+1 methods anymore
        verify(credentialRepository, never()).countByTenantId(any());
        verify(apiKeyRepository, never()).countActiveByTenantId(any(), any());
        verify(auditLogRepository, never()).findFirstByTenantIdOrderByCreatedAtDesc(any());
    }
```
Run (expect COMPILE FAILURE — the aggregate methods don't exist yet):
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest' -q
```
Expected: compilation error referencing `countGroupedByTenantId` / `countActiveGroupedByTenantId` / `findLatestCreatedAtGroupedByTenantId`. This is the red state.

**Step 2.2 — Add aggregate query to `CredentialRepository` (3 min).**
Mirror `countByTenantId` semantics (`COUNT(*) WHERE tenant_id = ?`) but grouped. Add after `countByTenantId` (line 24):
```java
    /**
     * Phase QW-3 — TenantAdminService.list() N+1 제거용 배치 집계.
     * countByTenantId(UUID) 와 동일한 의미를 tenant 전체에 대해 한 번에 계산한다:
     * 각 tenant_id 별 credential row 수. 결과는 Object[]{UUID tenantId, Long count}.
     * credential row 가 없는 tenant 는 결과에 포함되지 않으므로(0 행) 호출부에서
     * 기본값 0 으로 처리한다 — countByTenantId 가 0 을 반환하는 것과 동일.
     */
    @Query("select c.tenantId, count(c) from Credential c group by c.tenantId")
    List<Object[]> countGroupedByTenantId();
```

**Step 2.3 — Add aggregate query to `ApiKeyRepository` (3 min).**
This is the highest-risk method: it must encode the **exact** `countActiveByTenantId` predicate (`revokedAt is null AND (expiresAt is null OR expiresAt > :now)`). Copy the where-clause verbatim and add `group by`. Add after `countActiveByTenantId` (line 42):
```java
    /**
     * Phase QW-3 — TenantAdminService.list() N+1 제거용 배치 집계.
     * countActiveByTenantId(tenantId, now) 와 **동일한 active 판정**을 tenant 전체에
     * 대해 한 번에 계산한다. where 절은 countActiveByTenantId 와 바이트 단위로 같아야
     * 한다(revokedAt IS NULL AND (expiresAt IS NULL OR expiresAt > :now)). 결과는
     * Object[]{UUID tenantId, Long activeCount}. active key 가 없는 tenant 는 결과
     * 행이 없으므로 호출부에서 0 으로 처리(countActiveByTenantId 가 0 반환과 동일).
     */
    @Query("""
            select k.tenantId, count(k) from ApiKey k
            where k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > :now)
            group by k.tenantId
            """)
    java.util.List<Object[]> countActiveGroupedByTenantId(@Param("now") Instant now);
```
Note: `Instant` and `@Param` are already imported in this file (lines 8, 6).

**Step 2.4 — Add aggregate query to `AuditLogRepository` (3 min).**
`findFirstByTenantIdOrderByCreatedAtDesc` returns the row with max `created_at` for one tenant; the batch equivalent is `MAX(createdAt) GROUP BY tenantId`. Since `toView` only reads `.getCreatedAt()` from that row, `MAX(createdAt)` is value-identical. First Read the file head to place the import/method correctly, then add after the `findFirstByTenantIdOrderByCreatedAtDesc` declaration (line 86):
```java
    /**
     * Phase QW-3 — TenantAdminService.list() N+1 제거용 배치 집계.
     * findFirstByTenantIdOrderByCreatedAtDesc(tenantId) 는 tenant 의 최신 audit row 를
     * 반환하고 toView 는 거기서 createdAt 만 읽는다. 따라서 배치 등가물은 tenant_id 별
     * MAX(createdAt). 결과는 Object[]{UUID tenantId, Instant maxCreatedAt}. audit row 가
     * 없는 tenant 는 결과 행이 없으므로 호출부에서 null 처리(기존 .orElse(null) 와 동일).
     * tenant_id IS NULL 인 PLATFORM row 는 group key 가 null 이라 tenant lookup 에서
     * 자연히 무시된다.
     */
    @Query("select a.tenantId, max(a.createdAt) from AuditLog a where a.tenantId is not null group by a.tenantId")
    java.util.List<Object[]> findLatestCreatedAtGroupedByTenantId();
```
Confirm `org.springframework.data.jpa.repository.Query` and entity field names (`tenantId`, `createdAt`) by Reading `AuditLogRepository.java` lines 1–30 and the `AuditLog` entity before writing — if the JPA field is named differently, adjust the JPQL path (do not guess).

**Step 2.5 — Rewrite `TenantAdminService.list()` + `toView` to use the maps (5 min).**
Add helper to drain `List<Object[]>` into a `Map`, change `list()`'s cross-tenant branch to fetch the 3 aggregates once and pass per-tenant values into an overloaded `toView`. Keep the single-tenant scope branch and `get`/`create`/`update` on the existing per-tenant `toView(Tenant)`.

Replace `list()` (lines 72–82):
```java
    @Transactional(readOnly = true)
    public List<TenantAdminDto.TenantView> list() {
        return tenantBoundary.currentTenantScope()
                .map(tid -> tenants.findById(tid)
                        .map(this::toView)
                        .map(java.util.List::of)
                        .orElseGet(java.util.List::of))
                .orElseGet(this::listAllWithBatchAggregates);
    }

    /**
     * PLATFORM_OPERATOR cross-tenant 목록. KPI 3종(credentials/apiKeys/lastEventAt)을
     * tenant 당 3쿼리(N+1) 대신 GROUP BY 배치 집계 3쿼리로 모은 뒤 toView 에서 lookup.
     * 반환 숫자/필드는 per-tenant 경로와 동일 — UI 표시값 불변.
     */
    private List<TenantAdminDto.TenantView> listAllWithBatchAggregates() {
        Map<UUID, Long> credByTenant =
                toCountMap(credentialRepository.countGroupedByTenantId());
        Map<UUID, Long> activeKeysByTenant =
                toCountMap(apiKeyRepository.countActiveGroupedByTenantId(Instant.now()));
        Map<UUID, Instant> lastEventByTenant =
                toInstantMap(auditLogRepository.findLatestCreatedAtGroupedByTenantId());

        return tenants.findAll().stream()
                .map(t -> toView(t,
                        credByTenant.getOrDefault(t.getId(), 0L),
                        activeKeysByTenant.getOrDefault(t.getId(), 0L),
                        lastEventByTenant.get(t.getId())))
                .toList();
    }

    private static Map<UUID, Long> toCountMap(List<Object[]> rows) {
        Map<UUID, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return m;
    }

    private static Map<UUID, Instant> toInstantMap(List<Object[]> rows) {
        Map<UUID, Instant> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((UUID) r[0], (Instant) r[1]);
        }
        return m;
    }
```
Then add an overloaded `toView` that takes precomputed values, and keep the original single-tenant `toView(Tenant)` delegating to the existing per-tenant repo calls (used by `get`/`create`/`update`). Replace the existing `toView` (lines 100–108) with:
```java
    /**
     * 단건 경로(get/create/update)용 — per-tenant 쿼리 3개로 KPI 집계.
     * list() 는 listAllWithBatchAggregates() 가 배치 집계 후 아래 오버로드를 직접 호출.
     */
    private TenantAdminDto.TenantView toView(Tenant t) {
        long credentials = credentialRepository.countByTenantId(t.getId());
        long apiKeys = apiKeyRepository.countActiveByTenantId(t.getId(), Instant.now());
        Instant lastEventAt = auditLogRepository
                .findFirstByTenantIdOrderByCreatedAtDesc(t.getId())
                .map(AuditLog::getCreatedAt)
                .orElse(null);
        return toView(t, credentials, apiKeys, lastEventAt);
    }

    private TenantAdminDto.TenantView toView(Tenant t, long credentials, long apiKeys, Instant lastEventAt) {
        return TenantAdminDto.TenantView.from(t, credentials, apiKeys, lastEventAt);
    }
```
`Map`, `HashMap`, `UUID`, `Instant`, `List`, `AuditLog` are all already imported (lines 8, 25–32).

> Note on `Instant.now()`: it remains here unchanged. The Clock injection for this exact line is **Phase QW-4's** `cq-clock-inconsistency` finding (`Instant.now()` → `clock.instant()`), explicitly out of scope for QW-3 to keep this Task a pure query-shape refactor. Do not add a `Clock` dependency in this Task.

**Step 2.6 — Run the unit test (green) (3 min).**
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest' -q
```
Expected: all tests GREEN including the new `listUsesBatchAggregatesNotPerTenantQueries` and the pre-existing `createPersistsTenantAndAppendsAudit` / `createRejectsDuplicateSlug` (proves single-tenant `toView` path is untouched).

**Step 2.7 — Invariant verification: numbers unchanged + JPQL parses against the real model (4 min).**
Two layers:
1. **Value-equivalence is pinned by the test** — Step 2.1's assertions encode that absent-tenant → `0`/`null` exactly as the per-tenant methods returned, and that `apiKeys` uses the active predicate (tenant B has no active-key row → 0). The `never()` verifies the N+1 methods are gone from `list()`.
2. **JPQL validity** — the unit test mocks repositories, so it does NOT validate that the new `@Query` JPQL parses against Hibernate's metamodel. Run the broader admin-app slice/test suite (and, env-permitting, any aggregate-touching IT) to force Hibernate to parse the queries:
   ```bash
   cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && ./gradlew :core:compileJava :admin-app:compileJava -q && ./gradlew :admin-app:test -q
   ```
   Expected: EXIT 0. If any `@SpringBootTest`/repository slice in admin-app boots Hibernate, a malformed JPQL path (e.g. wrong field name) surfaces as a `QuerySyntaxException` at context load. If no such test exists in admin-app, record that the JPQL is validated only at first real boot and ensure the `core:test` run in Task overall (or `VpdIsolationIT`, env-permitting) exercises it. Do not hand-wave: if neither a slice nor IT parses these queries in-env, note it explicitly in the final report as a residual env-gap.

**Step 2.8 — Commit (2 min).**
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
git add core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java \
        core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java && \
git commit -m "perf(admin): TenantAdminService.list() N+1 → GROUP BY 배치 집계

테넌트당 credentials/apiKeys/lastEventAt 3쿼리(1+3N)를 tenant_id GROUP BY
집계 3쿼리로 묶고 Map lookup. 신규 집계 JPQL 의 active 판정 predicate 는
countActiveByTenantId 와 동일, lastEventAt 은 MAX(createdAt) 등가.
단건 get/create/update 는 per-tenant toView 현행 유지. 반환 TenantView
숫자/필드 불변(단위 테스트로 검증).

finding: perf-tenant-view-nplus1

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3 — Hibernate batch settings (`perf-tenant-eager-collections-touch`)

Add JDBC batching + batch-fetch sizing to the shared `spring.jpa.properties`. Query results and behavior are unchanged — only round-trip count drops. **Deliberately exclude `order_inserts`/`order_updates`.**

### Files
- `core/src/main/resources/application-common.yml`

### Steps

**Step 3.1 — Add the two batch keys (3 min).**
Insert under `spring.jpa.properties`, at 6-space indent, immediately after `hibernate.default_schema: APP_OWNER` (line 18). Edit `core/src/main/resources/application-common.yml`:

Old:
```yaml
      hibernate.default_schema: APP_OWNER
    hibernate.ddl-auto: validate
```
New:
```yaml
      hibernate.default_schema: APP_OWNER
      # Phase QW-3: JDBC 배치 + 배치 페치 사이징.
      # batch_size: 같은 flush 에 쌓인 동종 INSERT/UPDATE 를 30개 단위로 한 번에
      #   JDBC 전송 — 라운드트립만 줄고 DML 결과는 불변.
      # default_batch_fetch_size: LAZY 연관(예: Tenant.allowedOrigins/acceptedFormats)
      #   접근 시 N개 부모를 IN(...) 16개 묶음으로 페치 — 결과 동일, 쿼리 횟수만 감소.
      # order_inserts/order_updates 는 의도적으로 제외: flush DML 순서를 전역
      #   재배열하면 TenantAdminService.replaceAllowedOrigins/replaceAcceptedFormats
      #   가 의존하는 "DELETE 먼저 flush 후 INSERT"(Oracle unique 제약 회피, em.flush()
      #   로 명시 제어 중인 경로) 등 미감사 write 경로의 순서 제약을 깰 위험 → risk=low
      #   별도 판단 항목으로 유보.
      hibernate.jdbc.batch_size: 30
      hibernate.default_batch_fetch_size: 16
    hibernate.ddl-auto: validate
```
(`hibernate.ddl-auto: validate` stays at its 4-space indent — it's a `spring.jpa` direct child, not a `properties` entry — unchanged.)

**Step 3.2 — Invariant verification: YAML parses + properties land in the right block + behavior tests unaffected (4 min).**
1. Confirm the two keys are under `properties` (6-space indent) and `ddl-auto` is still outside it:
   ```bash
   cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
   grep -nE "batch_size|default_batch_fetch_size|hibernate.ddl-auto|hibernate.default_schema" core/src/main/resources/application-common.yml
   ```
   Expected: `batch_size`/`default_batch_fetch_size` lines indented 6 spaces (same column as `hibernate.default_schema`); `hibernate.ddl-auto` indented 4 spaces.
2. Compile + run a representative unit/slice suite to prove nothing regresses (batch settings don't alter results, only batching):
   ```bash
   cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
   ./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q && \
   ./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest' -q
   ```
   Expected: EXIT 0, tests GREEN. (YAML parse errors would surface at any Spring context boot; the compile gate plus the slice test cover syntax. Full write-path ordering invariance — `replaceAllowedOrigins`'s DELETE-then-INSERT — is preserved *because* `order_inserts`/`order_updates` were omitted; if `VpdIsolationIT` or a tenant-update IT is runnable in-env, run it as bonus confirmation, else record env-skip.)

**Step 3.3 — Commit (2 min).**
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
git add core/src/main/resources/application-common.yml && \
git commit -m "perf(core): Hibernate jdbc.batch_size=30 + default_batch_fetch_size=16

LAZY 연관 배치 페치 + JDBC DML 배치로 라운드트립 감소. 쿼리 결과·동작 불변.
order_inserts/order_updates 는 의도적 제외 — flush DML 순서 전역 재배열이
replaceAllowedOrigins/replaceAcceptedFormats 의 DELETE-먼저 flush 순서
제약을 깰 위험(risk=low 별도 항목).

finding: perf-tenant-eager-collections-touch

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4 — Dependency advisory verification (`sec-nimbus-jose-jwt-940`, `sec-vite-esbuild-devserver`)

**No blind bump.** Verify real resolved advisories first. Branch: advisory confirmed → patch bump + regression-test green; no advisory / dev-only no-impact → record keep-rationale in followups, no bump.

### Files (conditionally modified)
- `gradle/libs.versions.toml` (only if nimbus advisory confirmed)
- `admin-ui/package.json` + `admin-ui/package-lock.json` (only if vite/esbuild advisory confirmed)
- ALWAYS: append rationale to the phase followups doc (path determined in Step 4.1)

### Steps

**Step 4.1 — Locate the followups sink + record current pins (3 min).**
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
ls docs/superpowers/ 2>/dev/null; find docs -iname "*followup*" -o -iname "*follow-up*" 2>/dev/null; \
grep -n 'nimbus-jose-jwt' gradle/libs.versions.toml; \
grep -n '"vite"\|"esbuild"' admin-ui/package.json admin-ui/package-lock.json | head</parameter>
```
Record: nimbus pinned `9.40` (libs.versions.toml:8, consumed by core:26 + sdk-java:20); vite declared `^5.4.6` (package.json) resolving to `5.4.21`; esbuild `0.21.5` (transitive, in package-lock). If no followups doc exists, the rationale goes into the final return message **and** a new `docs/superpowers/followups/2026-05-31-QW-3-dependency-decisions.md` (created in Step 4.5).

**Step 4.2 — Verify nimbus-jose-jwt 9.40 advisories (5 min).**
Load WebSearch, then query for advisories resolved *after* 9.40 in the 9.x line:
```
ToolSearch query: select:WebSearch
```
Then WebSearch:
- `nimbus-jose-jwt 9.40 CVE security advisory fixed version`
- `com.nimbusds nimbus-jose-jwt 9.x changelog security DoS JWE JWS parsing after 9.40`

Decision rule:
- If a concrete CVE/GHSA is documented as **fixed in a specific 9.x ≥ 9.41 patch** that 9.40 is vulnerable to (and the fixed version is still 9.x, not a major bump): proceed to Step 4.3 with that exact fixed version.
- If the only fixes are in 10.x (major) or there is **no** advisory affecting 9.40 in the paths we use (JWKS verification / ID-Token / license JWT): do **not** bump (major upgrades are out of this spec's scope per design §5.4). Record in Step 4.5.

**Step 4.3 — (Conditional) Bump nimbus to the fixed 9.x patch (4 min).**
Only if Step 4.2 confirmed a 9.x advisory fix. Edit `gradle/libs.versions.toml:8`:
```
nimbus-jose-jwt = "<confirmed-fixed-9.x-version>"
```
Then verify the auth-critical paths regress clean (these are plain unit tests — no Testcontainers — so they run in this env):
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
./gradlew :core:compileJava :admin-app:compileJava -q && \
./gradlew :core:test --tests 'com.crosscert.passkey.core.jwt.SigningKeyProviderTest' --tests 'com.crosscert.passkey.core.jwt.IdTokenIssuerTest'
```
Expected: EXIT 0, both GREEN (proves JWKS cache + RS256 signing/verification + Ed25519 path unaffected by the bump). If red, revert the version edit and treat as "bump blocked — record in followups".

**Step 4.4 — Verify vite 5.4.21 / esbuild 0.21.5 advisories (4 min).**
WebSearch:
- `vite 5.4.21 esbuild 0.21.5 CVE GHSA-67mh-4wv8-2f99 dev server fixed version`
- `vite 5.4.x latest patch esbuild dev server CORS advisory resolved`

Decision rule:
- The finding itself states these are **dev-server-only**; prod serves static `vite build` output (admin-app static serving), so there's **no runtime exposure**. If a higher 5.4.x patch (or esbuild ≥0.25.x via a vite 5.4.x patch) resolves GHSA-67mh-4wv8-2f99 **and** the bump stays within the `^5.4.x` semver range already declared: optionally bump to the patch in Step 4.6.
- Given dev-only impact + design §5.4 ("dev-only 무영향이면 followups에 유지근거 기록하고 범프 안 함"), the default is **no bump** unless the patch is trivially in-range and verified. Do not perform a major vite upgrade.

**Step 4.5 — (Conditional) Bump vite to in-range patch (4 min).**
Only if Step 4.4 found a safe in-`^5.4.x` patch. Edit `admin-ui/package.json` vite to the confirmed `^5.4.x`, then:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins/admin-ui && \
npm install && npx tsc -b && npm test && npm run build
```
Expected: all EXIT 0, vitest green (22 tests), `npm run build` produces static output. If anything regresses, revert `package.json`/`package-lock.json` and record "vite bump blocked".

**Step 4.6 — Write the decision record (always) (4 min).**
Regardless of branch, write `docs/superpowers/followups/2026-05-31-QW-3-dependency-decisions.md` capturing both decisions with evidence (advisory IDs / release-note URLs from Steps 4.2/4.4, the chosen action, and — for any "no bump" — the explicit keep-rationale: current pin, why not vulnerable in our usage, scope boundary against major bumps). Template:
```markdown
# QW-3 Dependency Decisions (2026-05-31)

## nimbus-jose-jwt (pinned 9.40, libs.versions.toml:8; core:26 + sdk-java:20)
- Advisories checked: <CVE/GHSA ids or "none found affecting 9.40 in our JWKS/ID-Token/license paths">
- Sources: <release-note / advisory URLs>
- Decision: <bumped to 9.x.y | keep 9.40>
- Rationale: <if keep: no in-9.x advisory affecting our paths; 10.x is a major upgrade out of QW-3 scope per design §5.4. if bump: SigningKeyProviderTest + IdTokenIssuerTest green>

## vite / esbuild (admin-ui devDep; vite 5.4.21 / esbuild 0.21.5)
- Advisories checked: GHSA-67mh-4wv8-2f99 (esbuild dev-server CORS), vite 5.4 dev-server fs.deny
- Decision: <bumped to vite ^5.4.x | keep>
- Rationale: dev-server-only; prod serves static `vite build` output (admin-app static serving) → no runtime exposure. <if keep: not bumped, dev hygiene noted; if bump: in-range patch, tsc/vitest/build green>
```

**Step 4.7 — Commit (2 min).**
Stage only what actually changed (followups doc always; libs.versions.toml and/or admin-ui package files only if bumped):
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins && \
git add docs/superpowers/followups/2026-05-31-QW-3-dependency-decisions.md && \
git add -- gradle/libs.versions.toml admin-ui/package.json admin-ui/package-lock.json 2>/dev/null ; \
git commit -m "chore(deps): QW-3 의존성 advisory 검증 + 결정 기록

nimbus-jose-jwt 9.40 / vite 5.4.21·esbuild 0.21.5 실제 해소 advisory 확인.
<범프 시: 패치 범프 + SigningKeyProviderTest/IdTokenIssuerTest green />
<유지 시: 유지 근거 followups 기록, 무검증 범프 금지 준수 />

findings: sec-nimbus-jose-jwt-940, sec-vite-esbuild-devserver

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
(Replace the angle-bracket lines with the actual decision before committing.)

---

## Self-Review

### Spec coverage — every QW-3 finding maps to a Task
| Finding id | Task | Behavior/UI invariant check |
|---|---|---|
| `perf-apikey-no-tenant-index` | Task 1 (V38 index) | Step 1.3 structural sanity + Step 1.4 Flyway boot (env-conditional); pure schema add, no code/UI touch |
| `perf-tenant-view-nplus1` | Task 2 (GROUP BY batch) | Step 2.1 test pins identical numbers (active predicate, absent→0/null) + `never()` on N+1 methods; Step 2.7 JPQL parse |
| `perf-tenant-eager-collections-touch` | Task 3 (batch settings) | Step 3.2 keys-in-right-block + slice test; `order_inserts/updates` omitted to preserve write-path ordering |
| `sec-nimbus-jose-jwt-940` | Task 4 (advisory verify, branch) | Step 4.3 SigningKeyProviderTest/IdTokenIssuerTest green if bumped; else keep-rationale recorded |
| `sec-vite-esbuild-devserver` | Task 4 (advisory verify, branch) | Step 4.5 tsc/vitest/build green if bumped; else dev-only keep-rationale recorded |

### Placeholder scan
No "TBD"/"적절히 처리"/"테스트 추가" placeholders. Every code Step has a literal code block (SQL, JPQL, Java, YAML, commit messages). Conditional steps (Task 4 bumps) have explicit decision rules and exact commands; the only intentionally deferred literal is the *version number* in Steps 4.3/4.5, which is unknowable until the advisory search returns — gated behind a documented decision rule, not a placeholder.

### Type consistency
- Aggregate `@Query` returns `List<Object[]>`; drained via `((Number) r[1]).longValue()` (defensive against Oracle returning `BigInteger`/`BigDecimal` for `count`) and `(Instant) r[1]` for `MAX(createdAt)`. Map types `Map<UUID,Long>` / `Map<UUID,Instant>` match `TenantView.from(Tenant, long, long, Instant)`.
- `countActiveGroupedByTenantId(Instant now)` mirrors `countActiveByTenantId(UUID, Instant)`'s predicate exactly; `Instant`/`@Param` already imported in `ApiKeyRepository`.
- `toView(Tenant)` (per-tenant, used by get/create/update) and `toView(Tenant, long, long, Instant)` (batch) coexist; the test's `never()` assertions prove `list()` uses only the batch overload.
- YAML keys are flat dot-style (`hibernate.jdbc.batch_size`) matching existing `hibernate.dialect`/`hibernate.default_schema` convention; 6-space indent under `properties`, `ddl-auto` left at 4-space `spring.jpa` level.
- `getId()` confirmed on `BaseEntity` (returns `UUID`), so `t.getId()` map keys are `UUID` — consistent with aggregate row `(UUID) r[0]`.

### Residual risks flagged for the executor
- JPQL of the 3 new aggregate queries is **not** parsed by the mocked unit test (Step 2.7); it is only validated at a real Hibernate boot (`VpdIsolationIT` or any admin-app context test) — flaky in this env. Executor must record explicitly whether an in-env boot parsed them, or note the env-gap.
- V38 idempotent re-application is only exercised by `VpdIsolationIT` (env-conditional). The structural sanity (Step 1.3) guarantees the pattern matches the proven V24 template byte-for-byte, which is the mitigation.