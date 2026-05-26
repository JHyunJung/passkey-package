# Phase 4 — API Response Standardization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize every REST response (admin-app 13 methods + passkey-app WebAuthn 4 methods) to a single `ApiResponse<T>` envelope with a unified `BusinessException` + `ErrorCode` error model. The lone exception is `GET /.well-known/jwks.json`, which must keep the RFC 7517 wire format.

**Architecture:** A new `:core/api` package owns the envelope (`ApiResponse<T>`), the error catalog (`ErrorCode` enum), the marker (`BusinessException`), the `@RestControllerAdvice` (`GlobalExceptionHandler`), and a `TraceIdFilter` that puts `X-Trace-Id` into MDC + response header. Controllers return `ApiResponse.ok(data)` explicitly; exceptions bubble to the handler, which wraps them. `RotationConflictException` is deleted; `KeyRotationService` throws `BusinessException(ErrorCode.KEY_ROTATION_CONFLICT)` directly. admin-ui's `client.ts` gets an unwrap layer so existing page code (`api.get<T>(...).then(setX)`) keeps working — the envelope is stripped before `T` is returned.

**Tech Stack:** Java 17, Spring Boot 3.5.14, Spring Security 6, Jackson (already on classpath), SLF4J/Logback MDC, jakarta.validation, TypeScript + Vite (admin-ui).

---

## File Inventory

**Backend — `:core` (new infrastructure + delete old handler):**
- Create `core/src/main/java/com/crosscert/passkey/core/api/ApiResponse.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/ErrorDetail.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/FieldError.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/ErrorCode.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/BusinessException.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/GlobalExceptionHandler.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/TraceIdFilter.java`
- Delete `core/src/main/java/com/crosscert/passkey/core/config/GlobalExceptionHandler.java`
- Create `core/src/test/java/com/crosscert/passkey/core/api/ApiResponseTest.java`
- Create `core/src/test/java/com/crosscert/passkey/core/api/GlobalExceptionHandlerTest.java`
- Create `core/src/test/java/com/crosscert/passkey/core/api/TraceIdFilterTest.java`

**Backend — `:admin-app` (controller migrations + RotationConflictException removal):**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeView.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`
- Delete `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/RotationConflictException.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsStatusView.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java` (throw BusinessException for not-found / duplicate)
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java` (throw BusinessException for not-found)

**Backend — `:admin-app` test updates:**
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyRotationServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java` (envelope unwrap)
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java` (BusinessException assertion)

**Backend — `:passkey-app` (controller migrations + ceremony exception mapping):**
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/RegistrationController.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/AuthenticationController.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java` (throw BusinessException)
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`
- Modify `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java` (envelope unwrap helper)

**Frontend — `admin-ui`:**
- Modify `admin-ui/src/api/client.ts`
- Modify `admin-ui/src/api/types.ts` (add `ApiEnvelope`, `ApiError`)

**Docs:**
- Create `docs/superpowers/followups/2026-05-26-rp-migration-guide.md`

---

## Task 1: `:core/api` infrastructure — ApiResponse + ErrorDetail + FieldError + ErrorCode + BusinessException + TraceIdFilter

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/api/ApiResponse.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/ErrorDetail.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/FieldError.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/ErrorCode.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/BusinessException.java`
- Create `core/src/main/java/com/crosscert/passkey/core/api/TraceIdFilter.java`
- Create `core/src/test/java/com/crosscert/passkey/core/api/ApiResponseTest.java`
- Create `core/src/test/java/com/crosscert/passkey/core/api/TraceIdFilterTest.java`

This task lands the inert pieces (no behavioral coupling to controllers yet). `GlobalExceptionHandler` lands in Task 2 because it needs to coexist briefly with the old handler.

- [ ] **Step 1: Write `ApiResponse.java`**

```java
package com.crosscert.passkey.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        ErrorDetail error,
        String traceId,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "Success", data, null, currentTraceId(), Instant.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, "OK", message, data, null, currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", "Success", null, null, currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return new ApiResponse<>(false, code.getCode(), code.getMessage(), null,
                new ErrorDetail(code.getCode(), null), currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, String detailMessage) {
        return new ApiResponse<>(false, code.getCode(), detailMessage, null,
                new ErrorDetail(code.getCode(), null), currentTraceId(), Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, List<FieldError> fieldErrors) {
        return new ApiResponse<>(false, code.getCode(), code.getMessage(), null,
                new ErrorDetail(code.getCode(), fieldErrors), currentTraceId(), Instant.now());
    }

    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}
```

- [ ] **Step 2: Write `ErrorDetail.java`**

```java
package com.crosscert.passkey.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(String errorCode, List<FieldError> fieldErrors) {}
```

- [ ] **Step 3: Write `FieldError.java`**

```java
package com.crosscert.passkey.core.api;

public record FieldError(String field, Object rejectedValue, String reason) {}
```

- [ ] **Step 4: Write `ErrorCode.java`**

```java
package com.crosscert.passkey.core.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // Common (C)
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "Invalid input value"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "Method not allowed"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "C003", "Entity not found"),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "C004", "Required parameter missing"),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "C005", "Type mismatch"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "Server error"),

    // Auth (A)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "Authentication required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A002", "Access denied"),

    // Tenant (T)
    TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "T001", "Tenant not found"),
    TENANT_DUPLICATE(HttpStatus.CONFLICT, "T002", "Tenant code already exists"),

    // API Key (K)
    API_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "K001", "API key not found"),

    // Signing Key (S)
    KEY_ROTATION_CONFLICT(HttpStatus.CONFLICT, "S001", "Another rotation in progress"),
    KEY_NO_ACTIVE(HttpStatus.INTERNAL_SERVER_ERROR, "S002", "No ACTIVE signing key"),

    // MDS (M)
    MDS_SYNC_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "M001", "MDS BLOB sync failed"),

    // FIDO2 / WebAuthn (F)
    CHALLENGE_INVALID(HttpStatus.BAD_REQUEST, "F001", "Challenge invalid or expired"),
    REGISTRATION_FAILED(HttpStatus.BAD_REQUEST, "F002", "Registration verification failed"),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "F003", "Authentication verification failed"),
    ATTESTATION_REJECTED(HttpStatus.FORBIDDEN, "F004", "Attestation rejected by policy"),
    AAGUID_REVOKED(HttpStatus.FORBIDDEN, "F005", "Authenticator revoked");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status; this.code = code; this.message = message;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
```

- [ ] **Step 5: Write `BusinessException.java`**

```java
package com.crosscert.passkey.core.api;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
```

- [ ] **Step 6: Write `TraceIdFilter.java`**

```java
package com.crosscert.passkey.core.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = Optional.ofNullable(req.getHeader(TRACE_ID_HEADER))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        MDC.put(MDC_KEY, traceId);
        res.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

- [ ] **Step 7: Write `ApiResponseTest.java`**

```java
package com.crosscert.passkey.core.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void okWithDataPopulatesFieldsAndPicksUpTraceId() {
        MDC.put("traceId", "abc123");
        ApiResponse<String> r = ApiResponse.ok("payload");
        assertThat(r.success()).isTrue();
        assertThat(r.code()).isEqualTo("OK");
        assertThat(r.message()).isEqualTo("Success");
        assertThat(r.data()).isEqualTo("payload");
        assertThat(r.error()).isNull();
        assertThat(r.traceId()).isEqualTo("abc123");
        assertThat(r.timestamp()).isNotNull();
    }

    @Test
    void okVoidLeavesDataNull() {
        ApiResponse<Void> r = ApiResponse.ok();
        assertThat(r.success()).isTrue();
        assertThat(r.data()).isNull();
    }

    @Test
    void errorFromCodeFillsErrorDetail() {
        ApiResponse<Void> r = ApiResponse.error(ErrorCode.TENANT_NOT_FOUND);
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("T001");
        assertThat(r.message()).isEqualTo("Tenant not found");
        assertThat(r.error().errorCode()).isEqualTo("T001");
        assertThat(r.error().fieldErrors()).isNull();
    }

    @Test
    void errorWithDetailOverridesMessageButKeepsCode() {
        ApiResponse<Void> r = ApiResponse.error(ErrorCode.INVALID_INPUT, "custom detail");
        assertThat(r.code()).isEqualTo("C001");
        assertThat(r.message()).isEqualTo("custom detail");
    }

    @Test
    void errorWithFieldErrorsIncludesAllOfThem() {
        FieldError fe = new FieldError("email", "abc", "must be valid");
        ApiResponse<Void> r = ApiResponse.error(ErrorCode.INVALID_INPUT, List.of(fe));
        assertThat(r.error().fieldErrors()).containsExactly(fe);
    }
}
```

