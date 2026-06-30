# rp-app Kotlin → Java 전면 마이그레이션 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app 모듈의 35개 main + 3개 test Kotlin 소스를 순수 Java로 환원하고 Kotlin 플러그인·런타임 의존성을 제거해 전 모듈을 단일 언어(Java)로 통일한다.

**Architecture:** 순수 형태 변환 + cleanup(접근 A). 동작 변경 0. `data class`→`record`(+`@JsonProperty`), `companion object`→`static`, `object`→`final class`+`static`, `@Volatile`→`volatile`. properties는 모두 `@ConfigurationPropertiesScan`(VALUE_OBJECT 생성자 바인딩) 경로라 record로 안전하며, derived 필드를 가진 RelayProperties만 일반 class로 유지. 기존 테스트(Java 8 + 변환분 3)로 동일성을 보장한다.

**Tech Stack:** Java 17, Spring Boot 3, Spring Security, Thymeleaf, Jackson, sdk-java(이미 Java), JUnit 5 + AssertJ, Gradle Kotlin DSL.

## Global Constraints

- 패키지·클래스명은 100% 보존한다(logback-spring.xml이 `SecretMaskingConverter`/`RedactingMessageJsonProvider`/`CompactMdcConverter`를 FQCN으로 참조 — 변경 금지).
- 동작 변경 0. 엔드포인트·시그니처·검증 규칙·로깅 포맷·예외 매핑 모두 동일하게 보존한다.
- `?.`/`?:`/`as?`/null 처리는 의미를 그대로 보존한다(sdk-java 환원 #1 회귀 함정 — 컴파일·테스트 통과해도 못 잡으므로 전수 점검).
- 모든 DTO/payload record 컴포넌트에 `@JsonProperty("...")`를 명시한다(jackson-module-kotlin 제거 후에도 파라미터명 비의존 역직렬화 보장).
- 변환된 `.java`는 `src/main/java/...`·`src/test/java/...` 동일 패키지 경로에 두고, 원본 `.kt`는 같은 커밋에서 `git rm`한다(중복 클래스 컴파일 충돌 방지).
- 회귀 판정은 `./gradlew :rp-app:test` 모듈 단위로만 한다. 전체 `./gradlew build`는 SliceConfig 충돌·Oracle 경합으로 항상 실패하므로 게이트로 쓰지 않는다.
- 격리 worktree에서 작업한다. subagent는 cwd/브랜치를 검증해 메인 repo에 커밋하지 않는다.

---

## 작업 순서 개요 (의존성: 리프 → 루트)

각 태스크는 한 패키지(또는 응집 단위)를 변환하고 `:rp-app:compileJava`로 검증한다. 컴파일은 변환된 클래스가 기존 Java 테스트·다른 Kotlin 클래스(미변환분 포함)와 호환됨을 즉시 증명한다. 단위 테스트가 있는 클래스(SecretRedactor, CompactMdcConverter, RegRelayCodec 등)는 해당 테스트로 동작 동일성까지 확인한다. 전체 `:rp-app:test`는 마지막 두 태스크에서 돌린다.

- Task 1: `common/exception` (ErrorCode, BusinessException, GlobalExceptionHandler)
- Task 2: `common/response` (FieldError, ErrorDetail, PageResponse, ApiResponse)
- Task 3: `web/dto` (7개)
- Task 4: `user` (RpAppUser, InMemoryUserStore) — 2개
- Task 5: `config` properties (PasskeyProperties, WellKnownProperties, CorsProperties, RelayProperties) — 4개
- Task 6: `config` 나머지 (RelayKeyGuard, ReloadableApiKeySupplier, WebSecurityConfig, PasskeyClientConfiguration) — 4개
- Task 7: `web` 로깅 5개 (SecretRedactor, SecretMaskingConverter, RedactingMessageJsonProvider, CompactMdcConverter, RequestLoggingFilter) + `common/filter` (TraceIdFilter) — 6개
- Task 8: `web` 컨트롤러 3개(PageController, WellKnownController, WebAuthnController) + `web/relay` RegRelayCodec — 4개
- Task 9: 루트 (RpAppApplication) + 테스트 3개 변환 + build.gradle.kts 정리 → `:rp-app:test` 전체
- Task 10: 실제 부팅 검증(local 프로필) + 머지

---

### Task 1: common/exception 패키지

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/ErrorCode.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/BusinessException.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/GlobalExceptionHandler.java`
- Delete: 위 3개의 `.kt`(`src/main/kotlin/.../common/exception/*.kt`)

**Interfaces:**
- Produces:
  - `enum ErrorCode { ... }` with `HttpStatus status()`, `String code()`, `String message()` 접근자(Kotlin val → enum 인스턴스 메서드). 멤버명·값 동일.
  - `class BusinessException extends RuntimeException` with `ErrorCode errorCode` getter `getErrorCode()`, 2개 생성자(`(ErrorCode)`, `(ErrorCode, String)`).
  - `@RestControllerAdvice class GlobalExceptionHandler` — 핸들러 시그니처 동일.
- Consumes: `ApiResponse.error(...)`, `FieldError`는 Task 2가 정의(이 태스크에서는 아직 Kotlin 버전이 존재하므로 컴파일됨 — 순서 무관).

- [ ] **Step 1: ErrorCode.java 작성**

```java
package com.crosscert.passkey.rpapp.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "Invalid input value"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "Method not allowed"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "C003", "Entity not found"),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "C004", "Required parameter missing"),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "C005", "Type mismatch"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "Server error"),

    // Auth
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "Authentication required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A002", "Access denied"),

    // WebAuthn flow (rp-app domain)
    USERNAME_TAKEN(HttpStatus.CONFLICT, "W001", "Username already registered"),
    PENDING_REG_MISSING(HttpStatus.BAD_REQUEST, "W002", "Registration token missing or expired"),
    PENDING_AUTH_MISSING(HttpStatus.BAD_REQUEST, "W003", "Authentication token missing or expired"),

    // Passkey-server proxy
    PASSKEY_API_ERROR(HttpStatus.BAD_GATEWAY, "P001", "Upstream passkey server error"),
    PASSKEY_AUTH_ERROR(HttpStatus.UNAUTHORIZED, "P002", "Invalid X-API-Key (rp-app config)"),
    PASSKEY_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "P003", "Upstream rate limit exceeded"),
    PASSKEY_ID_TOKEN(HttpStatus.UNAUTHORIZED, "P004", "ID Token verification failed");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String message() { return message; }
}
```

> 접근자명을 `status()`/`code()`/`message()`로 둔다. Kotlin `val status` 의 JVM getter 는 `getStatus()` 였으나, GlobalExceptionHandler·ApiResponse 의 Kotlin 호출부(`code.status`, `code.code`, `code.message`)는 프로퍼티 문법이라 환원되는 Java 호출부와 함께 새 접근자명으로 통일한다. 이 enum 의 소비처(GlobalExceptionHandler, ApiResponse)는 같은 PR 에서 전부 Java 로 바뀌므로 일관된다.

- [ ] **Step 2: BusinessException.java 작성**

```java
package com.crosscert.passkey.rpapp.common.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
```

> 원본 Kotlin `val errorCode` 접근은 `e.errorCode`(GlobalExceptionHandler)였다. Java getter `getErrorCode()`로 두고 GlobalExceptionHandler(Step 3)에서 `e.getErrorCode()`로 호출한다.

- [ ] **Step 3: GlobalExceptionHandler.java 작성**

```java
package com.crosscert.passkey.rpapp.common.exception;

import com.crosscert.passkey.rpapp.common.response.ApiResponse;
import com.crosscert.passkey.rpapp.common.response.FieldError;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
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

    // ── 템플릿 8 ───────────────────────────────────────────────────

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e, HttpServletRequest req) {
        log.warn("[BusinessException] {} {} - {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        // rejectedValue 를 echo 하지 않는다(core GlobalExceptionHandler 와 동일) —
        // 필드명 + 메시지로 충분하고, 입력값 반사를 피한다.
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), null, fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT, errors));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.MISSING_PARAMETER, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.TYPE_MISMATCH, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT, "Malformed JSON"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(ErrorCode.ACCESS_DENIED.status())
                .body(ApiResponse.error(ErrorCode.ACCESS_DENIED));
    }

    // ── SDK 예외 변환 4 ────────────────────────────────────────────

    @ExceptionHandler(PasskeyAuthException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyAuth(PasskeyAuthException e) {
        log.error("[PasskeyAuth] upstream rejected X-API-Key — check rp-app/.env", e);
        return ResponseEntity.status(ErrorCode.PASSKEY_AUTH_ERROR.status())
                .body(ApiResponse.error(ErrorCode.PASSKEY_AUTH_ERROR));
    }

    @ExceptionHandler(PasskeyRateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyRateLimit(PasskeyRateLimitException e) {
        return ResponseEntity.status(ErrorCode.PASSKEY_RATE_LIMITED.status())
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiResponse.error(ErrorCode.PASSKEY_RATE_LIMITED));
    }

    @ExceptionHandler(PasskeyIdTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyIdToken(PasskeyIdTokenException e) {
        return ResponseEntity.status(ErrorCode.PASSKEY_ID_TOKEN.status())
                .body(ApiResponse.error(ErrorCode.PASSKEY_ID_TOKEN, e.getMessage()));
    }

    @ExceptionHandler(PasskeyApiException.class)
    public ResponseEntity<ApiResponse<Void>> handlePasskeyApi(PasskeyApiException e) {
        log.error("[PasskeyApi] upstream code={} traceId={}", e.getCode(), e.getTraceId(), e);
        return ResponseEntity.status(ErrorCode.PASSKEY_API_ERROR.status())
                .body(ApiResponse.error(ErrorCode.PASSKEY_API_ERROR, e.getMessage()));
    }

    // ── 마지막 fallback ────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("[Unhandled] {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.status())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
```

> `e.retryAfterSeconds`(Kotlin 프로퍼티)는 sdk-java(Java)의 `getRetryAfterSeconds()` getter. `e.code`/`e.traceId`도 `getCode()`/`getTraceId()`. SDK가 Java라 getter 형식임을 확인하고 호출한다.
> `.map { }.toList()`(Kotlin) → `.stream().map(...).toList()`(Java 16+ `Stream.toList()`).

- [ ] **Step 4: 원본 .kt 3개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/exception/ErrorCode.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/exception/BusinessException.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/exception/GlobalExceptionHandler.kt
```

- [ ] **Step 5: 컴파일 검증**

Run: `./gradlew :rp-app:compileJava :rp-app:compileKotlin`
Expected: BUILD SUCCESSFUL. (ApiResponse/FieldError는 아직 Kotlin이지만 `error(...)`·`new FieldError(...)`가 Java에서 호출 가능하므로 통과. enum 접근자 `status()` 호출도 컴파일됨.)

> 주의: `ApiResponse`(Kotlin)의 `error(...)` 가 아직 `@JvmStatic`이라 Java에서 `ApiResponse.error(...)`로 호출 가능. Task 2에서 Java로 바뀌어도 동일 시그니처라 호환.

- [ ] **Step 6: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/common/exception/
git commit -m "refactor(rp-app): common/exception Kotlin→Java 환원"
```

---

### Task 2: common/response 패키지

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/FieldError.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/ErrorDetail.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/PageResponse.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/ApiResponse.java`
- Delete: 위 4개의 `.kt`

**Interfaces:**
- Produces:
  - `record FieldError(String field, Object rejectedValue, String reason)`
  - `record ErrorDetail(String errorCode, List<FieldError> fieldErrors)` + `@JsonInclude(NON_NULL)`
  - `record PageResponse<T>(...)`
  - `record ApiResponse<T>(boolean success, String code, String message, T data, ErrorDetail error, String traceId, LocalDateTime timestamp)` + `@JsonInclude(NON_NULL)` + static factory 6개: `ok(T)`, `ok(String, T)`, `ok()`, `error(ErrorCode)`, `error(ErrorCode, List<FieldError>)`, `error(ErrorCode, String)`.
- Consumes: `ErrorCode`(Task 1, Java), `MDC.get`, `LocalDateTime.now()`.

- [ ] **Step 1: FieldError.java**

```java
package com.crosscert.passkey.rpapp.common.response;

public record FieldError(String field, Object rejectedValue, String reason) {}
```

- [ ] **Step 2: ErrorDetail.java**

```java
package com.crosscert.passkey.rpapp.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(String errorCode, List<FieldError> fieldErrors) {}
```

- [ ] **Step 3: PageResponse.java**

```java
package com.crosscert.passkey.rpapp.common.response;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {}
```

- [ ] **Step 4: ApiResponse.java**

```java
package com.crosscert.passkey.rpapp.common.response;

import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        ErrorDetail error,
        String traceId,
        LocalDateTime timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "Success", data, null, MDC.get("traceId"), LocalDateTime.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, "OK", message, data, null, MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "OK", "Success", null, null, MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return new ApiResponse<>(false, code.code(), code.message(), null,
                new ErrorDetail(code.code(), null), MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, List<FieldError> fieldErrors) {
        return new ApiResponse<>(false, code.code(), code.message(), null,
                new ErrorDetail(code.code(), fieldErrors), MDC.get("traceId"), LocalDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, String detailMessage) {
        return new ApiResponse<>(false, code.code(), detailMessage, null,
                new ErrorDetail(code.code(), null), MDC.get("traceId"), LocalDateTime.now());
    }
}
```

> Kotlin `companion object @JvmStatic fun ok/error` → Java `static`. 인자 순서·기본 문자열("OK"/"Success")·MDC 키·`LocalDateTime.now()` 모두 보존. record 접근자명은 기존 Kotlin 프로퍼티명과 동일(`success()`, `code()`, ...).

- [ ] **Step 5: 원본 .kt 4개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/response/FieldError.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/response/ErrorDetail.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/response/PageResponse.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/response/ApiResponse.kt
```

- [ ] **Step 6: 컴파일 검증**

Run: `./gradlew :rp-app:compileJava :rp-app:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/common/response/
git commit -m "refactor(rp-app): common/response Kotlin→Java 환원(record + static factory)"
```

---

### Task 3: web/dto 패키지 (7개)

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/{RegisterStartReq,RegisterCompleteReq,RegisterOptionsResp,LoginStartReq,LoginCompleteReq,LoginOptionsResp,LoginResultResp}.java` (7)
- Delete: `src/main/kotlin/.../web/dto/*.kt` (7)

> dto 디렉토리 실제 7개 파일(확인됨): RegisterStartReq, RegisterCompleteReq, RegisterOptionsResp, LoginStartReq, LoginCompleteReq, LoginOptionsResp, LoginResultResp. 아래 Step 1(요청 4)·Step 2(응답 3)이 전부를 커버한다.

**Interfaces:**
- Produces: 각 record. 검증 어노테이션은 `@field:NotBlank`/`@field:NotNull`(Kotlin) → record 컴포넌트에 직접 `@NotBlank`/`@NotNull`.
- Consumes: `JsonNode`(Jackson), jakarta validation.

- [ ] **Step 1: 요청 DTO 4개 작성**

```java
// RegisterStartReq.java
package com.crosscert.passkey.rpapp.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterStartReq(
        @NotBlank String username,
        @NotBlank String displayName
) {}
```

```java
// RegisterCompleteReq.java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterCompleteReq(
        @NotNull JsonNode publicKeyCredential,
        @NotBlank String regRelayToken
) {}
```

```java
// LoginStartReq.java
package com.crosscert.passkey.rpapp.web.dto;

public record LoginStartReq(String username) {}
```

```java
// LoginCompleteReq.java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginCompleteReq(
        @NotNull JsonNode publicKeyCredential,
        @NotBlank String authenticationToken
) {}
```

> 검증 회귀 주의: Kotlin `@field:NotNull val publicKeyCredential: JsonNode?`는 필드에 어노테이션이 붙었다. Java record는 컴포넌트에 직접 `@NotNull JsonNode publicKeyCredential`로 두면 Spring이 record 검증 시 동일하게 인식한다(`@Valid @RequestBody`). 기존 WebAuthnControllerTest의 400 케이스로 검증된다(Task 9).

- [ ] **Step 2: 응답 DTO 3개 작성**

```java
// RegisterOptionsResp.java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * register/begin 응답. 무상태 릴레이를 위해 registrationToken·userHandle 을 HMAC 서명한
 * 불투명 regRelayToken(spec §5)을 반환한다. 클라이언트는 register/finish 에 이 토큰을
 * 다시 실어 보낸다(userHandle 조작 불가).
 */
public record RegisterOptionsResp(
        JsonNode publicKeyCredentialCreationOptions,
        String regRelayToken
) {}
```

```java
// LoginOptionsResp.java
package com.crosscert.passkey.rpapp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * authenticate/begin 응답. 무상태 릴레이를 위해 단명 authenticationToken 을 클라이언트에
 * 반환한다. 클라이언트는 authenticate/finish 요청에 이 토큰을 다시 실어 보낸다.
 */
public record LoginOptionsResp(
        JsonNode publicKeyCredentialRequestOptions,
        String authenticationToken
) {}
```

```java
// LoginResultResp.java
package com.crosscert.passkey.rpapp.web.dto;

/**
 * authenticate/finish 성공 결과. id-token 은 rp-app 내부에서만 검증·소비하고 노출하지
 * 않는다(spec §4). 클라이언트는 이 결과로 "인증됨"을 알고 자기 UX 를 진행한다.
 */
public record LoginResultResp(
        boolean authenticated,
        String userHandle,
        String displayName
) {}
```

- [ ] **Step 3: 원본 .kt 7개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/dto/*.kt
```

- [ ] **Step 4: 컴파일 검증**

Run: `./gradlew :rp-app:compileJava :rp-app:compileKotlin`
Expected: BUILD SUCCESSFUL. WebAuthnController(아직 Kotlin)가 `req.username`/`req.displayName`(프로퍼티 접근)을 쓰는데, record는 `username()`/`displayName()` 접근자다.
→ Kotlin은 Java record의 컴포넌트를 synthetic property(`req.username`)로도 호출 가능하므로 통과 예상. 단 통과 여부로 가설을 검증한다. 컴파일 실패 시 Task 3을 Task 8 직전으로 미뤄 컨트롤러와 함께 변환·커밋한다.

> **순서 결정(중요):** Kotlin은 Java record 접근자를 `r.username()` 또는 synthetic property `r.username`으로 부른다. `@field:NotBlank val username: String?`(nullable)이 `@NotBlank String username`(non-null 선언, 값은 null 가능)으로 바뀌어도 런타임 검증은 `@Valid`로 동일. 이 Step의 컴파일 통과로 가설을 검증한다.

- [ ] **Step 5: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/dto/
git commit -m "refactor(rp-app): web/dto 8개 Kotlin→Java record 환원"
```

---

### Task 4: user 패키지 (RpAppUser, InMemoryUserStore)

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/user/RpAppUser.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/user/InMemoryUserStore.java`
- Delete: 두 `.kt`
- Test(기존, 변경 없음): `rp-app/src/test/java/com/crosscert/passkey/rpapp/user/InMemoryUserStoreTest.java`

**Interfaces:**
- Produces:
  - `record RpAppUser(String userHandle, String username, String displayName, Instant createdAt, String credentialId) implements Serializable` + 각 컴포넌트 `@JsonProperty`.
  - `class InMemoryUserStore` — public 메서드: `String createPending(String, String)`, `boolean isUsernameTakenByOther(String, String)`, `void confirmRegistration(String, String, String, String)`, `Optional<RpAppUser> findByUserHandle(String)`, `Optional<RpAppUser> findByUsername(String)`.
- Consumes: `RpAppUser`, `BusinessException`/`ErrorCode`(Task 1), `ObjectMapper`, `@Value`.

- [ ] **Step 1: RpAppUser.java**

```java
package com.crosscert.passkey.rpapp.user;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;

public record RpAppUser(
        @JsonProperty("userHandle") String userHandle,
        @JsonProperty("username") String username,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("createdAt") Instant createdAt,
        // confirmRegistration 후 채워짐. 없으면 null (pending).
        @JsonProperty("credentialId") String credentialId
) implements Serializable {}
```

> 원본 Kotlin은 `@field:JsonProperty`. record는 컴포넌트에 `@JsonProperty`(record 컴포넌트 어노테이션이 field·accessor·param에 전파). `InMemoryUserStoreTest`의 JSON 직렬화/역직렬화 라운드트립이 이를 검증.

- [ ] **Step 2: InMemoryUserStore.java**

```java
package com.crosscert.passkey.rpapp.user;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * username ↔ userHandle ↔ credential 매핑 저장소.
 *
 * 맵은 in-memory 캐시이고, 확정 등록된 user(credentialId ≠ null)만 JSON 파일에
 * 미러링한다. pending user 는 메모리에만 두어 재기동 시 자연 정리된다. 단일 인스턴스
 * 데모를 가정하며 파일 락은 두지 않는다.
 */
@Component
public class InMemoryUserStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserStore.class);

    private final ObjectMapper mapper;
    private final ConcurrentMap<String, RpAppUser> byHandle = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> byUsername = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final Path file;

    public InMemoryUserStore(
            ObjectMapper mapper,
            @Value("${rp-app.user-store.file:./data/rp-app-users.json}") String file) {
        this.mapper = mapper;
        this.file = Path.of(file);
        load();
    }

    /** 기동 시 파일에서 확정 user 복원. 파일이 없으면 빈 상태로 시작. 손상되면 quarantine 후 빈 상태로 시작(크래시 금지). */
    private void load() {
        if (!Files.exists(file)) {
            log.info("user-store: no persisted file at {} — starting empty", file);
            return;
        }
        try {
            List<RpAppUser> users = mapper.readValue(file.toFile(), new TypeReference<List<RpAppUser>>() {});
            for (RpAppUser u : users) {
                // 방어: 확정(credentialId)만 신뢰하고, 맵 key 가 될 필수 필드가 null 이면 skip(NPE 회피).
                if (u.credentialId() == null || u.userHandle() == null || u.username() == null) continue;
                byHandle.put(u.userHandle(), u);
                byUsername.put(u.username(), u.userHandle());
            }
            log.info("user-store: loaded {} confirmed user(s) from {}", byHandle.size(), file);
        } catch (Exception e) {
            log.warn("user-store: failed to read {} — quarantining and starting empty. cause={}", file, e.toString());
            quarantineCorruptFile();
        }
    }

    /** 손상된 store 파일을 .corrupt-<epochMillis> 로 옮겨 다음 persist 가 원본을 덮어쓰지 못하게 한다. */
    private void quarantineCorruptFile() {
        try {
            Path dest = file.resolveSibling(file.getFileName().toString() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
            log.warn("user-store: quarantined corrupt file to {}", dest);
        } catch (IOException ignore) {
            // quarantine 실패는 best-effort — 그래도 기동은 계속.
        }
    }

    /** 확정 user(credentialId ≠ null)만 골라 파일에 쓴다. temp 파일 → ATOMIC_MOVE(미지원 FS 면 비원자 move 폴백). 실패해도 예외 비전파. */
    private synchronized void persist() {
        List<RpAppUser> confirmed = byHandle.values().stream()
                .filter(u -> u.credentialId() != null)
                .toList();
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            mapper.writeValue(tmp.toFile(), confirmed);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); // 비원자적 폴백
            }
        } catch (IOException e) {
            log.error("user-store: failed to persist {} user(s) to {} — cause={}",
                    confirmed.size(), file, e.toString());
        } finally {
            // 성공 시엔 move 로 tmp 가 사라져 no-op. 실패 시 stale tmp 정리.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 정리 실패는 무시.
            }
        }
    }

    /**
     * username 으로 새 userHandle (32B base64url) 발급 + pending 상태로 저장.
     *
     * begin 단계에서는 username 을 점유하지 **않는다**(byUsername 매핑을 만들지 않음).
     * begin 만 하고 finish 를 못 한 사용자(다이얼로그 취소·페이지 이탈·네트워크 단절)가 같은
     * username 으로 영구히 재시도하지 못하던 W001 버그를 막기 위함. 진짜 username 충돌 방지는
     * confirmRegistration 의 putIfAbsent(최종 권위) + 컨트롤러의 isUsernameTakenByOther
     * 선검사가 처리하므로, begin 점유는 정상 재시도를 차단하는 부작용만 있었다.
     */
    public String createPending(String username, String displayName) {
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String userHandle = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        RpAppUser user = new RpAppUser(userHandle, username, displayName, Instant.now(), null);
        // byHandle 에만 pending 을 둔다(username 미점유). 영속화하지 않음 — 재기동 시 자연 정리.
        byHandle.put(userHandle, user);
        return userHandle;
    }

    /**
     * username 이 이미 **다른** userHandle 로 점유돼 있는지 검사. 컨트롤러가 upstream
     * registrationFinish **전에** 호출해, 유효 begin 에서 온(HMAC 으로 증명된) username 이라도
     * finish 시점에 다른 사용자에게 확정돼 있으면 typed-login 탈취/충돌을 미리 차단하기 위함이다.
     * 같은 handle(정상 재확정)이면 false.
     */
    public boolean isUsernameTakenByOther(String username, String userHandle) {
        String existingHandle = byUsername.get(username);
        return existingHandle != null && !existingHandle.equals(userHandle);
    }

    /**
     * registration/finish 성공 후 확정. userHandle 이 (pending 으로) 있으면 credentialId 를 채우고,
     * 없으면(재시작·다중 인스턴스로 pending 유실) relay 의 서명된 username/displayName 로 user 를
     * 결정적으로 생성해 확정한다. 어느 경로든 확정 user 는 파일에 영속화된다(완전 무상태, P0-4).
     *
     * 방어(이중): username 이 이미 다른 userHandle 로 점유돼 있으면 거부(탈취/충돌 방지).
     * putIfAbsent 로 원자적 점유 검사 + 같은-handle 재확정(idempotent)은 허용. HMAC 은 "유효
     * begin 에서 온 username"만 증명하지 "finish 시점 미점유"는 증명하지 못하므로 필요하다.
     */
    public void confirmRegistration(String userHandle, String username, String displayName, String credentialId) {
        String existingHandle = byUsername.putIfAbsent(username, userHandle);
        if (existingHandle != null && !existingHandle.equals(userHandle)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        byHandle.compute(userHandle, (k, existing) -> {
            if (existing != null) {
                return new RpAppUser(existing.userHandle(), existing.username(), existing.displayName(),
                        existing.createdAt(), credentialId);
            } else {
                return new RpAppUser(userHandle, username, displayName, Instant.now(), credentialId);
            }
        });
        persist();
    }

    public Optional<RpAppUser> findByUserHandle(String userHandle) {
        return Optional.ofNullable(byHandle.get(userHandle));
    }

    public Optional<RpAppUser> findByUsername(String username) {
        String handle = byUsername.get(username);
        if (handle == null) return Optional.empty();
        return findByUserHandle(handle);
    }
}
```

> 회귀 주의: `@Synchronized private fun persist()`(Kotlin) → `private synchronized void persist()`. `byHandle.compute { _, existing -> ... }` 람다 → `byHandle.compute(userHandle, (k, existing) -> ...)`. `existingHandle != userHandle`(Kotlin `!=`는 `equals`) → `!existingHandle.equals(userHandle)`(참조 비교 `!=` 쓰면 회귀!). `RpAppUser` 생성자 인자 순서(userHandle, username, displayName, createdAt, credentialId) 보존.

- [ ] **Step 3: 원본 .kt 2개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/user/RpAppUser.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/user/InMemoryUserStore.kt
```

- [ ] **Step 4: 컴파일 + user 테스트**

Run: `./gradlew :rp-app:compileJava :rp-app:compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.user.InMemoryUserStoreTest"`
Expected: PASS (전 메서드 — createPending, isUsernameTakenByOther, confirmRegistration putIfAbsent, persist 라운드트립, quarantine).

> 주의: Kotlin WebAuthnController가 `users.findByUsername(...).map { it.userHandle }`로 record 접근(`it.userHandle()` 또는 synthetic property `it.userHandle`)을 한다. 컴파일 통과 확인. 실패 시 Task 8과 묶는다(Task 3의 순서 결정 노트와 동일 처리).

- [ ] **Step 5: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/user/
git commit -m "refactor(rp-app): user 패키지 Kotlin→Java 환원(record + ConcurrentMap)"
```

---

### Task 5: config properties 4개

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyProperties.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/WellKnownProperties.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/CorsProperties.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/RelayProperties.java`
- Delete: 위 4개 `.kt`

**Interfaces:**
- Produces:
  - `record PasskeyProperties(URI baseUrl, String apiKey, Path apiKeyFile, Duration apiKeyReload, String tenantId, URI issuerBase, Duration connectTimeout, Duration readTimeout, Duration jwksCacheTtl)` + `@ConfigurationProperties(prefix="passkey")`.
  - `record WellKnownProperties(List<AndroidApp> android, Ios ios)` + 중첩 `record AndroidApp(String packageName, List<String> sha256Fingerprints)`, `record Ios(List<String> appIds)` + `@ConfigurationProperties(prefix="rp-app.well-known")`.
  - `record CorsProperties(List<String> allowedOrigins)` + compact 생성자 검증 + `@ConfigurationProperties(prefix="rp.cors")`.
  - `class RelayProperties` — 생성자 `(String secret, Duration ttl)`, 접근자 `String secret()`, `Duration ttl()`, 정규화(DEMO_SECRET 폴백, 5분 기본) + `@ConfigurationProperties(prefix="rp.relay")`.
- Consumes: `RelayKeyGuard.DEMO_SECRET`(Task 6에서 변환되지만 Kotlin 버전이 아직 존재하므로 `RelayKeyGuard.DEMO_SECRET` 상수 접근 가능).

- [ ] **Step 1: PasskeyProperties.java**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "passkey")
public record PasskeyProperties(
        URI baseUrl,
        String apiKey,
        /**
         * 설정 시 이 파일에서 API Key 를 핫리로드한다(env 보다 우선). 미설정이면
         * apiKey 를 폴백으로 쓴다(기존 동작 보존). 파일 내용은 키 평문 한 줄.
         */
        Path apiKeyFile,
        /** apiKeyFile mtime 폴링 주기. 기본 10s. */
        Duration apiKeyReload,
        String tenantId,
        /**
         * ID Token 의 iss claim 비교용 prefix. passkey-app 의 IdTokenIssuer 가
         * issuer-base + "/" + tenantId 로 발급한다.
         */
        URI issuerBase,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl
) {}
```

- [ ] **Step 2: WellKnownProperties.java**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 네이티브 앱 패스키용 Well-Known URI(assetlinks.json / apple-app-site-association)
 * 메타데이터. rp-app 를 RP 레퍼런스 구현으로 쓰는 고객사는 코드를 고치지 않고
 * 환경변수/yml override 로 자기 앱 값만 채운다.
 *
 * 모든 @ConfigurationProperties 가 @ConfigurationPropertiesScan(VALUE_OBJECT 생성자
 * 바인딩) 으로만 등록되므로 Java record 로 외부 yml override 가 정상 동작한다.
 */
@ConfigurationProperties(prefix = "rp-app.well-known")
public record WellKnownProperties(
        List<AndroidApp> android,
        Ios ios
) {
    public record AndroidApp(
            String packageName,
            List<String> sha256Fingerprints
    ) {}

    public record Ios(
            List<String> appIds
    ) {}
}
```

