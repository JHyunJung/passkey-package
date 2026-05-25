package com.crosscert.passkey.core.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Phase 0 baseline error mapping. Maps domain {@link IllegalArgumentException}
 * to a 400 ProblemDetail; everything else is left to Spring's default
 * MVC exception resolvers — including framework 4xx exceptions
 * (validation, malformed JSON, ResponseStatusException) which Spring
 * already renders as RFC 7807 ProblemDetail in Boot 3.x.
 *
 * Phase 1+ will add specific handlers for FIDO2 ceremony errors,
 * API-key authentication failures, and rate-limit responses. Keep
 * this class lean until those land.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid request");
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
