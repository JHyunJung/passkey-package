# SDK + 샘플 RP + 테스트 페이지 설계 문서

작성일: 2026-05-27
상태: 검토 대기
선행 작업: Phase 0~8 완료 (`docs/superpowers/specs/2026-05-25-phase-0-foundation-design.md` 이하)
관련 합의: Phase 0 § 9 / Phase 1 § 14 — "sdk-java, examples/sample-rp → Phase 4"

## 1. 배경과 목표

Phase 0 합의에서 `:passkey-app` 의 RP API 표면을 외부 고객 RP 가 통합하는 경험은 **별도 `sdk-java` + `sample-rp` 묶음** 으로 검증하기로 했다. Phase 4 는 실제로는 API 응답 표준화로 스코프가 교체되어 진행됐고, 본 작업이 원래 Phase 4 합의를 살린 dogfood 묶음이다.

### 1.1 목표 (Definition of Done)

다음 세 가지가 한 묶음으로 동작한다:

1. **`sdk-java`** — `:passkey-app` 의 RP API 를 감싸는 중간 레벨 도메인 Java 클라이언트. `mavenLocal()` 로 배포.
2. **`sample-rp`** — `sdk-java` 만 의존하는 Spring Boot 3.5 단일 모듈 앱. `:9090` 에서 동작.
3. **테스트 페이지** — `sample-rp` 가 서빙하는 Thymeleaf 3개 페이지 (`/`, `/register`, `/login`) + `helpers.js`. 브라우저로 등록·로그인 전 흐름을 시연.

Passkey2 운영자가 README 의 7단계만 따라 하면 7분 안에 브라우저에서 passkey 등록과 로그인이 동작해야 한다.

### 1.2 의도적으로 제외하는 것

| 항목 | 다룰 곳 |
|---|---|
| Maven Central 배포 + group/artifact 확정 | 외부 고객사 통합 단계 |
| SDK 자동 재시도/서킷브레이커 | 운영 페일 패턴이 쌓인 뒤 |
| JWKS pre-warm | 첫 로그인 지연이 문제 될 때 |
| sample-rp 영구 저장소 | 데모가 운영 시나리오로 확장될 때 |
| 멀티 tenant sample-rp | 별도 spec |
| admin 2FA via passkey (admin dogfood) | 별도 spec |

후속 항목은 `docs/superpowers/followups/2026-05-27-sdk-and-sample-rp-followups.md` 로 옮겨 추적한다.

## 2. 시스템 구성 + 디렉토리 레이아웃

```
~/Git/10-work/crosscert/
├─ Passkey2/                         (기존 — 변경 없음)
│   ├─ passkey-app/        :8080     RP API
│   ├─ admin-app/          :8081     관리자 + admin-ui
│   └─ docker-compose.yml             oracle, redis
│
├─ sdk-java/                          (신규 — 별개 Gradle 프로젝트)
│   ├─ settings.gradle.kts             rootProject.name = "passkey-sdk-java"
│   ├─ build.gradle.kts                java-library + maven-publish
│   └─ src/main/java/com/crosscert/passkey/sdk/
│
└─ sample-rp/                         (신규 — 별개 Spring Boot 앱)
    ├─ settings.gradle.kts             rootProject.name = "sample-rp"
    ├─ build.gradle.kts                spring-boot + sdk-java(mavenLocal)
    ├─ scripts/bootstrap-sample-rp.sh
    └─ src/main/
        ├─ java/com/crosscert/passkey/samplerp/
        └─ resources/
            ├─ application.yml
            ├─ static/{css,js}/
            └─ templates/
```

### 2.1 호출 관계

```
브라우저(Thymeleaf 페이지 + helpers.js)
   ↓ /webauthn/{register,login}/{options,complete}
sample-rp:9090  ───── sdk-java (PasskeyClient) ─────→  passkey-app:8080
                                                       /api/v1/rp/**
                                                       /.well-known/jwks.json
```

### 2.2 분리 원칙

- `sdk-java` 와 `sample-rp` 는 Passkey2 의 `settings.gradle.kts` 에 **포함되지 않는다**. Phase 0 합의 "메인 빌드 그래프 밖" 그대로.
- 두 신규 프로젝트는 자체 `gradlew` 를 가진다.
- `sdk-java` 는 `publishToMavenLocal` 로 `sample-rp` 에 노출 — 외부 고객 통합 시뮬레이션과 가장 가까운 형태.

## 3. `sdk-java` 모듈 표면