> `@JvmRecord` 우회가 필요했던 이유(Kotlin data class가 진짜 record가 아니라서)는 Java record로 가면 소멸한다. scan 경로라 외부 yml override 정상.

- [ ] **Step 3: CorsProperties.java**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * cross-origin 웹 SPA 를 위한 정확한 origin 화이트리스트.
 * ⚠️ reflected-origin(요청 Origin 반사)·와일드카드 금지(spec §3). 정확한 origin 목록만.
 * 설정: rp.cors.allowed-origins (콤마 구분 또는 YAML 리스트). 비면 CORS 비활성.
 */
@ConfigurationProperties(prefix = "rp.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {
    public CorsProperties {
        // "no wildcard" 규칙을 부팅 시 강제한다(spec §3). 와일드카드·빈 값 거부.
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin.isBlank()) {
                    throw new IllegalArgumentException("rp.cors.allowed-origins 에 빈 값 금지");
                }
                if (origin.contains("*")) {
                    throw new IllegalArgumentException(
                            "rp.cors.allowed-origins 에 와일드카드 금지(정확한 origin 만): " + origin);
                }
            }
        }
    }
}
```

> Kotlin `init { require(...) }` → record compact 생성자에서 검증. `require`는 `IllegalArgumentException` 던짐 → Java도 동일 예외. WebAuthnControllerTest가 fixture @Bean으로 CorsProperties 주입하므로 Task 9에서 검증.

- [ ] **Step 4: RelayProperties.java (record 아님 — 일반 class)**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 등록 relay 토큰 HMAC 서명 설정(spec §5). registrationToken↔userHandle 바인딩용.
 * secret 은 운영 시 환경변수로 주입(데모 기본값은 비밀 아님). ttl 은 passkey-app
 * challenge TTL(5분)과 정렬.
 *
 * 생성자 파라미터(nullable)를 받아 정규화한 non-null 값을 노출한다. @ConfigurationPropertiesScan
 * 으로만 등록되므로 Spring 이 VALUE_OBJECT(생성자) 바인딩을 쓴다 — derived 필드라도 정상 바인딩.
 * RelayKeyGuardTest 는 new RelayProperties(null, ..) 로 DEMO_SECRET 폴백을 검증한다.
 */
@ConfigurationProperties(prefix = "rp.relay")
public class RelayProperties {

    private final String secret;
    private final Duration ttl;

    public RelayProperties(String secret, Duration ttl) {
        // 데모 폴백. non-dev 프로필에선 RelayKeyGuard 가 차단(P2-a).
        this.secret = (secret == null || secret.isBlank()) ? RelayKeyGuard.DEMO_SECRET : secret;
        this.ttl = (ttl == null) ? Duration.ofMinutes(5) : ttl;
    }

    public String secret() { return secret; }
    public Duration ttl() { return ttl; }
}
```

