# SDK Kotlin → Java 환원 + 통합 가이드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdk-java` 모듈의 24개 Kotlin 파일을 순수 Java로 환원하고(Kotlin 런타임 의존성 제거), 외부 RP 개발자용 통합 가이드 `sdk-java/README.md`를 작성한다.

**Architecture:** DTO/envelope/IdTokenClaims는 Java `record`로, `PasskeyClientConfig`는 Builder 패턴으로(필수값은 `builder(...)` 인자 강제, `defaults()`는 Builder에 위임해 보존), 나머지는 일반 Java class로 1:1 직번역한다. 동작 동일성은 이미 Java로 작성된 기존 테스트(WireMock 계약 + idtoken 알고리즘 + 동적 키)로 보장한다. `rp-app`은 Kotlin을 유지하고 SDK 호출부 1곳만 Builder API에 맞춰 수정한다.

**Tech Stack:** Java 17 (record), Spring Web `RestClient`, Jackson (databind + jsr310), nimbus-jose-jwt 9.40, SLF4J, JUnit 5 + AssertJ + WireMock 3.10.

## Global Constraints

- 패키지/클래스/메서드 시그니처는 **현재 공개 API와 동일하게 유지**한다 (소비처 호환). 접근자는 record 스타일(`config.baseUrl()`, `claims.iss()`)을 유지한다.
- Kotlin 소스(`src/main/kotlin/**`)는 환원 완료 후 **전부 삭제**한다. 산출물은 `src/main/java/com/crosscert/passkey/sdk/**`.
- 모든 record 컴포넌트에 `@JsonProperty("...")`를 **명시**한다 → Jackson 역직렬화가 파라미터명에 의존하지 않게 하여 `-parameters` 컴파일 옵션 없이 안전.
- 로거는 명시적 `LoggerFactory.getLogger(...)`를 쓴다 (Lombok `@Slf4j` 미사용 — 원본 동작 보존 + Lombok 바이트코드 검증 함정 회피).
- 검증은 **모듈 단위**로 한다: `./gradlew :sdk-java:test`, `:rp-app:compileKotlin`. 전체 `./gradlew build`는 SliceConfig 충돌·Oracle 컨테이너 경합으로 항상 실패하므로 머지 게이트로 쓰지 않는다.
- 작업 디렉토리는 격리 worktree `.claude/worktrees/sdk-java-revert` (브랜치 `sdk-java-revert`). 모든 커맨드/커밋은 이 worktree 안에서. 절대경로로 메인 repo에 쓰지 않도록 상대경로 사용 + `git status`로 검증.
- nimbus/spring/jackson 의존성 버전·좌표는 기존 `build.gradle.kts`/root BOM이 관리하는 값을 그대로 유지한다.

---

## 파일 구조

환원 대상 (전부 `sdk-java/src/main/java/com/crosscert/passkey/sdk/` 하위, 기존 `.kt`는 삭제):

| 패키지 | 파일 | 형태 |
|---|---|---|
| `dto` | RegistrationStartRequest, RegistrationStartResponse, RegistrationFinishRequest, RegistrationFinishResponse, AuthenticationStartRequest, AuthenticationStartResponse, AuthenticationFinishRequest, AuthenticationFinishResponse | record |
| `envelope` | ApiResponseEnvelope&lt;T&gt;, EnvelopeError, EnvelopeFieldError | record |
| `exception` | PasskeyApiException, PasskeyAuthException, PasskeyRateLimitException, PasskeyConfigurationException, PasskeyIdTokenException | class |
| `idtoken` | IdTokenClaims (record), IdTokenVerifier (class), JwksCache (class) | record + class |
| `internal` | PasskeyResponseErrorHandler, RedactingRequestInterceptor, TraceIdPropagationInterceptor | class |
| (root) | PasskeyClientConfig (Builder), PasskeyClient | class |

수정 대상:
- `sdk-java/build.gradle.kts` — Kotlin 플러그인/의존성 제거.
- `rp-app/src/main/kotlin/.../config/PasskeyClientConfiguration.kt` — Builder 호출로 변경 (Kotlin 유지).
- 테스트는 `defaults()` 보존으로 **무수정** (Task 7에서 확인).

작업 순서: 의존성 바닥(envelope → exception → dto → idtoken → internal)부터 위로 쌓고, Config·Client를 마지막에, 그다음 빌드/소비처/검증, 마지막에 가이드 문서.

---

### Task 1: envelope 패키지 → record

**Files:**
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope/EnvelopeFieldError.java`
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope/EnvelopeError.java`
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope/ApiResponseEnvelope.java`
- Delete: 동일 3개 `.kt`

**Interfaces:**
- Produces:
  - `EnvelopeFieldError(String field, Object rejectedValue, String reason)` — 접근자 `field()`, `rejectedValue()`, `reason()`.
  - `EnvelopeError(String errorCode, List<EnvelopeFieldError> fieldErrors)` — 접근자 `errorCode()`, `fieldErrors()`.
  - `ApiResponseEnvelope<T>(boolean success, String code, String message, T data, EnvelopeError error, String traceId, String timestamp)` — 접근자 `success()`, `code()`, `message()`, `data()`, `error()`, `traceId()`, `timestamp()`.

- [ ] **Step 1: EnvelopeFieldError.java 작성**

```java
package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeFieldError(
    @JsonProperty("field") String field,
    @JsonProperty("rejectedValue") Object rejectedValue,
    @JsonProperty("reason") String reason
) {}
```

- [ ] **Step 2: EnvelopeError.java 작성**

```java
package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeError(
    @JsonProperty("errorCode") String errorCode,
    @JsonProperty("fieldErrors") List<EnvelopeFieldError> fieldErrors
) {}
```

- [ ] **Step 3: ApiResponseEnvelope.java 작성**

```java
package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseEnvelope<T>(
    @JsonProperty("success") boolean success,
    @JsonProperty("code") String code,
    @JsonProperty("message") String message,
    @JsonProperty("data") T data,
    @JsonProperty("error") EnvelopeError error,
    @JsonProperty("traceId") String traceId,
    @JsonProperty("timestamp") String timestamp
) {}
```

- [ ] **Step 4: 기존 .kt 3개 삭제**

```bash
rm sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/envelope/EnvelopeFieldError.kt \
   sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/envelope/EnvelopeError.kt \
   sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/envelope/ApiResponseEnvelope.kt
```

- [ ] **Step 5: 커밋**

```bash
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/envelope
git commit -m "refactor(sdk): envelope 패키지 Kotlin→Java record 환원"
```

> 컴파일은 Task 9(Config/Client)까지 끝나야 통과하므로 개별 Task에서는 컴파일을 돌리지 않는다. 빌드 검증은 Task 10에 집약한다.

---

### Task 2: exception 패키지 → Java class

**Files:**
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/exception/PasskeyApiException.java`
- Create: `.../exception/PasskeyAuthException.java`
- Create: `.../exception/PasskeyRateLimitException.java`
- Create: `.../exception/PasskeyConfigurationException.java`
- Create: `.../exception/PasskeyIdTokenException.java`
- Delete: 동일 5개 `.kt`

