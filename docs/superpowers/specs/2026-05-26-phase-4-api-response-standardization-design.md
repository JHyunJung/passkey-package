# Phase 4 — API Response Standardization Design

**Date:** 2026-05-26
**Status:** Design (awaiting plan)
**Predecessor:** Phase 3 (MDS + key rotation) — tag `phase-3-mds-key-complete`

## 1. Goal

`/.well-known/jwks.json` 한 개를 제외한 **모든 REST API 응답을 `ApiResponse<T>` 단일 envelope으로 표준화**한다. 응답 성공/실패를 동일 스키마로 통일하고, 에러 코드를 중앙에서 관리하며, 분산 환경에서의 추적성을 위해 `traceId`를 모든 응답·로그에 포함한다.

본 Phase는 `spring-boot-api-response-template.md`의 패턴을 우리 프로젝트 규약(record, no Lombok, multi-module `:core` 공유, FIDO2/WebAuthn protocol 인지)에 맞춰 적용한다.

## 2. Architecture

```
Request
  ↓
TraceIdFilter (X-Trace-Id in/out + MDC put)
  ↓
SecurityFilterChain
  ↓
Controller (returns ApiResponse<T> or throws BusinessException)
  ↓
@RestControllerAdvice GlobalExceptionHandler (catches all, wraps as ApiResponse error)
  ↓
[JwksController는 raw JWKSet Map 직접 반환 — RFC 7517 wire format 예외]
```

**핵심 설계 결정 (확정)**

| 항목 | 결정 |
|------|------|
| 공통 컴포넌트 위치 | `:core/src/main/java/com/crosscert/passkey/core/api/` (단일 출처, 두 앱 공유) |
| 응답 envelope | `ApiResponse<T>` Java record |
| 예외 처리 | `BusinessException` + `ErrorCode` enum 일원화 |
| `RotationConflictException` | Phase 3에서 도입했으나 본 Phase에서 **제거**, `BusinessException(ErrorCode.KEY_ROTATION_CONFLICT)`로 통합 |
| Wrap 방식 | Controller가 명시적으로 `ApiResponse.ok(data)` 반환 (`@ResponseBodyAdvice` 자동 wrap 안 씀) |
| Wrap 대상 | admin-app 13 메서드 + passkey-app WebAuthn 4 메서드 = **17 메서드** |
| Protocol-exempt | `GET /.well-known/jwks.json` 1 메서드 (RFC 7517 + OIDC Discovery 강제) |
| TraceIdFilter | 포함 (X-Trace-Id header + MDC + logback %X{traceId}) |
| PageResponse<T> | 제외 (YAGNI — 현재 list 엔드포인트 모두 전체 반환) |
| Lombok | 제외 (프로젝트 전반 record 통일 정책 유지) |
| admin-ui 마이그레이션 | 동시 진행, client.ts에 unwrap layer 도입 → 페이지 호출부 무변경 |

## 3. Component Inventory

### 3.1 `:core/src/main/java/com/crosscert/passkey/core/api/` — 7 신규 클래스

#### 3.1.1 `ApiResponse<T>` (record)

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

템플릿과의 차이: `LocalDateTime` → `Instant` (시간대 모호성 제거, 이미 프로젝트 전반에 Instant 사용 중).

#### 3.1.2 `ErrorDetail` + `FieldError` (record)

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(String errorCode, List<FieldError> fieldErrors) {}

public record FieldError(String field, Object rejectedValue, String reason) {}
```

#### 3.1.3 `ErrorCode` (enum)

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

**Prefix 체계:** `C` Common, `A` Auth, `T` Tenant, `K` API Key, `S` Signing Key, `M` MDS, `F` FIDO2/WebAuthn.

#### 3.1.4 `BusinessException`

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

#### 3.1.5 `GlobalExceptionHandler`

기존 `core/config/GlobalExceptionHandler.java` (ProblemDetail 기반)를 **완전 대체**한다. 응답은 `ApiResponse<Void>` 한 가지로 통일.

```java
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

**기존 대비 변경:**
- `BusinessException` 처리 추가 (template 핵심)
- `MethodArgument*`/`HttpMessage*`/`Method*Not*` 핸들러 추가 (이전엔 Spring 기본 ProblemDetail 의존)
- `AccessDeniedException`, `AuthenticationException` 추가 (Spring Security 흐름 일관화)
- 기존 `IllegalArgumentException` 핸들러는 ApiResponse 포맷으로 변환