> 접근자명을 `secret()`/`ttl()`로 둬 record 스타일과 통일(RegRelayCodec·RelayKeyGuard의 Kotlin `props.secret`/`props.ttl` 프로퍼티 접근이 Java getter를 못 찾을 수 있으므로, 소비처가 모두 Java로 바뀌는 같은 PR 내에서 `props.secret()`/`props.ttl()`로 호출 통일). `RelayKeyGuardTest`는 `new RelayProperties(secret, ttl)` 생성자만 쓰므로 영향 없음.

- [ ] **Step 5: 원본 .kt 4개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/PasskeyProperties.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/WellKnownProperties.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/CorsProperties.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/RelayProperties.kt
```

- [ ] **Step 6: 컴파일 검증**

Run: `./gradlew :rp-app:compileJava :rp-app:compileKotlin`
Expected: BUILD SUCCESSFUL이거나, RelayProperties 접근자 변경으로 Kotlin RegRelayCodec/RelayKeyGuard(`props.secret`)가 깨질 수 있다.
→ Kotlin은 Java `secret()` 메서드를 `props.secret()`로만 부른다(프로퍼티 syntax `props.secret` 불가, getXxx 아니므로). RegRelayCodec/RelayKeyGuard/WebSecurityConfig는 아직 Kotlin이라 `props.secret`로 호출 → **컴파일 실패 가능**.
→ 대응: Task 5와 Task 6을 함께 묶어 진행(아래 노트). properties 접근자를 쓰는 Kotlin 소비처(RelayKeyGuard, RegRelayCodec, WebSecurityConfig)가 같은 단계에서 Java로 바뀌도록 한다.

> **순서 결정(중요):** RelayProperties/CorsProperties/WellKnownProperties의 접근자를 record 스타일(`secret()`/`allowedOrigins()`/`android()`)로 두면, 아직 Kotlin인 소비처(RelayKeyGuard `props.secret`, WebSecurityConfig `cors.allowedOrigins`, WellKnownController `props.android`)가 프로퍼티 문법으로 호출해 컴파일이 깨진다. **따라서 Task 5(properties)·Task 6(config 나머지)·Task 8의 WellKnownController를 한 단계로 묶거나, 임시로 properties에 Java getter(`getSecret()` 등)를 추가했다가 소비처 변환 후 제거하는 대신, 아래 권장 순서를 따른다:** Task 5 → Task 6 → Task 7 → Task 8을 연속 진행하고, 각 Task 끝의 `compileKotlin`이 통과할 때까지는 다음 Task와 묶어 커밋한다. 컴파일이 통과하면 개별 커밋한다. (실행자는 `compileKotlin` 실패 시 "다음 의존 Task까지 진행 후 함께 검증"으로 처리한다.)

- [ ] **Step 7: 커밋 (compileKotlin 통과 시)**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/config/{PasskeyProperties,WellKnownProperties,CorsProperties,RelayProperties}.java
git commit -m "refactor(rp-app): config properties 4개 Kotlin→Java 환원(record/class)"
```