**Interfaces:**
- Consumes: `EnvelopeFieldError` (Task 1).
- Produces:
  - `PasskeyApiException(int httpStatus, String code, String message, String traceId, List<EnvelopeFieldError> fieldErrors)` extends `RuntimeException`. 접근자 `getHttpStatus()`, `getCode()`, `getTraceId()`, `getFieldErrors()` (List, null→빈 리스트, 방어복사).
  - `PasskeyAuthException(String code, String message, String traceId, List<EnvelopeFieldError> fieldErrors)` extends `PasskeyApiException` (httpStatus=401 고정).
  - `PasskeyRateLimitException(String code, String message, String traceId, List<EnvelopeFieldError> fieldErrors, long retryAfterSeconds)` extends `PasskeyApiException` (httpStatus=429). 접근자 `getRetryAfterSeconds()`.
  - `PasskeyConfigurationException(String message)` extends `RuntimeException`.
  - `PasskeyIdTokenException(String message)` + `PasskeyIdTokenException(String message, Throwable cause)` extends `RuntimeException`.

> 접근자 명: Kotlin `val httpStatus` 는 JVM에서 `getHttpStatus()` 였다(클래스이므로 `@JvmName` 미적용 시 Java bean getter). `PasskeyRateLimitException.retryAfterSeconds` 만 `@get:JvmName("retryAfterSeconds")` 였으나, Java에서 `getRetryAfterSeconds()` 로 환원해도 소비처(테스트/rp-app) 어디서도 이 필드를 읽지 않으므로(전수 확인) 영향 없다. 일관성을 위해 bean getter 로 통일한다.

- [ ] **Step 1: PasskeyApiException.java 작성**

```java
package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import java.util.List;

public class PasskeyApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final String traceId;
    private final List<EnvelopeFieldError> fieldErrors;

    public PasskeyApiException(int httpStatus, String code, String message,
                              String traceId, List<EnvelopeFieldError> fieldErrors) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.traceId = traceId;
        this.fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public String getTraceId() { return traceId; }
    public List<EnvelopeFieldError> getFieldErrors() { return fieldErrors; }
}
```

- [ ] **Step 2: PasskeyAuthException.java 작성**

```java
package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import java.util.List;

public class PasskeyAuthException extends PasskeyApiException {
    public PasskeyAuthException(String code, String message, String traceId,
                               List<EnvelopeFieldError> fieldErrors) {
        super(401, code, message, traceId, fieldErrors);
    }
}
```

- [ ] **Step 3: PasskeyRateLimitException.java 작성**

```java
package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import java.util.List;

public class PasskeyRateLimitException extends PasskeyApiException {
    private final long retryAfterSeconds;

    public PasskeyRateLimitException(String code, String message, String traceId,
                                    List<EnvelopeFieldError> fieldErrors, long retryAfterSeconds) {
        super(429, code, message, traceId, fieldErrors);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
```

- [ ] **Step 4: PasskeyConfigurationException.java 작성**

```java
package com.crosscert.passkey.sdk.exception;

/**
 * SDK 설정 오류 — 요청이 네트워크를 타기 전에 감지되는 클라이언트측 구성 문제.
 * 예: API key Supplier 가 null/blank 를 반환(키 소스 미설정/비워짐).
 *
 * wire 응답이 아니므로 PasskeyApiException 의 httpStatus/code/traceId 계약에
 * 들어맞지 않아 별도 타입으로 분리한다. unchecked — 잘못된 설정은 fail-fast.
 */
public class PasskeyConfigurationException extends RuntimeException {
    public PasskeyConfigurationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: PasskeyIdTokenException.java 작성**

```java
package com.crosscert.passkey.sdk.exception;