#### 3.1.6 `TraceIdFilter`

```java
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

`logback-spring.xml` 패턴에 `%X{traceId:-}` 포함하도록 갱신.

**Component scan:** `:core`의 다른 `@Component` (e.g., `core.vpd.TenantContextHolder`)가 admin-app/passkey-app 양쪽에서 이미 자동 등록되어 동작하는 동일 패턴 사용 (두 앱의 `@SpringBootApplication`이 `com.crosscert.passkey.*`을 scan하므로 `core.api.TraceIdFilter` + `core.api.GlobalExceptionHandler`도 자동 등록).

### 3.2 기존 `core/config/GlobalExceptionHandler.java` 제거

ProblemDetail 기반 기존 핸들러는 본 Phase의 `core/api/GlobalExceptionHandler`로 완전 대체된다. 파일 삭제 + import 정리.

### 3.3 `:admin-app` 변경

#### `RotationConflictException.java` 제거
`KeyRotationService`가 직접 `throw new BusinessException(ErrorCode.KEY_ROTATION_CONFLICT)` 호출. 호출자 (`KeyMgmtController`)의 try/catch도 제거. 테스트(`KeyRotationServiceTest`, `KeyRotationIT`, `KeyMgmtControllerSecurityTest`)는 `BusinessException` 검증으로 변경.

#### 컨트롤러 메서드 변경 (13 메서드)

| Controller | Method | After |
|------------|--------|-------|
| `TenantAdminController` | `GET /` | `ApiResponse<List<TenantView>>` |
| | `GET /{id}` | `ApiResponse<TenantView>`; not found → `BusinessException(ErrorCode.TENANT_NOT_FOUND)` |
| | `POST /` | `ApiResponse<TenantView>`; duplicate → `BusinessException(ErrorCode.TENANT_DUPLICATE)` |
| `MeController` | `GET /me` | record `MeView(String email, String role)` 신설 → `ApiResponse<MeView>` (필드명 기존 Map key와 동일하게 유지 → SPA 무변경) |
| `KeyMgmtController` | `GET /` | `ApiResponse<KeyMgmtDto.KeyList>` |
| | `POST /rotate` | `ApiResponse<RotateResponse>`; try/catch 완전 제거 |
| `ApiKeyAdminController` | `GET /` | `ApiResponse<List<ApiKeyView>>` |
| | `POST /` | `ApiResponse<ApiKeyIssueResponse>` |
| | `DELETE /{id}` | `ApiResponse<Void>` (200으로 통일, 204 폐기); not found → `BusinessException(ErrorCode.API_KEY_NOT_FOUND)` |
| `AuditLogController` | `GET /` | `ApiResponse<List<AuditLogView>>` |
| | `GET /verify` | `ApiResponse<VerifyResult>` |
| `MdsAdminController` | `GET /status` | record `MdsStatusView(int version, String nextUpdate, String fetchedAt)` → `ApiResponse<MdsStatusView>` |
| | `POST /sync` | `ApiResponse<SyncResult>` (sync 실패는 도메인 예외 아님 → 200 + envelope success=true, SyncResult.status로 RP 분기) |

### 3.4 `:passkey-app` 변경

#### 컨트롤러 메서드 변경 (4 메서드)

| Controller | Method | After |
|------------|--------|-------|
| `RegistrationController` | `POST /start` | `ApiResponse<RegistrationStartResponse>` |
| | `POST /finish` | `ApiResponse<RegistrationFinishResponse>` |
| `AuthenticationController` | `POST /start` | `ApiResponse<AuthenticationStartResponse>` |
| | `POST /finish` | `ApiResponse<AuthenticationFinishResponse>` |

도메인 예외 → `BusinessException`:
- challenge 만료 → `CHALLENGE_INVALID`
- attestation 정책 위반 → `ATTESTATION_REJECTED`
- AAGUID 차단 → `AAGUID_REVOKED`
- 등록 검증 실패 → `REGISTRATION_FAILED`
- 인증 검증 실패 → `AUTHENTICATION_FAILED`

#### `JwksController` — 변경 없음

`GET /.well-known/jwks.json`은 RFC 7517 wire format 강제로 **wrap하지 않는다**. 컨트롤러 javadoc에 예외 사유 명시. `JwksAssembler.build()`는 throw하지 않으므로 GlobalExceptionHandler에 잡힐 일 없음. (만일 unexpected throw 시 envelope으로 wrap된 에러를 받음 → RP는 어차피 키 못 받음 → 결과 동일하게 안전.)

### 3.5 `:admin-ui` 변경

#### `api/types.ts` — `ApiEnvelope<T>` + `ApiError` class 추가

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
    public readonly fieldErrors?: ApiEnvelope<unknown>['error'] extends infer E
      ? E extends { fieldErrors?: infer F } ? F : never
      : never,
    public readonly traceId?: string
  ) {
    super(`[${code}] ${serverMessage}`);
    this.name = 'ApiError';
  }
}
```

