package com.crosscert.passkey.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemJsonTest {

    @Test
    void unauthorizedBytesMatchLegacyLiteral() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ProblemJson.write(res, 401, "Unauthorized");
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString())
                .isEqualTo("{\"type\":\"about:blank\",\"status\":401,\"title\":\"Unauthorized\"}");
    }

    @Test
    void forbiddenWithErrorBytesMatchLegacyLiteral() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ProblemJson.write(res, 403, "Forbidden", "insufficient_scope");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString())
                .isEqualTo("{\"type\":\"about:blank\",\"status\":403,\"title\":\"Forbidden\",\"error\":\"insufficient_scope\"}");
    }

    @Test
    void tooManyRequestsBytesMatchLegacyLiteral() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ProblemJson.write(res, 429, "Too Many Requests");
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString())
                .isEqualTo("{\"type\":\"about:blank\",\"status\":429,\"title\":\"Too Many Requests\"}");
    }
}