public class PasskeyIdTokenException extends RuntimeException {
    public PasskeyIdTokenException(String message) {
        super(message);
    }
    public PasskeyIdTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 6: 기존 .kt 5개 삭제 후 커밋**

```bash
rm sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/exception/*.kt
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/exception sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/exception
git commit -m "refactor(sdk): exception 패키지 Kotlin→Java class 환원"
```

---

### Task 3: dto 패키지 → record

**Files:**
- Create: 8개 `.java` (아래 Step), Delete: 동일 8개 `.kt`

**Interfaces:**
- Produces (접근자는 record 컴포넌트명과 동일):
  - `RegistrationStartRequest(String userHandle, String displayName, String username)`
  - `RegistrationStartResponse(String registrationToken, JsonNode publicKeyCredentialCreationOptions)`
  - `RegistrationFinishRequest(String registrationToken, JsonNode publicKeyCredential)`
  - `RegistrationFinishResponse(String credentialId, String aaguid, String attestationFormat, String createdAt)`
  - `AuthenticationStartRequest(String userHandle)` — `@JsonInclude(NON_NULL)`, userHandle nullable
  - `AuthenticationStartResponse(String authenticationToken, JsonNode publicKeyCredentialRequestOptions)`
  - `AuthenticationFinishRequest(String authenticationToken, JsonNode publicKeyCredential)`
  - `AuthenticationFinishResponse(String idToken, String tokenType, long expiresIn)`

- [ ] **Step 1: RegistrationStartRequest.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegistrationStartRequest(
    @JsonProperty("userHandle") String userHandle,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("username") String username
) {}
```

- [ ] **Step 2: RegistrationStartResponse.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationStartResponse(
    @JsonProperty("registrationToken") String registrationToken,
    @JsonProperty("publicKeyCredentialCreationOptions") JsonNode publicKeyCredentialCreationOptions
) {}
```

- [ ] **Step 3: RegistrationFinishRequest.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record RegistrationFinishRequest(
    @JsonProperty("registrationToken") String registrationToken,
    @JsonProperty("publicKeyCredential") JsonNode publicKeyCredential
) {}
```

- [ ] **Step 4: RegistrationFinishResponse.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationFinishResponse(
    @JsonProperty("credentialId") String credentialId,
    @JsonProperty("aaguid") String aaguid,
    @JsonProperty("attestationFormat") String attestationFormat,
    @JsonProperty("createdAt") String createdAt
) {}
```

- [ ] **Step 5: AuthenticationStartRequest.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthenticationStartRequest(
    @JsonProperty("userHandle") String userHandle
) {}
```

- [ ] **Step 6: AuthenticationStartResponse.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationStartResponse(
    @JsonProperty("authenticationToken") String authenticationToken,
    @JsonProperty("publicKeyCredentialRequestOptions") JsonNode publicKeyCredentialRequestOptions
) {}
```

- [ ] **Step 7: AuthenticationFinishRequest.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record AuthenticationFinishRequest(
    @JsonProperty("authenticationToken") String authenticationToken,
    @JsonProperty("publicKeyCredential") JsonNode publicKeyCredential
) {}
```

- [ ] **Step 8: AuthenticationFinishResponse.java**

```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationFinishResponse(
    @JsonProperty("idToken") String idToken,
    @JsonProperty("tokenType") String tokenType,
    @JsonProperty("expiresIn") long expiresIn
) {}
```

- [ ] **Step 9: 기존 .kt 8개 삭제 후 커밋**

```bash
rm sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/dto/*.kt
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/dto sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/dto
git commit -m "refactor(sdk): dto 패키지 Kotlin→Java record 환원"
```

---

### Task 4: idtoken 패키지 → record + class

**Files:**
- Create: `.../idtoken/IdTokenClaims.java`, `.../idtoken/JwksCache.java`, `.../idtoken/IdTokenVerifier.java`
- Delete: 동일 3개 `.kt`

**Interfaces:**
- Consumes: `PasskeyIdTokenException` (Task 2).
- Produces:
  - `IdTokenClaims(String iss, String sub, String aud, Instant iat, Instant exp, List<String> amr, String credId, String aaguid)` — record, 접근자 `iss()` 등.
  - `JwksCache(URI baseUrl, Duration ttl, Clock clock)` — `JWKSet get()`.
  - `IdTokenVerifier(JwksCache jwks, Clock clock)` — `IdTokenClaims verify(String compactJwt)`.

> `IdTokenVerifierAlgTest.java` 가 `new JwksCache(baseUrl, ttl, clock)` 와 `new IdTokenVerifier(jwks, clock)` 를 직접 호출하므로 이 두 생성자 시그니처를 반드시 보존한다.

- [ ] **Step 1: IdTokenClaims.java (record)**

```java
package com.crosscert.passkey.sdk.idtoken;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record IdTokenClaims(
    @JsonProperty("iss") String iss,
    @JsonProperty("sub") String sub,
    @JsonProperty("aud") String aud,
    @JsonProperty("iat") Instant iat,
    @JsonProperty("exp") Instant exp,
    @JsonProperty("amr") List<String> amr,
    @JsonProperty("credId") String credId,
    @JsonProperty("aaguid") String aaguid
) {}
```

- [ ] **Step 2: JwksCache.java**

```java
package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class JwksCache {

    private final URL jwksUrl;
    private final Duration ttl;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public JwksCache(URI baseUrl, Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
        try {
            this.jwksUrl = baseUrl.resolve("/.well-known/jwks.json").toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad baseUrl for JWKS", e);
        }
    }

    public JWKSet get() {
        Snapshot cur = snapshot.get();
        Instant now = clock.instant();
        if (cur != null && cur.fetchedAt().plus(ttl).isAfter(now)) {
            return cur.jwks();
        }
        JWKSet fresh = fetch();
        snapshot.set(new Snapshot(fresh, now));
        return fresh;
    }

    private JWKSet fetch() {
        try {
            return JWKSet.load(jwksUrl);
        } catch (IOException | ParseException e) {
            throw new PasskeyIdTokenException("JWKS fetch failed: " + jwksUrl, e);
        }
    }

    private record Snapshot(JWKSet jwks, Instant fetchedAt) {}
}
```

- [ ] **Step 3: IdTokenVerifier.java**

```java
package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

public class IdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(IdTokenVerifier.class);

    private final JwksCache jwks;
    private final Clock clock;

    public IdTokenVerifier(JwksCache jwks, Clock clock) {
        this.jwks = jwks;
        this.clock = clock;
    }

    public IdTokenClaims verify(String compactJwt) {
        Instant started = clock.instant();
        try {
            SignedJWT jwt = SignedJWT.parse(compactJwt);
            // Pin alg to RS256 — the issuer signs exclusively with RS256, so this
            // rejects alg-confusion / "none" / HS* downgrade attempts.
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                log.warn("id-token verify failed: reason=unexpected-alg alg={}",
                        jwt.getHeader().getAlgorithm());
                throw new PasskeyIdTokenException(
                        "Unexpected JWS algorithm: " + jwt.getHeader().getAlgorithm());
            }
            String kid = jwt.getHeader().getKeyID();
            JWK key = jwks.get().getKeyByKeyId(kid);
            if (!(key instanceof RSAKey rsa)) {
                log.warn("id-token verify failed: reason=unknown-kid kid={}", kid);
                throw new PasskeyIdTokenException("Unknown or non-RSA kid: " + kid);
            }
            RSASSAVerifier verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            if (!jwt.verify(verifier)) {
                log.warn("id-token verify failed: reason=signature kid={}", kid);
                throw new PasskeyIdTokenException("Signature verification failed");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Date expDate = c.getExpirationTime();
            Instant exp = (expDate == null) ? null : expDate.toInstant();
            if (exp == null || !exp.isAfter(now)) {
                log.warn("id-token verify failed: reason=expired exp={} now={}", exp, now);
                throw new PasskeyIdTokenException("ID Token expired (exp=" + exp + ", now=" + now + ")");
            }
            @SuppressWarnings("unchecked")
            List<String> amr = (List<String>) c.getClaim("amr");
            long durMs = Duration.between(started, clock.instant()).toMillis();
            // sub is the opaque userHandle; truncate so logs don't leak the full id.
            String subShort = truncate(c.getSubject(), 8);
            log.info("id-token verified: reason=success iss={} sub={} durMs={}",
                    c.getIssuer(), subShort, durMs);
            Date iatDate = c.getIssueTime();
            return new IdTokenClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    (c.getAudience() == null || c.getAudience().isEmpty()) ? null : c.getAudience().get(0),
                    (iatDate == null) ? null : iatDate.toInstant(),
                    exp,
                    (amr == null) ? List.of() : List.copyOf(amr),
                    (String) c.getClaim("cred_id"),
                    (String) c.getClaim("aaguid"));
        } catch (PasskeyIdTokenException e) {
            // Already logged at the precise reason branch above; rethrow.
            throw e;
        } catch (Exception e) {
            // Parse/structural failure (malformed JWT, JWK fetch error, etc.)
            log.warn("id-token verify failed: reason=parse cause={}", e.toString());
            throw new PasskeyIdTokenException("ID Token parsing failed", e);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return (s.length() <= n) ? s : s.substring(0, n) + "...";
    }
}
```

> 주의: 원본 Kotlin은 `c.issuer` 가 String 을 직접 반환했다. nimbus `JWTClaimsSet.getIssuer()` 도 String 이라 `IdTokenClaims.iss` 에 그대로 전달. `getAudience()` 는 `List<String>` 반환이므로 first-or-null 로직 유지.

- [ ] **Step 4: 기존 .kt 3개 삭제 후 커밋**

```bash
rm sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/idtoken/*.kt
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/idtoken
git commit -m "refactor(sdk): idtoken 패키지 Kotlin→Java 환원"
```

---

### Task 5: PasskeyClientConfig → Builder 패턴

**Files:**
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java`
- Delete: `.../PasskeyClientConfig.kt`

**Interfaces:**
- Produces:
  - `PasskeyClientConfig.builder(URI baseUrl, Supplier<String> apiKeySupplier)` → `Builder`.
  - `Builder.connectTimeout(Duration)`, `.readTimeout(Duration)`, `.jwksCacheTtl(Duration)`, `.clock(Clock)`, `.traceIdProvider(Supplier<String>)`, `.build()`.
  - `PasskeyClientConfig.defaults(URI, Supplier<String>)` → `PasskeyClientConfig` (Builder 위임, 테스트 호환 보존).
  - 접근자: `baseUrl()`, `apiKeySupplier()`, `connectTimeout()`, `readTimeout()`, `jwksCacheTtl()`, `clock()`, `traceIdProvider()`.
  - 상수: `PasskeyClientConfig.MDC_TRACE_ID_KEY = "traceId"`.
- 기본값: connect 3s, read 10s, jwksTtl 5m, clock systemUTC, traceIdProvider `MDC.get("traceId")`.

- [ ] **Step 1: PasskeyClientConfig.java 작성**

```java
package com.crosscert.passkey.sdk;

import org.slf4j.MDC;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * SDK 클라이언트 설정. 필수값(baseUrl, apiKeySupplier)은 {@link #builder} 인자로
 * 강제되고, 선택값은 미설정 시 기본값으로 치환된다.
 *
 * apiKeySupplier/traceIdProvider 의 반환값 null/blank 검증은 생성 시점이 아니라
 * 매 요청 시점(인터셉터)에서 한다 — 키가 도중에 비워질 수 있으므로.
 */
public final class PasskeyClientConfig {