> compileKotlin이 실패하면 커밋하지 말고 Task 6으로 진행해 소비처를 Java로 바꾼 뒤 함께 커밋한다.

---

### Task 6: config 나머지 4개

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/RelayKeyGuard.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplier.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/WebSecurityConfig.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.java`
- Delete: 위 4개 `.kt`
- Test(기존): `ReloadableApiKeySupplierTest.java`, `RelayKeyGuardTest.java`, `PasskeyClientWiringIT.java`

**Interfaces:**
- Produces:
  - `class RelayKeyGuard` + `public static final String DEMO_SECRET`, `@EventListener void check()`.
  - `class ReloadableApiKeySupplier implements Supplier<String>` — 생성자 `(Path keyFile, Duration pollInterval, String envFallback)`, `String get()`.
  - `class WebSecurityConfig` + `@Bean SecurityFilterChain chain(HttpSecurity)`.
  - `class PasskeyClientConfiguration` + `@Bean Supplier<String> apiKeySupplier(PasskeyProperties)`, `@Bean PasskeyClient passkeyClient(PasskeyProperties, Supplier<String>)`.
- Consumes: Task 5 properties(`secret()`/`ttl()`/`allowedOrigins()`/baseUrl 등), sdk-java `PasskeyClient`/`PasskeyClientConfig`(Java Builder).

- [ ] **Step 1: RelayKeyGuard.java**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/** non-dev/local 프로필에서 relay 데모 키 사용을 차단(P2-a, spec §5). */
@Component
public class RelayKeyGuard {

    /** 데모/개발용 기본 HMAC 키. RelayProperties 폴백과 공유(상수 한곳 정의). */
    public static final String DEMO_SECRET = "dev-rp-relay-secret-not-for-prod-change-me";

    private final RelayProperties props;
    private final Environment env;

    public RelayKeyGuard(RelayProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        Set<String> active = Set.of(env.getActiveProfiles());
        // dev/local 이 명시적으로 active 일 때만 데모 키 허용. 빈 프로필(env-only 운영)은
        // 운영으로 간주해 데모 키를 거부한다.
        boolean devOrLocal = active.contains("dev") || active.contains("local");
        if (!devOrLocal && DEMO_SECRET.equals(props.secret())) {
            throw new IllegalStateException(
                    "rp.relay.secret 이 데모 기본 키입니다. 운영(또는 프로필 미지정) 환경에서는 RP_RELAY_SECRET 로 강한 키를 주입하세요.");
        }
    }
}
```

> `env.activeProfiles.toSet()` → `Set.of(env.getActiveProfiles())`. `DEMO_SECRET == props.secret`(Kotlin `==`=equals) → `DEMO_SECRET.equals(props.secret())`. `props.secret`(프로퍼티) → `props.secret()`.

- [ ] **Step 2: ReloadableApiKeySupplier.java**

```java
package com.crosscert.passkey.rpapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * API Key 를 파일에서 핫리로드하는 Supplier. SDK 의 RedactingRequestInterceptor
 * 가 요청마다 get() 을 호출하므로, 운영자가 키 파일만 바꾸면 재기동 없이 다음 요청부터
 * 새 키가 반영된다.
 *
 * 스레드 안전: 캐시 상태(cachedKey + lastModified)를 불변 holder 로 묶어 단일 volatile
 * 참조로 발행한다. lastPollAt 의 race 는 무해하므로 별도 volatile.
 */
public class ReloadableApiKeySupplier implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ReloadableApiKeySupplier.class);

    /** (mtime, key) 를 한 번에 발행하기 위한 불변 holder. key==null 이면 env 폴백 신호. */
    private record State(long lastModified, String cachedKey) {}

    private static final State EMPTY = new State(Long.MIN_VALUE, null);

    private final Path keyFile;
    private final String envFallback;
    private final long pollMillis;

    private volatile State state = EMPTY;
    private volatile long lastPollAt = Long.MIN_VALUE;

    public ReloadableApiKeySupplier(Path keyFile, Duration pollInterval, String envFallback) {
        this.keyFile = keyFile;
        this.envFallback = envFallback;
        this.pollMillis = (pollInterval == null) ? 0L : Math.max(0L, pollInterval.toMillis());
    }

    @Override
    public String get() {
        if (keyFile == null) {
            return envFallback;
        }
        maybeReload();
        String key = state.cachedKey();
        return (key != null) ? key : envFallback;
    }

    private void maybeReload() {
        long now = System.currentTimeMillis();
        // 폴링 throttle: 캐시 유무와 무관하게 poll 주기 내면 디스크 안 본다.
        // 최초 호출(lastPollAt==MIN_VALUE)은 항상 통과한다.
        if (lastPollAt != Long.MIN_VALUE && now - lastPollAt < pollMillis) {
            return;
        }
        lastPollAt = now;
        State current = state;
        try {
            long mtime = Files.getLastModifiedTime(keyFile).toMillis();
            if (mtime == current.lastModified() && current.cachedKey() != null) {
                return; // 안 바뀌었고 이미 읽은 캐시 있음
            }
            String raw = Files.readString(keyFile).trim();
            if (raw.isEmpty()) {
                // 빈/공백 파일 → 캐시 비워 env 폴백. mtime 갱신해 반복 재읽기 방지.
                state = new State(mtime, null);
                log.warn("api-key file is empty/blank, falling back to env: {}", keyFile);
            } else {
                state = new State(mtime, raw);
            }
        } catch (IOException e) {
            // 읽기 실패: 직전 유효 키 유지(fail-safe).
            log.warn("api-key file reload failed (keeping last good key): {} cause={}", keyFile, e.toString());
        } catch (RuntimeException e) {
            log.warn("api-key file reload failed (keeping last good key): {} cause={}", keyFile, e.toString());
        }
    }
}
```

> 회귀 주의: `Supplier<String?>`(Kotlin) → `Supplier<String>`(null 반환 허용 — Java 제네릭에선 표현 불가하나 동작 동일). `@Volatile var` → `volatile`. `maxOf(0L, ...)` → `Math.max(0L, ...)`. `data class State` → 내부 `record State`. `key ?: envFallback` → `(key != null) ? key : envFallback`. State 두 필드 동시 발행(불변 record 단일 volatile 대입) 의미 보존.

- [ ] **Step 3: WebSecurityConfig.java**

```java
package com.crosscert.passkey.rpapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class WebSecurityConfig {

    private final CorsProperties cors;

    public WebSecurityConfig(CorsProperties cors) {
        this.cors = cors;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(
                                "/", "/register", "/login",
                                "/passkey/**", "/css/**", "/js/**",
                                "/.well-known/**", "/robots.txt")
                        .permitAll()
                        .anyRequest().permitAll()) // 데모용 — 보호 리소스 없음
                // 무상태 클라이언트: 서버 세션·CSRF 토큰을 쿠키에 담지 않으므로 CSRF 비활성.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .formLogin(f -> f.disable())
                .httpBasic(h -> h.disable())
                .logout(l -> l.disable());
        return http.build();
    }

    /**
     * /passkey/ 경로(**)에만 적용되는 CORS 정책.
     * ⚠️ 정확한 origin 목록만 허용(reflected-origin·와일드카드 금지, spec §3).
     * 자격증명(쿠키)을 보내지 않으므로 allowCredentials=false.
     */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.allowedOrigins()); // 정확 목록, 반사 금지
        config.setAllowedMethods(List.of("POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/passkey/**", config);
        return source;
    }
}
```

> `config.allowedOrigins = ...`(Kotlin 프로퍼티 set) → `config.setAllowedOrigins(...)`. `cors.allowedOrigins`(프로퍼티) → `cors.allowedOrigins()`(record 접근자). `listOf(...)` → `List.of(...)`. Kotlin DSL 람다 `{ a -> a... }` → Java 람다 `a -> a...`. `corsConfigurationSource()`는 Kotlin에선 public이었으나 외부 미사용이라 private로(동작 무관, 원하면 public 유지 가능 — 보수적으로 동일 가시성 위해 `public` 유지해도 무방. 여기선 private).

- [ ] **Step 4: PasskeyClientConfiguration.java**

```java
package com.crosscert.passkey.rpapp.config;

import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Supplier;

@Configuration
public class PasskeyClientConfiguration {

    /**
     * 재기동 없이 API Key 를 교체/회수 반영하기 위한 동적 키 소스.
     * api-key-file 이 설정되면 그 파일을 핫리로드하고, 아니면 api-key(env)를 폴백한다.
     */
    @Bean
    public Supplier<String> apiKeySupplier(PasskeyProperties props) {
        Duration reload = (props.apiKeyReload() != null) ? props.apiKeyReload() : Duration.ofSeconds(10);
        return new ReloadableApiKeySupplier(props.apiKeyFile(), reload, props.apiKey());
    }

    @Bean
    public PasskeyClient passkeyClient(PasskeyProperties props, Supplier<String> apiKeySupplier) {
        // SDK 의 Builder 는 필수값(baseUrl, apiKeySupplier)을 인자로 강제하고, 선택값은
        // null 을 넘기면 기본값으로 치환한다. baseUrl 은 필수 — 누락 시 fail-fast.
        if (props.baseUrl() == null) {
            throw new IllegalStateException("passkey.base-url 이 설정되지 않았습니다");
        }
        return PasskeyClient.of(
                PasskeyClientConfig.builder(props.baseUrl(), apiKeySupplier)
                        .connectTimeout(props.connectTimeout())
                        .readTimeout(props.readTimeout())
                        .jwksCacheTtl(props.jwksCacheTtl())
                        .build());
    }
}
```

> Kotlin `props.apiKeyReload ?: Duration.ofSeconds(10)` → 삼항. `props.baseUrl!!`(non-null assert, 누락 시 NPE→500 fail-fast) → 명시적 null 체크 + `IllegalStateException`(부팅 시 fail-fast, 의미 동일·메시지 개선). `Supplier<String?>` → `Supplier<String>`. sdk-java Builder API(`PasskeyClientConfig.builder(...).connectTimeout(...).build()`)는 이미 Java라 그대로.

- [ ] **Step 5: 원본 .kt 4개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/RelayKeyGuard.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplier.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/WebSecurityConfig.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.kt
```

- [ ] **Step 6: 컴파일 + config 테스트**

Run: `./gradlew :rp-app:compileJava :rp-app:compileKotlin`
Expected: BUILD SUCCESSFUL. (RegRelayCodec(Kotlin)이 아직 `props.secret`/`props.ttl`을 프로퍼티로 부르면 실패 — Task 8과 묶어야 함. WebAuthnController(Kotlin)는 `props.tenantId`/`props.issuerBase`를 record 접근자로 부름 → Kotlin은 Java record를 `props.tenantId()` 또는 synthetic property `props.tenantId`로 호출 가능하므로 통과 예상.)

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.config.*"`
Expected: PASS (ReloadableApiKeySupplierTest, RelayKeyGuardTest, PasskeyClientWiringIT).