#### `api/client.ts` 재작성 (auto-unwrap)

```typescript
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: 'include',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  let envelope: ApiEnvelope<T>;
  try { envelope = await res.json(); }
  catch { throw new ApiError(res.status, 'C999', `Non-JSON response (status ${res.status})`); }
  if (!envelope.success) {
    throw new ApiError(res.status, envelope.code ?? 'C999',
                       envelope.message ?? 'Unknown error',
                       envelope.error?.fieldErrors, envelope.traceId);
  }
  return envelope.data as T;
}
```

#### 페이지 영향 — **변경 거의 없음**
client.ts가 `.data` 자동 추출하므로 페이지 코드 (`api.get<TenantView[]>(...).then(setTenants)`)는 그대로 작동. 에러는 `ApiError` 인스턴스로 throw → 기존 `String(e)`는 `[S001] Another rotation in progress` 식으로 더 깔끔하게 출력된다.

## 4. Wire Format Examples

### Success

```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": { "id": 1, "code": "acme", "name": "Acme Inc." },
  "traceId": "a1b2c3d4e5f6g7h8",
  "timestamp": "2026-05-26T10:00:00Z"
}
```

### Validation failure

```json
{
  "success": false,
  "code": "C001",
  "message": "Invalid input value",
  "error": {
    "errorCode": "C001",
    "fieldErrors": [
      { "field": "code", "rejectedValue": "", "reason": "must not be blank" }
    ]
  },
  "traceId": "a1b2c3d4e5f6g7h8",
  "timestamp": "2026-05-26T10:00:00Z"
}
```

### Business exception (rotation conflict)

```json
{
  "success": false,
  "code": "S001",
  "message": "Another rotation in progress",
  "error": { "errorCode": "S001" },
  "traceId": "a1b2c3d4e5f6g7h8",
  "timestamp": "2026-05-26T10:00:00Z"
}
```
HTTP: `409 Conflict`

### JWKS (RFC 7517, **unwrap**)

```json
{
  "keys": [
    { "kty": "RSA", "kid": "abc123", "alg": "RS256", "use": "sig",
      "n": "...", "e": "AQAB" }
  ]
}
```

## 5. Migration order

```
T1. :core 인프라 깔기 (사용처 없음 → 영향 0)
    ApiResponse → ErrorDetail → FieldError → ErrorCode → BusinessException
    → GlobalExceptionHandler (기존 대체) → TraceIdFilter → logback 패턴 갱신

T2. admin-app 컨트롤러 migration (한 컨트롤러 = 한 commit)
    TenantAdminController + 테스트
    MeController + 테스트
    ApiKeyAdminController + 테스트
    AuditLogController + 테스트
    MdsAdminController + 테스트
    KeyMgmtController + 테스트 (RotationConflictException 제거 포함)
    → AdminFlowIT 갱신

T3. passkey-app 컨트롤러 migration
    RegistrationController + 테스트
    AuthenticationController + 테스트
    → Fido2EndToEndIT 갱신
    → JwksController 변경 없음 확인

T4. admin-ui client.ts + types.ts unwrap layer
    → 6개 page 수동 smoke test

T5. RP 마이그레이션 가이드 문서
    docs/superpowers/followups/2026-05-26-rp-migration-guide.md

T6. Phase DoD
    - 전체 build green (134+ tests)
    - boot + 양쪽 sample curl로 envelope 응답 확인
    - JWKS curl로 RFC 7517 shape 유지 확인
    - tag: phase-4-api-response-standard-complete
```

각 단계는 자체적으로 green 상태를 유지한다 (per-task TDD).

## 6. Testing strategy

### Unit
- `ApiResponseTest`: 각 정적 factory의 필드 채움 + MDC traceId 픽업
- `ErrorCodeTest`: enum 값별 HttpStatus 매핑 sanity
- `BusinessExceptionTest`: errorCode 보존
- `GlobalExceptionHandlerTest`: MockMvc로 각 예외 케이스가 정확한 `ApiResponse` + HTTP status로 변환
- `TraceIdFilterTest`: header 있/없 + MDC put/remove + response header set