**좌표:** `com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT` (dogfood 단계 잠정값).
**런타임 의존성:** Spring `RestClient` (spring-web 6.x), Nimbus JOSE+JWT, Jackson.
**런타임에 없는 것:** Spring Boot starter, webauthn4j (서버 측 책임).

### 3.1 패키지 구조

```
com.crosscert.passkey.sdk/
├ PasskeyClient.java               엔트리포인트
├ PasskeyClientConfig.java         baseUrl, apiKey, timeout, jwks cache TTL, traceIdProvider
├ dto/
│   ├ RegistrationStartRequest     {userHandle, displayName, username}
│   ├ RegistrationStartResponse    {registrationToken, publicKeyCredentialCreationOptions: JsonNode}
│   ├ RegistrationFinishRequest    {registrationToken, publicKeyCredential: JsonNode}
│   ├ RegistrationFinishResponse   {credentialId, aaguid, attestationFormat, createdAt}
│   ├ AuthenticationStartRequest   {userHandle?}
│   ├ AuthenticationStartResponse  {authenticationToken, publicKeyCredentialRequestOptions: JsonNode}
│   ├ AuthenticationFinishRequest  {authenticationToken, publicKeyCredential: JsonNode}
│   └ AuthenticationFinishResponse {idToken, tokenType, expiresIn}
├ idtoken/
│   ├ IdTokenVerifier              .verify(jwt) → IdTokenClaims
│   ├ IdTokenClaims                record (iss, sub, aud, iat, exp, amr, credId, aaguid?)
│   └ JwksCache                    Nimbus RemoteJWKSet + 5분 TTL
├ envelope/
│   └ ApiResponseEnvelope<T>       passkey-app 의 ApiResponse 미러 (필드: success, code, message, data, error, traceId, timestamp)
└ exception/
    ├ PasskeyApiException          RuntimeException. httpStatus, code, message, traceId, fieldErrors
    ├ PasskeyAuthException         X-API-Key 거부 (401)
    ├ PasskeyRateLimitException    429 + retryAfterSeconds
    └ PasskeyIdTokenException      JWKS 검증 실패
```

### 3.2 `PasskeyClient` 공개 메서드 + wire 매핑

```java
public final class PasskeyClient {

    public static PasskeyClient of(PasskeyClientConfig config) { ... }

    // FIDO2 ceremony
    public RegistrationStartResponse  registrationStart (RegistrationStartRequest req);
    public RegistrationFinishResponse registrationFinish(RegistrationFinishRequest req);
    public AuthenticationStartResponse  authenticationStart (AuthenticationStartRequest req);
    public AuthenticationFinishResponse authenticationFinish(AuthenticationFinishRequest req);

    // ID Token
    public IdTokenClaims verifyIdToken(String compactJwt);
}
```

각 메서드의 wire 매핑 (Phase 1 spec § 4 참조):

| SDK 메서드 | HTTP | passkey-app 경로 |
|---|---|---|
| `registrationStart`     | POST | `/api/v1/rp/registration/start` |
| `registrationFinish`    | POST | `/api/v1/rp/registration/finish` |
| `authenticationStart`   | POST | `/api/v1/rp/authentication/start` |
| `authenticationFinish`  | POST | `/api/v1/rp/authentication/finish` |
| `verifyIdToken` (JWKS)  | GET  | `/.well-known/jwks.json` |

이 매핑이 `PasskeyClientContractIT` (§ 8.1) 의 WireMock stub 5개와 1:1 대응. wire format 이 바뀌면 contract test 가 가장 먼저 깨진다.

### 3.3 `PasskeyClientConfig`

```java
public record PasskeyClientConfig(
    URI baseUrl,                     // https://passkey.local:8080
    String apiKey,                   // X-API-Key 값
    Duration connectTimeout,         // default 3s
    Duration readTimeout,            // default 10s
    Duration jwksCacheTtl,           // default 5m
    Clock clock,                     // ID Token 검증 시각 (test inject)
    Supplier<String> traceIdProvider // default: () -> MDC.get("traceId")
) {
    public static PasskeyClientConfig defaults(URI baseUrl, String apiKey) { ... }
}
```

### 3.4 동작 규칙