> RegRelayCodec(Task 8)이 RelayProperties를 소비하므로, Task 6의 compileKotlin이 RegRelayCodec 때문에 실패하면 Task 8까지 진행 후 함께 검증·커밋한다.

- [ ] **Step 7: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/config/{RelayKeyGuard,ReloadableApiKeySupplier,WebSecurityConfig,PasskeyClientConfiguration}.java
git commit -m "refactor(rp-app): config 나머지 4개 Kotlin→Java 환원"
```

---

### Task 7: web 로깅 5개 + common/filter 1개

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/SecretRedactor.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/SecretMaskingConverter.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/RedactingMessageJsonProvider.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/CompactMdcConverter.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/RequestLoggingFilter.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/common/filter/TraceIdFilter.java`
- Delete: 위 6개 `.kt`
- Test(기존 .kt, Task 9에서 .java로 변환): SecretRedactorTest, CompactMdcConverterTest, PayloadLoggingTest

**Interfaces:**
- Produces:
  - `final class SecretRedactor` + `public static String redact(String)`.
  - `class SecretMaskingConverter extends MessageConverter` + `convert(ILoggingEvent)`.
  - `class RedactingMessageJsonProvider extends AbstractFieldJsonProvider<ILoggingEvent>` + `writeTo(...)`.
  - `class CompactMdcConverter extends ClassicConverter` + `convert(ILoggingEvent)`.
  - `class RequestLoggingFilter extends OncePerRequestFilter` + `public static final String ACTOR_EMAIL_ATTR`.
  - `class TraceIdFilter extends OncePerRequestFilter`.
- Consumes: logback-classic, logstash-encoder, jakarta servlet.

- [ ] **Step 1: SecretRedactor.java (object → final class + static)**

```java
package com.crosscert.passkey.rpapp.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그 메시지에서 비밀값(API key/JWT/password/bcrypt)을 마스킹하는 공유 헬퍼.
 * 텍스트 출력의 SecretMaskingConverter 와 JSON 출력의 RedactingMessageJsonProvider 가
 * 같은 로직을 공유해 drift 를 막는다.
 *
 * Mirror of com.crosscert.passkey.core.logging.SecretRedactor — rp-app does not
 * depend on :core, so the class is duplicated here. Keep the two in sync.
 */
public final class SecretRedactor {

    private SecretRedactor() {}

    // X-API-Key: pk_… header form (apply BEFORE standalone pk_ pattern).
    private static final Pattern API_KEY_HEADER =
            Pattern.compile("(?i)(X-API-Key:\\s*)(pk_[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+");

    // Bearer eyJ… JWT — keep "Bearer ", strip rest
    private static final Pattern JWT_BEARER =
            Pattern.compile("(?i)(Bearer\\s+)eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    // Standalone JWS without "Bearer " prefix
    private static final Pattern JWS_STANDALONE =
            Pattern.compile("(?<![A-Za-z0-9_/-])eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    // pk_ + 8 base64url + tail → keep prefix (11 chars), strip secret tail
    private static final Pattern API_KEY =
            Pattern.compile("pk_[A-Za-z0-9_-]{8}[A-Za-z0-9_-]+");

    // password="xxx" or password=xxx
    private static final Pattern PASSWORD_KV =
            Pattern.compile("(?i)(password)\\s*=\\s*\"?[^\\s\"\\]]+\"?");

    // bcrypt hash $2a$/$2b$/$2y$ (62 chars total)
    private static final Pattern BCRYPT =
            Pattern.compile("\\$2[ayb]\\$\\d{2}\\$[A-Za-z0-9./]{53}");

    // JSON 토큰 필드 값 마스킹 (authenticationToken/registrationToken/regRelayToken).
    private static final Pattern JSON_TOKEN_FIELD =
            Pattern.compile("(\\\\?\"(?:authenticationToken|registrationToken|regRelayToken)\\\\?\"\\s*:\\s*\\\\?\")[^\"\\\\]+");

    public static String redact(String msg) {
        if (msg == null || msg.isEmpty()) return msg;

        // Order matters: header-form first, then JWT, standalone JWS, standalone pk_, password, bcrypt, JSON field.
        String result = API_KEY_HEADER.matcher(msg).replaceAll("$1$2<redacted>");
        result = JWT_BEARER.matcher(result).replaceAll("$1<redacted>");
        result = JWS_STANDALONE.matcher(result).replaceAll("<jws-redacted>");
        result = API_KEY.matcher(result).replaceAll(m -> m.group().substring(0, 11) + "<redacted>"); // pk_ + 8 + <redacted>
        result = PASSWORD_KV.matcher(result).replaceAll("$1=<redacted>");
        result = BCRYPT.matcher(result).replaceAll("<bcrypt-redacted>");
        return JSON_TOKEN_FIELD.matcher(result).replaceAll("$1<redacted>");
    }
}
```

> 최우선 회귀 주의: Kotlin `replaceAll { m -> ... }`(함수형) → Java `Matcher.replaceAll(Function<MatchResult,String>)`(Java 9+). `m.group().substring(0, 11)` 동일. 다른 6개는 `replaceAll(String)` 그대로. 치환 순서·`$1$2<redacted>` 백레퍼런스 정확히 보존. `import Matcher`는 람다 오버로드용 — 실제론 `Pattern.matcher().replaceAll(Function)`이라 Matcher import 불요할 수 있으나 무해.

- [ ] **Step 2: SecretMaskingConverter.java**

```java
package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Pattern-based defense-in-depth redaction applied to every formatted log message.
 * Used as a custom MessageConverter in logback-spring.xml (conversionWord="msg").
 * Redaction logic is shared with JSON output via SecretRedactor.
 *
 * Sample-rp copy of com.crosscert.passkey.core.logging.SecretMaskingConverter.
 */
public class SecretMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedactor.redact(super.convert(event));
    }
}
```

- [ ] **Step 3: RedactingMessageJsonProvider.java**

```java
package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import net.logstash.logback.composite.AbstractFieldJsonProvider;
import net.logstash.logback.composite.JsonWritingUtils;

import java.io.IOException;

/**
 * JSON 로그의 message 필드에 텍스트 모드와 동일한 비밀값 마스킹을 적용한다.
 * LogstashEncoder 기본 message provider 는 마스킹을 우회하므로 이걸로 교체한다.
 */
public class RedactingMessageJsonProvider extends AbstractFieldJsonProvider<ILoggingEvent> {

    public RedactingMessageJsonProvider() {
        setFieldName("message");
    }

    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
        JsonWritingUtils.writeStringField(
                generator, getFieldName(),
                SecretRedactor.redact(event.getFormattedMessage()));
    }
}
```

> Kotlin `init { fieldName = "message" }` → 생성자 `setFieldName("message")`. `fieldName`(프로퍼티) → `getFieldName()`. `event.formattedMessage` → `event.getFormattedMessage()`.

- [ ] **Step 4: CompactMdcConverter.java**

```java
package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * 값이 있는 MDC 키만 [traceId=.. tenantId=.. ..] 로 묶어 출력한다. 빈 키 생략, 전부 비면 "".
 *
 * core 의 CompactMdcConverter Java 트윈 — rp-app 은 :core 비의존이라 중복.
 */
public class CompactMdcConverter extends ClassicConverter {

    private static final String[] KEYS = {"traceId", "tenantId", "actorEmail", "apiKeyPrefix"};
    private static final int MAX_VALUE_LEN = 16;

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String key : KEYS) {
            String v = mdc.get(key);
            if (v == null || v.isBlank()) continue;
            if (v.length() > MAX_VALUE_LEN) v = v.substring(0, MAX_VALUE_LEN);
            sb.append(sb.length() == 0 ? "[" : " ").append(key).append('=').append(v);
        }
        return sb.length() == 0 ? "" : sb.append(']').toString();
    }
}
```

> `private companion object { val KEYS; const MAX }` → `private static final`. `v.isNullOrBlank()` → `v == null || v.isBlank()`. `sb.isEmpty()` → `sb.length() == 0`. CompactMdcConverterTest(Task 9)가 고정 순서·16자 캡·blank 처리를 검증.

- [ ] **Step 5: RequestLoggingFilter.java**

```java
package com.crosscert.passkey.rpapp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * rp-app local twin of core's RequestLoggingFilter. 한 줄 요약(method/path/status/durMs)은
 * 항상 INFO/WARN/ERROR 로, request/response 본문은 전용 payload 로거에 DEBUG 로 남긴다.
 * 본문은 2KB 캡 + %msg SecretMaskingConverter 마스킹(삼중 방어).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Logger payloadLog = LoggerFactory.getLogger("com.crosscert.passkey.payload");

    private static final String MDC_ACTOR_EMAIL = "actorEmail";

    /** Mirror of core's RequestLoggingFilter.ACTOR_EMAIL_ATTR. */
    public static final String ACTOR_EMAIL_ATTR = "com.crosscert.passkey.actorEmail";

    private static final Set<String> EXCLUDED_PATHS = Set.of("/actuator/health");
    private static final int MAX_BODY = 2048;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        boolean debugBody = payloadLog.isDebugEnabled();
        HttpServletRequest wrappedReq = debugBody ? new ContentCachingRequestWrapper(req) : req;
        HttpServletResponse wrappedRes = debugBody ? new ContentCachingResponseWrapper(res) : res;

        long startNs = System.nanoTime();
        try {
            chain.doFilter(wrappedReq, wrappedRes);
        } finally {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            int status = wrappedRes.getStatus();
            boolean actorPut = populateActorEmailMdc(req);
            try {
                String msg = String.format(
                        "request: method=%s path=%s status=%d durMs=%d",
                        req.getMethod(), path, status, durMs);
                if (status >= 500) log.error(msg);
                else if (status >= 400) log.warn(msg);
                else log.info(msg);

                if (debugBody) {
                    logBody((ContentCachingRequestWrapper) wrappedReq, (ContentCachingResponseWrapper) wrappedRes);
                }
            } finally {
                if (debugBody) {
                    ((ContentCachingResponseWrapper) wrappedRes).copyBodyToResponse();
                }
                if (actorPut) MDC.remove(MDC_ACTOR_EMAIL);
            }
        }
    }

    private void logBody(ContentCachingRequestWrapper req, ContentCachingResponseWrapper res) {
        String reqBody = cap(new String(req.getContentAsByteArray(), StandardCharsets.UTF_8));
        String resBody = cap(new String(res.getContentAsByteArray(), StandardCharsets.UTF_8));
        if (!reqBody.isEmpty()) payloadLog.debug("req body: {}", reqBody);
        if (!resBody.isEmpty()) payloadLog.debug("resp body: {}", resBody);
    }

    private String cap(String s) {
        return s.length() <= MAX_BODY ? s : s.substring(0, MAX_BODY) + "…[truncated " + s.length() + "]";
    }

    private boolean populateActorEmailMdc(HttpServletRequest req) {
        try {
            Object v = req.getAttribute(ACTOR_EMAIL_ATTR);
            if (v == null) return false;
            String name = v.toString();
            if (name.isBlank()) return false;
            MDC.put(MDC_ACTOR_EMAIL, name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
```

> 회귀 주의: `companion object const ACTOR_EMAIL_ATTR` → `public static final`. `String(bytes, Charsets.UTF_8)` → `new String(bytes, StandardCharsets.UTF_8)`. `"…[truncated ${s.length}]"` → 문자열 연결. `wrappedRes.status` → `getStatus()`. `req.getAttribute(...) ?: return false` → null 체크. PayloadLoggingTest(Task 9)가 DEBUG body 로깅·INFO 억제·2KB 캡을 검증.

- [ ] **Step 6: TraceIdFilter.java**

```java
package com.crosscert.passkey.rpapp.common.filter;

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
import java.util.UUID;

/**
 * X-Trace-Id 헤더가 있으면 사용, 없으면 새로 발급. MDC 키 "traceId" 는
 * SDK 의 PasskeyClientConfig.MDC_TRACE_ID_KEY 와 반드시 동일해야 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        String traceId = (header != null && !header.isBlank())
                ? header
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
```