- [ ] **Step 8: Write `TraceIdFilterTest.java`**

```java
package com.crosscert.passkey.core.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceIdFilterTest {

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void generatesTraceIdWhenHeaderAbsent() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Trace-Id")).thenReturn(null);

        filter.doFilter(req, res, chain);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(res).setHeader(eq("X-Trace-Id"), cap.capture());
        assertThat(cap.getValue()).hasSize(16);
        verify(chain).doFilter(req, res);
        assertThat(MDC.get("traceId")).isNull();  // cleared after chain
    }

    @Test
    void honorsIncomingTraceIdHeader() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Trace-Id")).thenReturn("incoming-id");

        filter.doFilter(req, res, chain);

        verify(res).setHeader("X-Trace-Id", "incoming-id");
    }

    @Test
    void clearsMdcEvenIfChainThrows() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Trace-Id")).thenReturn("x");
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(chain).doFilter(any(), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(req, res, chain))
                .hasMessage("boom");
        assertThat(MDC.get("traceId")).isNull();
    }

    private static String eq(String s) { return org.mockito.ArgumentMatchers.eq(s); }
}
```

- [ ] **Step 9: Run the tests**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-4-api-response-standard
./gradlew :core:test --tests ApiResponseTest --tests TraceIdFilterTest 2>&1 | tail -20
```

Expected: 5 + 3 = 8 tests pass.

- [ ] **Step 10: Codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/api/ApiResponse.java \
        core/src/main/java/com/crosscert/passkey/core/api/ErrorDetail.java \
        core/src/main/java/com/crosscert/passkey/core/api/FieldError.java \
        core/src/main/java/com/crosscert/passkey/core/api/ErrorCode.java \
        core/src/main/java/com/crosscert/passkey/core/api/BusinessException.java \
        core/src/main/java/com/crosscert/passkey/core/api/TraceIdFilter.java \
        core/src/test/java/com/crosscert/passkey/core/api/ApiResponseTest.java \
        core/src/test/java/com/crosscert/passkey/core/api/TraceIdFilterTest.java
cat > /tmp/codex-p4-t1.txt <<'PROMPT'
Code review for Phase 4 T1 — :core/api infrastructure pieces.

ApiResponse<T> (record) with ok/error static factories. ErrorDetail +
FieldError (records). ErrorCode enum (20 codes across 7 prefixes:
C, A, T, K, S, M, F). BusinessException carries ErrorCode.
TraceIdFilter @Order HIGHEST_PRECEDENCE: reads X-Trace-Id or generates
16-char hex, MDC put + response header, MDC removed in finally.

This task is intentionally inert: no controllers use these yet.
GlobalExceptionHandler lands in T2.

Review:
1. ErrorCode codes unique + HttpStatus mapping sensible.
2. MDC traceId leak risk (clear in finally — verified by test).
3. ApiResponse.ok() vs ok(data) overload ambiguity for null data.
4. JsonInclude NON_NULL semantics with boolean success field
   (success=false is serialized — boolean default is false, but record
   forces explicit constructor pass-through so always emitted).
5. TraceIdFilter @Component auto-registration in both admin-app and
   passkey-app (core.api package scanned by both apps since their
   @SpringBootApplication base packages are com.crosscert.passkey.*).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t1.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t1-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t1-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t1-out.txt 2>&1
cat /tmp/codex-p4-t1-out.txt
```

- [ ] **Step 11: Commit**

```bash
git commit -m "feat(core): ApiResponse + ErrorCode + BusinessException + TraceIdFilter (Phase 4 T1)"
```

---

## Task 2: `GlobalExceptionHandler` — replace old ProblemDetail handler

**Files:**
- Create `core/src/main/java/com/crosscert/passkey/core/api/GlobalExceptionHandler.java`
- Delete `core/src/main/java/com/crosscert/passkey/core/config/GlobalExceptionHandler.java`
- Create `core/src/test/java/com/crosscert/passkey/core/api/GlobalExceptionHandlerTest.java`

After this task lands, every unhandled exception across both apps produces an `ApiResponse<Void>` envelope. Controllers still return their old types — the handler only matters for thrown exceptions and Spring framework errors. Behavior changes for the existing `IllegalArgumentException` path (envelope instead of ProblemDetail) but no Phase 0–3 test asserts on the ProblemDetail body, so the green bar holds.

- [ ] **Step 1: Write the new `GlobalExceptionHandler.java`**

```java
package com.crosscert.passkey.core.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e, HttpServletRequest req) {
        log.warn("[BusinessException] {} {} - code={} msg={}",
                 req.getMethod(), req.getRequestURI(), e.getErrorCode().getCode(), e.getMessage());
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.MISSING_PARAMETER, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.TYPE_MISMATCH, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "Malformed JSON request"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
                .body(ApiResponse.error(ErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException e) {
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.getStatus())
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("[IllegalArgument] {} {} - {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("[Unhandled] {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
```

- [ ] **Step 2: Delete the old handler**

```bash
rm core/src/main/java/com/crosscert/passkey/core/config/GlobalExceptionHandler.java
```

- [ ] **Step 3: Write `GlobalExceptionHandlerTest.java`**

```java
package com.crosscert.passkey.core.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;

    @RestController
    static class TestController {
        @GetMapping("/test/business") String business() {
            throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
        }
        @GetMapping("/test/business-detail") String businessDetail() {
            throw new BusinessException(ErrorCode.KEY_ROTATION_CONFLICT, "lease held by other");
        }
        @GetMapping("/test/illegal") String illegal() {
            throw new IllegalArgumentException("bad value");
        }
        @GetMapping("/test/boom") String boom() {
            throw new RuntimeException("kaboom");
        }
    }

    @Test
    void businessExceptionMapsToErrorCodeStatusAndEnvelope() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("T001"))
                .andExpect(jsonPath("$.message").value("Tenant not found"))
                .andExpect(jsonPath("$.error.errorCode").value("T001"));
    }

    @Test
    void businessExceptionWithDetailKeepsCodeButCustomMessage() throws Exception {
        mvc.perform(get("/test/business-detail"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("S001"))
                .andExpect(jsonPath("$.message").value("lease held by other"));
    }

    @Test
    void illegalArgumentMapsToInvalidInput() throws Exception {
        mvc.perform(get("/test/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value("bad value"));
    }

    @Test
    void unhandledExceptionMapsToInternalServerError() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C999"))
                .andExpect(jsonPath("$.message").value("Server error"));
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :core:test --tests GlobalExceptionHandlerTest 2>&1 | tail -20
```

Expected: 4 tests pass.

- [ ] **Step 5: Run full :core suite to confirm old handler removal didn't break anything**

```bash
./gradlew :core:test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Codex review**

```bash
git add core/src/main/java/com/crosscert/passkey/core/api/GlobalExceptionHandler.java \
        core/src/test/java/com/crosscert/passkey/core/api/GlobalExceptionHandlerTest.java
git add -u core/src/main/java/com/crosscert/passkey/core/config/GlobalExceptionHandler.java
cat > /tmp/codex-p4-t2.txt <<'PROMPT'
Code review for Phase 4 T2 — GlobalExceptionHandler replacement.

Old core/config/GlobalExceptionHandler (ProblemDetail-based, 1 handler
for IllegalArgumentException) deleted. New core/api/GlobalExceptionHandler
adds 10 handlers covering BusinessException, validation, missing param,
type mismatch, malformed JSON, method-not-allowed, access denied,
authentication, illegal argument, and a catch-all.

Response shape: ResponseEntity<ApiResponse<Void>> with ErrorCode-driven
HTTP status. Errors picked up by MDC traceId via ApiResponse.error().

