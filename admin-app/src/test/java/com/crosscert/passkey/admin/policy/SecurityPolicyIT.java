package com.crosscert.passkey.admin.policy;

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
 * Phase F1 Task 6 (Gap #20) — SecurityPolicy round-trip smoke IT.
 *
 * <p>Single happy-path test: GET (seeded defaults from V31) → PUT (new values)
 * → GET (values persisted). RBAC + validation are covered by the manual smoke
 * at Phase F1 Task 17, per execution policy §1 (tests minimal).
 *
 * <p>Self-contained Testcontainers scaffolding mirroring {@code AdminFlowIT}
 * and {@code AaguidPolicyCeremonyIT} — no shared base class exists in this
 * module by design.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SecurityPolicyIT {

    // ------------------------------------------------------------
    // Containers (verbatim from AdminFlowIT)
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
    // Login + cookie helpers (verbatim from AdminFlowIT)
    // ------------------------------------------------------------

    /**
     * Performs Spring Security form login and returns an HttpHeaders
     * pre-populated with the session cookie + CSRF token, suitable for
     * subsequent JSON API calls.
     *
     * <p>Flow:
     * <ol>
     *   <li>Seed: GET /admin/api/me to acquire XSRF-TOKEN cookie.
     *       AdminSecurityConfig sets {@code setCsrfRequestAttributeName(null)}
     *       so the CSRF cookie is emitted on every response — including
     *       the 401 we get here (no auth).</li>
     *   <li>POST /admin/login as form data, sending the XSRF cookie back
     *       in both Cookie header (CookieCsrfTokenRepository validates
     *       cookie==header) and X-XSRF-TOKEN header.</li>
     *   <li>Replace cookies by name (not append) so that a rotated
     *       XSRF-TOKEN on login does not leave two same-named cookies
     *       in the outgoing Cookie header — servers don't all agree on
     *       which one "wins", and CookieCsrfTokenRepository would reject
     *       a mismatch between Cookie and X-XSRF-TOKEN header.</li>
     *   <li>Post-login "warm-up" GET /admin/api/me with the session
     *       cookie. This forces the server to emit a fresh CSRF cookie
     *       for the authenticated session (in case Spring's
     *       CsrfAuthenticationStrategy did not rotate during the
     *       login POST), guaranteeing the returned headers are ready
     *       for state-changing JSON calls.</li>
     * </ol>
     */
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

        // 3. Warm-up GET /me with the authenticated session cookie.
        //    Spring's default CsrfAuthenticationStrategy may rotate the
        //    XSRF token at login, but in any case this round-trip
        //    guarantees the cookie + header pair we return matches the
        //    server's authoritative session token.
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

    /**
     * Parse each Set-Cookie header's name=value pair and upsert it into
     * the cookie jar. Later Set-Cookie values for the same cookie name
     * replace earlier ones — matching browser semantics and avoiding
     * the "two XSRF-TOKEN cookies in the same Cookie header" hazard
     * that codex P1 flagged.
     */
    private static void mergeSetCookiesByName(Map<String, String> jar, List<String> setCookies) {
        if (setCookies == null) return;
        for (String sc : setCookies) {
            String pair = sc.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1);
            if (name.isEmpty()) continue;
            // Empty value = server-sent cookie delete. Remove from jar.
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
    void getThenPutThenGetRoundtrip() {
        // Alice = PLATFORM_OPERATOR (V11 seed; V23 role rename) — can PUT.
        HttpHeaders auth = loginAs("alice@crosscert.com", "alice-temp-pw");
        auth.setContentType(MediaType.APPLICATION_JSON);

        // 1. GET — seeded defaults from V31 (idle=30, minLen=12, mfa=Y, cors=[]).
        ResponseEntity<JsonNode> initial = rest.exchange(
                url("/admin/api/security-policy"),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(initial.getStatusCode().value())
                .as("initial GET: %s body=%s", initial.getStatusCode(), initial.getBody())
                .isEqualTo(200);
        assertThat(initial.getBody().get("sessionIdleTimeoutMinutes").asInt()).isEqualTo(30);

        // 2. PUT — new values.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionIdleTimeoutMinutes", 15);
        body.put("passwordMinLength", 16);
        body.put("mfaRequired", false);
        body.put("corsAllowlist", List.of("https://a.example.com"));
        ResponseEntity<JsonNode> putRes = rest.exchange(
                url("/admin/api/security-policy"),
                HttpMethod.PUT, new HttpEntity<>(body, auth), JsonNode.class);
        assertThat(putRes.getStatusCode().value())
                .as("PUT: %s body=%s", putRes.getStatusCode(), putRes.getBody())
                .isEqualTo(200);

        // 3. GET — values are persisted.
        ResponseEntity<JsonNode> readback = rest.exchange(
                url("/admin/api/security-policy"),
                HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class);
        assertThat(readback.getStatusCode().value()).isEqualTo(200);
        assertThat(readback.getBody().get("sessionIdleTimeoutMinutes").asInt()).isEqualTo(15);
        assertThat(readback.getBody().get("passwordMinLength").asInt()).isEqualTo(16);
        assertThat(readback.getBody().get("mfaRequired").asBoolean()).isFalse();
        JsonNode cors = readback.getBody().get("corsAllowlist");
        assertThat(cors.isArray()).isTrue();
        assertThat(cors).hasSize(1);
        assertThat(cors.get(0).asText()).isEqualTo("https://a.example.com");
    }
}