> `req.getHeader(HEADER)?.takeIf { !it.isBlank() } ?: UUID...` → null/blank 체크 삼항. `.replace("-", "")`(Kotlin String.replace) → Java `String.replace(CharSequence, CharSequence)` 동일.

- [ ] **Step 7: 원본 .kt 6개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/SecretRedactor.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/SecretMaskingConverter.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/RedactingMessageJsonProvider.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverter.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/RequestLoggingFilter.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/common/filter/TraceIdFilter.kt
```

- [ ] **Step 8: 컴파일 검증**

Run: `./gradlew :rp-app:compileJava :rp-app:compileKotlin`
Expected: BUILD SUCCESSFUL. (이 6개는 다른 rp-app 클래스에 거의 의존하지 않으므로 독립 컴파일. 단 SecretRedactorTest/CompactMdcConverterTest/PayloadLoggingTest는 아직 .kt라 `compileTestKotlin`에서 이 Java 클래스를 호출 — Kotlin이 Java를 호출하므로 통과.)

- [ ] **Step 9: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/{SecretRedactor,SecretMaskingConverter,RedactingMessageJsonProvider,CompactMdcConverter,RequestLoggingFilter}.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/common/filter/TraceIdFilter.java
git commit -m "refactor(rp-app): web 로깅 5개 + TraceIdFilter Kotlin→Java 환원"
```

---

### Task 8: web 컨트롤러·코덱 4개

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/PageController.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WellKnownController.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java`
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodec.java`
- Delete: 위 4개 `.kt`
- Test(기존): WebAuthnControllerTest.java, WellKnownControllerTest.java, RegRelayCodecTest.java

**Interfaces:**
- Produces:
  - `class PageController` — `index()/register()/login()` → 뷰명 String.
  - `class WellKnownController` — `assetLinks()`, `appleAppSiteAssociation()`.
  - `class WebAuthnController` — 4 엔드포인트 + `public static String normalizeTenantId(String)`.
  - `class RegRelayCodec` — `String encode(String,String,String,String)`, `RegRelay decode(String)`, 중첩 `record RegRelay(String registrationToken, String userHandle, String username, String displayName)`.
- Consumes: Task 1~7 전부(ApiResponse, dto, InMemoryUserStore, properties, RegRelayCodec, sdk-java).

- [ ] **Step 1: PageController.java**

```java
package com.crosscert.passkey.rpapp.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() { return "index"; }

    @GetMapping("/register")
    public String register() { return "register"; }

    @GetMapping("/login")
    public String login() { return "login"; }
}
```

- [ ] **Step 2: WellKnownController.java**

```java
package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.config.WellKnownProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 네이티브 앱 패스키용 Well-Known URI 호스팅. OS/CDN 이 직접 가져가 "도메인 → 앱" 소유를
 * 검증하는 표준 wire format 이므로 ApiResponse envelope 없이 표준 JSON 을 직접 반환한다.
 */
@RestController
public class WellKnownController {

    private static final Logger log = LoggerFactory.getLogger(WellKnownController.class);

    /** 패스키 자동완성을 위한 표준 relation 조합. */
    private static final List<String> RELATIONS = List.of(
            "delegate_permission/common.handle_all_urls",
            "delegate_permission/common.get_login_creds");

    private final WellKnownProperties props;

    public WellKnownController(WellKnownProperties props) {
        this.props = props;
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> assetLinks() {
        List<WellKnownProperties.AndroidApp> androids =
                props.android() != null ? props.android() : List.of();
        List<Map<String, Object>> statements = new ArrayList<>();
        for (WellKnownProperties.AndroidApp app : androids) {
            List<String> fingerprints =
                    app.sha256Fingerprints() != null ? app.sha256Fingerprints() : List.of();
            statements.add(Map.of(
                    "relation", RELATIONS,
                    "target", Map.of(
                            "namespace", "android_app",
                            "package_name", app.packageName(),
                            "sha256_cert_fingerprints", fingerprints)));
        }
        if (statements.isEmpty()) {
            log.warn("assetlinks.json: Android 앱이 설정되지 않았습니다 — Android 패스키가 동작하지 않습니다 (rp-app.well-known.android 확인)");
        } else if (log.isDebugEnabled()) {
            log.debug("assetlinks served: androidApps={}", statements.size());
        }
        return statements;
    }

    @GetMapping(value = "/.well-known/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> appleAppSiteAssociation() {
        List<String> apps = List.of();
        if (props.ios() != null && props.ios().appIds() != null) {
            apps = props.ios().appIds();
        }
        if (apps.isEmpty()) {
            log.warn("apple-app-site-association: iOS 앱이 설정되지 않았습니다 — iOS 패스키가 동작하지 않습니다 (rp-app.well-known.ios.app-ids 확인)");
        } else if (log.isDebugEnabled()) {
            log.debug("aasa served: iosApps={}", apps.size());
        }
        return Map.of("webcredentials", Map.of("apps", apps));
    }
}
```

> 회귀 주의: Kotlin `.orEmpty()`(null→빈 리스트) → null 체크 + `List.of()`. `mapOf("a" to b)` → `Map.of("a", b)`. `props.android.orEmpty().map { app -> ... }` → for 루프 + ArrayList(중첩 Map.of). `props.ios?.appIds.orEmpty()` → null-safe 체인. record 접근자 `app.packageName()`/`app.sha256Fingerprints()`/`props.ios()`/`props.android()`. WellKnownControllerTest가 JSON 구조를 검증.

- [ ] **Step 3: WebAuthnController.java**

```java
package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.crosscert.passkey.rpapp.common.response.ApiResponse;
import com.crosscert.passkey.rpapp.config.PasskeyProperties;
import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.rpapp.user.RpAppUser;
import com.crosscert.passkey.rpapp.web.dto.LoginCompleteReq;
import com.crosscert.passkey.rpapp.web.dto.LoginOptionsResp;
import com.crosscert.passkey.rpapp.web.dto.LoginResultResp;
import com.crosscert.passkey.rpapp.web.dto.LoginStartReq;
import com.crosscert.passkey.rpapp.web.dto.RegisterCompleteReq;
import com.crosscert.passkey.rpapp.web.dto.RegisterOptionsResp;
import com.crosscert.passkey.rpapp.web.dto.RegisterStartReq;
import com.crosscert.passkey.rpapp.web.relay.RegRelayCodec;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.sdk.dto.AuthenticationStartRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationStartResponse;
import com.crosscert.passkey.sdk.dto.RegistrationFinishRequest;
import com.crosscert.passkey.sdk.dto.RegistrationFinishResponse;
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import jakarta.validation.Valid;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/passkey")
public class WebAuthnController {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnController.class);
    private static final Pattern HEX32 = Pattern.compile("(?i)[0-9a-f]{32}");

    private final PasskeyClient passkey;
    private final InMemoryUserStore users;
    private final PasskeyProperties props;
    private final RegRelayCodec relay;

    public WebAuthnController(PasskeyClient passkey, InMemoryUserStore users,
                             PasskeyProperties props, RegRelayCodec relay) {
        this.passkey = passkey;
        this.users = users;
        this.props = props;
        this.relay = relay;
    }

    /**
     * tenantId 를 표준 UUID 문자열(소문자+대시)로 정규화한다.
     * UUID 대시 형식 또는 RAW(16) hex 32자 모두 허용. 파싱 불가하면 입력을 그대로 반환.
     */
    public static String normalizeTenantId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 대시 없는 32자 hex → UUID 대시 형식으로
        if (HEX32.matcher(s).matches()) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        try {
            return UUID.fromString(s).toString(); // 항상 소문자+대시
        } catch (IllegalArgumentException e) {
            return raw; // UUID 가 아니면 원본 비교에 맡김
        }
    }

    /** Last 12 chars of a base64url id for correlation — never the full id.
     *  Short ids (≤12 chars) are masked to "***". */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }

    // ── Registration ─────────────────────────────────────────────

    @PostMapping("/register/begin")
    public ApiResponse<RegisterOptionsResp> registerOptions(@Valid @RequestBody RegisterStartReq req) {
        log.info("register/options entry: usernamePresent={}", req.username() != null);
        String userHandle = users.createPending(req.username(), req.displayName());
        RegistrationStartResponse sdkResp;
        try {
            sdkResp = passkey.registrationStart(
                    new RegistrationStartRequest(userHandle, req.displayName(), req.username()));
        } catch (RuntimeException e) {
            log.warn("register/options upstream-failed: cause={}", e.toString());
            throw e;
        }
        log.info("register/options ok: userHandle={}", idTail(userHandle));
        String regRelayToken = relay.encode(
                sdkResp.registrationToken(), userHandle, req.username(), req.displayName());
        return ApiResponse.ok(
                new RegisterOptionsResp(sdkResp.publicKeyCredentialCreationOptions(), regRelayToken));
    }

    @PostMapping("/register/finish")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req) {
        RegRelayCodec.RegRelay r;
        try {
            r = relay.decode(req.regRelayToken());
        } catch (IllegalArgumentException e) {
            log.warn("register/complete failed: reason=relay-invalid cause={}", e.getMessage());
            throw new BusinessException(ErrorCode.PENDING_REG_MISSING, e.getMessage());
        }
        log.info("register/complete entry: userHandle={}", idTail(r.userHandle()));
        // upstream finish 전 username 점유 선검사.
        if (users.isUsernameTakenByOther(r.username(), r.userHandle())) {
            log.warn("register/complete failed: reason=username-taken userHandle={}", idTail(r.userHandle()));
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        RegistrationFinishResponse fin;
        try {
            fin = passkey.registrationFinish(
                    new RegistrationFinishRequest(r.registrationToken(), req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("register/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        users.confirmRegistration(r.userHandle(), r.username(), r.displayName(), fin.credentialId());
        log.info("register/complete ok: userHandle={} credentialId={}",
                idTail(r.userHandle()), idTail(fin.credentialId()));
        return ApiResponse.ok("Passkey registered", fin);
    }

    // ── Login ────────────────────────────────────────────────────

    @PostMapping("/authenticate/begin")
    public ApiResponse<LoginOptionsResp> loginOptions(@RequestBody LoginStartReq req) {
        log.info("login/options entry: flow={}", req.username() == null ? "discoverable" : "typed");
        String userHandle = (req.username() == null)
                ? null
                : users.findByUsername(req.username()).map(RpAppUser::userHandle).orElse(null);
        AuthenticationStartResponse sdkResp;
        try {
            sdkResp = passkey.authenticationStart(new AuthenticationStartRequest(userHandle));
        } catch (RuntimeException e) {
            log.warn("login/options upstream-failed: cause={}", e.toString());
            throw e;
        }
        log.info("login/options ok: userHandlePresent={}", userHandle != null);
        return ApiResponse.ok(new LoginOptionsResp(
                sdkResp.publicKeyCredentialRequestOptions(),
                sdkResp.authenticationToken()));
    }

    @PostMapping("/authenticate/finish")
    public ApiResponse<LoginResultResp> loginComplete(@Valid @RequestBody LoginCompleteReq req) {
        log.info("login/complete entry");
        AuthenticationFinishResponse fin;
        try {
            fin = passkey.authenticationFinish(
                    new AuthenticationFinishRequest(req.authenticationToken(), req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("login/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        IdTokenClaims claims;
        try {
            claims = passkey.verifyIdToken(fin.idToken());
        } catch (RuntimeException e) {
            log.warn("login/complete failed: reason=id-token-verify-failed cause={}", e.toString());
            throw e;
        }

        // tenantId 표기 차이로 인한 거짓 mismatch 방지: 비교 전 양쪽 정규화.
        String expectedTenant = normalizeTenantId(props.tenantId());

        // iss = "<issuerBase>/<tenantId>". issuerBase 누락 시 NPE→500 fail-fast(원본 Java 동작).
        if (props.issuerBase() == null) {
            throw new NullPointerException("passkey.issuer-base 미설정");
        }
        String issPrefix = props.issuerBase().toString();
        String tokenIss = claims.iss();
        boolean issOk = tokenIss != null
                && tokenIss.startsWith(issPrefix + "/")
                && Objects.equals(
                        normalizeTenantId(tokenIss.substring((issPrefix + "/").length())), expectedTenant);
        if (!issOk) {
            log.warn("login/complete failed: reason=iss-mismatch expectedPrefix={} expectedTenant={} got={}",
                    issPrefix, expectedTenant, tokenIss);
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "iss mismatch");
        }
        if (!Objects.equals(expectedTenant, normalizeTenantId(claims.aud()))) {
            log.warn("login/complete failed: reason=aud-mismatch expected={} got={}",
                    expectedTenant, claims.aud());
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "aud mismatch");
        }

        // 검증 통과 ID Token 은 항상 sub(opaque userHandle)를 갖는다.
        RpAppUser user = users.findByUserHandle(claims.sub())
                .orElseThrow(() -> {
                    log.warn("login/complete failed: reason=unknown-sub subTail={}", idTail(claims.sub()));
                    return new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub");
                });
        log.info("login/complete ok: subTail={} userHandle={}",
                idTail(claims.sub()), idTail(user.userHandle()));
        return ApiResponse.ok(new LoginResultResp(true, user.userHandle(), user.displayName()));
    }
}
```