Review:
1. Order of catch — BusinessException must win over IllegalArgumentException
   even though BusinessException extends RuntimeException; Spring's
   @ExceptionHandler dispatcher uses exception-type specificity, so
   BusinessException > Exception (catch-all) by reflection. Verified.
2. AccessDeniedException — must come from org.springframework.security.
   access (NOT java.nio.file). Verified import.
3. AuthenticationException — Spring Security's; thrown by filter chain
   pre-controller. Whether @ExceptionHandler catches it depends on the
   security configuration (most filters write 401 directly). Acceptable:
   the handler is a safety net.
4. Catch-all Exception handler logs at ERROR level with stack trace —
   not a security leak (response body is generic "Server error").
5. Phase 0–3 tests that asserted on the old ProblemDetail shape: none.
   Verified by grep "ProblemDetail" in src/test (returns nothing
   relevant after handler removal).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t2.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t2-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t2-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t2-out.txt 2>&1
cat /tmp/codex-p4-t2-out.txt
```

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(core): GlobalExceptionHandler — ApiResponse envelope across 10 handlers (Phase 4 T2)"
```

---

## Task 3: `TenantAdminController` migration

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java`

- [ ] **Step 1: Update `TenantAdminController.java`**

Open the file and replace the three method bodies with the envelope-wrapping versions. The exact existing methods are:

```java
List<TenantAdminDto.TenantView> list()
TenantAdminDto.TenantView get(@PathVariable String id)
@PreAuthorize("hasRole('ADMIN')")
ResponseEntity<TenantAdminDto.TenantView> create(
    @Valid @RequestBody TenantAdminDto.TenantCreateRequest req,
    Authentication auth)
```

Change them to:

```java
import com.crosscert.passkey.core.api.ApiResponse;

@GetMapping
public ApiResponse<List<TenantAdminDto.TenantView>> list() {
    return ApiResponse.ok(service.list());
}

@GetMapping("/{id}")
public ApiResponse<TenantAdminDto.TenantView> get(@PathVariable String id) {
    return ApiResponse.ok(service.get(id));  // service throws BusinessException(TENANT_NOT_FOUND)
}

@PreAuthorize("hasRole('ADMIN')")
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<TenantAdminDto.TenantView> create(
        @Valid @RequestBody TenantAdminDto.TenantCreateRequest req,
        Authentication auth) {
    return ApiResponse.ok("Tenant created", service.create(req, auth.getName()));
}
```

Remove the `ResponseEntity` import if no longer used. Add `import org.springframework.http.HttpStatus;` and `import org.springframework.web.bind.annotation.ResponseStatus;`.

- [ ] **Step 2: Update `TenantAdminService.java`**

Find the `get(String id)` method. It currently likely returns Optional or throws an unspecified exception. Make it throw `BusinessException(ErrorCode.TENANT_NOT_FOUND)`:

```java
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;

