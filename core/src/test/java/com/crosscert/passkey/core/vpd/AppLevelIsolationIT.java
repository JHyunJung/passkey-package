package com.crosscert.passkey.core.vpd;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 acceptance gate — VPD-OFF mode isolation proof.
 *
 * <p>With {@code passkey.vpd.enabled=false} the Oracle VPD session-context
 * mechanism ({@link TenantAwareDataSource}) is not the isolation mechanism.
 * Tenant isolation must be provided entirely by the Hibernate {@code @Filter}
 * enabled through {@link TenantFilterAspect} at {@code @Transactional} entry.
 *
 * <p>This test targets the four "risky" {@link CredentialRepository} methods
 * that carry NO explicit {@code tenant_id} WHERE clause — they have historically
 * relied on VPD to enforce isolation. With VPD off, the Hibernate filter must
 * step in as the sole guard.
 *
 * <h2>Risky methods under test</h2>
 * <ol>
 *   <li>{@link CredentialRepository#findByUserHandle(byte[])} — returns
 *       {@code List<Credential>}, no tenant predicate in JPQL.</li>
 *   <li>{@link CredentialRepository#findCredentialIdsByUserHandle(byte[])} —
 *       returns {@code List<byte[]>}, no tenant predicate.</li>
 *   <li>{@link CredentialRepository#findByCredentialIdForUpdate(byte[])} —
 *       pessimistic-lock lookup, no tenant predicate.</li>
 *   <li>{@link CredentialRepository#findOwnedForUpdate(byte[], byte[])} —
 *       ownership check by credentialId + userHandle, no tenant predicate.</li>
 * </ol>
 *
 * <h2>Seed strategy</h2>
 * Two tenants (T_A, T_B) share the SAME {@code userHandle} ("shared-user") and
 * have DIFFERENT {@code credentialId}s. Without the filter both tenants' rows
 * are returned — with the filter set to T_A only T_A's row is visible.
 *
 * <h2>Why this cannot be a false pass</h2>
 * <ul>
 *   <li>Each test asserts the POSITIVE half (T_A's row is found) AND the
 *       NEGATIVE half (T_B's row is absent), so an over-aggressive empty
 *       result would fail the positive assertion.</li>
 *   <li>Without the filter (context cleared, null) or without the aspect,
 *       {@code findByUserHandle} returns 2 rows — the "no context = all rows
 *       visible" companion test validates the baseline.</li>
 * </ul>
 *
 * <h2>@Transactional helper bean pattern</h2>
 * Calling {@link CredentialRepository} methods directly from the test class
 * would not trigger {@link TenantFilterAspect} because the test class is not
 * a Spring-managed bean; its calls are not intercepted by the AOP proxy. The
 * workaround is a small {@link RiskyMethodHelper} {@code @Service} whose
 * {@code @Transactional} methods delegate to the repository. The aspect fires
 * at the {@code @Transactional} boundary of these helper methods, reading the
 * context that the test set BEFORE calling into the service.
 *
 * <p>Uses the same Testcontainers Oracle XE 21 / bootstrap-vpd.sql /
 * application-test.yml pattern as {@link TenantFilterAspectIT} and
 * {@link VpdIsolationIT}. The test connects as APP_ADMIN_USER which holds
 * EXEMPT ACCESS POLICY — Oracle VPD is bypassed at the DB level, making the
 * Hibernate filter the ONLY active isolation mechanism.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = "passkey.vpd.enabled=false")
class AppLevelIsolationIT {

    // ── Minimal Spring Boot test app ────────────────────────────────────────
    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {

        @Configuration
        static class ServiceConfig {
            @Bean
            RiskyMethodHelper riskyMethodHelper(CredentialRepository repo) {
                return new RiskyMethodHelper(repo);
            }
        }
    }

    // ── Testcontainers Oracle XE ─────────────────────────────────────────────
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE =
            new OracleContainer(ORACLE_IMAGE)
                    .withUsername("APP_OWNER")
                    .withPassword(SYS_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                            "/tmp/bootstrap-vpd.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-vpd.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        // Finding #3 (Approach A): Flyway runs as APP_OWNER (schema owner)
        // so that migrations can GRANT on APP_OWNER objects without error.
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    // ── Test dependencies ────────────────────────────────────────────────────
    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    CredentialRepository credentialRepository;

    @Autowired
    RiskyMethodHelper helper;

    @Autowired
    JdbcTemplate jdbc;

    // ── Shared byte arrays ───────────────────────────────────────────────────
    /** userHandle shared by BOTH tenants' credentials — this is what makes the
     *  risky methods dangerous: a query by userHandle alone without a tenant
     *  filter would return rows from both tenants. */
    private static final byte[] SHARED_USER = "shared-user".getBytes();
    private static final byte[] CRED_ID_A   = "cred-id-A".getBytes();
    private static final byte[] CRED_ID_B   = "cred-id-B".getBytes();

    // ── Tenant UUIDs captured per run ────────────────────────────────────────
    private UUID tenantAId;
    private UUID tenantBId;

    // ── Seed + cleanup ───────────────────────────────────────────────────────

    /**
     * Seeds two tenants + one credential per tenant.
     *
     * <p>Both credentials share the same {@code userHandle} but have different
     * {@code credentialId}s so the four risky methods can each produce a
     * meaningful cross-tenant leak if the filter is absent.
     *
     * <p>Seeding is performed with {@link TenantContextHolder} CLEARED so the
     * aspect does NOT enable the filter during persistence — APP_ADMIN_USER
     * bypasses VPD anyway, but clearing the context makes the behavior
     * consistent regardless of aspect state during seeding.
     *
     * @return {@code {tenantAId, tenantBId}}
     */
    private UUID[] seed() {
        TenantContextHolder.clear();

        Tenant tA = new Tenant("app-iso-a", "App Isolation Tenant A", "localhost", "App Iso RP A");
        tA.addAllowedOrigin("http://localhost", 0);
        tA.addAcceptedFormat("none");
        tA = tenantRepository.save(tA);
        final UUID idA = tA.getId();

        Tenant tB = new Tenant("app-iso-b", "App Isolation Tenant B", "localhost", "App Iso RP B");
        tB.addAllowedOrigin("http://localhost", 0);
        tB.addAcceptedFormat("none");
        tB = tenantRepository.save(tB);
        final UUID idB = tB.getId();

        // Both credentials share SHARED_USER — different credentialIds.
        credentialRepository.save(new Credential(idA, SHARED_USER, CRED_ID_A, "pk-A".getBytes(), null));
        credentialRepository.save(new Credential(idB, SHARED_USER, CRED_ID_B, "pk-B".getBytes(), null));

        return new UUID[]{idA, idB};
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
    }

    // ── Test 1: findByUserHandle ─────────────────────────────────────────────

    /**
     * Proves that {@code findByUserHandle(sharedUser)} under tenant-A context
     * returns only T_A's credential — not T_B's.
     *
     * <p>Without the filter, the derived-query returns both rows (same
     * userHandle, two tenants). The Hibernate filter restricts it to the
     * context tenant.
     *
     * <p>Isolation is verified via both halves:
     * <ul>
     *   <li><b>Positive</b>: at least one result with tenantId == A.</li>
     *   <li><b>Negative</b>: no result with tenantId == B.</li>
     * </ul>
     * The companion baseline check (no context → 2 rows) proves the seeding is
     * correct and the positive assertion cannot silently pass on an empty table.
     */
    @Test
    void findByUserHandle_isolatesTenantA() {
        UUID[] ids = seed();
        tenantAId = ids[0];
        tenantBId = ids[1];

        // ── Baseline (no context): both rows visible ──────────────────────
        // This proves both seed rows exist and the method actually returns data.
        // Without this check a completely empty result could sneak past.
        TenantContextHolder.clear();
        List<Credential> baseline = helper.findByUserHandle(SHARED_USER);
        assertThat(baseline)
                .as("no-context baseline: both T_A and T_B rows must be present (filter OFF)")
                .hasSize(2)
                .extracting(Credential::getTenantId)
                .containsExactlyInAnyOrder(tenantAId, tenantBId);

        // ── Filter ON (context = T_A) ─────────────────────────────────────
        TenantContextHolder.set(tenantAId);
        List<Credential> filtered = helper.findByUserHandle(SHARED_USER);

        assertThat(filtered)
                .as("filter ON (T_A context): findByUserHandle must return only T_A's credential")
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.getTenantId())
                        .as("every returned credential must belong to T_A")
                        .isEqualTo(tenantAId));
        assertThat(filtered)
                .as("T_B's credential must NOT be visible under T_A context (no cross-tenant leak)")
                .noneMatch(c -> tenantBId.equals(c.getTenantId()));
    }

    // ── Test 2: findCredentialIdsByUserHandle ────────────────────────────────

    /**
     * Proves that {@code findCredentialIdsByUserHandle(sharedUser)} under
     * tenant-A context returns exactly 1 credentialId (T_A's), not 2.
     *
     * <p>Without the filter the JPQL projects both tenants' credentialIds
     * because both rows share the same userHandle. The filter restricts
     * visibility to T_A's row.
     */
    @Test
    void findCredentialIdsByUserHandle_isolatesTenantA() {
        UUID[] ids = seed();
        tenantAId = ids[0];
        tenantBId = ids[1];

        // ── Baseline: 2 credentialIds without filter ──────────────────────
        TenantContextHolder.clear();
        List<byte[]> allIds = helper.findCredentialIdsByUserHandle(SHARED_USER);
        assertThat(allIds)
                .as("no-context baseline: both credentialIds must be returned (filter OFF)")
                .hasSize(2);

        // ── Filter ON (context = T_A) ─────────────────────────────────────
        TenantContextHolder.set(tenantAId);
        List<byte[]> filteredIds = helper.findCredentialIdsByUserHandle(SHARED_USER);

        assertThat(filteredIds)
                .as("filter ON (T_A context): exactly 1 credentialId must be returned (T_A's only)")
                .hasSize(1);

        // The single returned credentialId must be T_A's, not T_B's.
        assertThat(filteredIds.get(0))
                .as("the returned credentialId must match T_A's CRED_ID_A")
                .isEqualTo(CRED_ID_A);
    }

    // ── Test 3: findByCredentialIdForUpdate ──────────────────────────────────

    /**
     * Proves that looking up T_B's {@code credentialId} under tenant-A context
     * returns empty — T_B's credential is invisible under the filter.
     *
     * <p>Without the filter, {@code findByCredentialIdForUpdate(credIdB)} would
     * return T_B's row regardless of the active tenant — a classic
     * cross-tenant data exposure path in the authentication finish flow.
     */
    @Test
    void findByCredentialIdForUpdate_bCredentialInvisibleUnderAContext() {
        UUID[] ids = seed();
        tenantAId = ids[0];
        tenantBId = ids[1];

        // ── Baseline: T_B's credential visible without filter ─────────────
        TenantContextHolder.clear();
        Optional<Credential> baselineB = helper.findByCredentialIdForUpdate(CRED_ID_B);
        assertThat(baselineB)
                .as("no-context baseline: T_B's credential must be found (filter OFF)")
                .isPresent();

        // ── Filter ON (context = T_A) ─────────────────────────────────────
        TenantContextHolder.set(tenantAId);
        Optional<Credential> filteredB = helper.findByCredentialIdForUpdate(CRED_ID_B);

        assertThat(filteredB)
                .as("filter ON (T_A context): T_B's credentialId must be INVISIBLE (no cross-tenant leak)")
                .isEmpty();

        // Sanity: T_A's own credential is still accessible.
        Optional<Credential> filteredA = helper.findByCredentialIdForUpdate(CRED_ID_A);
        assertThat(filteredA)
                .as("filter ON (T_A context): T_A's own credentialId must still be accessible")
                .isPresent()
                .get()
                .extracting(Credential::getTenantId)
                .isEqualTo(tenantAId);
    }

    // ── Test 4: findOwnedForUpdate ───────────────────────────────────────────

    /**
     * Proves that looking up T_B's {@code credentialId} with the shared
     * {@code userHandle} under tenant-A context returns empty.
     *
     * <p>Without the filter, {@code findOwnedForUpdate(credIdB, sharedUser)}
     * would return T_B's row — the shared userHandle satisfies the
     * userHandle predicate, and the credentialId matches T_B's row.
     * Only the tenant filter prevents the cross-tenant match.
     */
    @Test
    void findOwnedForUpdate_bCredentialInvisibleUnderAContext() {
        UUID[] ids = seed();
        tenantAId = ids[0];
        tenantBId = ids[1];

        // ── Baseline: T_B's credential found without filter ───────────────
        TenantContextHolder.clear();
        Optional<Credential> baselineB = helper.findOwnedForUpdate(CRED_ID_B, SHARED_USER);
        assertThat(baselineB)
                .as("no-context baseline: T_B credential must be found with (credIdB, sharedUser) — filter OFF")
                .isPresent();

        // ── Filter ON (context = T_A) ─────────────────────────────────────
        TenantContextHolder.set(tenantAId);
        Optional<Credential> filteredB = helper.findOwnedForUpdate(CRED_ID_B, SHARED_USER);

        assertThat(filteredB)
                .as("filter ON (T_A context): T_B credential must be INVISIBLE via findOwnedForUpdate (no cross-tenant leak)")
                .isEmpty();

        // Sanity: T_A's credential is still accessible.
        Optional<Credential> filteredA = helper.findOwnedForUpdate(CRED_ID_A, SHARED_USER);
        assertThat(filteredA)
                .as("filter ON (T_A context): T_A's own credential must still be accessible via findOwnedForUpdate")
                .isPresent()
                .get()
                .extracting(Credential::getTenantId)
                .isEqualTo(tenantAId);
    }

    // ── Test 5: prove VPD is bypassed so @Filter is the ONLY isolation ───────

    /**
     * Guards the load-bearing premise of this entire test class: that Oracle
     * VPD is NOT active on this session, so every isolation result above is
     * attributable to the Hibernate {@code @Filter} ALONE — never to VPD.
     *
     * <p>The premise is otherwise expressed only in comments. If a future
     * change swaps the connecting user from APP_ADMIN_USER to an APP_RUNTIME
     * identity (which IS subject to VPD), the four tests above could keep
     * passing for the WRONG reason — VPD's {@code tenant_id = SYS_CONTEXT(...)}
     * predicate would do the isolation while the {@code @Filter} silently
     * stopped mattering. This test converts that comment into an executable
     * assertion so the false-pass cannot happen unnoticed.
     *
     * <p><b>How it proves VPD is bypassed:</b> with {@link TenantContextHolder}
     * CLEARED there is no tenant in scope. If VPD were applied to this session,
     * a missing tenant context would make the V20 policy predicate evaluate to
     * {@code '1=0'} (or, equivalently, {@code set_tenant} would never have run),
     * so {@code findByUserHandle} would return ZERO rows — the safe default. The
     * fact that it instead returns BOTH seeded rows proves the session is EXEMPT
     * ACCESS POLICY (APP_ADMIN_USER) and VPD does not constrain it. Therefore
     * the per-tenant filtering observed in the other four tests is the work of
     * the Hibernate {@code @Filter} only.
     *
     * <p><b>Double protection:</b> this class also runs under
     * {@code passkey.vpd.enabled=false}, so {@link TenantAwareDataSource} does
     * not call {@code ctx_pkg.set_tenant} on borrow. Even on a VPD-subject user
     * the context would never be set — but relying on that alone would be
     * fragile, so this test asserts the OBSERVED outcome (2 rows, no context)
     * rather than the configuration. Both layers must hold for the row count
     * to be 2.
     */
    @Test
    void vpdIsBypassed_provesThatFilterIsTheOnlyIsolation() {
        UUID[] ids = seed();
        tenantAId = ids[0];
        tenantBId = ids[1];

        // No tenant in scope → if VPD governed this session the predicate would
        // be '1=0' and the result would be EMPTY. Both rows visible ⇒ EXEMPT.
        TenantContextHolder.clear();
        List<Credential> all = helper.findByUserHandle(SHARED_USER);

        assertThat(all)
                .as("APP_ADMIN_USER (EXEMPT ACCESS POLICY) + passkey.vpd.enabled=false → "
                        + "VPD inactive on this session; with no tenant context both rows must be "
                        + "visible. If only 1 (or 0) appeared, VPD would be doing the isolation and "
                        + "the @Filter proofs above would be false-passes.")
                .hasSize(2)
                .extracting(Credential::getTenantId)
                .containsExactlyInAnyOrder(tenantAId, tenantBId);
    }

    // ── @Transactional helper bean ───────────────────────────────────────────

    /**
     * Thin {@code @Transactional} service that provides an AOP-proxied join
     * point for {@link TenantFilterAspect}.
     *
     * <p>Calling {@link CredentialRepository} methods directly from the test
     * class would bypass the aspect because the test instance is not
     * Spring-managed. This helper is a {@code @Service} bean — its
     * {@code @Transactional} methods are intercepted by the aspect, which
     * reads {@link TenantContextHolder} at transaction entry and enables
     * the Hibernate filter on the bound session.
     *
     * <p>The test sets {@link TenantContextHolder} BEFORE calling any helper
     * method so the context is available when the aspect fires.
     */
    @Service
    static class RiskyMethodHelper {

        private final CredentialRepository repo;

        RiskyMethodHelper(CredentialRepository repo) {
            this.repo = repo;
        }

        @Transactional(readOnly = true)
        public List<Credential> findByUserHandle(byte[] userHandle) {
            return repo.findByUserHandle(userHandle);
        }

        @Transactional(readOnly = true)
        public List<byte[]> findCredentialIdsByUserHandle(byte[] userHandle) {
            return repo.findCredentialIdsByUserHandle(userHandle);
        }

        @Transactional
        public Optional<Credential> findByCredentialIdForUpdate(byte[] credentialId) {
            return repo.findByCredentialIdForUpdate(credentialId);
        }

        @Transactional
        public Optional<Credential> findOwnedForUpdate(byte[] credentialId, byte[] userHandle) {
            return repo.findOwnedForUpdate(credentialId, userHandle);
        }
    }
}
