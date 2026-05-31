package com.crosscert.passkey.app.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Shared RFC7807 problem+json writer for security filters that run
 * OUTSIDE Spring MVC (ApiKeyAuthFilter, RateLimitFilter), where the
 * GlobalExceptionHandler/ApiResponse envelope is unavailable.
 *
 * <p>Output is byte-identical to the previously inline literals:
 * {@code {"type":"about:blank","status":<status>,"title":"<title>"[,"error":"<error>"]}}.
 * title/error are hardcoded literals at the call sites — never user
 * input — so manual JSON assembly carries no injection risk. Callers
 * own any extra headers (e.g. Retry-After).
 */
final class ProblemJson {

    private ProblemJson() {}

    static void write(HttpServletResponse res, int status, String title) throws IOException {
        write(res, status, title, null);
    }

    static void write(HttpServletResponse res, int status, String title, String error)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        StringBuilder body = new StringBuilder(96)
                .append("{\"type\":\"about:blank\",\"status\":")
                .append(status)
                .append(",\"title\":\"")
                .append(title)
                .append('"');
        if (error != null) {
            body.append(",\"error\":\"").append(error).append('"');
        }
        body.append('}');
        res.getWriter().write(body.toString());
    }
}
