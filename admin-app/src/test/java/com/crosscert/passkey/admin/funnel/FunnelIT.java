package com.crosscert.passkey.admin.funnel;

import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F3 Task 4 (Gap #3/#9) — Funnel endpoint IT.
 *
 * <p>Two scenarios over GET /admin/api/tenants/{id}/funnel?windowDays=7:
 * <ol>
 *   <li>{@link #getFunnelReturnsShapeWithZeroOrMoreCounts()} — the FunnelDto.View
 *       JSON SHAPE (windowDays / registration / authentication / conversion /
 *       series / byEventType) on a tenant with no seeded ceremony events.</li>
 *   <li>{@link #getFunnelComputesRealCountsFromCeremonyEvent()} — seeds real
 *       {@code ceremony_event} rows (V41) and asserts the COMPUTED counts:
 *       4 registration attempts, 3 successes, ratio 0.75.</li>
 * </ol>
 *
 * <p>Self-contained Testcontainers scaffolding mirroring {@code TenantKpiIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class FunnelIT {

    // ------------------------------------------------------------
    // Containers (verbatim from TenantKpiIT)
    // ------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

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
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource ds;

    private String url(String path) { return "http://localhost:" + port + path; }

    // ------------------------------------------------------------
    // The smoke scenario — JSON shape only (no seeded events)
    // ------------------------------------------------------------

    @Test
    void getFunnelReturnsShapeWithZeroOrMoreCounts() {
        // Alice = PLATFORM_OPERATOR (test seed V9001; V23 role rename) — may create/see any tenant.
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // Production tenant seed was stripped (profile separation); only admin users are
        // restored in test (V9001). Create a fresh tenant so this IT is self-sufficient.
        String tenantId = createTenant(auth, "funnel-shape");
        JsonNode body = fetchFunnel(auth, tenantId, 7);

        // Shape + element-typed assertions. This tenant has no seeded ceremony
        // events, so counts are 0 — but field types must still be correct.
        assertThat(body.get("windowDays").asInt()).isEqualTo(7);

        JsonNode registration = body.get("registration");
        assertThat(registration).isNotNull();
        assertThat(registration.get("attempts").isNumber()).isTrue();
        assertThat(registration.get("success").isNumber()).isTrue();
        assertThat(registration.get("ratio").isNumber()).isTrue();

        JsonNode authentication = body.get("authentication");
        assertThat(authentication).isNotNull();
        assertThat(authentication.get("attempts").isNumber()).isTrue();
        assertThat(authentication.get("success").isNumber()).isTrue();
        assertThat(authentication.get("ratio").isNumber()).isTrue();

        assertThat(body.get("conversion").isNumber()).isTrue();

        JsonNode series = body.get("series");
        assertThat(series).isNotNull();
        assertThat(series.isArray()).isTrue();
        assertThat(series.size()).as("series length must equal windowDays").isEqualTo(7);
        for (JsonNode point : series) {
            assertThat(point.get("day").isTextual()).as("series[].day non-null text").isTrue();
            assertThat(point.get("attempts").isNumber()).as("series[].attempts numeric").isTrue();
            assertThat(point.get("success").isNumber()).as("series[].success numeric").isTrue();
        }

        JsonNode byEventType = body.get("byEventType");
        assertThat(byEventType).isNotNull();
        assertThat(byEventType.isArray()).isTrue();
        // byEventType may be empty when no ceremony events exist; if any element
        // is present its shape must still be {type:string, n:number}.
        for (JsonNode evt : byEventType) {
            assertThat(evt.get("type").isTextual()).as("byEventType[].type non-null text").isTrue();
            assertThat(evt.get("n").isNumber()).as("byEventType[].n numeric").isTrue();
        }
    }

    // ------------------------------------------------------------
    // Real-count scenario — seed ceremony_event, assert computed funnel
    // ------------------------------------------------------------

    @Test
    void getFunnelComputesRealCountsFromCeremonyEvent() {
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");
        // Own tenant created here — guarantees no ceremony_event rows from other
        // tenants/tests skew the count assertions below.
        String tenantId = createTenant(auth, "funnel-counts");
        UUID tenant = UUID.fromString(tenantId);

        // Seed 4 begins + 3 successes within the 7-day window (SYSTIMESTAMP).
        // FunnelService maps registration.attempts <- REGISTRATION_BEGIN,
        // registration.success <- REGISTRATION_FINISH_OK ("FINISH_OK" string).
        seedCeremonyEvents(tenant, CeremonyAction.REGISTRATION_BEGIN, 4);
        seedCeremonyEvents(tenant, CeremonyAction.REGISTRATION_SUCCESS, 3);

        JsonNode body = fetchFunnel(auth, tenantId, 7);

        JsonNode registration = body.get("registration");
        assertThat(registration).as("registration stage present").isNotNull();
        assertThat(registration.get("attempts").asLong())
                .as("registration.attempts == 4 (REGISTRATION_BEGIN count)")
                .isEqualTo(4L);
        assertThat(registration.get("success").asLong())
                .as("registration.success == 3 (REGISTRATION_FINISH_OK count)")
                .isEqualTo(3L);
        assertThat(registration.get("ratio").asDouble())
                .as("registration.ratio == success/attempts == 3/4")
                .isEqualTo(0.75);
    }

    // ------------------------------------------------------------
    // Shared helpers (DRY across both scenarios)
    // ------------------------------------------------------------

    /**
     * Creates a tenant with the given slug as the authenticated admin and returns
     * its UUID. POST /admin/api/tenants returns ApiResponse&lt;TenantView&gt; (201);
     * the id lives at {@code data.id} (matches AdminFlowIT step ③).
     */
    private String createTenant(HttpHeaders auth, String slug) {
        String body = """
                {"slug":"%s","displayName":"%s","rpId":"%s.example.com","rpName":"%s",
                 "allowedOrigins":["http://localhost"],
                 "acceptedFormats":["none","packed"],
                 "requireUserVerification":true,
                 "mdsRequired":false,
                 "attestationConveyance":"NONE",
                 "webauthnTimeoutMs":60000}
                """.formatted(slug, slug, slug, slug);
        ResponseEntity<JsonNode> create = rest.exchange(
                url("/admin/api/tenants"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JsonNode.class);
        assertThat(create.getStatusCode().value())
                .as("create tenant %s: %s body=%s", slug, create.getStatusCode(), create.getBody())
                .isEqualTo(201);
        JsonNode envelope = create.getBody();
        assertThat(envelope).isNotNull();
        JsonNode data = envelope.get("data");
        assertThat(data).as("create tenant ApiResponse.data").isNotNull();
        return data.get("id").asText();
    }

    /** GET the funnel for a tenant and assert 200; returns the FunnelDto.View body (no envelope). */
    private JsonNode fetchFunnel(HttpHeaders auth, String tenantId, int windowDays) {
        ResponseEntity<JsonNode> funnel = rest.exchange(
                url("/admin/api/tenants/" + tenantId + "/funnel?windowDays=" + windowDays),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(funnel.getStatusCode().value())
                .as("GET /admin/api/tenants/%s/funnel: %s body=%s",
                        tenantId, funnel.getStatusCode(), funnel.getBody())
                .isEqualTo(200);
        // FunnelController returns FunnelDto.View directly (no ApiResponse envelope —
        // matches AaguidPolicyController / SecurityPolicyController convention).
        JsonNode body = funnel.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    /**
     * Inserts {@code count} ceremony_event rows for {@code tenant} with the given
     * action at SYSTIMESTAMP (within the funnel window). tenant_id is RAW(16) so
     * the UUID is bound as its 16-byte big-endian form ({@link #uuidToBytes}).
     * id defaults to SYS_GUID(); created_at/updated_at are required (BaseEntity).
     */
    private void seedCeremonyEvents(UUID tenant, String action, int count) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        byte[] tenantBytes = uuidToBytes(tenant);
        for (int i = 0; i < count; i++) {
            jdbc.update(
                    "INSERT INTO APP_OWNER.ceremony_event "
                            + "(tenant_id, action, created_at, updated_at) "
                            + "VALUES (?, ?, SYSTIMESTAMP, SYSTIMESTAMP)",
                    tenantBytes, action);
        }
    }

    /**
     * Convert a UUID to the 16-byte big-endian RAW representation used by Oracle.
     * Required when binding a UUID against a RAW(16) column via JDBC.
     */
    private static byte[] uuidToBytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    // ------------------------------------------------------------
    // Login + cookie helpers (verbatim from TenantKpiIT)
    // ------------------------------------------------------------

    private HttpHeaders loginAs(String email, String password) {
        Map<String, String> jar = new LinkedHashMap<>();

        // 1. Seed CSRF cookie via unauthenticated GET.
        ResponseEntity<String> seed = rest.exchange(
                url("/admin/api/me"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        mergeSetCookiesByName(jar, seed.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertThat(jar.get("XSRF-TOKEN"))
                .as("CSRF cookie should be emitted on seed request (even 401)")
                .isNotNull();

        // 2. POST form login.
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        loginHeaders.set(HttpHeaders.COOKIE, renderCookieHeader(jar));
        loginHeaders.set("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        String body = "email=" + email + "&password=" + password;
        ResponseEntity<String> login = rest.exchange(
                url("/admin/login"), HttpMethod.POST,
                new HttpEntity<>(body, loginHeaders), String.class);
        assertThat(login.getStatusCode().is2xxSuccessful())
                .as("login as %s: %s body=%s", email, login.getStatusCode(), login.getBody())
                .isTrue();
        mergeSetCookiesByName(jar, login.getHeaders().get(HttpHeaders.SET_COOKIE));

        // 3. Warm-up GET /me to lock the session+CSRF cookie pair.
        HttpHeaders warmHeaders = new HttpHeaders();
        warmHeaders.set(HttpHeaders.COOKIE, renderCookieHeader(jar));
        ResponseEntity<String> warm = rest.exchange(
                url("/admin/api/me"), HttpMethod.GET,
                new HttpEntity<>(warmHeaders), String.class);
        mergeSetCookiesByName(jar, warm.getHeaders().get(HttpHeaders.SET_COOKIE));

        HttpHeaders auth = new HttpHeaders();
        auth.setContentType(MediaType.APPLICATION_JSON);
        auth.set(HttpHeaders.COOKIE, renderCookieHeader(jar));
        auth.set("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"));
        return auth;
    }

    private static void mergeSetCookiesByName(Map<String, String> jar, List<String> setCookies) {
        if (setCookies == null) return;
        for (String sc : setCookies) {
            String pair = sc.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1);
            if (name.isEmpty()) continue;
            if (value.isEmpty()) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    private static String renderCookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
