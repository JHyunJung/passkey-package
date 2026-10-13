package com.crosscert.passkey.core.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8 — DEAD 상태 LicenseGuardFilter 의 예외 경로 판정을 실제 Tomcat 위에서 고정한다.
 *
 * <p>MockMvc 는 '..' 정규화·';' 경로 파라미터 제거·%2e 디코드를 하지 않으므로,
 * 임베디드 Tomcat 에 raw socket 으로 요청을 보내 운영과 같은
 * requestURI / servletPath / pathInfo 조합을 만든다. 필터 뒤의 서블릿은
 * 도달하면 200 + {@link #PASSED} 를 쓴다 — 즉 200 은 "필터가 통과시켰다",
 * 503 + L001 은 "필터가 차단했다" 를 뜻한다.
 */
class LicenseGuardFilterExemptPathTest {

    private static final String PASSED = "PASSED_TO_SERVLET";

    private static WebServer server;

    @BeforeAll
    static void startTomcat() {
        LicenseGuardFilter filter = new LicenseGuardFilter(
                deadStateMachine(), new ObjectMapper().findAndRegisterModules());
        ServletContextInitializer init = ctx -> {
            ctx.addFilter("licenseGuardFilter", filter)
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            ctx.addServlet("reached", new HttpServlet() {
                @Override
                protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
                    res.setStatus(HttpServletResponse.SC_OK);
                    res.setContentType("text/plain");
                    String pathInfo = req.getPathInfo() == null ? "" : req.getPathInfo();
                    res.getWriter().write(PASSED + " " + req.getServletPath() + pathInfo);
                }
            }).addMapping("/");
        };
        server = new TomcatServletWebServerFactory(0).getWebServer(init);
        server.start();
    }

    @AfterAll
    static void stopTomcat() {
        if (server != null) {
            server.stop();
        }
    }

    // ── 특성화: 변경 전후 모두 같아야 한다 ─────────────────────────────

    /** DEAD_EXEMPT 6개 경로와 실제로 쓰이는 하위·쿼리 형태 — 모두 통과. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/admin/api/license",
            "/admin/api/system/info",
            "/admin/login",
            "/admin/logout",
            "/actuator/health",
            "/actuator/health/",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/health?probe=1",
            "/actuator/info",
            "/admin/login?error"
    })
    void legitimateExemptPaths_passThrough(String path) throws IOException {
        RawResponse r = rawGet(path);
        assertThat(r.status()).as("status for %s", path).isEqualTo(200);
        assertThat(r.text()).contains(PASSED);
    }

    /** 예외가 아닌 경로(접두사만 비슷한 경로 포함) — 503 + L001. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/",
            "/api/v1/rp/registration/start",
            "/admin/api/tenants",
            "/admin/api/licenses",
            "/admin/loginx",
            "/actuator/healthz",
            "/actuator/prometheus"
    })
    void nonExemptPaths_blockedWith503(String path) throws IOException {
        RawResponse r = rawGet(path);
        assertThat(r.status()).as("status for %s", path).isEqualTo(503);
        assertThat(r.text()).contains("\"code\":\"L001\"").doesNotContain(PASSED);
    }

    /**
     * 지금도 원본 URI 기준으로 예외가 아닌 변형들 — 변경 후에도 차단이 유지되어
     * 새 규칙이 예외 집합을 넓히지 않음을 고정한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health;jsessionid=ABC",
            "/actuator/health;jsessionid=ABC/../../api/v1/rp/registration/start",
            "//actuator/health",
            "/actuator/%68ealth",
            "/admin/api/%6Cicense"
    })
    void variantsAlreadyBlockedToday_stayBlocked(String path) throws IOException {
        RawResponse r = rawGet(path);
        assertThat(r.status()).as("status for %s", path).isEqualTo(503);
        assertThat(r.text()).contains("\"code\":\"L001\"").doesNotContain(PASSED);
    }

    /**
     * 인코딩된 슬래시(%2F)·백슬래시를 섞은 순회 — Tomcat 이 400 으로 거부하든(encodedSolidusHandling,
     * 백슬래시 거부) 필터가 503 으로 막든, 서블릿에는 절대 도달하지 않음을 고정한다.
     * 상태 코드는 400/404/503 중 무엇이든 허용한다. Step 2 의 사전 확인에서 Tomcat 이
     * 200 이 아닌 값을 돌려준 순회 입력도 이 목록 끝에 옮겨 붙인다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health/..%2F..%2Fapi/v1/rp/registration/start",
            "/actuator/health%2F..%2F..%2Fapi/v1/rp/registration/start",
            "/actuator/health/%2e%2e%2f%2e%2e%2fapi/v1/rp/registration/start",
            "/actuator/health/..\\..\\api/v1/rp/registration/start"
    })
    void encodedSlashOrBackslashTraversal_neverReachesServlet(String path) throws IOException {
        RawResponse r = rawGet(path);
        assertThat(r.text()).as("body for %s", path).doesNotContain(PASSED);
        assertThat(r.status()).as("status for %s", path).isIn(400, 404, 503);
    }

    // ── S8: 예외 경로 접두사 + 순회로 비예외 경로에 도달하는 우회 — 차단되어야 한다 ──

    /**
     * Tomcat 이 '..'·%2e·';' 를 정규화해 실제로는 비예외 경로(servletPath)로 디스패치되지만
     * 원본 URI 는 예외 접두사로 시작하는 요청들. 현재 코드는 원본 URI 만 보아 통과시킨다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health/../../api/v1/rp/registration/start",
            "/actuator/health/%2e%2e/%2e%2e/api/v1/rp/registration/start",
            "/actuator/health/%2E%2E/%2E%2E/api/v1/rp/registration/start",
            "/actuator/health/.%2e/.%2e/api/v1/rp/registration/start",
            "/actuator/info/..;/..;/api/v1/rp/registration/start",
            "/actuator/health/x;jsessionid=ABC/../../../api/v1/rp/registration/start",
            "/admin/login/../api/tenants",
            "/admin/api/license/%2e%2e/tenants"
    })
    void traversalFromExemptPrefix_toNonExemptPath_blockedWith503(String path) throws IOException {
        RawResponse r = rawGet(path);
        assertThat(r.status()).as("status for %s", path).isEqualTo(503);
        assertThat(r.text()).contains("\"code\":\"L001\"").doesNotContain(PASSED);
    }

    /**
     * 원본에 ';'(경로 파라미터, 예: ;jsessionid) 나 '..' 가 있으면 정규화 결과가
     * 예외 경로여도 예외로 보지 않는다(스펙 S8). 정상 클라이언트(healthcheck wget,
     * admin-ui fetch, formLogin)는 이런 형태를 보내지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health/liveness;jsessionid=ABC",
            "/admin/api/license/;x",
            "/actuator/health/x/../liveness"
    })
    void rawSemicolonOrDotDot_notExempt(String path) throws IOException {
        RawResponse r = rawGet(path);
        assertThat(r.status()).as("status for %s", path).isEqualTo(503);
        assertThat(r.text()).contains("\"code\":\"L001\"").doesNotContain(PASSED);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private record RawResponse(int status, String text) {}

    /** java.net.URI/HttpClient 의 정규화를 피하려고 요청 줄을 그대로 쓴다. */
    private static RawResponse rawGet(String rawPath) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + rawPath + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String text = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int sp = text.indexOf(' ');
            int status = Integer.parseInt(text.substring(sp + 1, sp + 4));
            return new RawResponse(status, text);
        }
    }

    private static LicenseStateMachine deadStateMachine() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        LicenseToken expired = new LicenseToken(
                "test-customer",
                "lic-test-dead",
                past.minus(Duration.ofDays(30)),
                past.minus(Duration.ofDays(30)),
                past,                                               // expiresAt = yesterday
                UUID.nameUUIDFromBytes("test".getBytes()).toString(),
                Set.of("mds"),
                LicenseLimits.DEFAULTS);
        LicenseStateMachine sm = new LicenseStateMachine(expired, past, Instant::now);
        assertThat(sm.state()).isEqualTo(LicenseState.DEAD);
        return sm;
    }
}