public TenantAdminDto.TenantView get(String id) {
    Tenant t = repo.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
    return TenantAdminDto.TenantView.from(t);
}
```

Find `create()`. If it currently throws a generic exception or DataIntegrityViolationException on duplicate code, catch and translate:

```java
public TenantAdminDto.TenantView create(TenantAdminDto.TenantCreateRequest req, String actorEmail) {
    if (repo.existsByCode(req.code())) {
        throw new BusinessException(ErrorCode.TENANT_DUPLICATE);
    }
    // ... rest of existing create logic
}
```

(If `existsByCode` doesn't exist on the repo, add it as a derived query.)

- [ ] **Step 3: Update `TenantAdminControllerSecurityTest.java`**

The existing security tests only assert on HTTP status. Add jsonPath envelope assertions to each test method:

```java
@Test
@WithMockUser(roles = "VIEWER")
void viewerCanList() throws Exception {
    when(service.list()).thenReturn(List.of());
    mvc.perform(get("/admin/api/tenants"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data").isArray());
}

@Test
void anonymousGetIsUnauthorized() throws Exception {
    mvc.perform(get("/admin/api/tenants"))
            .andExpect(status().isUnauthorized());
    // body shape depends on security filter chain — don't assert on it
}

@Test
@WithMockUser(roles = "VIEWER")
void viewerCannotPost() throws Exception {
    mvc.perform(post("/admin/api/tenants").with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"acme\",\"name\":\"Acme\"}"))
            .andExpect(status().isForbidden());
    // 403 from @PreAuthorize → AccessDeniedException → handler wraps as ApiResponse
}

@Test
@WithMockUser(username = "alice@example.com", roles = "ADMIN")
void adminCanPost() throws Exception {
    var view = new TenantAdminDto.TenantView(...);  // fill with existing test fixture
    when(service.create(any(), eq("alice@example.com"))).thenReturn(view);
    mvc.perform(post("/admin/api/tenants").with(csrf())
                .contentType("application/json")
                .content("{\"code\":\"acme\",\"name\":\"Acme\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Tenant created"))
            .andExpect(jsonPath("$.data.code").value("acme"));
}
```

Add one new test for the not-found path:

```java
@Test
@WithMockUser(roles = "VIEWER")
void getReturnsApiErrorWhenTenantNotFound() throws Exception {
    when(service.get("missing")).thenThrow(
            new com.crosscert.passkey.core.api.BusinessException(
                com.crosscert.passkey.core.api.ErrorCode.TENANT_NOT_FOUND));
    mvc.perform(get("/admin/api/tenants/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("T001"))
            .andExpect(jsonPath("$.error.errorCode").value("T001"));
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :admin-app:test --tests TenantAdminControllerSecurityTest 2>&1 | tail -20
```

Expected: existing tests still pass + 1 new test passes.

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java
cat > /tmp/codex-p4-t3.txt <<'PROMPT'
Code review for Phase 4 T3 — TenantAdminController envelope migration.

GET /admin/api/tenants → ApiResponse<List<TenantView>>
GET /admin/api/tenants/{id} → ApiResponse<TenantView>; not-found via BusinessException
POST /admin/api/tenants → @ResponseStatus(CREATED) + ApiResponse<TenantView>("Tenant created", ...)

Service-layer changes:
- get(id) throws BusinessException(TENANT_NOT_FOUND) on miss
- create(req) throws BusinessException(TENANT_DUPLICATE) on existsByCode

Tests assert envelope shape: success/code/message/data fields,
plus the not-found error path returns T001.

Review:
1. @PreAuthorize on POST preserved — 403 still flows through
   AccessDeniedException → handler.
2. 201 Created semantics — @ResponseStatus on the controller method
   sets the HTTP status; ApiResponse.ok("Tenant created", ...) wraps
   the body. Two layers consistent.
3. existsByCode race condition — between exists check and INSERT, another
   tenant could be created. Acceptable for admin operation (low rate);
   DB unique constraint is the safety net.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t3.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t3-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t3-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t3-out.txt 2>&1
cat /tmp/codex-p4-t3-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): TenantAdminController → ApiResponse envelope + BusinessException (Phase 4 T3)"
```

---

## Task 4: `MeController` migration

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/config/MeView.java`

There is currently no `MeControllerTest`. Add a minimal smoke test inline.

- [ ] **Step 1: Create `MeView.java`**

```java
package com.crosscert.passkey.admin.config;

public record MeView(String email, String role) {}
```

The field names match the existing `Map<String, Object>` keys so the JSON shape (inside `data`) does not change for admin-ui's `Me` interface.

- [ ] **Step 2: Update `MeController.java`**

Replace the entire method:

```java
package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api")
public class MeController {

    @GetMapping("/me")
    public ApiResponse<MeView> me(Authentication auth) {
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElse("UNKNOWN");
        return ApiResponse.ok(new MeView(auth.getName(), role));
    }
}
```

(Preserve the existing role-extraction logic — copy from the file before deleting.)

- [ ] **Step 3: Smoke run**

```bash
./gradlew :admin-app:compileJava :admin-app:compileTestJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. (No dedicated test for /me; covered by AdminFlowIT later.)

- [ ] **Step 4: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/config/MeController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/MeView.java
cat > /tmp/codex-p4-t4.txt <<'PROMPT'
Code review for Phase 4 T4 — MeController envelope migration.

Map<String,Object> → record MeView(email, role) → ApiResponse<MeView>.
Field names preserved so admin-ui Me interface stays untouched
(SPA gets {data:{email,role}} which is identical to old top-level shape
once unwrap layer strips envelope in T11).

Review:
1. Role extraction logic preserved verbatim.
2. SPA contract: was {email, role} top-level, now {data:{email, role}}.
   T11 unwrap layer flattens this. Until T11 lands, admin-ui /me would
   misbehave — order matters but each task ends with compileJava OK.
3. AdminFlowIT covers /me indirectly (login step).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t4.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t4-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t4-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t4-out.txt 2>&1
cat /tmp/codex-p4-t4-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin): MeController → ApiResponse<MeView> (Phase 4 T4)"
```

---

## Task 5: `ApiKeyAdminController` migration

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java`

- [ ] **Step 1: Update `ApiKeyAdminController.java`**

Replace the three methods:

```java
import com.crosscert.passkey.core.api.ApiResponse;

@GetMapping
public ApiResponse<List<ApiKeyAdminDto.ApiKeyView>> list(
        @RequestParam(required = false) String tenantId) {
    return ApiResponse.ok(service.list(tenantId));
}

@PreAuthorize("hasRole('ADMIN')")
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<ApiKeyAdminDto.ApiKeyCreateResponse> issue(
        @Valid @RequestBody ApiKeyAdminDto.ApiKeyCreateRequest req,
        Authentication auth) {
    return ApiResponse.ok("API key issued", service.issue(req, auth.getName()));
}

@PreAuthorize("hasRole('ADMIN')")
@DeleteMapping("/{id}")
public ApiResponse<Void> revoke(@PathVariable Long id, Authentication auth) {
    service.revoke(id, auth.getName());
    return ApiResponse.ok();
}
```

Drop `ResponseEntity` import. Add `HttpStatus`, `ResponseStatus` imports.

- [ ] **Step 2: Update `ApiKeyAdminService.java`**

Find `revoke(Long id, ...)`. Make it throw `BusinessException(ErrorCode.API_KEY_NOT_FOUND)` if the row doesn't exist:

```java
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;

@Transactional
public void revoke(Long id, String actorEmail) {
    ApiKey k = repo.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));
    // ... existing revoke logic on k
}
```

- [ ] **Step 3: Update `ApiKeyAdminControllerSecurityTest.java`**

Add envelope jsonPath assertions to each test. For the new not-found case:

```java
@Test
@WithMockUser(username = "alice@example.com", roles = "ADMIN")
void revokeReturnsApiErrorWhenNotFound() throws Exception {
    doThrow(new com.crosscert.passkey.core.api.BusinessException(
              com.crosscert.passkey.core.api.ErrorCode.API_KEY_NOT_FOUND))
        .when(service).revoke(eq(999L), anyString());
    mvc.perform(delete("/admin/api/api-keys/999").with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("K001"));
}
```

Update existing `adminCanIssue` to assert:

```java
.andExpect(status().isCreated())
.andExpect(jsonPath("$.success").value(true))
.andExpect(jsonPath("$.data.token").exists());
```

And `viewerCanList`:

```java
.andExpect(status().isOk())
.andExpect(jsonPath("$.success").value(true))
.andExpect(jsonPath("$.data").isArray());
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :admin-app:test --tests ApiKeyAdminControllerSecurityTest 2>&1 | tail -20
```

Expected: 5 (existing) + 1 (new not-found) = 6 tests pass.

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java
cat > /tmp/codex-p4-t5.txt <<'PROMPT'
Code review for Phase 4 T5 — ApiKeyAdminController envelope migration.

GET /admin/api/api-keys → ApiResponse<List<ApiKeyView>>
POST / → @ResponseStatus(CREATED) + ApiResponse<ApiKeyCreateResponse>
DELETE /{id} → 204→200 unified; ApiResponse<Void>; not-found via BusinessException(K001)

Tests: 5 existing get jsonPath envelope assertions; 1 new test for K001.

Review:
1. Status change 204 → 200 for DELETE — admin-ui only consumer. SPA's
   api.delete<void> path handles 200 with body. No external API client.
2. POST 201 + ApiResponse.ok("API key issued", ...) — message present in
   envelope, status from @ResponseStatus.
3. revoke() service throws BusinessException — must be inside @Transactional
   so the not-found check participates in the same TX as audit append
   (preserves existing audit behavior).

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t5.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t5-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t5-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t5-out.txt 2>&1
cat /tmp/codex-p4-t5-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): ApiKeyAdminController → ApiResponse envelope + K001 not-found (Phase 4 T5)"
```

---

## Task 6: `AuditLogController` migration

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java`

- [ ] **Step 1: Update `AuditLogController.java`**

Replace both methods:

```java
import com.crosscert.passkey.core.api.ApiResponse;

@GetMapping
public ApiResponse<List<AuditLogView>> list(
        @RequestParam(required = false) String action,
        @RequestParam(required = false) Long actorId,
        @RequestParam(required = false) String resourceType,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
    return ApiResponse.ok(service.list(action, actorId, resourceType, page, size));
}

@PreAuthorize("hasRole('ADMIN')")
@GetMapping("/verify")
public ApiResponse<AuditChainVerifier.Result> verify() {
    return ApiResponse.ok(service.verify());
}
```

- [ ] **Step 2: Update `AuditLogControllerSecurityTest.java`**

Add envelope assertions to each existing test:

```java
.andExpect(jsonPath("$.success").value(true))
.andExpect(jsonPath("$.data").isArray())  // list
.andExpect(jsonPath("$.data.valid").exists())  // verify
```

(Adapt jsonPath to the actual AuditChainVerifier.Result record field names.)

- [ ] **Step 3: Run tests**

```bash
./gradlew :admin-app:test --tests AuditLogControllerSecurityTest 2>&1 | tail -20
```

Expected: 4 tests pass.

- [ ] **Step 4: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java
cat > /tmp/codex-p4-t6.txt <<'PROMPT'
Code review for Phase 4 T6 — AuditLogController envelope migration.

GET /admin/api/audit → ApiResponse<List<AuditLogView>>
GET /admin/api/audit/verify → ApiResponse<AuditChainVerifier.Result>

No domain exceptions added — verify always returns a Result (may have
valid=false). Audit list with no rows returns empty array, not error.

Review:
1. Pagination params unchanged.
2. Audit chain "broken" state → Result with valid=false → still 200 +
   envelope success=true (the verification call succeeded; the chain
   being broken is the data we're returning, not a server error).
3. ADMIN-only on /verify preserved.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t6.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t6-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t6-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t6-out.txt 2>&1
cat /tmp/codex-p4-t6-out.txt
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(admin): AuditLogController → ApiResponse envelope (Phase 4 T6)"
```

---

## Task 7: `MdsAdminController` migration + `MdsStatusView` record

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java`
- Create `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsStatusView.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java`

- [ ] **Step 1: Create `MdsStatusView.java`**

```java
package com.crosscert.passkey.admin.mds;

public record MdsStatusView(int version, String nextUpdate, String fetchedAt) {}
```

Field names match the existing Map keys + admin-ui's `MdsStatusView` interface.

- [ ] **Step 2: Update `MdsAdminController.java`**

Replace both methods. Locate where the status Map is assembled (likely from `MdsBlobStore.getStatus()` or similar) and convert to the record:

```java
import com.crosscert.passkey.core.api.ApiResponse;

@GetMapping("/status")
public ApiResponse<MdsStatusView> status() {
    var s = store.getStatus();
    return ApiResponse.ok(new MdsStatusView(
            s.version(),
            s.nextUpdate() == null ? null : s.nextUpdate().toString(),
            s.fetchedAt() == null ? null : s.fetchedAt().toString()));
}

@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/sync")
public ApiResponse<MdsSchedulerService.SyncResult> sync() {
    return ApiResponse.ok(scheduler.runOnce());
}
```

(If `store.getStatus()` returns a different shape, adapt the mapping. Read MdsBlobStore.java first.)

- [ ] **Step 3: Update `MdsAdminControllerSecurityTest.java`**

Add envelope assertions:

```java
.andExpect(jsonPath("$.success").value(true))
.andExpect(jsonPath("$.data.version").exists())  // status
.andExpect(jsonPath("$.data.status").exists())   // sync result
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :admin-app:test --tests MdsAdminControllerSecurityTest 2>&1 | tail -20
```

Expected: 4 tests pass.

- [ ] **Step 5: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsStatusView.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java
cat > /tmp/codex-p4-t7.txt <<'PROMPT'
Code review for Phase 4 T7 — MdsAdminController envelope migration.

Map<String,Object> → record MdsStatusView(version, nextUpdate, fetchedAt)
inside ApiResponse<MdsStatusView>. SyncResult wrapped directly.

SyncResult.status==FAILED is NOT a server error — sync call succeeded,
result indicates upstream MDS failure. Stays 200 + envelope success=true.
RP branches on data.status.

Review:
1. nextUpdate/fetchedAt nullable handling — toString on null guarded.
2. SyncResult shape preserved (.status / .version / .error consumed by
   admin-ui Login.tsx-style code in MdsStatus.tsx).
3. ADMIN-only on /sync preserved.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t7.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t7-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t7-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t7-out.txt 2>&1
cat /tmp/codex-p4-t7-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin): MdsAdminController → ApiResponse<MdsStatusView> + envelope (Phase 4 T7)"
```

---

## Task 8: `KeyMgmtController` migration + delete `RotationConflictException`

**Files:**
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java`
- Modify `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`
- Delete `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/RotationConflictException.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyRotationServiceTest.java`
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java`

- [ ] **Step 1: Update `KeyRotationService.java`**

Find the line `throw new RotationConflictException("another rotation in progress")` and replace:

```java
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;

// ...inside rotate():
if (!leases.tryAcquire(LEASE_NAME, holder, LEASE_TTL)) {
    throw new BusinessException(ErrorCode.KEY_ROTATION_CONFLICT);
}
```

Find the `orElseThrow` for ACTIVE key and replace:

```java
SigningKey old = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
        .orElseThrow(() -> new BusinessException(ErrorCode.KEY_NO_ACTIVE));
```

Remove the `import com.crosscert.passkey.admin.keymgmt.RotationConflictException;` line.

- [ ] **Step 2: Update `KeyMgmtController.java`**

Replace the entire `rotate` method:

```java
import com.crosscert.passkey.core.api.ApiResponse;

@GetMapping
public ApiResponse<KeyMgmtDto.KeyList> list() {
    var keys = repo.findAll().stream()
            .map(KeyMgmtDto.SigningKeyView::from)
            .toList();
    return ApiResponse.ok(new KeyMgmtDto.KeyList(keys));
}

@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/rotate")
public ApiResponse<KeyMgmtDto.RotateResponse> rotate(Authentication auth) {
    long actorId = admins.findByEmail(auth.getName()).orElseThrow().getId();
    var result = rotation.rotate(actorId, auth.getName());
    return ApiResponse.ok(new KeyMgmtDto.RotateResponse(result.oldKid(), result.newKid()));
}
```

Remove the try/catch around `rotation.rotate(...)`. Remove the `import org.springframework.http.HttpStatus;`, `import org.springframework.web.server.ResponseStatusException;`, and `import com.crosscert.passkey.admin.keymgmt.RotationConflictException;` lines.

- [ ] **Step 3: Delete `RotationConflictException.java`**

```bash
rm admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/RotationConflictException.java
```

- [ ] **Step 4: Update `KeyRotationServiceTest.java`**

Find tests that catch `RotationConflictException` and switch to `BusinessException`:

```java
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;

@Test
void rotateThrowsBusinessExceptionWhenLeaseUnavailable() {
    when(leases.tryAcquire(anyString(), anyString(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.rotate(0L, "(test)"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(ErrorCode.KEY_ROTATION_CONFLICT);
}
```

- [ ] **Step 5: Update `KeyMgmtControllerSecurityTest.java`**

Find the `rotateConflictWhenLeaseUnavailable` test. Replace the `thenThrow(new RotationConflictException(...))` stub with the new `BusinessException`:

```java
@Test
@WithMockUser(username = "alice@example.com", roles = "ADMIN")
void rotateConflictWhenLeaseUnavailable() throws Exception {
    when(admins.findByEmail(anyString())).thenReturn(
            Optional.of(adminUserWithId(7L)));
    when(rotation.rotate(anyLong(), anyString()))
            .thenThrow(new com.crosscert.passkey.core.api.BusinessException(
                    com.crosscert.passkey.core.api.ErrorCode.KEY_ROTATION_CONFLICT));
    mvc.perform(post("/admin/api/keys/rotate").with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("S001"))
            .andExpect(jsonPath("$.error.errorCode").value("S001"));
}
```

Remove the `import com.crosscert.passkey.admin.keymgmt.RotationConflictException;` line.

Update the other tests' success assertions:

```java
.andExpect(jsonPath("$.success").value(true))
.andExpect(jsonPath("$.data.oldKid").value("old"))
.andExpect(jsonPath("$.data.newKid").value("new"))
```

- [ ] **Step 6: Update `KeyRotationIT.java`**

Find scenario 3 (`rotateRejectsConcurrentWithConflict`). Replace the try/catch:

```java
@Test
void rotateRejectsConcurrentWithConflict() {
    jdbc.update("INSERT INTO APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                "VALUES ('key-rotation', 'somebody-else', SYSTIMESTAMP + INTERVAL '5' MINUTE)");

    assertThatThrownBy(() -> rotation.rotate(0L, "(test)"))
            .isInstanceOf(com.crosscert.passkey.core.api.BusinessException.class)
            .extracting(e -> ((com.crosscert.passkey.core.api.BusinessException) e).getErrorCode())
            .isEqualTo(com.crosscert.passkey.core.api.ErrorCode.KEY_ROTATION_CONFLICT);
}
```

Remove the old `try { ... } catch (IllegalStateException e) { ... }` block.

- [ ] **Step 7: Run all affected tests**

```bash
./gradlew :admin-app:test \
    --tests KeyRotationServiceTest \
    --tests KeyMgmtControllerSecurityTest \
    --tests KeyRotationIT 2>&1 | tail -25
```

Expected: 3 + 5 + 3 = 11 tests pass.

- [ ] **Step 8: Codex review**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyRotationServiceTest.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/KeyRotationIT.java
git add -u admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/RotationConflictException.java
cat > /tmp/codex-p4-t8.txt <<'PROMPT'
Code review for Phase 4 T8 — KeyMgmtController + RotationConflictException removal.

RotationConflictException class deleted. KeyRotationService throws
BusinessException(KEY_ROTATION_CONFLICT) directly. KeyMgmtController.rotate
removes try/catch entirely; GlobalExceptionHandler maps to 409.
ACTIVE-not-found also moved to BusinessException(KEY_NO_ACTIVE) → 500.

Tests updated: ServiceTest catches BusinessException; ControllerSecurityTest
stubs throw BusinessException; KeyRotationIT scenario 3 asserts on
BusinessException.

Review:
1. Phase 3 invariant preserved: 409 on concurrent rotation. Wire shape
   now {success:false, code:"S001", error:{errorCode:"S001"}}.
2. KEY_NO_ACTIVE was a 500 before too (via the old orElseThrow IllegalStateException
   path falling through to handler). Status preserved.
3. No leftover RotationConflictException references — grep verifies.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t8.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t8-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t8-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t8-out.txt 2>&1
cat /tmp/codex-p4-t8-out.txt
```

- [ ] **Step 9: Commit**

```bash
git commit -m "refactor(admin): KeyMgmtController → ApiResponse; delete RotationConflictException (Phase 4 T8)"
```

---

## Task 9: passkey-app — `RegistrationController` + `AuthenticationController` migration

**Files:**
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/RegistrationController.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/AuthenticationController.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java`
- Modify `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`
- Modify `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`

- [ ] **Step 1: Update `RegistrationController.java`**

```java
import com.crosscert.passkey.core.api.ApiResponse;

@PostMapping("/start")
public ApiResponse<RegistrationStartResponse> start(
        @Valid @RequestBody RegistrationStartRequest req) {
    return ApiResponse.ok(service.start(req));
}

@PostMapping("/finish")
public ApiResponse<RegistrationFinishResponse> finish(
        @Valid @RequestBody RegistrationFinishRequest req) {
    return ApiResponse.ok(service.finish(req));
}
```

- [ ] **Step 2: Update `AuthenticationController.java`** (same pattern)

```java
import com.crosscert.passkey.core.api.ApiResponse;

@PostMapping("/start")
public ApiResponse<AuthenticationStartResponse> start(
        @Valid @RequestBody AuthenticationStartRequest req) {
    return ApiResponse.ok(service.start(req));
}

@PostMapping("/finish")
public ApiResponse<AuthenticationFinishResponse> finish(
        @Valid @RequestBody AuthenticationFinishRequest req) {
    return ApiResponse.ok(service.finish(req));
}
```

- [ ] **Step 3: Update the four service classes — translate domain exceptions to BusinessException**

For each of `RegistrationStartService`, `RegistrationFinishService`, `AuthenticationStartService`, `AuthenticationFinishService`:

Read the file. Find any places that currently throw:
- `IllegalStateException("challenge ...")` or similar → `BusinessException(ErrorCode.CHALLENGE_INVALID)`
- attestation policy rejection → `BusinessException(ErrorCode.ATTESTATION_REJECTED)`
- AAGUID block (MdsVerifier rejecting) → `BusinessException(ErrorCode.AAGUID_REVOKED)`
- registration verification failure (webauthn4j ValidationException) → `BusinessException(ErrorCode.REGISTRATION_FAILED)`
- authentication verification failure → `BusinessException(ErrorCode.AUTHENTICATION_FAILED)`

Example pattern (RegistrationFinishService):

```java
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;

// challenge lookup
ChallengeStore.Entry entry = challenges.consume(req.challengeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_INVALID));

// MDS verifier reject
if (!mdsVerifier.isAllowed(aaguid)) {
    throw new BusinessException(ErrorCode.AAGUID_REVOKED);
}

// webauthn4j verify
try {
    manager.verify(registrationRequest, registrationParameters);
} catch (com.webauthn4j.verifier.exception.VerificationException e) {
    throw new BusinessException(ErrorCode.REGISTRATION_FAILED, e.getMessage());
}
```

Wrap webauthn4j ValidationException (or whatever its real name is — check the existing service) in BusinessException to avoid the generic Exception catch-all.

If the existing service throws nothing (just returns the response from a service method), no change needed.

- [ ] **Step 4: Update `Fido2EndToEndIT.java`**

The IT currently parses the response body directly. After wrap, it must unwrap. Add a helper at the top of the test class:

```java
private static <T> T unwrap(MvcResult result, Class<T> dataType) throws Exception {
    String body = result.getResponse().getContentAsString();
    JsonNode tree = mapper.readTree(body);
    if (!tree.path("success").asBoolean()) {
        throw new AssertionError("API call failed: " + body);
    }
    return mapper.treeToValue(tree.path("data"), dataType);
}
```

Then wherever the test currently does:

```java
RegistrationStartResponse start = mapper.readValue(body, RegistrationStartResponse.class);
```

Replace with:

```java
RegistrationStartResponse start = unwrap(result, RegistrationStartResponse.class);
```

(Apply to all 4 ceremony endpoints used across the 8 scenarios.)

- [ ] **Step 5: Run all affected tests**

```bash
./gradlew :passkey-app:test 2>&1 | tail -30
```

Expected: All passkey-app tests pass (Fido2EndToEndIT 8/8, JwksControllerTest unchanged, MdsVerifierTest unchanged, etc.).

- [ ] **Step 6: Verify JwksController unchanged**

```bash
diff <(git show HEAD:passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java) \
     passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java
```

Expected: no diff (or only doc comment additions if you added one).

Optionally add a class-level javadoc to JwksController:

```java
/**
 * Exposes the public JWKS (RFC 7517) for RPs to verify ID Tokens issued
 * by this passkey-app. Intentionally NOT wrapped in ApiResponse —
 * RFC 7517 + OIDC Discovery wire format is mandatory; any envelope
 * breaks standard JWT libraries (Nimbus, jose4j, jsonwebtoken, jose).
 */
@RestController
public class JwksController { ... }
```

- [ ] **Step 7: Codex review**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/RegistrationController.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/AuthenticationController.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/api/v1/rp/JwksController.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java \
        passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java
cat > /tmp/codex-p4-t9.txt <<'PROMPT'
Code review for Phase 4 T9 — passkey-app WebAuthn envelope migration.

RegistrationController + AuthenticationController: 4 methods now return
ApiResponse<T>. JwksController EXPLICITLY unchanged (RFC 7517 mandate)
and gets a doc comment explaining the exception.

Service-layer: domain exceptions translated to BusinessException with
F-prefix ErrorCode (F001 challenge invalid, F002 registration failed,
F003 authentication failed, F004 attestation rejected, F005 AAGUID
revoked).

Fido2EndToEndIT: helper unwrap(result, Class) handles envelope.

RP-FACING BREAKING CHANGE: RP-side JS clients now must read
.data.<webauthn-field> instead of top-level. T12 documents this.

Review:
1. JwksController unchanged — verified by diff (or only javadoc added).
2. webauthn4j ValidationException catch — must catch the actual class
   name from webauthn4j 0.31.5 (com.webauthn4j.verifier.exception.VerificationException
   or com.webauthn4j.validator.exception.ValidationException — verify).
3. Challenge consume "not found" — must throw before any state mutation
   so the wrong CHALLENGE_INVALID doesn't mask a different bug.
4. Fido2EndToEndIT unwrap helper — also use for error paths to assert
   $.code value when negative scenarios exist.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t9.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t9-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t9-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t9-out.txt 2>&1
cat /tmp/codex-p4-t9-out.txt
```

- [ ] **Step 8: Commit**

```bash
git commit -m "feat(passkey): WebAuthn controllers → ApiResponse envelope; JWKS unchanged (Phase 4 T9)"
```

---

## Task 10: `AdminFlowIT` envelope unwrap

**Files:**
- Modify `admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java`

AdminFlowIT chains 11 admin REST calls. After tasks 3–8, all return envelopes. The IT must unwrap each response.

- [ ] **Step 1: Add an unwrap helper in `AdminFlowIT.java`**

```java
private static <T> T unwrap(MockHttpServletResponse res, com.fasterxml.jackson.core.type.TypeReference<T> typeRef)
        throws Exception {
    String body = res.getContentAsString();
    JsonNode tree = mapper.readTree(body);
    if (!tree.path("success").asBoolean()) {
        throw new AssertionError("API call failed (code=" + tree.path("code").asText() +
                                  ", message=" + tree.path("message").asText() + "): " + body);
    }
    return mapper.convertValue(tree.path("data"), typeRef);
}
```

Use it at every step:

```java
// Step: list tenants
MockHttpServletResponse res = mvc.perform(get("/admin/api/tenants"))
        .andExpect(status().isOk()).andReturn().getResponse();
List<TenantView> tenants = unwrap(res, new TypeReference<>() {});
```

Apply to all 11 steps. For void operations:

```java
mvc.perform(delete("/admin/api/api-keys/" + id).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
```

- [ ] **Step 2: Run AdminFlowIT**

```bash
./gradlew :admin-app:test --tests AdminFlowIT 2>&1 | tail -15
```

Expected: 1/1 pass (the 11-step e2e scenario).

- [ ] **Step 3: Codex review**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/AdminFlowIT.java
cat > /tmp/codex-p4-t10.txt <<'PROMPT'
Code review for Phase 4 T10 — AdminFlowIT envelope unwrap.

11-step admin flow. Each REST step parses the envelope via unwrap helper
that fails fast on success=false. DELETE step asserts $.success directly
without unwrapping (void payload).

Review:
1. unwrap helper failure includes server code + message — easier debug.
2. TypeReference usage — handles generics like List<TenantView>.
3. Status assertions preserved (200 / 201 / etc.) so wire-level contract
   verified alongside body contract.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t10.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t10-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t10-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t10-out.txt 2>&1
cat /tmp/codex-p4-t10-out.txt
```

- [ ] **Step 4: Commit**

```bash
git commit -m "test(admin): AdminFlowIT envelope unwrap helper (Phase 4 T10)"
```

---

## Task 11: admin-ui `client.ts` unwrap layer + `types.ts` extension

**Files:**
- Modify `admin-ui/src/api/client.ts`
- Modify `admin-ui/src/api/types.ts`

- [ ] **Step 1: Append types to `admin-ui/src/api/types.ts`**

Append at the end of the file:

```typescript
export interface ApiEnvelope<T> {
  success: boolean;
  code: string;
  message: string;
  data?: T;
  error?: {
    errorCode: string;
    fieldErrors?: Array<{ field: string; rejectedValue: unknown; reason: string }>;
  };
  traceId?: string;
  timestamp?: string;
}

export class ApiError extends Error {
  constructor(
    public readonly httpStatus: number,
    public readonly code: string,
    public readonly serverMessage: string,
    public readonly fieldErrors?: Array<{ field: string; rejectedValue: unknown; reason: string }>,
    public readonly traceId?: string
  ) {
    super(`[${code}] ${serverMessage}`);
    this.name = 'ApiError';
  }
}
```

- [ ] **Step 2: Rewrite `admin-ui/src/api/client.ts`**

```typescript
import type { ApiEnvelope } from './types';
import { ApiError } from './types';

function getCookie(name: string): string | undefined {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : undefined;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && method !== 'HEAD') {
    const csrf = getCookie('XSRF-TOKEN');
    if (csrf) headers['X-XSRF-TOKEN'] = csrf;
  }
  const res = await fetch(path, {
    method, headers, credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) {
    window.location.href = '/admin/login';
    throw new ApiError(401, 'A001', 'Authentication required');
  }
  let envelope: ApiEnvelope<T>;
  try {
    envelope = await res.json();
  } catch {
    throw new ApiError(res.status, 'C999', `Non-JSON response (status ${res.status})`);
  }
  if (!envelope.success) {
    throw new ApiError(
      res.status,
      envelope.code ?? 'C999',
      envelope.message ?? 'Unknown error',
      envelope.error?.fieldErrors,
      envelope.traceId
    );
  }
  return envelope.data as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
  loginForm: async (email: string, password: string) => {
    // preserve existing form-login behavior — login endpoint may not be
    // envelope-shaped if Spring Security handles it directly. Adapt
    // based on existing code.
    const params = new URLSearchParams({ email, password });
    const res = await fetch('/admin/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString(),
    });
    if (!res.ok) throw new Error('login failed');
  },
};
```

(Preserve `getCookie` and the `loginForm` exactly as in the original file — copy from the pre-edit version.)

- [ ] **Step 3: Build admin-ui**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-4-api-response-standard/admin-ui
npm run build
cd ..
```

Expected: build succeeds, no TypeScript errors.

- [ ] **Step 4: Build admin-app bootJar (verifies SPA is bundled)**

```bash
./gradlew :admin-app:bootJar 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Codex review**

```bash
git add admin-ui/src/api/client.ts admin-ui/src/api/types.ts
cat > /tmp/codex-p4-t11.txt <<'PROMPT'
Code review for Phase 4 T11 — admin-ui client.ts envelope unwrap.

client.ts auto-unwraps server's ApiResponse<T> so every existing page
(api.get<TenantView[]>(...).then(setX)) keeps working without
modification.

Error path: throws ApiError class carrying httpStatus, code,
serverMessage, optional fieldErrors, optional traceId. Existing
try/catch in pages now receives ApiError instances (e.g. KeyManagement
rotate button → catch block shows "[S001] Another rotation in progress").

types.ts adds ApiEnvelope<T> + ApiError class.

Review:
1. 401 short-circuit — preserved (redirects to /admin/login).
2. Non-JSON response (e.g., 502 from upstream proxy) — caught into
   ApiError with synthetic C999 code.
3. envelope.data may be undefined for ok-void responses (DELETE /api-keys/{id}).
   Cast `as T` covers; consumers using `Promise<void>` work fine.
4. loginForm preserved unchanged — Spring Security form-login flow
   may not envelope-shape responses (200 redirect, not JSON body).
5. ApiError class extends Error — instanceof works for catch differentiation.

Output P1/P2/Confirmations.
PROMPT
{ cat /tmp/codex-p4-t11.txt; echo; echo "DIFF_START"; git diff --cached; echo "DIFF_END"; } > /tmp/codex-p4-t11-full.txt
codex exec -s read-only "$(cat /tmp/codex-p4-t11-full.txt)" -c 'model_reasoning_effort="high"' < /dev/null > /tmp/codex-p4-t11-out.txt 2>&1
cat /tmp/codex-p4-t11-out.txt
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(admin-ui): client.ts auto-unwrap ApiResponse + ApiError class (Phase 4 T11)"
```

---

## Task 12: RP migration guide doc

**Files:**
- Create `docs/superpowers/followups/2026-05-26-rp-migration-guide.md`

- [ ] **Step 1: Write the migration guide**

```markdown
# RP Migration Guide — Phase 4 API Response Standardization

**Date:** 2026-05-26
**Affects:** RP-side JavaScript clients calling `/api/v1/rp/registration/**` and `/api/v1/rp/authentication/**` on passkey-app.

## What changed

passkey-app's WebAuthn ceremony endpoints (4 methods) now return a uniform `ApiResponse<T>` envelope instead of raw `PublicKeyCredentialCreationOptions` / `PublicKeyCredentialRequestOptions` / verification result shapes.

**Unchanged (no migration needed):**
- `GET /.well-known/jwks.json` — still RFC 7517 wire format. All JWT verifier libraries (Nimbus, jose4j, jsonwebtoken, jose) continue to work without changes.

## Before / After

### Registration start

**Before:**
```javascript
const options = await fetch('/api/v1/rp/registration/start', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({ /* ... */ })
}).then(r => r.json());
const credential = await startRegistration(options); // simplewebauthn
```

**After:**
```javascript
const response = await fetch('/api/v1/rp/registration/start', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({ /* ... */ })
}).then(r => r.json());
if (!response.success) {
  throw new Error(`[${response.code}] ${response.message}`);
}
const credential = await startRegistration(response.data); // ← .data
```

### Registration finish, Authentication start, Authentication finish

Same pattern: `.data` is the previous top-level body.

## Envelope shape

```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": { /* ...the actual WebAuthn payload... */ },
  "traceId": "abc1234567890def",
  "timestamp": "2026-05-26T10:00:00Z"
}
```

On error:

```json
{
  "success": false,
  "code": "F001",
  "message": "Challenge invalid or expired",
  "error": { "errorCode": "F001" },
  "traceId": "abc1234567890def",
  "timestamp": "2026-05-26T10:00:00Z"
}
```

## Error code reference (FIDO2 prefix `F`)

| Code | Status | Meaning |
|------|--------|---------|
| F001 | 400 | Challenge invalid or expired |
| F002 | 400 | Registration verification failed |
| F003 | 401 | Authentication verification failed |
| F004 | 403 | Attestation rejected by policy |
| F005 | 403 | Authenticator revoked (AAGUID blocklist) |

## Trace ID

Every response includes `X-Trace-Id` header and `traceId` field. When reporting bugs, include this value — it matches server-side log entries.

You can also send your own `X-Trace-Id` request header (16 hex chars) to thread a distributed trace through your call.

## Recommended client helper

```javascript
async function rp(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body),
  });
  const env = await res.json();
  if (!env.success) {
    const err = new Error(`[${env.code}] ${env.message}`);
    err.code = env.code;
    err.traceId = env.traceId;
    throw err;
  }
  return env.data;
}
```

## JWKS still untouched

```javascript
// Unchanged — RFC 7517 wire format preserved
const jwks = await fetch('https://passkey.crosscert.com/.well-known/jwks.json')
  .then(r => r.json());
// jwks.keys[0].kty === "RSA"
```
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/followups/2026-05-26-rp-migration-guide.md
git commit -m "docs: RP migration guide for Phase 4 envelope (Phase 4 T12)"
```

(No codex review needed — pure documentation.)

---

## Task 13: DoD verify + tag

**Files:** none (verification only)

- [ ] **Step 1: Full clean build**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.worktrees/phase-4-api-response-standard
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Aggregate test counts**

```bash
total_t=0; total_f=0; total_e=0; total_s=0
for xml in $(find . -path "*/build/test-results/test/TEST-*.xml" 2>/dev/null); do
  name=$(basename "$xml" .xml | sed 's/^TEST-//')
  t=$(grep -oE 'tests="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  f=$(grep -oE 'failures="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  e=$(grep -oE 'errors="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  s=$(grep -oE 'skipped="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+')
  printf "  %-72s tests=%-3s f=%-2s e=%-2s s=%-2s\n" "$name" "${t:-0}" "${f:-0}" "${e:-0}" "${s:-0}"
  total_t=$((total_t + ${t:-0})); total_f=$((total_f + ${f:-0})); total_e=$((total_e + ${e:-0})); total_s=$((total_s + ${s:-0}))
done
echo "----"
echo "TOTAL: tests=$total_t failures=$total_f errors=$total_e skipped=$total_s"
```

Expected: `failures=0 errors=0`. Total should be 134 (Phase 3 baseline) + 12 new tests (ApiResponseTest 5, TraceIdFilterTest 3, GlobalExceptionHandlerTest 4) + maybe a few new error-path tests (T3 K001 etc.) ≈ 148+ tests.

- [ ] **Step 3: Boot both apps and smoke-test wire shapes**

```bash
docker compose up -d
./scripts/wait-for-oracle.sh

./gradlew :admin-app:bootRun --args='--spring.profiles.active=local' > /tmp/dod-admin.log 2>&1 &
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local --server.port=8082' > /tmp/dod-passkey.log 2>&1 &

until grep -qE "Started AdminApplication|APPLICATION FAILED" /tmp/dod-admin.log; do sleep 2; done
until grep -qE "Started PasskeyApplication|APPLICATION FAILED" /tmp/dod-passkey.log; do sleep 2; done

echo "=== JWKS (must be unwrapped, RFC 7517) ==="
curl -s http://localhost:8082/.well-known/jwks.json | jq '.keys[0] | {kty, alg, use}'
# Expected: {"kty":"RSA","alg":"RS256","use":"sig"}
# If output is {"data":{...},"success":true,...} → JWKS got wrapped, BUG

echo "=== Admin /me (must be wrapped) ==="
# Will 401 unauthenticated — that's OK, we just want to see envelope shape
curl -s http://localhost:8081/admin/api/tenants
# Expected: {"success":false,"code":"A001","message":"Authentication required",...}

echo "=== X-Trace-Id header round-trip ==="
curl -si http://localhost:8081/admin/api/tenants | grep -i x-trace-id
# Expected: X-Trace-Id: <16 hex chars>

echo "=== Custom X-Trace-Id honored ==="
curl -si -H "X-Trace-Id: my-trace-1234" http://localhost:8081/admin/api/tenants | grep -i x-trace-id
# Expected: X-Trace-Id: my-trace-1234

pkill -f "AdminApplication|PasskeyApplication"
```

- [ ] **Step 4: Manual admin-ui smoke test**

```bash
# With admin-app running, open http://localhost:8081/admin/ in browser:
# - Login as alice@example.com
# - Navigate Tenants page → list renders
# - Navigate Keys page → list renders
# - Click "Rotate now" → confirm → success message shows new kid
# - Navigate Audit Log → list renders
# - Navigate MDS Status → version + last fetched render
```

Verify each page works. Note that the SPA expects unwrapped data — `client.ts` should be handling this automatically.

- [ ] **Step 5: Tag**

```bash
git tag -a phase-4-api-response-standard-complete -m "$(cat <<'EOF'
Phase 4 (API response standardization) complete.

Acceptance:
- 134 Phase 0-3 baseline tests still green
- 12+ new infrastructure tests (ApiResponseTest, TraceIdFilterTest,
  GlobalExceptionHandlerTest, controller error-path tests) green
- AdminFlowIT envelope unwrap green
- Fido2EndToEndIT envelope unwrap green
- JWKS endpoint preserves RFC 7517 wire format (curl verified)
- X-Trace-Id round-trip verified (in/out headers + MDC)
- admin-ui manual smoke tested across all 6 pages

Phase 4 surface:
- :core/api package: ApiResponse<T> envelope, ErrorCode enum
  (20 codes across 7 prefixes), BusinessException, GlobalExceptionHandler
  (10 handlers), TraceIdFilter (HIGHEST_PRECEDENCE, X-Trace-Id + MDC)
- admin-app: 13 endpoint methods migrated to ApiResponse envelope.
  RotationConflictException deleted. Services throw BusinessException
  for not-found / duplicate / conflict cases.
- passkey-app: 4 WebAuthn ceremony endpoints migrated. JwksController
  intentionally unchanged (RFC 7517 + OIDC Discovery wire format).
  Service-layer domain exceptions translated to BusinessException
  with F-prefix codes.
- admin-ui: client.ts auto-unwraps envelope, throws ApiError class
  on failure. Pages unchanged.
- docs/superpowers/followups/2026-05-26-rp-migration-guide.md for
  external RP JS clients.

Followups deferred to Phase 5+:
- PageResponse<T> for paginated list endpoints
- OpenAPI spec auto-generation (springdoc)
- admin-ui unit tests (Vitest)
- i18n error message localization
- Per-tenant ErrorCode customization
EOF
)"

git tag -l "phase-*"
```

Expected output includes `phase-4-api-response-standard-complete`.

---

## Self-Review

### Spec coverage

- §1 Goal — every REST API standardized except JWKS → T3–T9 + T11 (admin + passkey + UI).
- §2 Architecture — flow & 11 key decisions → T1 (infra), T2 (handler), T3–T9 (controllers).
- §3.1 `:core/api/` 7 classes → T1 (6 classes) + T2 (GlobalExceptionHandler).
- §3.2 Old handler removal → T2 (rm step).
- §3.3 admin-app 6 controllers, 13 methods → T3 (Tenant), T4 (Me), T5 (ApiKey), T6 (Audit), T7 (Mds), T8 (KeyMgmt). RotationConflictException deletion → T8.
- §3.4 passkey-app 4 methods + JwksController unchanged → T9.
- §3.5 admin-ui client.ts + types.ts → T11.
- §4 Wire format examples → verified via T13 curl smoke + ControllerSecurityTest jsonPath.
- §5 Migration order (T1→T6 in spec) → maps to plan T1 (infra) → T2 (handler) → T3–T8 (admin controllers) → T9 (passkey) → T10 (AdminFlowIT) → T11 (UI) → T12 (docs) → T13 (DoD). Same sequencing intent.
- §6 Testing strategy → unit T1+T2; controller T3–T9; integration T9 (Fido2EndToEndIT) + T10 (AdminFlowIT) + T8 (KeyRotationIT); manual T13.
- §7 Risks → addressed in T9 (RP gate + JwksController doc), T11 (loginForm preserved), T13 (DoD includes JWKS curl check + X-Trace-Id check).
- §8 Out-of-scope → not implemented (correct).
- §9 Plan task estimate 11-13 → delivered 13 tasks.

### Placeholder scan

- No "TBD" / "TODO" / "implement later" anywhere in task bodies.
- "if codex flags X, do Y" — handled in user's standing rule (autonomous decisions during execution); not a placeholder.
- "Read the file first" instructions are intentional pre-flight steps, not placeholders.

### Type consistency

- `BusinessException` ctor: `(ErrorCode)` and `(ErrorCode, String detail)` — used consistently in T1 (definition), T3/5/7/8/9 (callers).
- `ApiResponse.ok(data)` / `ok(message, data)` / `ok()` / `error(code)` / `error(code, detail)` / `error(code, List<FieldError>)` — used consistently across T2 (handler) and T3–T9 (controllers).
- `ErrorCode.KEY_ROTATION_CONFLICT` — defined in T1, used in T8 (service throw + controller test + IT test).
- `ApiError` (TS class) — defined in T11 types.ts, thrown in T11 client.ts.
- `MeView(String email, String role)` — defined T4, JSON shape preserved for admin-ui's existing `Me` interface (T11 unwrap layer means `Me` still works unchanged).