    public static final String MDC_TRACE_ID_KEY = "traceId";

    private final URI baseUrl;
    private final Supplier<String> apiKeySupplier;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration jwksCacheTtl;
    private final Clock clock;
    private final Supplier<String> traceIdProvider;

    private PasskeyClientConfig(Builder b) {
        this.baseUrl = Objects.requireNonNull(b.baseUrl, "baseUrl");
        this.apiKeySupplier = Objects.requireNonNull(b.apiKeySupplier, "apiKeySupplier");
        this.connectTimeout = (b.connectTimeout != null) ? b.connectTimeout : Duration.ofSeconds(3);
        this.readTimeout = (b.readTimeout != null) ? b.readTimeout : Duration.ofSeconds(10);
        this.jwksCacheTtl = (b.jwksCacheTtl != null) ? b.jwksCacheTtl : Duration.ofMinutes(5);
        this.clock = (b.clock != null) ? b.clock : Clock.systemUTC();
        this.traceIdProvider = (b.traceIdProvider != null)
                ? b.traceIdProvider
                : () -> MDC.get(MDC_TRACE_ID_KEY);
    }

    public URI baseUrl() { return baseUrl; }
    public Supplier<String> apiKeySupplier() { return apiKeySupplier; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
    public Duration jwksCacheTtl() { return jwksCacheTtl; }
    public Clock clock() { return clock; }
    public Supplier<String> traceIdProvider() { return traceIdProvider; }

    public static Builder builder(URI baseUrl, Supplier<String> apiKeySupplier) {
        return new Builder(baseUrl, apiKeySupplier);
    }

    /** 모든 선택값에 기본값을 적용한 설정. (기존 외부 소비자/테스트 호환) */
    public static PasskeyClientConfig defaults(URI baseUrl, Supplier<String> apiKeySupplier) {
        return builder(baseUrl, apiKeySupplier).build();
    }

    public static final class Builder {
        private final URI baseUrl;
        private final Supplier<String> apiKeySupplier;
        private Duration connectTimeout;
        private Duration readTimeout;
        private Duration jwksCacheTtl;
        private Clock clock;
        private Supplier<String> traceIdProvider;

        private Builder(URI baseUrl, Supplier<String> apiKeySupplier) {
            this.baseUrl = baseUrl;
            this.apiKeySupplier = apiKeySupplier;
        }

        /** null 이면 기본값(3s) 적용. */
        public Builder connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        /** null 이면 기본값(10s) 적용. */
        public Builder readTimeout(Duration v) { this.readTimeout = v; return this; }
        /** null 이면 기본값(5m) 적용. */
        public Builder jwksCacheTtl(Duration v) { this.jwksCacheTtl = v; return this; }
        /** null 이면 기본값(systemUTC) 적용. */
        public Builder clock(Clock v) { this.clock = v; return this; }
        /** null 이면 기본값(MDC.get("traceId")) 적용. */
        public Builder traceIdProvider(Supplier<String> v) { this.traceIdProvider = v; return this; }

        public PasskeyClientConfig build() { return new PasskeyClientConfig(this); }
    }
}
```

- [ ] **Step 2: 기존 .kt 삭제 후 커밋**

```bash
rm sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/PasskeyClientConfig.kt
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/PasskeyClientConfig.kt
git commit -m "refactor(sdk): PasskeyClientConfig Builder 패턴 Java 환원"
```

---

### Task 6: internal 패키지 → Java class

**Files:**
- Create: `.../internal/TraceIdPropagationInterceptor.java`, `.../internal/RedactingRequestInterceptor.java`, `.../internal/PasskeyResponseErrorHandler.java`
- Delete: 동일 3개 `.kt`

**Interfaces:**
- Consumes: `PasskeyClientConfig` (Task 5), `ApiResponseEnvelope`/`EnvelopeError`/`EnvelopeFieldError` (Task 1), `PasskeyApiException`/`PasskeyAuthException`/`PasskeyRateLimitException`/`PasskeyConfigurationException` (Task 2).
- Produces:
  - `TraceIdPropagationInterceptor(PasskeyClientConfig)` implements `ClientHttpRequestInterceptor`.
  - `RedactingRequestInterceptor(PasskeyClientConfig)` implements `ClientHttpRequestInterceptor`.
  - `PasskeyResponseErrorHandler(ObjectMapper)` implements `ResponseErrorHandler`.

- [ ] **Step 1: TraceIdPropagationInterceptor.java**

```java
package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import java.io.IOException;

/**
 * 호출자의 traceId(기본=MDC)를 X-Trace-Id 로 전파. RedactingRequestInterceptor 보다
 * 먼저 등록되어야 redaction DEBUG 로그 시점에 헤더가 존재한다.
 */
public class TraceIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    private static final String TRACE_HEADER = "X-Trace-Id";

    private final PasskeyClientConfig config;