### Controller (17 변경 메서드)
- 각 `*ControllerSecurityTest`의 jsonPath assertion을 envelope 형태로 갱신
- 성공: `$.success=true`, `$.data.<field>` 검증
- 실패: `$.success=false`, `$.code=<expected>`, `$.error.errorCode` 검증

### Integration
- `AdminFlowIT`: 11 step 모두 envelope 풀어서 검증
- `Fido2EndToEndIT`: 8 시나리오, WebAuthn ceremony 응답 unwrap (helper 메서드 1개로 일관 처리)
- `KeyRotationIT`: scenario 3에서 `BusinessException` + `ErrorCode.KEY_ROTATION_CONFLICT` 검증으로 교체
- `MdsSchedulerIT`: 변경 없음 (서비스 레이어, 컨트롤러 안 거침)
- `JwksControllerTest`: **변경 없음** (RFC 7517 wire format 유지 확인이 곧 테스트 가치)

### Manual smoke (DoD)
- admin-ui 6개 page 클릭 — list / 생성 / 회전 / sync 모두 동작
- `curl /.well-known/jwks.json | jq .keys[0].kty` → `"RSA"` (wrap 안 됐는지 확인)
- `curl /admin/api/tenants` → `.success / .data` envelope shape 확인
- 의도적 잘못된 input → 400 + `.error.fieldErrors` 확인
- 응답 header `X-Trace-Id` 확인 + server log에서 동일 traceId 매칭

## 7. Risks & mitigations

| 위험 | 완화 |
|------|------|
| **RP 측 JS client 일제 깨짐** (WebAuthn 4개) | 마이그레이션 가이드 문서 + 사전 통보. 현재 외부 RP 0개라 즉시 영향 없음 |
| **JwksController에서 unexpected throw → wrap됨** | `JwksAssembler.build()`는 throw 안 함. 만약 throw 시 envelope으로 wrap된 에러 → RP는 어차피 키 못 받음 → 결과 동일하게 안전 |
| **MeController Map → record 전환** | record 필드명 = 기존 Map key (`email`, `role`) → JSON shape 동일 → SPA 무변경 |
| **DELETE 204 → 200 통일** | admin-ui만 호출 — `api.delete<Void>` 그대로 작동. 외부 클라이언트 없음 |
| **TraceIdFilter @Order vs Security filter chain** | `Ordered.HIGHEST_PRECEDENCE` → Security filter 이전 실행. MDC가 보안 흐름 로그까지 포함 |
| **AdminFlowIT 11 step 한꺼번에 깨짐** | step-by-step task로 컨트롤러 하나씩 migration → 모든 컨트롤러 적용 후 한 번에 갱신 |
| **ApiResponse 응답 사이즈 증가 (~200-300 bytes)** | 무시 가능. 캐싱 영향 없음 |

## 8. Out-of-scope (의도적 제외)

- **PageResponse<T>** — YAGNI. 현재 list 모두 전체 반환. 필요 시 별도 Phase
- **Lombok 도입** — record 통일 정책 유지
- **admin-ui 단위 테스트** — Vitest 도입은 별도 Phase
- **OpenAPI spec 자동 생성** — springdoc 도입은 별도 Phase
- **i18n 메시지** — ErrorCode 메시지 영문 고정
- **Per-tenant 에러 코드 customization** — 단일 글로벌 enum
- **PII 마스킹/감사 hook** — TraceIdFilter는 traceId만
- **Webhook outbound 응답 표준화** — 현재 outbound 없음
- **API versioning** (envelope v2) — single version

## 9. Plan task 예상 수

11-13 tasks:
- T1 `:core/api` 인프라 (7 클래스 + GlobalExceptionHandler 대체 + logback 갱신): 1-2
- T2 admin-app 컨트롤러 6개 + 각자 테스트 갱신: 6
- T3 passkey-app 컨트롤러 2개 + Fido2EndToEndIT 갱신: 2
- T4 admin-ui client.ts + types.ts + smoke 검증: 1
- T5 RP 마이그레이션 가이드 문서: 1
- T6 DoD verify + tag: 1

Phase 1 (27) / Phase 2 (25) / Phase 3 (26) 보다 작은 단일 표준화 Phase.