> **회귀 주의(다수):**
> - `normalizeTenantId`/`idTail`은 `companion object @JvmStatic` → `public static`/`private static`. Regex는 `Pattern` 상수로(`s.matches(Regex(...))` → `HEX32.matcher(s).matches()`).
> - `req.username!!`/`req.displayName!!`(@Valid로 검증된 non-null) → `req.username()`/`req.displayName()`(record 접근자). 검증은 `@Valid`가 진입 전 수행하므로 null 아님 보존.
> - `req.publicKeyCredential!!` → `req.publicKeyCredential()`.
> - `props.issuerBase!!.toString()`(누락 시 NPE→500) → null 체크 후 `NullPointerException`(원본 Java fail-fast 의미 정확 보존 — `IllegalStateException` 아님에 주의).
> - `tokenIss != null && tokenIss.startsWith("$issPrefix/") && normalize(...) == expectedTenant` → `Objects.equals(...)`(Kotlin `==`=equals). 문자열 비교를 `==`(참조)로 쓰면 회귀!
> - `claims.sub!!`/`claims.iss`/`claims.aud`(sdk-java Java record) → `claims.sub()`/`claims.iss()`/`claims.aud()`.
> - `.map { it.userHandle }` → `.map(RpAppUser::userHandle)`.
> - `orElseThrow { ... }`(람다) → `orElseThrow(() -> { ...; return new BusinessException(...); })`.
> - sdk-java DTO 접근자(`sdkResp.registrationToken()`/`fin.credentialId()`/`fin.idToken()` 등)는 모두 Java record라 `()` 형식. 정확한 컴포넌트명은 Step 5 표 참조(`IdTokenClaims`는 `sdk.idtoken` 패키지).

- [ ] **Step 4: RegRelayCodec.java**

```java
package com.crosscert.passkey.rpapp.web.relay;

import com.crosscert.passkey.rpapp.config.RelayProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 등록 relay 토큰 코덱(spec §5). {registrationToken, userHandle, username, displayName, exp} 를
 * HMAC-SHA256 으로 서명한 불투명 토큰 "base64url(payloadJson).base64url(hmac)" 을 만들고 검증한다.
 * 무상태(자기완결). username/displayName 을 함께 봉인해 finish 가 pending 없이 user 를 확정한다(P0-4).
 */
@Component
public class RegRelayCodec {

    /** 복원된 relay payload. */
    public record RegRelay(
            String registrationToken,
            String userHandle,
            String username,
            String displayName
    ) {}

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper mapper;
    private final byte[] key;
    private final long ttlSeconds;

    public RegRelayCodec(RelayProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.key = props.secret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.ttl().toSeconds();
    }

    /** {rt, uh, un, dn, exp} 를 서명한 relay 토큰 생성. */
    public String encode(String registrationToken, String userHandle, String username, String displayName) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        ObjectNodePayload p = new ObjectNodePayload(registrationToken, userHandle, username, displayName, exp);
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(p);
        } catch (Exception e) {
            throw new IllegalStateException("relay encode failed", e);
        }
        String p64 = B64.encodeToString(payload);
        String sig = B64.encodeToString(hmac(p64));
        return p64 + "." + sig;
    }

    /** relay 토큰 검증·복원. 서명 불일치/만료/형식오류면 IllegalArgumentException. */
    public RegRelay decode(String token) {
        if (token == null) throw new IllegalArgumentException("relay token missing");
        int dot = token.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("relay token malformed");
        String p64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] expected = hmac(p64);
        byte[] actual;
        try {
            actual = B64D.decode(sig);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("relay token bad signature encoding");
        }
        // 상수시간 비교(타이밍 공격 방지).
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("relay token bad signature");
        }
        ObjectNodePayload p;
        try {
            p = mapper.readValue(B64D.decode(p64), ObjectNodePayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("relay token bad payload");
        }
        if (p.exp() < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("relay token expired");
        }
        // 필수 4필드 검증(레거시 토큰 un/dn null 거부 — confirmRegistration NPE 방지).
        if (p.rt() == null || p.uh() == null || p.un() == null || p.dn() == null) {
            throw new IllegalArgumentException("relay token incomplete payload");
        }
        return new RegRelay(p.rt(), p.uh(), p.un(), p.dn());
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("relay hmac failed", e);
        }
    }

    /**
     * 직렬화 payload. 필드명을 짧게(rt/uh/un/dn/exp) 유지. @JsonCreator/@JsonProperty 로
     * 생성자 바인딩을 명시해 jackson-module-kotlin 유무와 무관하게 record 와 동일 복원.
     */
    record ObjectNodePayload(
            @JsonProperty("rt") String rt,
            @JsonProperty("uh") String uh,
            @JsonProperty("un") String un,
            @JsonProperty("dn") String dn,
            @JsonProperty("exp") long exp
    ) {
        @JsonCreator
        ObjectNodePayload {}
    }
}
```

> **회귀 주의:** `companion object`(HMAC_ALG/B64/B64D) → `static final`. `props.secret`/`props.ttl`(프로퍼티) → `props.secret()`/`props.ttl()`. `Instant.now().epochSecond` → `getEpochSecond()`. `"$p64.$sig"` → `p64 + "." + sig`. `ObjectNodePayload::class.java` → `ObjectNodePayload.class`. `internal data class ObjectNodePayload @JsonCreator constructor(...)` → record + compact `@JsonCreator` 생성자(또는 컴포넌트 `@JsonProperty`만으로 충분 — Jackson은 single-arg가 아닌 record를 `@JsonProperty`로 바인딩. `@JsonCreator`는 안전하게 명시). `encode`의 인자 순서(rt, uh, un, dn, exp)와 `decode`의 `new RegRelay(rt, uh, un, dn)` 매핑 순서 정확 보존. RegRelayCodecTest가 round-trip·변조·만료·incomplete를 검증.

- [ ] **Step 5: sdk-java DTO 접근자명 대조(확정됨)**

플랜 작성 시 sdk-java(Java record)의 컴포넌트명을 확인했고 Step 3 코드는 이미 일치한다(아래 표). 실행자는 이 표대로 호출하면 되며, 추가 grep은 선택이다.

| record | 컴포넌트(접근자 `()`) |
|---|---|
| `RegistrationStartResponse` | `registrationToken()`, `publicKeyCredentialCreationOptions()` |
| `RegistrationFinishResponse` | `credentialId()`, `aaguid()`, `attestationFormat()`, `createdAt()` |
| `AuthenticationStartResponse` | `authenticationToken()`, `publicKeyCredentialRequestOptions()` |
| `AuthenticationFinishResponse` | `idToken()`, `tokenType()`, `expiresIn()` |
| `IdTokenClaims` | `iss()`, `sub()`, `aud()`, `iat()`, `exp()`, `amr()`, `credId()`, `aaguid()` |
| 요청 DTO | `RegistrationStartRequest(userHandle, displayName, username)`, `RegistrationFinishRequest(registrationToken, publicKeyCredential)`, `AuthenticationStartRequest(userHandle)`, `AuthenticationFinishRequest(authenticationToken, publicKeyCredential)` — 생성자 인자 순서 |

> `IdTokenClaims`는 `com.crosscert.passkey.sdk.idtoken` 패키지(dto 아님)에 있다 — import 주의.

- [ ] **Step 6: 원본 .kt 4개 삭제**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/PageController.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/WellKnownController.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/WebAuthnController.kt \
       rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/web/relay/RegRelayCodec.kt
```

- [ ] **Step 7: 컴파일 + 관련 테스트**

Run: `./gradlew :rp-app:compileJava`
Expected: BUILD SUCCESSFUL. (이 시점에 main/kotlin은 RpAppApplication.kt 1개만 남음.)

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.web.WebAuthnControllerTest" --tests "com.crosscert.passkey.rpapp.web.WellKnownControllerTest" --tests "com.crosscert.passkey.rpapp.web.relay.RegRelayCodecTest"`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/
git commit -m "refactor(rp-app): web 컨트롤러·RegRelayCodec Kotlin→Java 환원"
```

---

### Task 9: RpAppApplication + 테스트 3개 변환 + build.gradle.kts 정리 → 전체 테스트

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/RpAppApplication.java`
- Create: `rp-app/src/test/java/com/crosscert/passkey/rpapp/web/SecretRedactorTest.java`
- Create: `rp-app/src/test/java/com/crosscert/passkey/rpapp/web/CompactMdcConverterTest.java`
- Create: `rp-app/src/test/java/com/crosscert/passkey/rpapp/web/PayloadLoggingTest.java`
- Delete: `RpAppApplication.kt`, 위 3개 `.kt` 테스트
- Modify: `rp-app/build.gradle.kts`

**Interfaces:**
- Produces: `class RpAppApplication` + `public static void main(String[])`.
- Consumes: 전체 변환된 Java 클래스.

- [ ] **Step 1: RpAppApplication.java**

```java
package com.crosscert.passkey.rpapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RpAppApplication {

    public static void main(String[] args) {
        // 배포 JVM 의 TZ 설정에 의존하지 않도록 기본 타임존을 KST 로 고정한다.
        // runApplication 이전에 호출해야 모든 빈이 KST 로 초기화된다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(RpAppApplication.class, args);
    }
}
```

> `fun main + runApplication<RpAppApplication>(*args)` → `static void main + SpringApplication.run(RpAppApplication.class, args)`. `TimeZone.setDefault`를 run 전에 호출하는 순서 보존.

- [ ] **Step 2: SecretRedactorTest.java (변환)**

기존 `SecretRedactorTest.kt`(180줄)의 모든 `@Test`를 Java로 옮긴다. backtick 메서드명은 카멜케이스로 바꾼다. 패턴:

```java
package com.crosscert.passkey.rpapp.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    // ── 1. X-API-Key 헤더 형식 ────────────────────────────────────────────────

    @Test
    void redactsApiKeyInHeader() {
        String out = SecretRedactor.redact("attempting X-API-Key: pk_devacme0longsecretvalue");
        assertThat(out).contains("pk_devacme0");
        assertThat(out).contains("<redacted>");
        assertThat(out).doesNotContain("longsecretvalue");
    }

    @Test
    void redactsApiKeyHeaderCaseInsensitive() {
        String out = SecretRedactor.redact("x-api-key: pk_abcd1234efgh5678IJKL");
        assertThat(out).contains("pk_abcd1234");
        assertThat(out).contains("<redacted>");
        assertThat(out).doesNotContain("efgh5678IJKL");
    }

    // ... 원본 .kt 의 나머지 @Test 전부 동일 변환(JWT/JWS/pk_/password/bcrypt/JSON token/null/empty)
}
```

