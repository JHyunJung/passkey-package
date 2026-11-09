package com.crosscert.passkey.core.license;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8 — Tomcat 밖에서 만들어지는 요청 형태(MockMvc, 비루트 context-path, VALID 상태,
 * ForwardedHeaderFilter 의 X-Forwarded-Prefix)의 LicenseGuardFilter 판정을 고정한다.
 * 실제 Tomcat 정규화 경로는 {@link LicenseGuardFilterExemptPathTest} 가 다룬다.
 */
class LicenseGuardFilterTest {

    private static final String PASSED = "PASSED_TO_SERVLET";

    private final ObjectMapper mapper = new ObjectMapper();

    /** MockMvc 가 만드는 형태: servletPath "" + pathInfo = 경로 (admin-app LicenseGuardFilterIT 와 동일). */
    @Test
    void dead_mockMvcShapedRequest_toExemptPath_passes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/api/license");
        req.setServletPath("");
        req.setPathInfo("/admin/api/license");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LicenseGuardFilter(stateMachine(Duration.ofDays(-1)), mapper).doFilter(req, res, chain);

        assertThat(chain.getRequest()).as("chain invoked").isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    /** DispatcherServlet("/") 매핑 형태: servletPath = 전체 경로, pathInfo = null. */
    @Test
    void dead_dispatcherShapedRequest_toExemptPath_passes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/api/system/info");
        req.setServletPath("/admin/api/system/info");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LicenseGuardFilter(stateMachine(Duration.ofDays(-1)), mapper).doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    /**
     * 비루트 context-path 에서는 원본 URI 가 /passkey/actuator/health 라 지금은 예외가 아니다(503).
     * 새 규칙도 원본 URI 조건을 함께 요구하므로 그대로 503 — 예외 집합을 넓히지 않는다.
     */
    @Test
    void dead_nonRootContextPath_staysBlocked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/passkey/actuator/health");
        req.setContextPath("/passkey");
        req.setServletPath("/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LicenseGuardFilter(stateMachine(Duration.ofDays(-1)), mapper).doFilter(req, res, chain);

        assertThat(chain.getRequest()).as("chain must not be invoked").isNull();
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("\"code\":\"L001\"");
    }

    /** VALID 상태에서는 경로와 무관하게 통과(순회 형태 포함) — 예외 판정은 DEAD 에서만 쓰인다. */
    @Test
    void valid_traversalShapedRequest_passes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(
                "GET", "/actuator/health/../../api/v1/rp/registration/start");
        req.setServletPath("/api/v1/rp/registration/start");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new LicenseGuardFilter(stateMachine(Duration.ofDays(365)), mapper).doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-License-Warning")).isNull();
    }

    /**
     * 운영과 같은 순서(ForwardedHeaderFilter → LicenseGuardFilter). X-Forwarded-Prefix 가
     * 예외 경로 밖(/passkey)이면 wrapper 의 requestURI 가 /passkey/actuator/health 가 되어
     * 지금도 503 — 새 규칙도 원본 URI 조건을 요구하므로 그대로 503.
     */
    @Test
    void dead_forwardedPrefixOutsideExempt_staysBlocked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        req.setServletPath("/actuator/health");
        req.addHeader("X-Forwarded-Prefix", "/passkey");
        MockHttpServletResponse res = new MockHttpServletResponse();

        forwardedChain(Duration.ofDays(-1)).doFilter(req, res);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("\"code\":\"L001\"").doesNotContain(PASSED);
    }

    /**
     * S8 — 클라이언트가 보낸 X-Forwarded-Prefix 를 예외 경로로 주면 ForwardedHeaderFilter wrapper 의
     * requestURI 가 /actuator/health/api/v1/... 가 되어 현재 코드는 통과시킨다. 실제 디스패치
     * 경로(servletPath)는 비예외이므로 차단되어야 한다.
     */
    @Test
    void dead_forwardedPrefixOfExemptPath_toNonExemptServletPath_blockedWith503() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/registration/start");
        req.setServletPath("/api/v1/rp/registration/start");
        req.addHeader("X-Forwarded-Prefix", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();

        forwardedChain(Duration.ofDays(-1)).doFilter(req, res);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("\"code\":\"L001\"").doesNotContain(PASSED);
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** ForwardedHeaderFilter → LicenseGuardFilter → 도달하면 200 + PASSED 를 쓰는 서블릿. */
    private MockFilterChain forwardedChain(Duration validFor) {
        HttpServlet reached = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
                res.setStatus(HttpServletResponse.SC_OK);
                res.getWriter().write(PASSED);
            }
        };
        return new MockFilterChain(reached,
                new ForwardedHeaderFilter(),
                new LicenseGuardFilter(stateMachine(validFor), mapper));
    }

    private static LicenseStateMachine stateMachine(Duration validFor) {
        Instant now = Instant.now();
        LicenseToken token = new LicenseToken(
                "test", "lic-test-001",
                now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(30)),
                now.plus(validFor),
                "00000000-0000-0000-0000-000000000001",
                Set.of("mds"),
                LicenseLimits.DEFAULTS);
        return new LicenseStateMachine(token, now, Instant::now);
    }
}
