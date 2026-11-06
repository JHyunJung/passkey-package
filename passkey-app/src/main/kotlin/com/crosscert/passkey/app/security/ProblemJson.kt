package com.crosscert.passkey.app.security

import jakarta.servlet.http.HttpServletResponse

/**
 * Shared RFC7807 problem+json writer for security filters that run
 * OUTSIDE Spring MVC (ApiKeyAuthFilter, RateLimitFilter), where the
 * GlobalExceptionHandler/ApiResponse envelope is unavailable.
 *
 * Output is byte-identical to the previously inline literals:
 * `{"type":"about:blank","status":<status>,"title":"<title>"[,"error":"<error>"]}`.
 * title/error are hardcoded literals at the call sites — never user
 * input — so manual JSON assembly carries no injection risk. Callers
 * own any extra headers (e.g. Retry-After).
 */
internal object ProblemJson {

    fun write(res: HttpServletResponse, status: Int, title: String, error: String? = null) {
        res.status = status
        res.contentType = "application/problem+json"
        val errorMember = if (error != null) ",\"error\":\"$error\"" else ""
        res.writer.write("""{"type":"about:blank","status":$status,"title":"$title"$errorMember}""")
    }
}