> 원본 `SecretRedactorTest.kt`를 읽어 모든 케이스를 빠짐없이 옮긴다. `assertThat`은 동일(AssertJ). Kotlin `"..."` 문자열은 Java 그대로. 변환 후 누락 케이스 없는지 원본과 1:1 대조.

- [ ] **Step 3: CompactMdcConverterTest.java (변환)**

```java
package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactMdcConverterTest {

    private String convert(Map<String, String> mdc) {
        CompactMdcConverter c = new CompactMdcConverter();
        c.start();
        LoggingEvent ev = new LoggingEvent();
        ev.setLevel(Level.INFO);
        ev.setMessage("x");
        ev.setMDCPropertyMap(mdc);
        return c.convert(ev);
    }

    @Test
    void emptyMdc_returnsEmptyString() {
        assertThat(convert(Map.of())).isEmpty();
    }

    @Test
    void onlyTraceId_returnsOnlyThatKey() {
        assertThat(convert(Map.of("traceId", "fae20e9bec04")))
                .isEqualTo("[traceId=fae20e9bec04]");
    }

    @Test
    void multipleKeys_renderInFixedOrder() {
        assertThat(convert(Map.of(
                "actorEmail", "a@x.com",
                "traceId", "abc",
                "tenantId", "t1")))
                .isEqualTo("[traceId=abc tenantId=t1 actorEmail=a@x.com]");
    }

    @Test
    void longValue_isCappedTo16Chars() {
        assertThat(convert(Map.of("traceId", "0123456789abcdefGHIJ")))
                .isEqualTo("[traceId=0123456789abcdef]");
    }

    @Test
    void blankValue_isTreatedAsAbsent() {
        assertThat(convert(Map.of("traceId", "", "tenantId", "t1")))
                .isEqualTo("[tenantId=t1]");
    }
}
```

> `ev.level = Level.INFO`(Kotlin 프로퍼티 set) → `ev.setLevel(...)`. `ev.message = "x"` → `ev.setMessage("x")`. `mapOf("a" to b)` → `Map.of("a", b)`. `emptyMap()` → `Map.of()`.

- [ ] **Step 4: PayloadLoggingTest.java (변환)**

```java
package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadLoggingTest {

    private final Logger payloadLogger =
            (Logger) LoggerFactory.getLogger("com.crosscert.passkey.payload");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private void attach(Level level) {
        appender.list.clear();
        appender.start();
        payloadLogger.setLevel(level);
        payloadLogger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        payloadLogger.detachAppender(appender);
        appender.stop();
    }

    private void runFilter(String body) throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/passkey/register/finish");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {
            // 다운스트림이 요청 본문을 소비해야 ContentCachingRequestWrapper 가 캐싱한다.
            ((HttpServletRequest) rq).getInputStream().readAllBytes();
        });
    }

    @Test
    void debugLevel_logsRequestBody() throws Exception {
        attach(Level.DEBUG);
        runFilter("{\"hello\":\"world\"}");
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(m -> m.contains("hello"));
    }

    @Test
    void infoLevel_doesNotLogRequestBody() throws Exception {
        attach(Level.INFO);
        runFilter("{\"hello\":\"world\"}");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void longBody_isCappedTo2kb() throws Exception {
        attach(Level.DEBUG);
        runFilter("x".repeat(5000));
        var payloadMsgs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("req body"))
                .toList();
        assertThat(payloadMsgs).isNotEmpty();
        assertThat(payloadMsgs.get(0).length()).isLessThan(2200);
    }
}
```

> `as Logger` → `(Logger)` 캐스트. `payloadLogger.level = level` → `setLevel`. `req.contentType = ...` → `setContentType`. `FilterChain { rq, rs -> ... }`(SAM) → Java 람다. `it.formattedMessage` → `ILoggingEvent::getFormattedMessage`. `.first()` → `.get(0)`. `"x".repeat(5000)`은 Java도 동일(`String.repeat`).

- [ ] **Step 5: 원본 .kt 삭제(RpAppApplication + 테스트 3개)**

```bash
git rm rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/RpAppApplication.kt \
       rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/SecretRedactorTest.kt \
       rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/CompactMdcConverterTest.kt \
       rp-app/src/test/kotlin/com/crosscert/passkey/rpapp/web/PayloadLoggingTest.kt
```

- [ ] **Step 6: 빈 kotlin 디렉토리 정리 확인**

Run: `find rp-app/src -name "*.kt"`
Expected: 출력 없음(0개). 남아 있으면 누락된 파일을 변환한다.

- [ ] **Step 7: build.gradle.kts 정리**

`rp-app/build.gradle.kts`를 다음으로 교체:

```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

// group / version / toolchain / repositories 모두 root allprojects + subprojects 가 처리.

dependencies {
    implementation(project(":sdk-java"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")

    implementation(rootProject.libs.logstash.logback.encoder)
    runtimeOnly("org.codehaus.janino:janino:3.1.12")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // RpAppSmokeIT 용 webauthn4j-test (ClientPlatform + PackedAuthenticator).
    testImplementation(rootProject.libs.webauthn4j.test)
    // junit-platform-launcher 는 root subprojects 가 자동 적용
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("rp-app.jar")
    // 실행 가능한 jar 를 루트 deploy/ 에 모아 배포 편의를 높인다.
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("deploy"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Java 17 UUID reflection access (passkey-app 와 동일 패턴)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
```

> 제거: `alias(libs.plugins.kotlin.jvm)`, `alias(libs.plugins.kotlin.spring)`, `implementation("...jackson-module-kotlin")`, `implementation(kotlin("stdlib"))`, `tasks.withType<KotlinCompile> { javaParameters }` 블록. 유지: java 플러그인·sdk-java·spring starters·logstash·janino·테스트 의존성·bootJar·test 설정.

- [ ] **Step 8: 전체 모듈 테스트**

Run: `./gradlew :rp-app:clean :rp-app:test`
Expected: BUILD SUCCESSFUL. 모든 테스트 통과:
- Java(기존 8): RpAppSmokeIT, ReloadableApiKeySupplierTest, RelayKeyGuardTest, PasskeyClientWiringIT, WellKnownControllerTest, WebAuthnControllerTest, InMemoryUserStoreTest, RegRelayCodecTest
- 변환분(3): SecretRedactorTest, CompactMdcConverterTest, PayloadLoggingTest

> 실패 시 base worktree(`git stash` 또는 main)와 대조해 pre-existing 여부 확인. SliceConfig/Oracle 관련이면 무관(전체 build 함정), rp-app 자체 테스트면 회귀.

- [ ] **Step 9: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/RpAppApplication.java \
        rp-app/src/test/java/com/crosscert/passkey/rpapp/web/ \
        rp-app/build.gradle.kts
git commit -m "refactor(rp-app): RpAppApplication·테스트 3개 Java 변환 + Kotlin 플러그인 제거"
```

---

### Task 10: 실제 부팅 검증 + 머지

**Files:** (코드 변경 없음 — 검증·머지만)

- [ ] **Step 1: 컴파일 잔재 확인**

Run: `find rp-app/src -name "*.kt"; grep -rn "kotlin" rp-app/build.gradle.kts`
Expected: 둘 다 출력 없음(Kotlin 완전 제거).

- [ ] **Step 2: 부팅 검증 (local 프로필)**

Run (background 또는 별도 터미널):
```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :rp-app:bootRun
```
Expected 확인 항목(로그):
- 애플리케이션 정상 기동(`Started RpAppApplication`).
- `@ConfigurationProperties` 바인딩 정상(passkey/rp.relay/rp.cors/rp-app.well-known 바인딩 에러 없음).
- RelayKeyGuard가 local 프로필에서 DEMO_SECRET 허용(IllegalStateException 없음).
- 시큐리티 체인 등록(STATELESS, CSRF off).
- `GET /` / `/.well-known/assetlinks.json` 200 응답(curl 또는 browse).

> 부팅 후 `Ctrl+C` 또는 background process kill로 종료. 실제 passkey 등록/로그인 e2e는 본 검증 범위 밖(passkey-app/redis 필요 — 비목표). 바인딩·기동·정적 엔드포인트 확인까지.

- [ ] **Step 3: base 대조(선택, 회귀 의심 시)**

`:rp-app:test`가 main(변환 전)에서도 같은 실패를 내는지 base worktree로 확인. rp-app 자체 테스트가 main에선 green이고 변환 후 red면 회귀 → 해당 Task로 복귀.

- [ ] **Step 4: 머지**

REQUIRED SUB-SKILL: superpowers:finishing-a-development-branch 로 main에 `--no-ff` 머지한다. 충돌 시 메모리(worktree stale base) 참고해 union 해소.

- [ ] **Step 5: 메모리 기록**

`MEMORY.md`에 rp-app Java 환원 완료 1줄 추가 + `project_rp_app_java_revert_done.md` 작성(sdk-java 환원과 짝). 핵심 교훈: `==`→`Objects.equals` 회귀 주의, properties scan 경로라 record 안전, SecretRedactor 람다 replaceAll, issuerBase NPE fail-fast 보존.

---

## Self-Review

**1. Spec coverage** (spec 섹션 → Task 매핑):
- §2 인벤토리 35 main: Task 1~9 전부 커버 — exception 3(T1) + response 4(T2) + dto 7(T3) + user 2(T4) + config 8(T5:4+T6:4) + web 로깅 5(T7) + common/filter 1(T7) + web 컨트롤러 3 + web/relay 1(T8) + root 1(T9) = 35. ✓
- §3 변환 규칙: data class→record(T2·3·5), companion→static(T1·2·8), object→class(T7 SecretRedactor), @Volatile(T6), 람다 replaceAll(T7), main(T9). ✓
- §3.1 properties 바인딩: scan 경로 record 안전(T5), RelayProperties class(T5). ✓
- §3.2 SecretRedactor 람다: T7 Step 1 `replaceAll(Function)`. ✓
- §3.3 RegRelayCodec 순서·null: T8 Step 4. ✓
- §4 빌드 정리: T9 Step 7. ✓
- §5 함정: T4(==), T7(람다), T8(Objects.equals/NPE fail-fast), Global Constraints. ✓
- §6 검증: T1~9 모듈 테스트 + T10 부팅. ✓
- §7 작업 순서: Task 순서가 리프→루트 의존성 따름. ✓

**2. Placeholder scan:** "TBD"/"TODO"/"적절히 처리" 없음. 모든 code step에 완전한 코드 포함. ✓ (단 Task 3 Step 1·Task 8 Step 5는 의도된 "디렉토리/접근자명 확인" 검증 스텝 — placeholder 아님.)

**3. Type consistency:**
- ErrorCode 접근자 `status()`/`code()`/`message()` — T1 정의, T2(ApiResponse)·T1(GlobalExceptionHandler) 사용 일치. ✓
- BusinessException `getErrorCode()` — T1 정의, T1 GlobalExceptionHandler 사용. ✓
- ApiResponse static `ok`/`error` 시그니처 — T2 정의, T8 컨트롤러 사용 일치. ✓
- properties 접근자(`secret()`/`ttl()`/`allowedOrigins()`/`android()`/`ios()`/`baseUrl()` 등) — T5 정의, T6·T8 사용 일치. ✓
- RegRelay/RegRelayCodec(`encode`/`decode`/`RegRelay` 접근자) — T8 정의, T8 WebAuthnController 사용 일치. ✓
- RpAppUser 접근자(`userHandle()`/`credentialId()` 등) — T4 정의, T4·T8 사용 일치. ✓
- sdk-java DTO 접근자 — Step 5 표로 확정, Step 3 코드와 일치(grep으로 검증). ✓
- registerOptions의 sdkResp 변수는 인라인 try-catch로 정리(헬퍼·잔재 줄 제거). ✓

**4. 미해결 리스크 표기:** Task 3·4·5·6의 "compileKotlin이 중간 단계에서 깨질 수 있음 → 의존 Task와 묶어 진행"을 각 Task의 순서 결정 노트에 명시. 실행자는 subagent-driven 모드에서 Task 경계가 깨지면 인접 Task를 함께 실행한다. ✓
