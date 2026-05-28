package com.crosscert.passkey.admin.funnel;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F3 Task 4 (Gap #3/#9) — Funnel endpoint shape smoke IT.
 *
 * <p>Single happy-path test: GET /admin/api/tenants/{id}/funnel?windowDays=7
 * returns the FunnelDto.View shape introduced in Task 1 (commit 726f707):
 * {@code windowDays}, {@code registration}, {@code authentication},
 * {@code conversion}, {@code series}, {@code byEventType}.
 *
 * <p>Ceremony events are not yet adopted in the seed dataset, so counts are
 * expected to be 0 — we only assert the JSON shape and series length, not
 * the numeric values.
 *
 * <p>Self-contained Testcontainers scaffolding mirroring {@code TenantKpiIT}.
 * Per execution policy §1: one round-trip only.
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

    private String url(String path) { return "http://localhost:" + port + path; }

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

    // ------------------------------------------------------------
    // The smoke scenario
    // ------------------------------------------------------------

    @Test
    void getFunnelReturnsShapeWithZeroOrMoreCounts() {
        // Alice = PLATFORM_OPERATOR (V11 seed; V23 role rename) — sees all tenants.
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        // 1. Get a tenant id.
        ResponseEntity<JsonNode> tenants = rest.exchange(
                url("/admin/api/tenants"),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(tenants.getStatusCode().value())
                .as("GET /admin/api/tenants: %s body=%s",
                        tenants.getStatusCode(), tenants.getBody())
                .isEqualTo(200);

        // Server returns ApiResponse envelope — unwrap consistently (matches TenantKpiIT pattern).
        JsonNode tenantsEnvelope = tenants.getBody();
        assertThat(tenantsEnvelope).isNotNull();
        JsonNode tenantList = tenantsEnvelope.get("data");
        assertThat(tenantList).as("tenants list ApiResponse.data").isNotNull();
        assertThat(tenantList.isArray()).isTrue();
        assertThat(tenantList).as("tenant list non-empty (V11/V19 seed data)").isNotEmpty();
        String tenantId = tenantList.get(0).get("id").asText();

        // 2. GET funnel.
        ResponseEntity<JsonNode> funnel = rest.exchange(
                url("/admin/api/tenants/" + tenantId + "/funnel?windowDays=7"),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(funnel.getStatusCode().value())
                .as("GET /admin/api/tenants/%s/funnel: %s body=%s",
                        tenantId, funnel.getStatusCode(), funnel.getBody())
                .isEqualTo(200);

        // FunnelController returns FunnelDto.View directly (no ApiResponse envelope —
        // matches AaguidPolicyController / SecurityPolicyController convention).
        JsonNode body = funnel.getBody();
        assertThat(body).isNotNull();

        // Shape + element-typed assertions. Ceremony events not yet adopted, so counts are 0
        // on a clean Testcontainers boot — but field types must still be correct.
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
        // byEventType may be empty when no ceremony events exist yet; if any element
        // is present its shape must still be {type:string, n:number}.
        for (JsonNode evt : byEventType) {
            assertThat(evt.get("type").isTextual()).as("byEventType[].type non-null text").isTrue();
            assertThat(evt.get("n").isNumber()).as("byEventType[].n numeric").isTrue();
        }
    }
}
