package com.crosscert.passkey.admin.tenant;

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
 * Phase F2 Task 5 (Gap #1/#2/#4) — TenantView KPI + webauthn fields smoke IT.
 *
 * <p>Single happy-path test: GET /admin/api/tenants returns the 5 fields added
 * in commit 075bbf0 ({@code credentials}, {@code apiKeys}, {@code lastEventAt},
 * {@code attestationConveyance}, {@code webauthnTimeoutMs}).
 *
 * <p>RBAC, validation, and write semantics are covered elsewhere (existing
 * tenant ITs + manual smoke). Per execution policy §1: one round-trip only.
 *
 * <p>Self-contained Testcontainers scaffolding mirroring {@code SecurityPolicyIT}
 * and {@code AdminFlowIT} — no shared base class exists in this module by design.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TenantKpiIT {

    // ------------------------------------------------------------
    // Containers (verbatim from SecurityPolicyIT)
    // ------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-schema.sql"),
                    "/tmp/bootstrap-schema.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-schema.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.getExitCode() + ")\n"
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
    // Login + cookie helpers (verbatim from SecurityPolicyIT)
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
    void getTenantsReturnsKpiAndWebauthnFields() {
        // Alice = PLATFORM_OPERATOR (V11 seed; V23 role rename) — sees all tenants.
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");

        ResponseEntity<JsonNode> listRes = rest.exchange(
                url("/admin/api/tenants"),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);

        assertThat(listRes.getStatusCode().value())
                .as("GET /admin/api/tenants: %s body=%s",
                        listRes.getStatusCode(), listRes.getBody())
                .isEqualTo(200);

        JsonNode envelope = listRes.getBody();
        assertThat(envelope).isNotNull();
        // ApiResponse<List<TenantView>> wraps the list in `data`.
        JsonNode list = envelope.get("data");
        assertThat(list).as("ApiResponse.data array").isNotNull();
        assertThat(list.isArray()).isTrue();
        assertThat(list).as("tenant list non-empty (V11/V19 seed data)").isNotEmpty();

        JsonNode first = list.get(0);
        // Commit 075bbf0 added 5 fields. Non-null fields must be present on
        // every tenant. {@code lastEventAt} is an {@code Instant} that may
        // legitimately be null when a tenant has no events yet — and the
        // project-wide {@code spring.jackson.default-property-inclusion:
        // non_null} (see core/application-common.yml) means Jackson omits
        // the key entirely in that case. The DTO field still exists; we
        // assert here that *if* present, it's typed as a string (Instant
        // serialization). Absence == "no events yet", which is the V11/V19
        // seed reality on a clean Testcontainers boot.
        assertThat(first.has("credentials")).as("credentials field").isTrue();
        assertThat(first.has("apiKeys")).as("apiKeys field").isTrue();
        assertThat(first.has("attestationConveyance")).as("attestationConveyance field").isTrue();
        assertThat(first.has("webauthnTimeoutMs")).as("webauthnTimeoutMs field").isTrue();
        if (first.has("lastEventAt")) {
            assertThat(first.get("lastEventAt").isTextual() || first.get("lastEventAt").isNull())
                    .as("lastEventAt — when present, must be Instant string or null")
                    .isTrue();
        }

        // KPI counts are numeric (may be 0 — that's fine).
        assertThat(first.get("credentials").isNumber()).isTrue();
        assertThat(first.get("apiKeys").isNumber()).isTrue();

        // Webauthn fields carry the V33 defaults for seeded tenants.
        assertThat(first.get("attestationConveyance").asText()).isEqualTo("NONE");
        assertThat(first.get("webauthnTimeoutMs").asInt()).isEqualTo(60000);
    }
}
