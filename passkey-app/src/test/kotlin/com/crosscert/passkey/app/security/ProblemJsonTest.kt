package com.crosscert.passkey.app.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse

class ProblemJsonTest {

    @Test
    fun unauthorizedBytesMatchLegacyLiteral() {
        val res = MockHttpServletResponse()
        ProblemJson.write(res, 401, "Unauthorized")
        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentType).isEqualTo("application/problem+json")
        assertThat(res.contentAsString).isEqualTo("""{"type":"about:blank","status":401,"title":"Unauthorized"}""")
    }

    @Test
    fun forbiddenWithErrorBytesMatchLegacyLiteral() {
        val res = MockHttpServletResponse()
        ProblemJson.write(res, 403, "Forbidden", "insufficient_scope")
        assertThat(res.status).isEqualTo(403)
        assertThat(res.contentType).isEqualTo("application/problem+json")
        assertThat(res.contentAsString)
            .isEqualTo("""{"type":"about:blank","status":403,"title":"Forbidden","error":"insufficient_scope"}""")
    }

    @Test
    fun tooManyRequestsBytesMatchLegacyLiteral() {
        val res = MockHttpServletResponse()
        ProblemJson.write(res, 429, "Too Many Requests")
        assertThat(res.status).isEqualTo(429)
        assertThat(res.contentType).isEqualTo("application/problem+json")
        assertThat(res.contentAsString)
            .isEqualTo("""{"type":"about:blank","status":429,"title":"Too Many Requests"}""")
    }

    @Test
    fun explicitNullErrorEqualsThreeArgOverload() {
        val res = MockHttpServletResponse()
        ProblemJson.write(res, 401, "Unauthorized", null)
        assertThat(res.contentAsString).isEqualTo("""{"type":"about:blank","status":401,"title":"Unauthorized"}""")
    }
}