    public TraceIdPropagationInterceptor(PasskeyClientConfig config) {
        this.config = config;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution exec) throws IOException {
        String traceId = config.traceIdProvider().get();
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(TRACE_HEADER, traceId);
        }
        return exec.execute(request, body);
    }
}
```

- [ ] **Step 2: RedactingRequestInterceptor.java**

```java
package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.PasskeyClientConfig;
import com.crosscert.passkey.sdk.exception.PasskeyConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * SDK 의 DEBUG 로그가 ID Token / publicKeyCredential 평문을 남기지 않도록 본문 일부를
 * 마스킹해 출력. 운영 환경에선 logging.level 을 INFO 이하로.
 */
public class RedactingRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RedactingRequestInterceptor.class);

    /**
     * X-API-Key: pk_<8-prefix><secret> 렌더링에서 secret 꼬리만 마스킹. 11자 prefix
     * (pk_ + base64url 8자)는 로그 상관용으로 보존. 헤더명 대소문자 무시.
     * 문자 클래스는 base64url(A-Z a-z 0-9 _ -).
     */
    static final Pattern API_KEY_HEADER =
            Pattern.compile("(?i)(X-API-Key\\s*[:=]\\s*\"?pk_[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+");

    private final Supplier<String> apiKeySupplier;

    public RedactingRequestInterceptor(PasskeyClientConfig config) {
        this.apiKeySupplier = config.apiKeySupplier();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution exec) throws IOException {
        // 매 요청마다 현재 유효 키를 다시 묻는다 — "재기동 없는 교체"의 심장.
        String currentKey = apiKeySupplier.get();
        if (currentKey == null || currentKey.isBlank()) {
            // fail-fast: null/blank 키로 호출하면 서버가 401 을 주고 그 원인이 SDK
            // 설정 문제임이 드러나지 않는다. 여기서 명확히 끊는다.
            throw new PasskeyConfigurationException(
                    "API key supplier returned null/blank — check api key source");
        }
        request.getHeaders().set("X-API-Key", currentKey);
        if (log.isDebugEnabled()) {
            log.debug("→ {} {} body={}", request.getMethod(), request.getURI(),
                    redact(new String(body, StandardCharsets.UTF_8)));
        }
        ClientHttpResponse res = exec.execute(request, body);
        if (log.isDebugEnabled()) {
            log.debug("← {} {} status={}", request.getMethod(), request.getURI(), res.getStatusCode());
        }
        return res;
    }

    /** ID Token + publicKeyCredential.response.* 값은 길이만 노출. */
    static String redact(String json) {
        if (json == null || json.isEmpty()) return json;
        String out = json
                .replaceAll("(?s)\"idToken\"\\s*:\\s*\"([^\"]{0,16}).*?\"",
                        "\"idToken\":\"$1…<redacted>\"")
                .replaceAll("(?s)\"(clientDataJSON|attestationObject|authenticatorData|signature)\"\\s*:\\s*\"[^\"]*\"",
                        "\"$1\":\"<redacted>\"");
        out = API_KEY_HEADER.matcher(out).replaceAll("$1<redacted>");
        return out;
    }
}
```

> 주의: Kotlin `String.replace(Regex, replacement)` 는 Java `String.replaceAll(regex, replacement)` 와 동일 의미(전체 치환, `$1` 백레퍼런스). `…`(U+2026) 리터럴 보존.

- [ ] **Step 3: PasskeyResponseErrorHandler.java**

```java
package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import java.io.IOException;
import java.util.List;

public class PasskeyResponseErrorHandler implements ResponseErrorHandler {

    private final ObjectMapper objectMapper;

    public PasskeyResponseErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        byte[] bodyBytes = response.getBody().readAllBytes();
        ApiResponseEnvelope<?> env = parseQuietly(bodyBytes);

        String code;
        String message;
        String traceId;
        List<EnvelopeFieldError> fieldErrors;
        // code 가 없는 envelope 은 비-envelope 응답(예: RFC 7807 problem+json)으로 취급.
        if (env != null && env.code() != null) {
            code = env.code();
            message = env.message();
            traceId = env.traceId();
            fieldErrors = (env.error() == null) ? null : env.error().fieldErrors();
        } else {
            code = "C999";
            message = "Upstream error (no envelope)";
            traceId = null;
            fieldErrors = null;
        }