1. 모든 요청에 `X-API-Key: <config.apiKey>` 자동 주입.
2. `traceIdProvider` 결과를 `X-Trace-Id` 헤더로 전파 (null 이면 헤더 생략).
3. 응답을 `ApiResponseEnvelope<T>` 로 역직렬화. `success=false` → 즉시 `PasskeyApiException` throw (재시도 없음).
4. HTTP 401 → `PasskeyAuthException`, 429 → `PasskeyRateLimitException(retryAfter)`, 그 외 4xx/5xx → `PasskeyApiException`.
5. `publicKeyCredentialCreationOptions` / `publicKeyCredentialRequestOptions` 는 `JsonNode` 로 보존 — 변환·재직렬화 없음.
6. `verifyIdToken` — `baseUrl + /.well-known/jwks.json` 을 `JwksCache` 가 5분 캐시. **서명·만료만 검증**하고 `IdTokenClaims` 반환. `iss`/`aud` 검증은 호출자 책임 (SDK 는 tenant 를 모름).

### 3.5 build.gradle.kts 골격

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

group = "com.crosscert.passkey"
version = "0.1.0-SNAPSHOT"

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

dependencies {
    // Spring Boot 3.5.x BOM 이 spring-web 6.2.x 를 가져오므로 동일 라인업으로 잠근다.
    // sample-rp 가 Boot 3.5 라 클래스 충돌 없이 동작.
    api("org.springframework:spring-web:6.2.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.21.0")
    api("com.nimbusds:nimbus-jose-jwt:9.40")
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
```

## 4. `sample-rp` 모듈 구조

**좌표:** `com.crosscert.passkey:sample-rp:0.0.1-SNAPSHOT`. 포트 **9090**.

### 4.1 패키지 구조

```
sample-rp/src/main/
├ java/com/crosscert/passkey/samplerp/
│   ├ SampleRpApplication.java
│   ├ config/
│   │   ├ PasskeyClientConfiguration   @ConfigurationProperties → PasskeyClient 빈
│   │   ├ SessionConfig                Spring Session in-memory (HashMap)
│   │   └ WebSecurityConfig            CSRF 활성화, /login·/register·/webauthn/** 공개
│   ├ user/
│   │   ├ InMemoryUserStore            ConcurrentHashMap<userHandle, SampleRpUser>
│   │   └ SampleRpUser                 record (userHandle, username, displayName, createdAt)
│   ├ web/
│   │   ├ PageController               GET / , /register , /login (Thymeleaf)
│   │   └ WebAuthnController           SDK 호출 4개 엔드포인트
│   ├ session/
│   │   └ SessionUser                  HttpSession attr key "USER"
│   └ common/                          ApiResponse 템플릿 동일 구조 (§ 5)
└ resources/
    ├ application.yml
    ├ static/
    │   ├ css/site.css
    │   └ js/helpers.js
    └ templates/
        ├ layout.html                  Thymeleaf fragment
        ├ index.html
        ├ register.html
        └ login.html
```

### 4.2 RP HTTP 엔드포인트

| 메서드 | 경로 | 역할 | SDK 호출 |
|---|---|---|---|
| GET | `/` | 홈, 세션 상태 표시 | — |
| GET | `/register` | 등록 페이지 | — |
| GET | `/login` | 로그인 페이지 | — |
| POST | `/logout` | 세션 무효화 | — |
| POST | `/webauthn/register/options` | `{username, displayName}` 받아 등록 시작 | `registrationStart` |
| POST | `/webauthn/register/complete` | 브라우저 credential → 등록 마무리 | `registrationFinish` + UserStore.confirmRegistration |
| POST | `/webauthn/login/options` | `{username?}` 받아 인증 시작 | `authenticationStart` (username → userHandle 변환) |
| POST | `/webauthn/login/complete` | 브라우저 assertion → 인증 + 세션 생성 | `authenticationFinish` → `verifyIdToken` → `HttpSession.setAttribute("USER", ...)` |

**`registrationToken` / `authenticationToken`** 은 RP 가 직접 HttpSession 에 임시 저장 (`PENDING_REG_TOKEN`, `PENDING_AUTH_TOKEN`) → complete 단계에서 꺼냄. 클라이언트로 노출하지 않음.

**userHandle 매핑** — `username → userHandle(random 32B base64url)` 매핑을 `InMemoryUserStore` 에서 유지. RP 의 책임임을 명시적으로 보여주기 위해 SDK 가 자동 생성하지 않음.

### 4.3 ID Token 후처리 (sample-rp 핵심 dogfood)

```java
AuthenticationFinishResponse fin = passkeyClient.authenticationFinish(req);
IdTokenClaims claims = passkeyClient.verifyIdToken(fin.idToken());

// iss/aud 는 RP 가 직접 — SDK 는 서명·만료만 봄
String expectedIss = config.baseUrl() + "/" + config.tenantId();   // 예: http://localhost:8080/sample-rp-demo
if (!claims.iss().equals(expectedIss)) throw new IllegalStateException("iss mismatch");
if (!claims.aud().equals(config.tenantId())) throw new IllegalStateException("aud mismatch");

SampleRpUser user = userStore.findByUserHandle(claims.sub()).orElseThrow();
session.setAttribute("USER", user);
```

이 5줄이 "ID Token 이 어떻게 자체 세션으로 변환되는지" 의 교과서 예시. iss/aud 모두 검증하는 것이 RP 의 표준 책임.

### 4.4 application.yml

```yaml
server:
  port: 9090

sample-rp:
  origin: ${SAMPLE_RP_ORIGIN:http://localhost:9090}    # iss/aud 검증 외, CORS·CSRF 출처 비교용

passkey:
  base-url: ${PASSKEY_BASE_URL:http://localhost:8080}
  api-key:   ${PASSKEY_API_KEY}
  tenant-id: ${PASSKEY_TENANT_ID}
  connect-timeout: 3s
  read-timeout: 10s
  jwks-cache-ttl: 5m

spring:
  session:
    store-type: none
  thymeleaf:
    cache: false

logging:
  level:
    com.crosscert.passkey.sdk: DEBUG     # 운영 환경에서는 INFO 이하. § 9 logging redaction 참조.
```

`.env` 의 4개 키 (`PASSKEY_BASE_URL`, `PASSKEY_TENANT_ID`, `PASSKEY_API_KEY`, `SAMPLE_RP_ORIGIN`) 모두 `application.yml` placeholder 로 소비된다.

## 5. API 응답 envelope — sample-rp 도 `ApiResponse<T>` 통일

`/webauthn/**` 4개 + 향후 추가될 모든 sample-rp REST 엔드포인트는 Passkey2 의 `spring-boot-api-response-template.md` 와 동일한 envelope 구조를 따른다. Thymeleaf HTML 응답은 envelope 대상이 아니다.

### 5.1 패키지 — 템플릿 그대로 복제

sample-rp 는 Passkey2 의 `:core` 모듈에 의존하지 않으므로 (별개 프로젝트), 템플릿의 8개 클래스를 자체에 그대로 둔다. sample-rp 는 페이지네이션 엔드포인트가 없지만 향후 추가될 가능성과 템플릿 무결성을 위해 `PageResponse` 까지 복제한다.

```
sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/
├ response/
│   ├ ApiResponse.java
│   ├ ErrorDetail.java
│   ├ FieldError.java
│   └ PageResponse.java
├ exception/
│   ├ ErrorCode.java
│   ├ BusinessException.java
│   └ GlobalExceptionHandler.java
└ filter/
    └ TraceIdFilter.java
```

### 5.2 sample-rp 의 `ErrorCode`

```java
public enum ErrorCode {
    // Common — 템플릿이 GlobalExceptionHandler 에서 직접 참조하는 코드 전부 포함
    INVALID_INPUT       (BAD_REQUEST,         "C001", "Invalid input value"),
    METHOD_NOT_ALLOWED  (METHOD_NOT_ALLOWED,  "C002", "Method not allowed"),
    ENTITY_NOT_FOUND    (NOT_FOUND,           "C003", "Entity not found"),
    MISSING_PARAMETER   (BAD_REQUEST,         "C004", "Required parameter missing"),
    TYPE_MISMATCH       (BAD_REQUEST,         "C005", "Type mismatch"),
    INTERNAL_SERVER_ERROR(INTERNAL_SERVER_ERROR,"C999","Server error"),

    // Auth
    UNAUTHORIZED        (UNAUTHORIZED,        "A001", "Authentication required"),
    ACCESS_DENIED       (FORBIDDEN,           "A002", "Access denied"),

    // WebAuthn flow (sample-rp 도메인)
    USERNAME_TAKEN      (CONFLICT,            "W001", "Username already registered"),
    PENDING_REG_MISSING (BAD_REQUEST,         "W002", "No pending registration in session"),
    PENDING_AUTH_MISSING(BAD_REQUEST,         "W003", "No pending authentication in session"),

    // Passkey-server proxy (SDK 예외 → envelope)
    PASSKEY_API_ERROR   (BAD_GATEWAY,         "P001", "Upstream passkey server error"),
    PASSKEY_AUTH_ERROR  (UNAUTHORIZED,        "P002", "Invalid X-API-Key (sample-rp config)"),
    PASSKEY_RATE_LIMITED(TOO_MANY_REQUESTS,   "P003", "Upstream rate limit exceeded"),
    PASSKEY_ID_TOKEN    (UNAUTHORIZED,        "P004", "ID Token verification failed");
}
```

### 5.3 GlobalExceptionHandler — SDK 예외 변환

템플릿의 8개 핸들러에 sample-rp 전용 4개 추가:

```java
@ExceptionHandler(PasskeyAuthException.class)
public ResponseEntity<ApiResponse<Void>> handlePasskeyAuth(PasskeyAuthException e) {
    log.error("[PasskeyAuth] upstream rejected X-API-Key — check sample-rp/.env", e);
    return ResponseEntity.status(ErrorCode.PASSKEY_AUTH_ERROR.getStatus())
        .body(ApiResponse.error(ErrorCode.PASSKEY_AUTH_ERROR));
}

@ExceptionHandler(PasskeyRateLimitException.class)
public ResponseEntity<ApiResponse<Void>> handlePasskeyRateLimit(PasskeyRateLimitException e) {
    return ResponseEntity.status(ErrorCode.PASSKEY_RATE_LIMITED.getStatus())
        .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
        .body(ApiResponse.error(ErrorCode.PASSKEY_RATE_LIMITED));
}

@ExceptionHandler(PasskeyIdTokenException.class)
public ResponseEntity<ApiResponse<Void>> handlePasskeyIdToken(PasskeyIdTokenException e) {
    return ResponseEntity.status(ErrorCode.PASSKEY_ID_TOKEN.getStatus())
        .body(ApiResponse.error(ErrorCode.PASSKEY_ID_TOKEN, e.getMessage()));
}

@ExceptionHandler(PasskeyApiException.class)
public ResponseEntity<ApiResponse<Void>> handlePasskeyApi(PasskeyApiException e) {
    log.error("[PasskeyApi] upstream code={} traceId={}", e.getCode(), e.getTraceId(), e);
    return ResponseEntity.status(ErrorCode.PASSKEY_API_ERROR.getStatus())
        .body(ApiResponse.error(ErrorCode.PASSKEY_API_ERROR, e.getMessage()));
}
```

### 5.4 컨트롤러 예 (envelope 반환)

```java
@PostMapping("/webauthn/register/options")
public ApiResponse<RegisterOptionsResp> registerOptions(@Valid @RequestBody StartReq req,
                                                        HttpSession s) {
    String userHandle = userStore.createPending(req.username(), req.displayName());
    var sdkResp = passkeyClient.registrationStart(
        new RegistrationStartRequest(userHandle, req.displayName(), req.username()));
    s.setAttribute("PENDING_REG_TOKEN", sdkResp.registrationToken());
    s.setAttribute("PENDING_USER_HANDLE", userHandle);
    return ApiResponse.ok(new RegisterOptionsResp(sdkResp.publicKeyCredentialCreationOptions()));
}

@PostMapping("/webauthn/register/complete")
public ApiResponse<RegistrationFinishResponse> registerComplete(
        @Valid @RequestBody FinishReq req, HttpSession s) {
    String token  = (String) s.getAttribute("PENDING_REG_TOKEN");
    String handle = (String) s.getAttribute("PENDING_USER_HANDLE");
    if (token == null) throw new BusinessException(ErrorCode.PENDING_REG_MISSING);

    var fin = passkeyClient.registrationFinish(
        new RegistrationFinishRequest(token, req.publicKeyCredential()));
    userStore.confirmRegistration(handle, fin.credentialId());
    s.removeAttribute("PENDING_REG_TOKEN");
    s.removeAttribute("PENDING_USER_HANDLE");      // 동반 cleanup — 둘이 짝이라 둘 다 지움
    return ApiResponse.ok("Passkey registered", fin);
}
```

핵심 — 반환 타입은 `ApiResponse<T>`. `@ResponseBodyAdvice` 자동 wrap 안 씀 (템플릿 원칙).

### 5.5 Wire 예시

성공 — register/complete:
```json
{
  "success": true,
  "code": "OK",
  "message": "Passkey registered",
  "data": {
    "credentialId": "AYx7q-…",
    "aaguid": "08987058-cadc-4b81-b6e1-30de50dcbe96",
    "attestationFormat": "packed",
    "createdAt": "2026-05-27T09:12:33Z"
  },
  "traceId": "f3a4b1c2d5e6a7b8",
  "timestamp": "2026-05-27T09:12:33"
}
```

실패 — upstream rate limit:
```json
{
  "success": false,
  "code": "P003",
  "message": "Upstream rate limit exceeded",
  "error": { "errorCode": "P003" },
  "traceId": "f3a4b1c2d5e6a7b8",
  "timestamp": "2026-05-27T09:12:33"
}
```

### 5.6 TraceId 전파

`TraceIdFilter` 가 브라우저 → sample-rp 의 `X-Trace-Id` 헤더를 받거나 새로 발급한다. SDK 의 `traceIdProvider` 기본값 `() -> MDC.get("traceId")` 가 같은 MDC 키를 읽어 passkey-app 으로 전파. sample-rp / SDK 양쪽 모두 `MDC_KEY = "traceId"` 를 상수로 둔다.

## 6. 브라우저 측 — helpers.js + Thymeleaf 페이지

### 6.1 `static/js/helpers.js`

WebAuthn 의 base64url ↔ ArrayBuffer 변환과 envelope unwrap 을 담는 단일 위치.

```javascript
export function b64urlToBuf(s) {
  const pad = '='.repeat((4 - s.length % 4) % 4);
  const bin = atob((s + pad).replace(/-/g, '+').replace(/_/g, '/'));
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

export function bufToB64url(buf) {
  const bin = String.fromCharCode(...new Uint8Array(buf));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function decodeCreationOptions(opts) {
  return {
    ...opts,
    challenge: b64urlToBuf(opts.challenge),
    user: { ...opts.user, id: b64urlToBuf(opts.user.id) },
    excludeCredentials: (opts.excludeCredentials || []).map(c => ({
      ...c, id: b64urlToBuf(c.id)
    }))
  };
}

export function encodeAttestationCredential(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON:    bufToB64url(cred.response.clientDataJSON),
      attestationObject: bufToB64url(cred.response.attestationObject)
    },
    clientExtensionResults: cred.getClientExtensionResults()
  };
}

export function decodeRequestOptions(opts) {
  return {
    ...opts,
    challenge: b64urlToBuf(opts.challenge),
    allowCredentials: (opts.allowCredentials || []).map(c => ({
      ...c, id: b64urlToBuf(c.id)
    }))
  };
}

export function encodeAssertionCredential(cred) {
  return {
    id: cred.id,
    rawId: bufToB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON:    bufToB64url(cred.response.clientDataJSON),
      authenticatorData: bufToB64url(cred.response.authenticatorData),
      signature:         bufToB64url(cred.response.signature),
      userHandle: cred.response.userHandle ? bufToB64url(cred.response.userHandle) : null
    },
    clientExtensionResults: cred.getClientExtensionResults()
  };
}

export async function postJson(url, body) {
  const csrf = document.querySelector('meta[name=csrf]').content;
  const res  = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
    body: JSON.stringify(body)
  });
  const env = await res.json();
  if (!env.success) {
    const err = new Error(env.message);
    err.code = env.code; err.traceId = env.traceId; err.fieldErrors = env.error?.fieldErrors;
    throw err;
  }
  return env.data;
}
```

### 6.2 등록 흐름 (register.html 인라인 JS)

```javascript
import { decodeCreationOptions, encodeAttestationCredential, postJson } from '/js/helpers.js';

document.getElementById('btn-register').addEventListener('click', async () => {
  const username    = document.getElementById('username').value;
  const displayName = document.getElementById('displayName').value;

  const start = await postJson('/webauthn/register/options', { username, displayName });

  const cred = await navigator.credentials.create({
    publicKey: decodeCreationOptions(start.publicKeyCredentialCreationOptions)
  });

  const fin = await postJson('/webauthn/register/complete', {
    publicKeyCredential: encodeAttestationCredential(cred)
  });

  showJson('result', fin);
});
```

### 6.3 로그인 흐름 (login.html 인라인 JS)

대칭. `decodeRequestOptions` + `navigator.credentials.get()` + `encodeAssertionCredential`. 성공 시 `window.location='/'`.

## 7. 부트스트랩 스크립트 + .env

### 7.1 사전 조건

- Passkey2 의 `docker-compose up -d` (Oracle, Redis) 실행 중
- `passkey-app:8080`, `admin-app:8081` 기동 상태
- admin-app 의 시드 관리자 계정 (Flyway V11) 존재

### 7.2 `sample-rp/scripts/bootstrap-sample-rp.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

ADMIN_BASE="${ADMIN_BASE:-http://localhost:8081}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
RP_ID="${RP_ID:-localhost}"
RP_NAME="${RP_NAME:-Sample RP}"
ORIGIN="${ORIGIN:-http://localhost:9090}"
ENV_FILE="$(dirname "$0")/../.env"

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

# 0. admin 로그인
curl -fsS -c "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  "$ADMIN_BASE/admin/api/auth/login" >/dev/null

# 1. demo tenant
TENANT_PAYLOAD=$(jq -nc \
  --arg id "sample-rp-demo" --arg name "Sample RP Demo" \
  --arg rpId "$RP_ID" --arg rpName "$RP_NAME" --arg origin "$ORIGIN" \
  '{tenantId:$id, displayName:$name, rpId:$rpId, rpName:$rpName,
    allowedOrigins:[$origin],
    attestationPolicy:{acceptedFormats:["none","packed"],
                       requireUserVerification:true, mdsRequired:false}}')

TENANT_RESP=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "$TENANT_PAYLOAD" "$ADMIN_BASE/admin/api/tenants")
TENANT_ID=$(echo "$TENANT_RESP" | jq -r '.data.tenantId // .data.id')

# 2. demo API key
KEY_RESP=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT_ID\",\"name\":\"sample-rp-bootstrap\",
       \"scopes\":[\"passkey:register\",\"passkey:authenticate\"]}" \
  "$ADMIN_BASE/admin/api/api-keys")

API_KEY=$(echo "$KEY_RESP" | jq -r '.data.plaintextKey')

# 3. .env
[ -f "$ENV_FILE" ] && cp "$ENV_FILE" "$ENV_FILE.bak"
cat > "$ENV_FILE" <<EOF
PASSKEY_BASE_URL=http://localhost:8080
PASSKEY_TENANT_ID=$TENANT_ID
PASSKEY_API_KEY=$API_KEY
SAMPLE_RP_ORIGIN=$ORIGIN
EOF

echo "✓ sample-rp/.env written"
echo "  tenantId : $TENANT_ID"
echo "  apiKey   : ${API_KEY:0:8}…  (full value in .env, 1회만 노출됨)"

# 4. health check
curl -fsS -H "X-API-Key: $API_KEY" \
  "http://localhost:8080/api/v1/rp/authentication/start" \
  -H 'Content-Type: application/json' -d '{}' \
  | jq -e '.success == true' >/dev/null \
  && echo "✓ X-API-Key validated against passkey-app" \
  || { echo "✗ X-API-Key validation failed"; exit 1; }
```

### 7.3 sample-rp 의 `.env` 소비

```bash
set -a; source .env; set +a
./gradlew bootRun
```

spring-dotenv 같은 외부 의존을 sample-rp 에 추가하지 않는다.

### 7.4 `.env.example` (커밋됨)

```
PASSKEY_BASE_URL=http://localhost:8080
PASSKEY_TENANT_ID=
PASSKEY_API_KEY=
SAMPLE_RP_ORIGIN=http://localhost:9090
```

실 `.env` 는 `.gitignore`.

### 7.5 운영자 7단계 흐름 (README 의 단일 진실 공급원)

```
[1] Passkey2: docker compose up -d
[2] Passkey2: ./gradlew :passkey-app:bootRun  (별 터미널)
[3] Passkey2: ./gradlew :admin-app:bootRun    (별 터미널)
[4] sdk-java: ./gradlew publishToMavenLocal
[5] sample-rp: ./scripts/bootstrap-sample-rp.sh
[6] sample-rp: set -a && source .env && set +a && ./gradlew bootRun
[7] 브라우저: http://localhost:9090
```

## 8. 테스트 전략 — 개발 속도 우선 (축소판)

### 8.1 자동 테스트는 2개만

| 테스트 | 위치 | 왜 필수 |
|---|---|---|
| `PasskeyClientContractIT` | `sdk-java` | passkey-app 응답 포맷 변경 시 가장 먼저 깨지는 회귀 채널. WireMock 으로 4 endpoint × success + JWKS stub. ~150줄. |
| `SampleRpSmokeIT` | `sample-rp` | webauthn4j-test ClientPlatform 으로 등록 → 로그인 happy path 1개. 풀 스택 생존 확인. |

그 외 unit, component, error-branch 테스트는 모두 제외.

### 8.2 의도적으로 제외

- SDK 4종 예외 분기 단위 테스트
- `IdTokenVerifierTest`, `JwksCacheTtlTest` (Nimbus 신뢰)
- sample-rp `GlobalExceptionHandler` 매핑 검증
- HttpSession 세션화 단위 테스트
- helpers.js 자동화 (Chrome devtools virtual authenticator 로 수동 1회)

### 8.3 수동 smoke 체크리스트 (README)

bootstrap → bootRun 후 5분 안에:
1. `http://localhost:9090/register` → 등록 → "Passkey registered"
2. `http://localhost:9090/login` → 로그인 → `/` 리다이렉트, 헤더 "로그인됨"
3. `/logout` → 세션 비움
4. 콘솔에 `X-Trace-Id` 가 sample-rp 와 passkey-app 양쪽 로그에 같은 값

### 8.4 진단 보조 (자동 테스트 부족분 보완)

- `application.yml` 의 `logging.level.com.crosscert.passkey.sdk: DEBUG` — SDK 가 보내는 요청·응답 envelope 이 로그에 남음.
- `GlobalExceptionHandler` 가 upstream traceId 를 로그에 명시 (§ 5.3).
- bootstrap 의 health-curl (§ 7.2 step 4) — 셋업 단계에서 X-API-Key 검증.

### 8.5 통과 기준

- `./gradlew :sdk-java:test` → `PasskeyClientContractIT` 통과
- `./gradlew :sample-rp:test` → `SampleRpSmokeIT` 통과
- bootstrap 끝의 health curl 통과
- 수동 smoke 4단계 통과

## 9. 위험 요소와 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| passkey-app 응답 envelope 스키마 변경 | SDK wire 호환 깨짐, sample-rp 동반 사망 | `PasskeyClientContractIT` 가 WireMock stub 으로 "오늘의 wire 스냅샷" 보유. passkey-app phase PR 에 SDK contract test 갱신이 묶여 들어가야 함을 README 에 명시. |
| `mavenLocal()` 캐시 stale | sample-rp 가 옛 SDK 로 동작 | 7단계 흐름의 `[4] publishToMavenLocal` 를 매번 다시 돌리도록 README 강제. 의심 시 `~/.m2/repository/com/crosscert/passkey/passkey-sdk-java/` 삭제. |
| API key 가 `.env` 평문 + git 누출 | tenant credential 유출 | `sample-rp/.gitignore` 에 `.env`. bootstrap 출력은 prefix 8자만. |
| RP origin 과 tenant `allowed_origins` 불일치 | webauthn4j 400, 메시지 불친절 | bootstrap 이 `ORIGIN` env 로 명시 채움. README 에 "포트 바꾸면 tenant 다시 생성". |
| `localhost` 와 `127.0.0.1` 별개 취급 | RP id 매칭 실패 | RP id `localhost` 고정. README + helpers.js 주석 명시. |
| Spring Session in-memory → 재시작 시 PENDING token 유실 | UX 미세 거슬림 | dogfood 범위 수용. README "재시작 후 등록 다시". |
| WebAuthn HTTPS 강제 (localhost 예외) | 다른 호스트 데모 불가 | `localhost` 전용. ngrok/Caddy 는 후속. |
| Chrome virtual authenticator 부재 환경 | 수동 smoke 막힘 | README 에 Chrome devtools WebAuthn 패널 활성화 1단락. 실 인증기 (Touch ID/Hello) 그대로 사용 가능. |
| SDK `traceIdProvider` 기본값과 sample-rp `TraceIdFilter` MDC 키 불일치 | traceId 전파 조용히 깨짐 | 양쪽 `MDC_KEY = "traceId"` 상수. sample-rp 주석에 "SDK 기본값과 동일" 명시. |
| Bucket4j rate limit 에 데모가 걸려 429 | 시연 중단 | Phase 1 정책 (60/min) 이 데모엔 충분. sample-rp tenant rate limit override 는 후속 항목. |
| SDK DEBUG 로그에 ID Token / publicKeyCredential 평문 노출 | 데모 환경에선 무해하지만 운영자가 같은 설정으로 prod 띄울 때 자격증명·credential 이 로그에 남음 | (a) `application.yml` 의 `logging.level.com.crosscert.passkey.sdk: DEBUG` 옆에 "운영 환경에서는 INFO 이하" 주석 명시. (b) SDK 의 요청·응답 로그에서 `idToken`, `publicKeyCredential.response.*` 필드는 길이만 출력하고 값은 마스킹 (`<redacted, len=N>`). (c) 마스킹 책임은 SDK 측 RestClient interceptor 가 보유. |

## 10. 후속 작업

`docs/superpowers/followups/2026-05-27-sdk-and-sample-rp-followups.md` 에 다음 8개 항목 기록:

1. Maven Central 배포 + group/artifact 확정
2. SDK 자동 재시도/서킷브레이커 (Resilience4j 옵션)
3. JWKS pre-warm 옵션
4. sample-rp 영구 저장소 (H2 file / Postgres)
5. 멀티 tenant sample-rp 시연
6. Kotlin/Scala 친화 표면 (DSL 빌더)
7. WebAuthn level 3 마이그레이션
8. admin 2FA via passkey (admin dogfood) — 원래 Phase 4 합의의 두 번째 절반