        if (status == 401) {
            throw new PasskeyAuthException(code, message, traceId, fieldErrors);
        }
        if (status == 429) {
            String retryAfter = response.getHeaders().getFirst("Retry-After");
            long retry = (retryAfter == null) ? 0 : Long.parseLong(retryAfter);
            throw new PasskeyRateLimitException(code, message, traceId, fieldErrors, retry);
        }
        throw new PasskeyApiException(status, code, message, traceId, fieldErrors);
    }

    private ApiResponseEnvelope<?> parseQuietly(byte[] body) {
        try {
            if (body == null || body.length == 0) {
                return null;
            }
            return objectMapper.readValue(body, ApiResponseEnvelope.class);
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 기존 .kt 3개 삭제 후 커밋**

```bash
rm sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/internal/*.kt
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/internal sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/internal
git commit -m "refactor(sdk): internal 패키지 Kotlin→Java 환원"
```

---

### Task 7: PasskeyClient → Java class

**Files:**
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java`
- Delete: `.../PasskeyClient.kt`

**Interfaces:**
- Consumes: 전 Task 산출물 전부.
- Produces:
  - `PasskeyClient.of(PasskeyClientConfig)` → `PasskeyClient` (static factory).
  - `RegistrationStartResponse registrationStart(RegistrationStartRequest)`
  - `RegistrationFinishResponse registrationFinish(RegistrationFinishRequest)`
  - `AuthenticationStartResponse authenticationStart(AuthenticationStartRequest)`
  - `AuthenticationFinishResponse authenticationFinish(AuthenticationFinishRequest)`
  - `IdTokenClaims verifyIdToken(String compactJwt)`

- [ ] **Step 1: PasskeyClient.java 작성**

```java
package com.crosscert.passkey.sdk;

import com.crosscert.passkey.sdk.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.sdk.dto.AuthenticationStartRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationStartResponse;
import com.crosscert.passkey.sdk.dto.RegistrationFinishRequest;
import com.crosscert.passkey.sdk.dto.RegistrationFinishResponse;
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import com.crosscert.passkey.sdk.idtoken.IdTokenVerifier;
import com.crosscert.passkey.sdk.idtoken.JwksCache;
import com.crosscert.passkey.sdk.internal.PasskeyResponseErrorHandler;
import com.crosscert.passkey.sdk.internal.RedactingRequestInterceptor;
import com.crosscert.passkey.sdk.internal.TraceIdPropagationInterceptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

public class PasskeyClient {

    private static final Logger log = LoggerFactory.getLogger(PasskeyClient.class);

    private final RestClient http;
    private final IdTokenVerifier idTokenVerifier;
    private final ObjectMapper objectMapper;

    private PasskeyClient(PasskeyClientConfig config) {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // JdkClientHttpRequestFactory (Java 11 HttpClient) — SimpleClientHttpRequestFactory 는
        // streaming mode 때문에 getErrorStream() 이 4xx/5xx 본문을 못 돌려줘 에러 envelope
        // 파싱이 항상 실패한다.
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(jdkClient);
        rf.setReadTimeout(config.readTimeout());

        this.http = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                // 순서 중요: TraceIdPropagation 이 먼저 → X-Trace-Id 가 redaction 로그 시점에 존재.
                .requestInterceptor(new TraceIdPropagationInterceptor(config))
                .requestInterceptor(new RedactingRequestInterceptor(config))
                .requestFactory(rf)
                .defaultStatusHandler(new PasskeyResponseErrorHandler(objectMapper))
                .build();

        this.idTokenVerifier = new IdTokenVerifier(
                new JwksCache(config.baseUrl(), config.jwksCacheTtl(), config.clock()),
                config.clock());
    }

    public static PasskeyClient of(PasskeyClientConfig config) {
        return new PasskeyClient(config);
    }

    public RegistrationStartResponse registrationStart(RegistrationStartRequest req) {
        return post("/api/v1/rp/registration/start", req,
                new TypeReference<ApiResponseEnvelope<RegistrationStartResponse>>() {});
    }

    public RegistrationFinishResponse registrationFinish(RegistrationFinishRequest req) {
        return post("/api/v1/rp/registration/finish", req,
                new TypeReference<ApiResponseEnvelope<RegistrationFinishResponse>>() {});
    }

    public AuthenticationStartResponse authenticationStart(AuthenticationStartRequest req) {
        return post("/api/v1/rp/authentication/start", req,
                new TypeReference<ApiResponseEnvelope<AuthenticationStartResponse>>() {});
    }

    public AuthenticationFinishResponse authenticationFinish(AuthenticationFinishRequest req) {
        return post("/api/v1/rp/authentication/finish", req,
                new TypeReference<ApiResponseEnvelope<AuthenticationFinishResponse>>() {});
    }

    public IdTokenClaims verifyIdToken(String compactJwt) {
        return idTokenVerifier.verify(compactJwt);
    }

    private <T> T post(String path, Object body, TypeReference<ApiResponseEnvelope<T>> typeRef) {
        Instant started = Instant.now();
        byte[] bytes;
        try {
            bytes = http.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (PasskeyApiException e) {
            // PasskeyResponseErrorHandler 가 4xx/5xx → PasskeyApiException 으로 변환.
            long durMs = Duration.between(started, Instant.now()).toMillis();
            log.warn("sdk call: POST {} status={} code={} durMs={}",
                    path, e.getHttpStatus(), e.getCode(), durMs);
            throw e;
        }
        try {
            ApiResponseEnvelope<T> env = objectMapper.readValue(
                    bytes, objectMapper.getTypeFactory().constructType(typeRef.getType()));
            long durMs = Duration.between(started, Instant.now()).toMillis();
            if (!env.success()) {
                // HTTP 200 with success=false (envelope-level failure).
                log.warn("sdk call: POST {} status=200 code={} durMs={}", path, env.code(), durMs);
                throw new PasskeyApiException(200, env.code(), env.message(), env.traceId(),
                        (env.error() == null) ? null : env.error().fieldErrors());
            }
            if (log.isDebugEnabled()) {
                log.debug("sdk call: POST {} status=200 durMs={}", path, durMs);
            }
            return env.data();
        } catch (PasskeyApiException e) {
            throw e;
        } catch (Exception e) {
            long durMs = Duration.between(started, Instant.now()).toMillis();
            log.warn("sdk call: POST {} envelope-parse-failure durMs={} cause={}",
                    path, durMs, e.toString());
            throw new PasskeyApiException(0, "C999",
                    "Envelope parse failure: " + e.getMessage(), null, null);
        }
    }
}
```

> 주의: 원본 Kotlin `env.data as T` 는 unchecked cast 였다. Java record `ApiResponseEnvelope<T>` 의 `data()` 는 이미 `T` 를 반환하므로 캐스트 불필요. `objectMapper.readValue(byte[], JavaType)` 오버로드는 checked exception 을 던지므로 바깥 try/catch 가 그대로 받는다.

- [ ] **Step 2: 기존 .kt 삭제 후 커밋**

```bash
rm sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/PasskeyClient.kt
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java sdk-java/src/main/kotlin/com/crosscert/passkey/sdk/PasskeyClient.kt
git commit -m "refactor(sdk): PasskeyClient Kotlin→Java 환원"
```

---

### Task 8: build.gradle.kts 정리 (Kotlin 제거)

**Files:**
- Modify: `sdk-java/build.gradle.kts`

**Interfaces:**
- Produces: 순수 `java-library` 빌드. `kotlin.jvm` 플러그인·`kotlin-stdlib`·`jackson-module-kotlin`·KotlinCompile 블록 제거.

- [ ] **Step 1: build.gradle.kts 전체 교체**

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

// group / version 은 root allprojects 가 com.crosscert.passkey / 0.0.1-SNAPSHOT 로 설정.
// toolchain 17 + repositories(mavenCentral) 은 root subprojects 가 처리.

java {
    withSourcesJar()
}

dependencies {
    // Spring Boot 3.5 BOM (root subprojects 에서 import) 이 spring-web 6.2.x,
    // jackson-databind, jackson-jsr310, slf4j-api 를 모두 관리.
    api("org.springframework:spring-web")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api(rootProject.libs.nimbus.jose.jwt)
    api("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation(rootProject.libs.wiremock.standalone)
    // junit-platform-launcher 는 root subprojects 가 모든 모듈에 testRuntimeOnly 적용
}

tasks.named<Test>("test") { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
```

> 제거 항목: `alias(libs.plugins.kotlin.jvm)`, `implementation(kotlin("stdlib"))`, `api("com.fasterxml.jackson.module:jackson-module-kotlin")`, `tasks.withType<KotlinCompile> { compilerOptions { javaParameters.set(true) } }`. 모든 DTO record 에 `@JsonProperty` 가 명시되어 있어 `-parameters`/javaParameters 없이 Jackson 역직렬화가 안전(Global Constraints).

- [ ] **Step 2: 커밋**

```bash
git add sdk-java/build.gradle.kts
git commit -m "build(sdk): Kotlin 플러그인·런타임 의존성 제거, 순수 java-library 화"
```

---

### Task 9: kotlin 디렉토리 잔여 정리 + 소비처(rp-app) 수정

**Files:**
- Delete: `sdk-java/src/main/kotlin` (잔여 빈 디렉토리)
- Modify: `rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.kt`

**Interfaces:**
- Consumes: `PasskeyClientConfig.builder(...)` (Task 5), `PasskeyClient.of(...)` (Task 7).

- [ ] **Step 1: 잔여 kotlin 트리 확인 후 삭제**

```bash
find sdk-java/src/main/kotlin -type f 2>/dev/null   # 비어 있어야 함 (출력 없음)
rm -rf sdk-java/src/main/kotlin
```

- [ ] **Step 2: rp-app PasskeyClientConfiguration.kt 의 passkeyClient 빈 수정**

기존 `passkeyClient(...)` 메서드 본문을 아래로 교체 (import 는 기존 `PasskeyClient`, `PasskeyClientConfig` 유지):

```kotlin
    @Bean
    fun passkeyClient(props: PasskeyProperties, apiKeySupplier: Supplier<String?>): PasskeyClient =
        // SDK 의 Builder 는 필수값(baseUrl, apiKeySupplier)을 인자로 강제하고, 선택값은
        // null 을 넘기면 기본값으로 치환한다. baseUrl 은 필수 — 누락 시 fail-fast(!!).
        PasskeyClient.of(
            PasskeyClientConfig.builder(props.baseUrl!!, apiKeySupplier)
                .connectTimeout(props.connectTimeout)
                .readTimeout(props.readTimeout)
                .jwksCacheTtl(props.jwksCacheTtl)
                .build(),
        )
```

> `apiKeySupplier` 의 타입은 rp-app 에서 `Supplier<String?>` 이고 SDK 빌더는 `Supplier<String>` 를 받는다. Kotlin 에서 `Supplier<String?>` → `Supplier<String>` 전달은 플랫폼 타입 호환으로 통과한다(기존 생성자 호출도 동일하게 통과했음). 컴파일 경고가 나오면 기존과 동일하게 무시 가능.

- [ ] **Step 3: 커밋**

```bash
git add sdk-java/src/main/kotlin rp-app/src/main/kotlin/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.kt
git commit -m "refactor(rp-app): SDK Builder API 로 PasskeyClient 빈 구성 변경"
```

---

### Task 10: 빌드·테스트 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: SDK 컴파일**

Run: `./gradlew :sdk-java:compileJava`
Expected: `BUILD SUCCESSFUL`. (kotlin 컴파일 태스크가 더 이상 없어야 함)

- [ ] **Step 2: SDK 테스트 실행 (동작 동일성 핵심 증거)**

Run: `./gradlew :sdk-java:test`
Expected: `BUILD SUCCESSFUL`. 통과 테스트:
- `PasskeyClientContractIT` (WireMock 계약: 등록/인증 start·finish, 401 에러 envelope)
- `IdTokenVerifierAlgTest` (JWS 알고리즘 핀 — RS256 통과/HS256 거부)
- `DynamicApiKeyHeaderIT` (Supplier 매 요청 평가, blank 키 fail-fast)

> 실패 시: `defaults()`/생성자 시그니처·`@JsonProperty` 누락·접근자명 불일치를 먼저 점검. base worktree 와 대조해 회귀 여부 확정(전체 build 실패는 무시 — Global Constraints).

- [ ] **Step 3: rp-app 컴파일 (소비처 호환)**

Run: `./gradlew :rp-app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: kotlin 흔적 없음 확인**

Run: `find sdk-java/src -name "*.kt" ; grep -rn "kotlin" sdk-java/build.gradle.kts`
Expected: 둘 다 출력 없음.

- [ ] **Step 5: 검증 결과 커밋 (선택 — 변경 없으면 생략)**

검증만 했으므로 코드 변경이 없으면 커밋 없음. 발견된 수정이 있으면 해당 Task 로 돌아가 고친 뒤 재검증.

---

### Task 11: 통합 가이드 문서 작성 (`sdk-java/README.md`)

**Files:**
- Create: `sdk-java/README.md`

**Interfaces:**
- Consumes: 환원된 공개 API 전체 (Task 1~7).

- [ ] **Step 1: README.md 작성**

아래 9개 섹션을 모두 채운 문서를 작성한다. 모든 코드 예제는 환원된 Java API 기준(`PasskeyClientConfig.builder(...)`, `PasskeyClient.of(...)`, record 접근자 `resp.registrationToken()` 등). `rp-app` 을 레퍼런스 통합 예제로 인용한다.

````markdown
# Passkey RP SDK (sdk-java)

Passkey2 RP(Relying Party) API 를 호출하는 순수 Java 클라이언트 라이브러리.
등록/인증 4종 + ID Token 검증을 얇게 감싼다.

## 1. 개요

- **무엇을 하나:** RP 서버가 Passkey2 백엔드의 `/api/v1/rp/*` 엔드포인트(등록 start/finish,
  인증 start/finish)를 호출하고, 발급된 ID Token(RS256 JWT)을 JWKS 로 검증한다.
- **요구 사항:** Java 17+, Spring Web(`RestClient`) 런타임. (transitive 로 spring-web,
  jackson-databind/jsr310, nimbus-jose-jwt, slf4j-api 를 끌어온다.)
- **순수 Java:** Kotlin 런타임 의존성 없음.

## 2. 설치

Maven local 또는 사내 레포에 publish 후 좌표로 의존:

```kotlin
// build.gradle.kts
implementation("com.crosscert.passkey:sdk-java:0.0.1-SNAPSHOT")
```

로컬 publish:

```bash
./gradlew :sdk-java:publishToMavenLocal
```

## 3. 빠른 시작

```java
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import java.net.URI;

PasskeyClient client = PasskeyClient.of(
    PasskeyClientConfig.builder(
        URI.create("https://passkey.example.com"),
        () -> System.getenv("PASSKEY_API_KEY")   // Supplier<String>: 매 요청마다 호출됨
    ).build());
```

`PasskeyClientConfig.defaults(baseUrl, apiKeySupplier)` 는 모든 선택값에 기본값을 적용한
지름길이다(= `builder(...).build()`).

## 4. 설정 레퍼런스

`PasskeyClientConfig.builder(baseUrl, apiKeySupplier)` — 필수 2개는 빌더 인자로 강제.

| 옵션 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| baseUrl (필수) | `URI` | — | Passkey2 백엔드 베이스 URL |
| apiKeySupplier (필수) | `Supplier<String>` | — | **매 요청마다** 호출되는 API Key 소스 |
| connectTimeout | `Duration` | 3s | 연결 타임아웃 |
| readTimeout | `Duration` | 10s | 응답 읽기 타임아웃 |
| jwksCacheTtl | `Duration` | 5m | JWKS 캐시 TTL |
| clock | `Clock` | systemUTC | (테스트용) 시계 |
| traceIdProvider | `Supplier<String>` | `MDC.get("traceId")` | X-Trace-Id 전파 소스 |

**동적 API Key:** `apiKeySupplier` 는 부팅 시 1회가 아니라 **매 요청 시점**에 호출된다. 따라서
Supplier 뒤편(파일/시크릿 매니저)에서 키를 교체하면 재기동 없이 다음 요청부터 반영된다. 반환값이
null/blank 면 그 요청은 `PasskeyConfigurationException` 으로 fail-fast.

## 5. API 사용

등록(2-step):

```java
// 1) start
RegistrationStartResponse start = client.registrationStart(
    new RegistrationStartRequest("userHandle", "홍길동", "gildong"));
JsonNode creationOptions = start.publicKeyCredentialCreationOptions(); // 브라우저로 전달
String regToken = start.registrationToken();

// 2) (브라우저에서 navigator.credentials.create 수행 후) finish
RegistrationFinishResponse fin = client.registrationFinish(
    new RegistrationFinishRequest(regToken, publicKeyCredentialJson));
String credentialId = fin.credentialId();
```

인증(2-step):

```java
AuthenticationStartResponse aStart = client.authenticationStart(
    new AuthenticationStartRequest(null)); // userHandle 생략 가능(discoverable)
AuthenticationFinishResponse aFin = client.authenticationFinish(
    new AuthenticationFinishRequest(aStart.authenticationToken(), publicKeyCredentialJson));
String idToken = aFin.idToken(); // 다음 섹션에서 검증
```

## 6. ID Token 검증

```java
IdTokenClaims claims = client.verifyIdToken(idToken);
String subject = claims.sub();   // 불투명 userHandle
String issuer  = claims.iss();
List<String> amr = claims.amr();
```

`verifyIdToken` 은 ① alg=RS256 핀(alg-confusion/none/HS\* 다운그레이드 거부) ② JWKS(`/.well-known/jwks.json`,
TTL 캐시) 로 서명 검증 ③ exp 만료 검사를 수행한다. 실패 시 `PasskeyIdTokenException`.

## 7. 에러 처리

| 예외 | 발생 조건 | 주요 필드 |
|---|---|---|
| `PasskeyApiException` | 4xx/5xx, 또는 HTTP 200 + `success=false` | `getHttpStatus()`, `getCode()`, `getTraceId()`, `getFieldErrors()` |
| `PasskeyAuthException` (extends 위) | HTTP 401 | (httpStatus=401) |
| `PasskeyRateLimitException` (extends 위) | HTTP 429 | `getRetryAfterSeconds()` |
| `PasskeyConfigurationException` | API Key Supplier 가 null/blank 반환 (요청 전 fail-fast) | message |
| `PasskeyIdTokenException` | ID Token 검증 실패(alg/서명/만료/파싱) | message |

envelope 파싱 실패나 비-envelope 에러 본문은 code `C999` 로 정규화된다.

```java
try {
    client.registrationStart(req);
} catch (PasskeyRateLimitException e) {
    sleep(e.getRetryAfterSeconds());
} catch (PasskeyApiException e) {
    log.warn("RP API 실패 status={} code={} trace={}", e.getHttpStatus(), e.getCode(), e.getTraceId());
}
```

## 8. 관측성

- **Trace 전파:** `traceIdProvider`(기본 `MDC.get("traceId")`)가 반환한 값이 `X-Trace-Id` 헤더로
  전파되어 백엔드 로그와 한 trace 로 묶인다. MDC 키 상수: `PasskeyClientConfig.MDC_TRACE_ID_KEY`.
- **로그 마스킹:** DEBUG 로그에서 `idToken`, `clientDataJSON`/`attestationObject`/`authenticatorData`/`signature`,
  `X-API-Key` secret 꼬리는 자동 마스킹된다. 운영 환경은 `logging.level` 을 INFO 이하로 둘 것.

## 9. 참조 통합 예제

`rp-app` 모듈이 이 SDK 의 레퍼런스 소비자다:
- `rp-app/.../config/PasskeyClientConfiguration.kt` — Spring `@Bean` 으로 `PasskeyClient` 구성
  (동적 API Key Supplier 핫리로드 포함).
- `rp-app/.../web/WebAuthnController.kt` — 등록/인증 4종 + ID Token 검증 호출 흐름.
````

- [ ] **Step 2: 커밋**

```bash
git add sdk-java/README.md
git commit -m "docs(sdk): 외부 RP 개발자용 통합 가이드 README 작성"
```

---

## Self-Review (작성자 점검 결과)

**1. Spec coverage:**
- 변환 인벤토리(spec §2) → Task 1~7 전부 커버 (envelope/exception/dto/idtoken/internal/Config/Client). ✅
- Builder 전환(spec §4.3) → Task 5. 단, spec 은 "defaults() 미보존"이라 했으나, 구현 단계 조사에서 테스트 3곳이 `defaults()` 사용 확인 → **defaults() 를 Builder 위임으로 보존**하기로 조정(테스트 무수정 + 외부 호환). 이 결정은 spec §4.3 의 "필요 시 추가 가능" 여지 내에 있고, plan Task 5/7/10 에 반영. ✅
- build 정리(spec §4.6) → Task 8. ✅
- 소비처 수정(spec §3) → Task 9(rp-app). 테스트 3곳은 defaults 보존으로 무수정(Task 10에서 확인). ✅
- 검증(spec §5) → Task 10. ✅
- 가이드 문서(spec §6, 9개 섹션) → Task 11. ✅

**2. Placeholder scan:** TBD/TODO/"적절히 처리" 없음. 모든 코드 스텝에 완전한 코드 포함. ✅

**3. Type consistency:**
- `PasskeyApiException` 접근자: Task 2 정의(`getHttpStatus/getCode/getTraceId/getFieldErrors`) ↔ Task 7 사용(`e.getHttpStatus()`, `e.getCode()`) 일치. ✅
- `ApiResponseEnvelope` 접근자: Task 1 정의(`success()/code()/message()/data()/error()/traceId()`) ↔ Task 6·7 사용 일치. ✅
- `EnvelopeError.fieldErrors()`: Task 1 정의 ↔ Task 6·7 `env.error().fieldErrors()` 일치. ✅
- `PasskeyClientConfig` 접근자: Task 5 정의(`baseUrl()/apiKeySupplier()/connectTimeout()/readTimeout()/jwksCacheTtl()/clock()/traceIdProvider()`) ↔ Task 6·7 사용 일치. ✅
- `JwksCache(URI,Duration,Clock)`/`IdTokenVerifier(JwksCache,Clock)`: Task 4 정의 ↔ Task 7 사용 + `IdTokenVerifierAlgTest` 기존 호출 일치. ✅
- `PasskeyClientConfig.builder/defaults`: Task 5 정의 ↔ Task 9(rp-app)·기존 테스트 일치. ✅

이상 없음. 구현 준비 완료.
