# SDK + Sample RP + Test Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 0 § 9 / Phase 1 § 14 의 후속 합의 (`sdk-java`, `examples/sample-rp`)를 살려서 외부 RP 개발자가 `:passkey-app` 의 RP API 를 7분 안에 통합할 수 있는 dogfood 묶음을 만든다.

**Architecture:** worktree 안의 `examples/sdk-java/` 와 `examples/sample-rp/` 에 두 개의 독립 Gradle 프로젝트를 만든다 (Passkey2 의 `settings.gradle.kts` 에 포함되지 않음). SDK 는 Spring `RestClient` 1-layer + 명시 예외 모델 + JWKS 검증. sample-rp 는 Spring Boot 3.5 + Thymeleaf 페이지 + HttpSession 세션화. SDK 는 `publishToMavenLocal` 로 sample-rp 에 노출. main merge 후 sibling 디렉토리 (`../sdk-java/`, `../sample-rp/`) 로 `git mv`.

**Tech Stack:**
- Java 17, Gradle Kotlin DSL (자체 wrapper)
- sdk-java: Spring web 6.2 RestClient, Nimbus JOSE+JWT 9.40, Jackson 2.21, WireMock(test)
- sample-rp: Spring Boot 3.5.x, Thymeleaf, Spring Session in-memory, webauthn4j-test (IT only)
- Node/JS 없음 (Thymeleaf 서버사이드 + 인라인 vanilla JS)

**Spec:** `docs/superpowers/specs/2026-05-27-sdk-and-sample-rp-design.md`

**Worktree note:** 이 plan 의 모든 경로는 worktree 안의 `examples/{sdk-java,sample-rp}` 기준. main merge 직후 `git mv examples/sdk-java ../sdk-java && git mv examples/sample-rp ../sample-rp` 로 sibling 으로 분리 (T21 참고).

---

## Task 1: sdk-java Gradle 골격 + wrapper

**Files:**
- Create: `examples/sdk-java/settings.gradle.kts`
- Create: `examples/sdk-java/build.gradle.kts`
- Create: `examples/sdk-java/gradle.properties`
- Create: `examples/sdk-java/.gitignore`

이 task 의 목적: 빈 라이브러리 프로젝트가 `./gradlew build` 로 통과하는 것. 코드는 다음 task 부터.

- [ ] **Step 1: settings.gradle.kts 작성**

`examples/sdk-java/settings.gradle.kts`:
```kotlin
rootProject.name = "passkey-sdk-java"
```

- [ ] **Step 2: build.gradle.kts 작성**

`examples/sdk-java/build.gradle.kts`:
```kotlin
plugins {
    `java-library`
    `maven-publish`
}

group = "com.crosscert.passkey"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    // Spring Boot 3.5.x BOM 이 spring-web 6.2.x 를 가져오므로 동일 라인업으로 잠근다.
    api("org.springframework:spring-web:6.2.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.21.0")
    api("com.nimbusds:nimbus-jose-jwt:9.40")
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

tasks.named<Test>("test") { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
```

- [ ] **Step 3: gradle.properties 작성**

`examples/sdk-java/gradle.properties`:
```
org.gradle.jvmargs=-Xmx1g
org.gradle.caching=true
```

- [ ] **Step 4: .gitignore 작성**

`examples/sdk-java/.gitignore`:
```
.gradle/
build/
out/
*.iml
.idea/
```

- [ ] **Step 5: Gradle wrapper 생성**

```bash
cd examples/sdk-java
gradle wrapper --gradle-version 8.10.2
```

(시스템에 gradle 이 없으면: Passkey2 의 wrapper 를 복사 — `cp ../../gradlew ./ && cp ../../gradlew.bat ./ && cp -r ../../gradle ./`. wrapper jar/properties 함께 복사돼야 함.)

- [ ] **Step 6: 빈 빌드 통과 확인**

```bash
cd examples/sdk-java
./gradlew build
```

Expected: BUILD SUCCESSFUL (테스트 0개, 컴파일 0개).

- [ ] **Step 7: Commit**

```bash
git add examples/sdk-java/
git commit -m "feat(sdk): sdk-java Gradle 골격 + wrapper (T1)"
```

---

## Task 2: SDK envelope + exception 타입

**Files:**
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope/ApiResponseEnvelope.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope/EnvelopeError.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope/EnvelopeFieldError.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/exception/PasskeyApiException.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/exception/PasskeyAuthException.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/exception/PasskeyRateLimitException.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/exception/PasskeyIdTokenException.java`

- [ ] **Step 1: ApiResponseEnvelope 작성**

`.../sdk/envelope/ApiResponseEnvelope.java`:
```java
package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseEnvelope<T>(
        boolean success,
        String code,
        String message,
        T data,
        EnvelopeError error,
        String traceId,
        String timestamp
) {}
```

- [ ] **Step 2: EnvelopeError + EnvelopeFieldError 작성**

`.../sdk/envelope/EnvelopeError.java`:
```java
package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeError(String errorCode, List<EnvelopeFieldError> fieldErrors) {}
```

`.../sdk/envelope/EnvelopeFieldError.java`:
```java
package com.crosscert.passkey.sdk.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvelopeFieldError(String field, Object rejectedValue, String reason) {}
```

- [ ] **Step 3: PasskeyApiException 작성 (base)**

`.../sdk/exception/PasskeyApiException.java`:
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
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public String getTraceId() { return traceId; }
    public List<EnvelopeFieldError> getFieldErrors() { return fieldErrors; }
}
```

- [ ] **Step 4: PasskeyAuthException 작성**

`.../sdk/exception/PasskeyAuthException.java`:
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

- [ ] **Step 5: PasskeyRateLimitException 작성**

`.../sdk/exception/PasskeyRateLimitException.java`:
```java
package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;

import java.util.List;

public class PasskeyRateLimitException extends PasskeyApiException {
    private final long retryAfterSeconds;

    public PasskeyRateLimitException(String code, String message, String traceId,
                                     List<EnvelopeFieldError> fieldErrors,
                                     long retryAfterSeconds) {
        super(429, code, message, traceId, fieldErrors);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
```

- [ ] **Step 6: PasskeyIdTokenException 작성**

`.../sdk/exception/PasskeyIdTokenException.java`:
```java
package com.crosscert.passkey.sdk.exception;

public class PasskeyIdTokenException extends RuntimeException {
    public PasskeyIdTokenException(String message) { super(message); }
    public PasskeyIdTokenException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 7: 컴파일 확인**

```bash
cd examples/sdk-java
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/envelope/
git add examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/exception/
git commit -m "feat(sdk): envelope + exception 타입 (T2)"
```

---

## Task 3: SDK DTO 8개

**Files (8개 record, 각각 별 파일):**
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/RegistrationStartRequest.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/RegistrationStartResponse.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/RegistrationFinishRequest.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/RegistrationFinishResponse.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/AuthenticationStartRequest.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/AuthenticationStartResponse.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/AuthenticationFinishRequest.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/AuthenticationFinishResponse.java`

- [ ] **Step 1: Registration DTO 4개 작성**

`.../sdk/dto/RegistrationStartRequest.java`:
```java
package com.crosscert.passkey.sdk.dto;

public record RegistrationStartRequest(
        String userHandle,
        String displayName,
        String username
) {}
```

`.../sdk/dto/RegistrationStartResponse.java`:
```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationStartResponse(
        String registrationToken,
        JsonNode publicKeyCredentialCreationOptions
) {}
```

`.../sdk/dto/RegistrationFinishRequest.java`:
```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RegistrationFinishRequest(
        String registrationToken,
        JsonNode publicKeyCredential
) {}
```

`.../sdk/dto/RegistrationFinishResponse.java`:
```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationFinishResponse(
        String credentialId,
        String aaguid,
        String attestationFormat,
        String createdAt
) {}
```

- [ ] **Step 2: Authentication DTO 4개 작성**

`.../sdk/dto/AuthenticationStartRequest.java`:
```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthenticationStartRequest(String userHandle) {}
```

`.../sdk/dto/AuthenticationStartResponse.java`:
```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationStartResponse(
        String authenticationToken,
        JsonNode publicKeyCredentialRequestOptions
) {}
```

`.../sdk/dto/AuthenticationFinishRequest.java`:
```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record AuthenticationFinishRequest(
        String authenticationToken,
        JsonNode publicKeyCredential
) {}
```

`.../sdk/dto/AuthenticationFinishResponse.java`:
```java
package com.crosscert.passkey.sdk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthenticationFinishResponse(
        String idToken,
        String tokenType,
        long expiresIn
) {}
```

- [ ] **Step 3: 컴파일 확인 + Commit**

```bash
cd examples/sdk-java
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/
git commit -m "feat(sdk): 8개 DTO records (T3)"
```

---

## Task 4: SDK IdToken 검증 (JwksCache + IdTokenClaims + IdTokenVerifier)

**Files:**
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenClaims.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/JwksCache.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java`

- [ ] **Step 1: IdTokenClaims record 작성**

`.../sdk/idtoken/IdTokenClaims.java`:
```java
package com.crosscert.passkey.sdk.idtoken;

import java.time.Instant;
import java.util.List;

public record IdTokenClaims(
        String iss,
        String sub,
        String aud,
        Instant iat,
        Instant exp,
        List<String> amr,
        String credId,
        String aaguid
) {}
```

- [ ] **Step 2: JwksCache 작성**

`.../sdk/idtoken/JwksCache.java`:
```java
package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWKSet;

import java.net.URI;
import java.net.URL;
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
        try {
            this.jwksUrl = baseUrl.resolve("/.well-known/jwks.json").toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad baseUrl for JWKS", e);
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    public JWKSet get() {
        Snapshot cur = snapshot.get();
        Instant now = clock.instant();
        if (cur != null && cur.fetchedAt.plus(ttl).isAfter(now)) {
            return cur.jwks;
        }
        JWKSet fresh = fetch();
        snapshot.set(new Snapshot(fresh, now));
        return fresh;
    }

    private JWKSet fetch() {
        try {
            return JWKSet.load(jwksUrl);
        } catch (RemoteKeySourceException | java.io.IOException | java.text.ParseException e) {
            throw new PasskeyIdTokenException("JWKS fetch failed: " + jwksUrl, e);
        }
    }

    private record Snapshot(JWKSet jwks, Instant fetchedAt) {}
}
```

- [ ] **Step 3: IdTokenVerifier 작성**

`.../sdk/idtoken/IdTokenVerifier.java`:
```java
package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

public class IdTokenVerifier {
    private final JwksCache jwks;
    private final Clock clock;

    public IdTokenVerifier(JwksCache jwks, Clock clock) {
        this.jwks = jwks;
        this.clock = clock;
    }

    public IdTokenClaims verify(String compactJwt) {
        try {
            SignedJWT jwt = SignedJWT.parse(compactJwt);
            String kid = jwt.getHeader().getKeyID();
            JWK key = jwks.get().getKeyByKeyId(kid);
            if (key == null || !(key instanceof RSAKey rsa)) {
                throw new PasskeyIdTokenException("Unknown or non-RSA kid: " + kid);
            }
            JWSVerifier verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            if (!jwt.verify(verifier)) {
                throw new PasskeyIdTokenException("Signature verification failed");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Instant exp = c.getExpirationTime() == null ? null : c.getExpirationTime().toInstant();
            if (exp == null || !exp.isAfter(now)) {
                throw new PasskeyIdTokenException("ID Token expired (exp=" + exp + ", now=" + now + ")");
            }
            @SuppressWarnings("unchecked")
            List<String> amr = (List<String>) c.getClaim("amr");
            return new IdTokenClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    c.getAudience().isEmpty() ? null : c.getAudience().get(0),
                    c.getIssueTime() == null ? null : c.getIssueTime().toInstant(),
                    exp,
                    amr == null ? List.of() : List.copyOf(amr),
                    (String) c.getClaim("cred_id"),
                    (String) c.getClaim("aaguid")
            );
        } catch (PasskeyIdTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new PasskeyIdTokenException("ID Token parsing failed", e);
        }
    }
}
```

- [ ] **Step 4: 컴파일 확인 + Commit**

```bash
cd examples/sdk-java
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/
git commit -m "feat(sdk): IdTokenVerifier + JwksCache (T4)"
```

---

## Task 5: SDK PasskeyClient + PasskeyClientConfig + redaction interceptor

**Files:**
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/PasskeyResponseErrorHandler.java`
- Create: `examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java`

- [ ] **Step 1: PasskeyClientConfig 작성**

`.../sdk/PasskeyClientConfig.java`:
```java
package com.crosscert.passkey.sdk;

import org.slf4j.MDC;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public record PasskeyClientConfig(
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl,
        Clock clock,
        Supplier<String> traceIdProvider
) {
    public static final String MDC_TRACE_ID_KEY = "traceId";

    public PasskeyClientConfig {
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(apiKey);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null)    readTimeout    = Duration.ofSeconds(10);
        if (jwksCacheTtl == null)   jwksCacheTtl   = Duration.ofMinutes(5);
        if (clock == null)          clock          = Clock.systemUTC();
        if (traceIdProvider == null) traceIdProvider = () -> MDC.get(MDC_TRACE_ID_KEY);
    }

    public static PasskeyClientConfig defaults(URI baseUrl, String apiKey) {
        return new PasskeyClientConfig(baseUrl, apiKey, null, null, null, null, null);
    }
}
```

- [ ] **Step 2: RedactingRequestInterceptor 작성**

`.../sdk/internal/RedactingRequestInterceptor.java`:
```java
package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SDK 의 DEBUG 로그가 ID Token / publicKeyCredential 평문을 남기지 않도록
 * 본문 일부를 마스킹해 출력. 운영 환경에선 logging.level 을 INFO 이하로.
 */
public class RedactingRequestInterceptor implements ClientHttpRequestInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RedactingRequestInterceptor.class);

    private final String apiKey;
    private final PasskeyClientConfig config;

    public RedactingRequestInterceptor(PasskeyClientConfig config) {
        this.config = config;
        this.apiKey = config.apiKey();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution exec)
            throws IOException {
        request.getHeaders().set("X-API-Key", apiKey);
        String traceId = config.traceIdProvider().get();
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set("X-Trace-Id", traceId);
        }
        if (log.isDebugEnabled()) {
            log.debug("→ {} {} body={}",
                    request.getMethod(), request.getURI(),
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
        return json
                .replaceAll("(?s)\"idToken\"\\s*:\\s*\"([^\"]{0,16}).*?\"",
                            "\"idToken\":\"$1…<redacted>\"")
                .replaceAll("(?s)\"(clientDataJSON|attestationObject|authenticatorData|signature)\"\\s*:\\s*\"[^\"]*\"",
                            "\"$1\":\"<redacted>\"");
    }
}
```

- [ ] **Step 3: PasskeyResponseErrorHandler 작성**

`.../sdk/internal/PasskeyResponseErrorHandler.java`:
```java
package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;

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

        String code     = env != null ? env.code()    : "C999";
        String message  = env != null ? env.message() : "Upstream error (no envelope)";
        String traceId  = env != null ? env.traceId() : null;
        var fieldErrors = env != null && env.error() != null ? env.error().fieldErrors() : null;

        if (status == 401) {
            throw new PasskeyAuthException(code, message, traceId, fieldErrors);
        }
        if (status == 429) {
            long retry = response.getHeaders().getFirst("Retry-After") == null
                    ? 0
                    : Long.parseLong(response.getHeaders().getFirst("Retry-After"));
            throw new PasskeyRateLimitException(code, message, traceId, fieldErrors, retry);
        }
        throw new PasskeyApiException(status, code, message, traceId, fieldErrors);
    }

    private ApiResponseEnvelope<?> parseQuietly(byte[] body) {
        try {
            if (body == null || body.length == 0) return null;
            return objectMapper.readValue(body, ApiResponseEnvelope.class);
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: PasskeyClient 작성**

`.../sdk/PasskeyClient.java`:
```java
package com.crosscert.passkey.sdk;

import com.crosscert.passkey.sdk.dto.*;
import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import com.crosscert.passkey.sdk.idtoken.IdTokenVerifier;
import com.crosscert.passkey.sdk.idtoken.JwksCache;
import com.crosscert.passkey.sdk.internal.PasskeyResponseErrorHandler;
import com.crosscert.passkey.sdk.internal.RedactingRequestInterceptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class PasskeyClient {
    private final RestClient http;
    private final IdTokenVerifier idTokenVerifier;
    private final ObjectMapper objectMapper;

    private PasskeyClient(PasskeyClientConfig config) {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) config.connectTimeout().toMillis());
        rf.setReadTimeout((int) config.readTimeout().toMillis());

        this.http = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
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
                new TypeReference<ApiResponseEnvelope<RegistrationStartResponse>>(){});
    }

    public RegistrationFinishResponse registrationFinish(RegistrationFinishRequest req) {
        return post("/api/v1/rp/registration/finish", req,
                new TypeReference<ApiResponseEnvelope<RegistrationFinishResponse>>(){});
    }

    public AuthenticationStartResponse authenticationStart(AuthenticationStartRequest req) {
        return post("/api/v1/rp/authentication/start", req,
                new TypeReference<ApiResponseEnvelope<AuthenticationStartResponse>>(){});
    }

    public AuthenticationFinishResponse authenticationFinish(AuthenticationFinishRequest req) {
        return post("/api/v1/rp/authentication/finish", req,
                new TypeReference<ApiResponseEnvelope<AuthenticationFinishResponse>>(){});
    }

    public IdTokenClaims verifyIdToken(String compactJwt) {
        return idTokenVerifier.verify(compactJwt);
    }

    private <T> T post(String path, Object body, TypeReference<ApiResponseEnvelope<T>> typeRef) {
        byte[] bytes = http.post()
                .uri(path)
                .body(body)
                .retrieve()
                .body(byte[].class);
        try {
            ApiResponseEnvelope<T> env = objectMapper.readValue(bytes, objectMapper.getTypeFactory()
                    .constructType(typeRef.getType()));
            if (!env.success()) {
                throw new PasskeyApiException(200, env.code(), env.message(), env.traceId(),
                        env.error() == null ? null : env.error().fieldErrors());
            }
            return env.data();
        } catch (PasskeyApiException e) {
            throw e;
        } catch (Exception e) {
            throw new PasskeyApiException(0, "C999", "Envelope parse failure: " + e.getMessage(),
                    null, null);
        }
    }
}
```

- [ ] **Step 5: 컴파일 확인 + Commit**

```bash
cd examples/sdk-java
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java \
        examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java \
        examples/sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/
git commit -m "feat(sdk): PasskeyClient + Config + redaction interceptor (T5)"
```

---

## Task 6: PasskeyClientContractIT (TDD — 자동 테스트 1번)

WireMock 으로 passkey-app 의 5개 wire (4 ceremony + JWKS) 를 stub 한다. SDK 가 이 wire 와 매칭 안 되면 회귀 알림 채널.

**Files:**
- Create: `examples/sdk-java/src/test/java/com/crosscert/passkey/sdk/PasskeyClientContractIT.java`
- Create: `examples/sdk-java/src/test/resources/contract/registration-start-success.json`
- Create: `examples/sdk-java/src/test/resources/contract/registration-finish-success.json`
- Create: `examples/sdk-java/src/test/resources/contract/authentication-start-success.json`
- Create: `examples/sdk-java/src/test/resources/contract/authentication-finish-success.json`
- Create: `examples/sdk-java/src/test/resources/contract/error-401.json`
- Create: `examples/sdk-java/src/test/resources/contract/jwks.json`

- [ ] **Step 1: Test fixture JSON 6개 작성 (passkey-app 의 실제 응답 모양)**

`examples/sdk-java/src/test/resources/contract/registration-start-success.json`:
```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "registrationToken": "rt_abc123",
    "publicKeyCredentialCreationOptions": {
      "challenge": "Y2gxMjM",
      "rp": { "id": "localhost", "name": "Test RP" },
      "user": { "id": "dWg0NTY", "displayName": "Alice", "name": "alice@example.com" },
      "pubKeyCredParams": [{"type":"public-key","alg":-7}],
      "timeout": 60000,
      "attestation": "indirect",
      "authenticatorSelection": {"userVerification":"required","residentKey":"preferred"}
    }
  },
  "traceId": "abc",
  "timestamp": "2026-05-27T09:00:00"
}
```

`examples/sdk-java/src/test/resources/contract/registration-finish-success.json`:
```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "credentialId": "Y3JlZElk",
    "aaguid": "08987058-cadc-4b81-b6e1-30de50dcbe96",
    "attestationFormat": "packed",
    "createdAt": "2026-05-27T09:01:00Z"
  },
  "traceId": "abc",
  "timestamp": "2026-05-27T09:01:00"
}
```

`examples/sdk-java/src/test/resources/contract/authentication-start-success.json`:
```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "authenticationToken": "at_xyz789",
    "publicKeyCredentialRequestOptions": {
      "challenge": "Y2gyMjM",
      "rpId": "localhost",
      "timeout": 60000,
      "userVerification": "required",
      "allowCredentials": [{"type":"public-key","id":"Y3JlZElk"}]
    }
  },
  "traceId": "abc",
  "timestamp": "2026-05-27T09:02:00"
}
```

`examples/sdk-java/src/test/resources/contract/authentication-finish-success.json`:
```json
{
  "success": true,
  "code": "OK",
  "message": "Success",
  "data": {
    "idToken": "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwOi8vbG9jYWxob3N0Iiwic3ViIjoiZHVnNDU2In0.sig",
    "tokenType": "Bearer",
    "expiresIn": 900
  },
  "traceId": "abc",
  "timestamp": "2026-05-27T09:03:00"
}
```

`examples/sdk-java/src/test/resources/contract/error-401.json`:
```json
{
  "success": false,
  "code": "A001",
  "message": "Invalid API key",
  "error": { "errorCode": "A001" },
  "traceId": "abc",
  "timestamp": "2026-05-27T09:00:00"
}
```

`examples/sdk-java/src/test/resources/contract/jwks.json`:
```json
{
  "keys": [{
    "kty": "RSA",
    "use": "sig",
    "alg": "RS256",
    "kid": "test-kid-1",
    "n": "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw",
    "e": "AQAB"
  }]
}
```

(주: jwks.json 의 `n` 값은 RFC 7517 예시 키. ContractIT 는 JWKS shape 만 검증하므로 실제 서명 검증은 별 task 가 아님.)

- [ ] **Step 2: 실패하는 ContractIT 작성**

`examples/sdk-java/src/test/java/com/crosscert/passkey/sdk/PasskeyClientContractIT.java`:
```java
package com.crosscert.passkey.sdk;

import com.crosscert.passkey.sdk.dto.*;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class PasskeyClientContractIT {

    static WireMockServer wm;
    static PasskeyClient client;

    @BeforeAll
    static void start() throws Exception {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        WireMock.configureFor("localhost", wm.port());

        stub("/api/v1/rp/registration/start",   "contract/registration-start-success.json");
        stub("/api/v1/rp/registration/finish",  "contract/registration-finish-success.json");
        stub("/api/v1/rp/authentication/start", "contract/authentication-start-success.json");
        stub("/api/v1/rp/authentication/finish","contract/authentication-finish-success.json");

        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(read("contract/jwks.json"))));

        client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()),
                "ck_test_apikey"));
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    private static void stub(String path, String fixture) throws Exception {
        wm.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(read(fixture))));
    }

    private static String read(String classpath) throws Exception {
        return new String(Files.readAllBytes(
                Path.of(PasskeyClientContractIT.class.getClassLoader()
                        .getResource(classpath).toURI())));
    }

    // ── 5 wire shapes × success ──────────────────────────────────────

    @Test
    void registrationStart_unwrapsEnvelope_returnsToken() {
        var resp = client.registrationStart(new RegistrationStartRequest("uh", "Alice", "alice@example.com"));
        assertThat(resp.registrationToken()).isEqualTo("rt_abc123");
        assertThat(resp.publicKeyCredentialCreationOptions().get("challenge").asText()).isEqualTo("Y2gxMjM");
    }

    @Test
    void registrationFinish_unwrapsEnvelope_returnsCredentialId() {
        var node = new ObjectMapper().createObjectNode().put("id", "fakeCred");
        var resp = client.registrationFinish(new RegistrationFinishRequest("rt_abc123", node));
        assertThat(resp.credentialId()).isEqualTo("Y3JlZElk");
        assertThat(resp.attestationFormat()).isEqualTo("packed");
    }

    @Test
    void authenticationStart_unwrapsEnvelope_returnsToken() {
        var resp = client.authenticationStart(new AuthenticationStartRequest("uh"));
        assertThat(resp.authenticationToken()).isEqualTo("at_xyz789");
        assertThat(resp.publicKeyCredentialRequestOptions().get("rpId").asText()).isEqualTo("localhost");
    }

    @Test
    void authenticationFinish_unwrapsEnvelope_returnsIdToken() {
        var node = JsonNodeFactory.instance.objectNode().put("id", "fakeAssert");
        var resp = client.authenticationFinish(new AuthenticationFinishRequest("at_xyz789", node));
        assertThat(resp.idToken()).startsWith("eyJ");
        assertThat(resp.expiresIn()).isEqualTo(900);
    }

    // ── X-API-Key header is sent ─────────────────────────────────────

    @Test
    void requestsCarryXApiKeyHeader() {
        client.registrationStart(new RegistrationStartRequest("uh", "Alice", "alice@example.com"));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/rp/registration/start"))
                .withHeader("X-API-Key", equalTo("ck_test_apikey")));
    }

    // ── 401 → PasskeyAuthException ───────────────────────────────────

    @Test
    void on401_throwsPasskeyAuthException() throws Exception {
        wm.stubFor(post(urlEqualTo("/api/v1/rp/registration/start"))
                .inScenario("auth").whenScenarioStateIs("rotated")
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(read("contract/error-401.json"))));
        wm.setScenarioState("auth", "rotated");

        assertThatThrownBy(() ->
                client.registrationStart(new RegistrationStartRequest("u", "A", "a@b.c")))
                .isInstanceOf(PasskeyAuthException.class)
                .satisfies(e -> {
                    var ae = (PasskeyAuthException) e;
                    assertThat(ae.getCode()).isEqualTo("A001");
                    assertThat(ae.getHttpStatus()).isEqualTo(401);
                });
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

```bash
cd examples/sdk-java
./gradlew test --tests PasskeyClientContractIT
```

Expected: BUILD SUCCESSFUL — 모든 테스트가 그린 (T5 까지 구현이 끝나있음). 만약 빨강이면 stub URL / envelope 파싱 / PasskeyClient 구현 확인 후 수정.

- [ ] **Step 4: Commit**

```bash
git add examples/sdk-java/src/test/
git commit -m "test(sdk): PasskeyClientContractIT — wire 5종 + 401 분기 (T6)"
```

---

## Task 7: SDK publishToMavenLocal 검증

`mavenLocal()` 에 게시되어 sample-rp 가 받을 수 있는 상태 확인.

- [ ] **Step 1: publishToMavenLocal 실행**

```bash
cd examples/sdk-java
./gradlew publishToMavenLocal
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: 게시 확인**

```bash
ls -la ~/.m2/repository/com/crosscert/passkey/passkey-sdk-java/0.1.0-SNAPSHOT/
```

Expected: `passkey-sdk-java-0.1.0-SNAPSHOT.jar`, `passkey-sdk-java-0.1.0-SNAPSHOT.pom`, `passkey-sdk-java-0.1.0-SNAPSHOT-sources.jar` 존재.

- [ ] **Step 3: Commit (이 task 는 의도적으로 코드 변경 없음 — 검증 단계)**

코드 변경이 없으므로 commit 생략. 다음 task 로.

---

## Task 8: sample-rp Gradle 골격 + wrapper

**Files:**
- Create: `examples/sample-rp/settings.gradle.kts`
- Create: `examples/sample-rp/build.gradle.kts`
- Create: `examples/sample-rp/gradle.properties`
- Create: `examples/sample-rp/.gitignore`

- [ ] **Step 1: settings.gradle.kts 작성**

`examples/sample-rp/settings.gradle.kts`:
```kotlin
rootProject.name = "sample-rp"
```

- [ ] **Step 2: build.gradle.kts 작성**

`examples/sample-rp/build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.crosscert.passkey"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories {
    mavenLocal()        // sdk-java 픽업
    mavenCentral()
}

dependencies {
    implementation("com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // SampleRpSmokeIT 용 webauthn4j-test (ClientPlatform + PackedAuthenticator)
    testImplementation("com.webauthn4j:webauthn4j-test:0.29.4.RELEASE")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Java 17 UUID reflection access (passkey-app 와 동일 패턴)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
```

- [ ] **Step 3: gradle.properties + .gitignore**

`examples/sample-rp/gradle.properties`:
```
org.gradle.jvmargs=-Xmx1g
org.gradle.caching=true
```

`examples/sample-rp/.gitignore`:
```
.gradle/
build/
out/
*.iml
.idea/
.env
.env.bak
```

- [ ] **Step 4: Gradle wrapper 생성**

```bash
cd examples/sample-rp
gradle wrapper --gradle-version 8.10.2
```

(시스템에 gradle 이 없으면: `cp ../../gradlew ./ && cp ../../gradlew.bat ./ && cp -r ../../gradle ./`)

- [ ] **Step 5: 빈 빌드 통과 확인**

```bash
cd examples/sample-rp
./gradlew help -q
./gradlew dependencies --configuration runtimeClasspath | head -30
```

Expected: 첫 줄에 `+--- com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT` 가 보임.

- [ ] **Step 6: Commit**

```bash
git add examples/sample-rp/
git commit -m "feat(sample-rp): sample-rp Gradle 골격 + wrapper (T8)"
```

---

## Task 9: sample-rp common envelope 미러 (8개 클래스)

spec § 5.1 의 8개 클래스를 sample-rp 자체에 둠 (`:core` 의존 없음).

**Files:**
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/response/ApiResponse.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/response/ErrorDetail.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/response/FieldError.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/response/PageResponse.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/exception/ErrorCode.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/exception/BusinessException.java`

- [ ] **Step 1: ApiResponse + ErrorDetail + FieldError 작성**

`.../samplerp/common/response/ApiResponse.java`:
```java
package com.crosscert.passkey.samplerp.common.response;

import com.crosscert.passkey.samplerp.common.exception.ErrorCode;
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
        return new ApiResponse<>(false, code.getCode(), code.getMessage(), null,
                new ErrorDetail(code.getCode(), null), MDC.get("traceId"), LocalDateTime.now());
    }
    public static ApiResponse<Void> error(ErrorCode code, List<FieldError> fieldErrors) {
        return new ApiResponse<>(false, code.getCode(), code.getMessage(), null,
                new ErrorDetail(code.getCode(), fieldErrors), MDC.get("traceId"), LocalDateTime.now());
    }
    public static ApiResponse<Void> error(ErrorCode code, String detailMessage) {
        return new ApiResponse<>(false, code.getCode(), detailMessage, null,
                new ErrorDetail(code.getCode(), null), MDC.get("traceId"), LocalDateTime.now());
    }
}
```

`.../samplerp/common/response/ErrorDetail.java`:
```java
package com.crosscert.passkey.samplerp.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(String errorCode, List<FieldError> fieldErrors) {}
```

`.../samplerp/common/response/FieldError.java`:
```java
package com.crosscert.passkey.samplerp.common.response;

public record FieldError(String field, Object rejectedValue, String reason) {}
```

- [ ] **Step 2: PageResponse 작성**

`.../samplerp/common/response/PageResponse.java`:
```java
package com.crosscert.passkey.samplerp.common.response;

import java.util.List;

public record PageResponse<T>(
        List<T> content, int page, int size,
        long totalElements, int totalPages,
        boolean hasNext, boolean hasPrevious
) {}
```

(sample-rp 는 페이지네이션 안 쓰지만 템플릿 무결성 위해 포함 — spec § 5.1.)

- [ ] **Step 3: ErrorCode 작성**

`.../samplerp/common/exception/ErrorCode.java`:
```java
package com.crosscert.passkey.samplerp.common.exception;

import org.springframework.http.HttpStatus;

import static org.springframework.http.HttpStatus.*;

public enum ErrorCode {
    // Common
    INVALID_INPUT       (BAD_REQUEST,             "C001", "Invalid input value"),
    METHOD_NOT_ALLOWED  (HttpStatus.METHOD_NOT_ALLOWED, "C002", "Method not allowed"),
    ENTITY_NOT_FOUND    (NOT_FOUND,               "C003", "Entity not found"),
    MISSING_PARAMETER   (BAD_REQUEST,             "C004", "Required parameter missing"),
    TYPE_MISMATCH       (BAD_REQUEST,             "C005", "Type mismatch"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "Server error"),

    // Auth
    UNAUTHORIZED        (HttpStatus.UNAUTHORIZED, "A001", "Authentication required"),
    ACCESS_DENIED       (FORBIDDEN,               "A002", "Access denied"),

    // WebAuthn flow (sample-rp domain)
    USERNAME_TAKEN      (CONFLICT,                "W001", "Username already registered"),
    PENDING_REG_MISSING (BAD_REQUEST,             "W002", "No pending registration in session"),
    PENDING_AUTH_MISSING(BAD_REQUEST,             "W003", "No pending authentication in session"),

    // Passkey-server proxy
    PASSKEY_API_ERROR   (BAD_GATEWAY,             "P001", "Upstream passkey server error"),
    PASSKEY_AUTH_ERROR  (HttpStatus.UNAUTHORIZED, "P002", "Invalid X-API-Key (sample-rp config)"),
    PASSKEY_RATE_LIMITED(TOO_MANY_REQUESTS,       "P003", "Upstream rate limit exceeded"),
    PASSKEY_ID_TOKEN    (HttpStatus.UNAUTHORIZED, "P004", "ID Token verification failed");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus()  { return status; }
    public String getCode()        { return code; }
    public String getMessage()     { return message; }
}
```

- [ ] **Step 4: BusinessException 작성**

`.../samplerp/common/exception/BusinessException.java`:
```java
package com.crosscert.passkey.samplerp.common.exception;

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

- [ ] **Step 5: 컴파일 확인 + Commit**

```bash
cd examples/sample-rp
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/response/ \
        examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/exception/
git commit -m "feat(sample-rp): ApiResponse envelope 템플릿 미러 + ErrorCode (T9)"
```

---

## Task 10: sample-rp TraceIdFilter + GlobalExceptionHandler

**Files:**
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/filter/TraceIdFilter.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: TraceIdFilter 작성**

`.../samplerp/common/filter/TraceIdFilter.java`:
```java
package com.crosscert.passkey.samplerp.common.filter;

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

/**
 * X-Trace-Id 헤더가 있으면 사용, 없으면 새로 발급. MDC 키 "traceId" 는
 * SDK 의 PasskeyClientConfig.MDC_TRACE_ID_KEY 와 반드시 동일해야 한다 — SDK 의
 * 기본 traceIdProvider 가 같은 키를 읽음.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = Optional.ofNullable(req.getHeader(HEADER))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
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

- [ ] **Step 2: GlobalExceptionHandler 작성 (템플릿 8개 + SDK 예외 4개)**

`.../samplerp/common/exception/GlobalExceptionHandler.java`:
```java
package com.crosscert.passkey.samplerp.common.exception;

import com.crosscert.passkey.samplerp.common.response.ApiResponse;
import com.crosscert.passkey.samplerp.common.response.FieldError;
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
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
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
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
                .body(ApiResponse.error(ErrorCode.ACCESS_DENIED));
    }

    // ── SDK 예외 변환 4 ────────────────────────────────────────────

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

    // ── 마지막 fallback ────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("[Unhandled] {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
```

- [ ] **Step 3: 컴파일 확인 + Commit**

```bash
cd examples/sample-rp
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/filter/ \
        examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/exception/GlobalExceptionHandler.java
git commit -m "feat(sample-rp): TraceIdFilter + GlobalExceptionHandler (T10)"
```

---

## Task 11: sample-rp UserStore + SampleRpUser + SessionUser

**Files:**
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/user/SampleRpUser.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/user/InMemoryUserStore.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/session/SessionKeys.java`

- [ ] **Step 1: SampleRpUser record**

`.../samplerp/user/SampleRpUser.java`:
```java
package com.crosscert.passkey.samplerp.user;

import java.io.Serializable;
import java.time.Instant;

public record SampleRpUser(
        String userHandle,
        String username,
        String displayName,
        Instant createdAt,
        String credentialId   // confirmRegistration 후 채워짐. 없으면 null (pending).
) implements Serializable {}
```

- [ ] **Step 2: InMemoryUserStore 작성**

`.../samplerp/user/InMemoryUserStore.java`:
```java
package com.crosscert.passkey.samplerp.user;

import com.crosscert.passkey.samplerp.common.exception.BusinessException;
import com.crosscert.passkey.samplerp.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryUserStore {
    private final ConcurrentMap<String, SampleRpUser> byHandle   = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String>       byUsername = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    /** username 으로 새 userHandle (32B base64url) 발급 + pending 상태로 저장. 중복이면 USERNAME_TAKEN. */
    public String createPending(String username, String displayName) {
        if (byUsername.containsKey(username)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String userHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        SampleRpUser user = new SampleRpUser(userHandle, username, displayName, Instant.now(), null);
        byHandle.put(userHandle, user);
        byUsername.put(username, userHandle);
        return userHandle;
    }

    /** registration/finish 성공 후 credentialId 채워서 확정. */
    public void confirmRegistration(String userHandle, String credentialId) {
        byHandle.computeIfPresent(userHandle, (k, u) ->
                new SampleRpUser(u.userHandle(), u.username(), u.displayName(), u.createdAt(), credentialId));
    }

    public Optional<SampleRpUser> findByUserHandle(String userHandle) {
        return Optional.ofNullable(byHandle.get(userHandle));
    }

    public Optional<SampleRpUser> findByUsername(String username) {
        String handle = byUsername.get(username);
        return handle == null ? Optional.empty() : findByUserHandle(handle);
    }
}
```

- [ ] **Step 3: SessionKeys 상수 모음**

`.../samplerp/session/SessionKeys.java`:
```java
package com.crosscert.passkey.samplerp.session;

public final class SessionKeys {
    public static final String USER                 = "USER";
    public static final String PENDING_REG_TOKEN    = "PENDING_REG_TOKEN";
    public static final String PENDING_USER_HANDLE  = "PENDING_USER_HANDLE";
    public static final String PENDING_AUTH_TOKEN   = "PENDING_AUTH_TOKEN";
    private SessionKeys() {}
}
```

- [ ] **Step 4: 컴파일 확인 + Commit**

```bash
cd examples/sample-rp
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/user/ \
        examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/session/
git commit -m "feat(sample-rp): InMemoryUserStore + SampleRpUser + SessionKeys (T11)"
```

---

## Task 12: sample-rp config (PasskeyClient 빈, Session, Security)

**Files:**
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/SampleRpApplication.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/PasskeyProperties.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/PasskeyClientConfiguration.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/WebSecurityConfig.java`

- [ ] **Step 1: SampleRpApplication 메인 클래스**

`.../samplerp/SampleRpApplication.java`:
```java
package com.crosscert.passkey.samplerp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SampleRpApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleRpApplication.class, args);
    }
}
```

- [ ] **Step 2: PasskeyProperties 작성**

`.../samplerp/config/PasskeyProperties.java`:
```java
package com.crosscert.passkey.samplerp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "passkey")
public record PasskeyProperties(
        URI baseUrl,
        String apiKey,
        String tenantId,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl
) {}
```

- [ ] **Step 3: PasskeyClientConfiguration 작성**

`.../samplerp/config/PasskeyClientConfiguration.java`:
```java
package com.crosscert.passkey.samplerp.config;

import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PasskeyClientConfiguration {

    @Bean
    public PasskeyClient passkeyClient(PasskeyProperties props) {
        return PasskeyClient.of(new PasskeyClientConfig(
                props.baseUrl(),
                props.apiKey(),
                props.connectTimeout(),
                props.readTimeout(),
                props.jwksCacheTtl(),
                Clock.systemUTC(),
                null   // SDK 가 MDC.get("traceId") 기본값 사용
        ));
    }
}
```

- [ ] **Step 4: WebSecurityConfig 작성**

`.../samplerp/config/WebSecurityConfig.java`:
```java
package com.crosscert.passkey.samplerp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/", "/register", "/login", "/logout",
                                     "/webauthn/**", "/css/**", "/js/**").permitAll()
                    .anyRequest().permitAll())   // 데모용 — 보호 리소스 없음
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .formLogin(f -> f.disable())
            .httpBasic(h -> h.disable())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/"));
        return http.build();
    }
}
```

- [ ] **Step 5: 컴파일 확인 + Commit**

```bash
cd examples/sample-rp
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/SampleRpApplication.java \
        examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/config/
git commit -m "feat(sample-rp): Spring Boot main + PasskeyClient 빈 + WebSecurity (T12)"
```

---

## Task 13: sample-rp PageController + WebAuthnController

**Files:**
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/PageController.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/dto/RegisterStartReq.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/dto/RegisterCompleteReq.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/dto/LoginStartReq.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/dto/LoginCompleteReq.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/dto/RegisterOptionsResp.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/dto/LoginOptionsResp.java`
- Create: `examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/WebAuthnController.java`

- [ ] **Step 1: PageController 작성**

`.../samplerp/web/PageController.java`:
```java
package com.crosscert.passkey.samplerp.web;

import com.crosscert.passkey.samplerp.session.SessionKeys;
import com.crosscert.passkey.samplerp.user.SampleRpUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index(HttpSession s, Model m) {
        SampleRpUser u = (SampleRpUser) s.getAttribute(SessionKeys.USER);
        m.addAttribute("user", u);
        return "index";
    }

    @GetMapping("/register")
    public String register() { return "register"; }

    @GetMapping("/login")
    public String login() { return "login"; }
}
```

- [ ] **Step 2: Web DTO 6개 작성**

`.../samplerp/web/dto/RegisterStartReq.java`:
```java
package com.crosscert.passkey.samplerp.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterStartReq(
        @NotBlank String username,
        @NotBlank String displayName
) {}
```

`.../samplerp/web/dto/RegisterCompleteReq.java`:
```java
package com.crosscert.passkey.samplerp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record RegisterCompleteReq(@NotNull JsonNode publicKeyCredential) {}
```

`.../samplerp/web/dto/LoginStartReq.java`:
```java
package com.crosscert.passkey.samplerp.web.dto;

public record LoginStartReq(String username) {}
```

`.../samplerp/web/dto/LoginCompleteReq.java`:
```java
package com.crosscert.passkey.samplerp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record LoginCompleteReq(@NotNull JsonNode publicKeyCredential) {}
```

`.../samplerp/web/dto/RegisterOptionsResp.java`:
```java
package com.crosscert.passkey.samplerp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RegisterOptionsResp(JsonNode publicKeyCredentialCreationOptions) {}
```

`.../samplerp/web/dto/LoginOptionsResp.java`:
```java
package com.crosscert.passkey.samplerp.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record LoginOptionsResp(JsonNode publicKeyCredentialRequestOptions) {}
```

- [ ] **Step 3: WebAuthnController 작성**

`.../samplerp/web/WebAuthnController.java`:
```java
package com.crosscert.passkey.samplerp.web;

import com.crosscert.passkey.samplerp.common.exception.BusinessException;
import com.crosscert.passkey.samplerp.common.exception.ErrorCode;
import com.crosscert.passkey.samplerp.common.response.ApiResponse;
import com.crosscert.passkey.samplerp.config.PasskeyProperties;
import com.crosscert.passkey.samplerp.session.SessionKeys;
import com.crosscert.passkey.samplerp.user.InMemoryUserStore;
import com.crosscert.passkey.samplerp.user.SampleRpUser;
import com.crosscert.passkey.samplerp.web.dto.*;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.*;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webauthn")
public class WebAuthnController {

    private final PasskeyClient passkey;
    private final InMemoryUserStore users;
    private final PasskeyProperties props;

    public WebAuthnController(PasskeyClient passkey, InMemoryUserStore users, PasskeyProperties props) {
        this.passkey = passkey;
        this.users   = users;
        this.props   = props;
    }

    // ── Registration ─────────────────────────────────────────────

    @PostMapping("/register/options")
    public ApiResponse<RegisterOptionsResp> registerOptions(@Valid @RequestBody RegisterStartReq req,
                                                            HttpSession s) {
        String userHandle = users.createPending(req.username(), req.displayName());
        var sdkResp = passkey.registrationStart(
                new RegistrationStartRequest(userHandle, req.displayName(), req.username()));
        s.setAttribute(SessionKeys.PENDING_REG_TOKEN, sdkResp.registrationToken());
        s.setAttribute(SessionKeys.PENDING_USER_HANDLE, userHandle);
        return ApiResponse.ok(new RegisterOptionsResp(sdkResp.publicKeyCredentialCreationOptions()));
    }

    @PostMapping("/register/complete")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req,
                                                                    HttpSession s) {
        String token  = (String) s.getAttribute(SessionKeys.PENDING_REG_TOKEN);
        String handle = (String) s.getAttribute(SessionKeys.PENDING_USER_HANDLE);
        if (token == null) throw new BusinessException(ErrorCode.PENDING_REG_MISSING);

        var fin = passkey.registrationFinish(new RegistrationFinishRequest(token, req.publicKeyCredential()));
        users.confirmRegistration(handle, fin.credentialId());
        s.removeAttribute(SessionKeys.PENDING_REG_TOKEN);
        s.removeAttribute(SessionKeys.PENDING_USER_HANDLE);
        return ApiResponse.ok("Passkey registered", fin);
    }

    // ── Login ────────────────────────────────────────────────────

    @PostMapping("/login/options")
    public ApiResponse<LoginOptionsResp> loginOptions(@RequestBody LoginStartReq req, HttpSession s) {
        String userHandle = req.username() == null ? null
                : users.findByUsername(req.username()).map(SampleRpUser::userHandle).orElse(null);
        var sdkResp = passkey.authenticationStart(new AuthenticationStartRequest(userHandle));
        s.setAttribute(SessionKeys.PENDING_AUTH_TOKEN, sdkResp.authenticationToken());
        return ApiResponse.ok(new LoginOptionsResp(sdkResp.publicKeyCredentialRequestOptions()));
    }

    @PostMapping("/login/complete")
    public ApiResponse<Void> loginComplete(@Valid @RequestBody LoginCompleteReq req, HttpSession s) {
        String token = (String) s.getAttribute(SessionKeys.PENDING_AUTH_TOKEN);
        if (token == null) throw new BusinessException(ErrorCode.PENDING_AUTH_MISSING);

        var fin = passkey.authenticationFinish(new AuthenticationFinishRequest(token, req.publicKeyCredential()));
        IdTokenClaims claims = passkey.verifyIdToken(fin.idToken());

        String expectedIss = props.baseUrl() + "/" + props.tenantId();
        if (!expectedIss.equals(claims.iss()))
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "iss mismatch");
        if (!props.tenantId().equals(claims.aud()))
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "aud mismatch");

        SampleRpUser user = users.findByUserHandle(claims.sub())
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub"));
        s.setAttribute(SessionKeys.USER, user);
        s.removeAttribute(SessionKeys.PENDING_AUTH_TOKEN);
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 4: 컴파일 확인 + Commit**

```bash
cd examples/sample-rp
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sample-rp/src/main/java/com/crosscert/passkey/samplerp/web/
git commit -m "feat(sample-rp): WebAuthnController + PageController + 6 DTOs (T13)"
```

---

## Task 14: sample-rp application.yml + Thymeleaf 페이지

**Files:**
- Create: `examples/sample-rp/src/main/resources/application.yml`
- Create: `examples/sample-rp/src/main/resources/templates/layout.html`
- Create: `examples/sample-rp/src/main/resources/templates/index.html`
- Create: `examples/sample-rp/src/main/resources/templates/register.html`
- Create: `examples/sample-rp/src/main/resources/templates/login.html`
- Create: `examples/sample-rp/src/main/resources/static/css/site.css`
- Create: `examples/sample-rp/src/main/resources/static/js/helpers.js`
- Create: `examples/sample-rp/.env.example`

- [ ] **Step 1: application.yml 작성**

`examples/sample-rp/src/main/resources/application.yml`:
```yaml
server:
  port: 9090

sample-rp:
  origin: ${SAMPLE_RP_ORIGIN:http://localhost:9090}

passkey:
  base-url: ${PASSKEY_BASE_URL:http://localhost:8080}
  api-key:   ${PASSKEY_API_KEY:}
  tenant-id: ${PASSKEY_TENANT_ID:}
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
    com.crosscert.passkey.sdk: DEBUG   # 운영 환경에서는 INFO 이하 (spec § 9 logging redaction)
    com.crosscert.passkey.samplerp: INFO
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%X{traceId:-}] %logger{36} - %msg%n"
```

- [ ] **Step 2: .env.example**

`examples/sample-rp/.env.example`:
```
PASSKEY_BASE_URL=http://localhost:8080
PASSKEY_TENANT_ID=
PASSKEY_API_KEY=
SAMPLE_RP_ORIGIN=http://localhost:9090
```

- [ ] **Step 3: helpers.js 작성**

`examples/sample-rp/src/main/resources/static/js/helpers.js`:
```javascript
// localhost 와 127.0.0.1 은 webauthn 에서 별개로 취급된다. 항상 localhost 로 접근하라.
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
    excludeCredentials: (opts.excludeCredentials || []).map(c => ({ ...c, id: b64urlToBuf(c.id) }))
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
    allowCredentials: (opts.allowCredentials || []).map(c => ({ ...c, id: b64urlToBuf(c.id) }))
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
  const csrfMeta = document.querySelector('meta[name=csrf]');
  const csrf = csrfMeta ? csrfMeta.content : '';
  const res  = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
    body: JSON.stringify(body),
    credentials: 'same-origin'
  });
  const env = await res.json();
  if (!env.success) {
    const err = new Error(env.message || 'Unknown error');
    err.code = env.code; err.traceId = env.traceId; err.fieldErrors = env.error?.fieldErrors;
    throw err;
  }
  return env.data;
}
```

- [ ] **Step 4: site.css**

`examples/sample-rp/src/main/resources/static/css/site.css`:
```css
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
       max-width: 720px; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; color: #222; }
header { display: flex; justify-content: space-between; align-items: center;
         border-bottom: 1px solid #eee; padding-bottom: .5rem; margin-bottom: 1.5rem; }
header .status { font-size: .9rem; color: #555; }
header .status.logged-in { color: #0a7d2c; font-weight: 600; }
nav a { margin-right: 1rem; }
form { display: flex; flex-direction: column; gap: .75rem; max-width: 360px; }
input { padding: .5rem; font-size: 1rem; }
button { padding: .6rem 1rem; font-size: 1rem; cursor: pointer; background: #1a73e8;
         color: white; border: 0; border-radius: 4px; }
button:hover { background: #155bb5; }
pre { background: #f6f8fa; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: .85rem; }
.error { color: #b00020; font-weight: 600; }
```

- [ ] **Step 5: layout.html fragment**

`examples/sample-rp/src/main/resources/templates/layout.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.thymeleaf.org">
<head th:fragment="head(title)">
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="csrf" th:content="${_csrf?.token}">
  <title th:text="${title}">Sample RP</title>
  <link rel="stylesheet" href="/css/site.css">
</head>
<body>
<header th:fragment="header">
  <nav>
    <a href="/">Home</a>
    <a href="/register">Register</a>
    <a href="/login">Login</a>
  </nav>
  <span class="status"
        th:classappend="${user != null} ? 'logged-in' : ''"
        th:text="${user != null} ? '로그인됨: ' + ${user.username} : '비로그인'">비로그인</span>
</header>
</body>
</html>
```

- [ ] **Step 6: index.html**

`examples/sample-rp/src/main/resources/templates/index.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{layout :: head('Sample RP — Home')}"></head>
<body>
<header th:replace="~{layout :: header}"></header>

<h1>Sample RP</h1>

<div th:if="${user == null}">
  <p>이 페이지는 Passkey2 의 RP API 통합을 sdk-java 로 시연하는 데모입니다.</p>
  <p><a href="/register">Register</a> 후 <a href="/login">Login</a> 으로 진행하세요.</p>
</div>

<div th:if="${user != null}">
  <p>안녕하세요, <strong th:text="${user.displayName}">User</strong> 님.</p>
  <pre th:text="|userHandle : ${user.userHandle}
credentialId : ${user.credentialId}|">claims</pre>
  <form method="post" action="/logout">
    <input type="hidden" th:name="${_csrf?.parameterName}" th:value="${_csrf?.token}">
    <button type="submit">Logout</button>
  </form>
</div>
</body>
</html>
```

- [ ] **Step 7: register.html**

`examples/sample-rp/src/main/resources/templates/register.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{layout :: head('Sample RP — Register')}"></head>
<body>
<header th:replace="~{layout :: header}"></header>

<h1>Register a passkey</h1>

<form id="form" onsubmit="return false">
  <label>Username <input id="username" required></label>
  <label>Display name <input id="displayName" required></label>
  <button id="btn-register" type="button">Register passkey</button>
</form>

<pre id="result"></pre>
<p id="error" class="error"></p>

<script type="module">
  import { decodeCreationOptions, encodeAttestationCredential, postJson } from '/js/helpers.js';

  const btn = document.getElementById('btn-register');
  const result = document.getElementById('result');
  const error  = document.getElementById('error');

  btn.addEventListener('click', async () => {
    error.textContent = '';
    result.textContent = '';
    try {
      const username    = document.getElementById('username').value;
      const displayName = document.getElementById('displayName').value;

      const start = await postJson('/webauthn/register/options', { username, displayName });
      const cred = await navigator.credentials.create({
        publicKey: decodeCreationOptions(start.publicKeyCredentialCreationOptions)
      });
      const fin = await postJson('/webauthn/register/complete', {
        publicKeyCredential: encodeAttestationCredential(cred)
      });
      result.textContent = 'Passkey registered:\n' + JSON.stringify(fin, null, 2);
    } catch (e) {
      error.textContent = '[' + (e.code || 'ERR') + '] ' + e.message
                        + (e.traceId ? '  (traceId=' + e.traceId + ')' : '');
    }
  });
</script>
</body>
</html>
```

- [ ] **Step 8: login.html**

`examples/sample-rp/src/main/resources/templates/login.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{layout :: head('Sample RP — Login')}"></head>
<body>
<header th:replace="~{layout :: header}"></header>

<h1>Login with a passkey</h1>

<form id="form" onsubmit="return false">
  <label>Username (선택) <input id="username"></label>
  <button id="btn-login" type="button">Login with passkey</button>
</form>

<p id="error" class="error"></p>

<script type="module">
  import { decodeRequestOptions, encodeAssertionCredential, postJson } from '/js/helpers.js';

  const btn = document.getElementById('btn-login');
  const error = document.getElementById('error');

  btn.addEventListener('click', async () => {
    error.textContent = '';
    try {
      const username = document.getElementById('username').value || null;
      const start = await postJson('/webauthn/login/options', { username });
      const assertion = await navigator.credentials.get({
        publicKey: decodeRequestOptions(start.publicKeyCredentialRequestOptions)
      });
      await postJson('/webauthn/login/complete', {
        publicKeyCredential: encodeAssertionCredential(assertion)
      });
      window.location = '/';
    } catch (e) {
      error.textContent = '[' + (e.code || 'ERR') + '] ' + e.message
                        + (e.traceId ? '  (traceId=' + e.traceId + ')' : '');
    }
  });
</script>
</body>
</html>
```

- [ ] **Step 9: 빌드 확인 + Commit**

```bash
cd examples/sample-rp
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

```bash
git add examples/sample-rp/src/main/resources/ examples/sample-rp/.env.example
git commit -m "feat(sample-rp): application.yml + Thymeleaf 3 pages + helpers.js (T14)"
```

---

## Task 15: bootstrap 스크립트

**Files:**
- Create: `examples/sample-rp/scripts/bootstrap-sample-rp.sh` (실행 권한 포함)

- [ ] **Step 1: 스크립트 작성**

`examples/sample-rp/scripts/bootstrap-sample-rp.sh`:
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

command -v jq   >/dev/null || { echo "✗ jq 가 필요합니다 (brew install jq)"; exit 1; }
command -v curl >/dev/null || { echo "✗ curl 이 필요합니다"; exit 1; }

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

# 0. admin 로그인
echo "→ admin 로그인 (${ADMIN_BASE})"
curl -fsS -c "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  "$ADMIN_BASE/admin/api/auth/login" >/dev/null

# 1. demo tenant
echo "→ demo tenant 생성"
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
echo "→ demo API key 발급"
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
echo "  apiKey   : ${API_KEY:0:8}…  (full value in .env, 1회만 노출)"

# 4. health check
echo "→ X-API-Key 헬스체크"
curl -fsS -H "X-API-Key: $API_KEY" \
  "http://localhost:8080/api/v1/rp/authentication/start" \
  -H 'Content-Type: application/json' -d '{}' \
  | jq -e '.success == true' >/dev/null \
  && echo "✓ X-API-Key validated against passkey-app" \
  || { echo "✗ X-API-Key validation failed — admin-app 응답 확인"; exit 1; }

echo
echo "다음: cd examples/sample-rp && set -a && source .env && set +a && ./gradlew bootRun"
```

- [ ] **Step 2: 실행 권한 + Commit**

```bash
chmod +x examples/sample-rp/scripts/bootstrap-sample-rp.sh
git add examples/sample-rp/scripts/
git commit -m "feat(sample-rp): bootstrap-sample-rp.sh — demo tenant + API key + health curl (T15)"
```

---

## Task 16: README + .gitignore

**Files:**
- Create: `examples/README.md`
- Modify: Passkey2 root `.gitignore` (`.env` 류 패턴 추가는 sample-rp 안에서만 처리 — root 는 변경 없음)

- [ ] **Step 1: examples/README.md 작성**

`examples/README.md`:
```markdown
# Passkey SDK + Sample RP

`:passkey-app` 의 RP API 를 외부 RP 개발자 관점에서 dogfooding 하는 묶음.

- `sdk-java/` — 중간 레벨 도메인 Java 클라이언트 (`com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT`).
- `sample-rp/` — `sdk-java` 만 의존하는 Spring Boot 3.5 데모 앱. `:9090`.
- 테스트 페이지 — `sample-rp` 가 서빙하는 Thymeleaf 3개 페이지.

> **이 두 프로젝트는 Passkey2 의 `settings.gradle.kts` 에 포함되지 않는다** (Phase 0 § 9). main merge 후 sibling 디렉토리 (`../sdk-java`, `../sample-rp`) 로 분리될 예정.

## 7-step quickstart

```
[1] Passkey2: docker compose up -d
[2] Passkey2: ./gradlew :passkey-app:bootRun        (별 터미널)
[3] Passkey2: ./gradlew :admin-app:bootRun          (별 터미널)
[4] sdk-java: cd examples/sdk-java && ./gradlew publishToMavenLocal
[5] sample-rp: cd ../sample-rp && ./scripts/bootstrap-sample-rp.sh
[6] sample-rp: set -a && source .env && set +a && ./gradlew bootRun
[7] 브라우저: http://localhost:9090
```

## Manual smoke checklist (5분)

bootstrap → bootRun 후 다음 4 단계를 직접 따라 한다:

1. `http://localhost:9090/register` → username + displayName → **Register passkey** → "Passkey registered" 로그가 보임
2. `http://localhost:9090/login` → 같은 username → **Login with passkey** → `/` 로 리다이렉트, 헤더에 "로그인됨: <username>"
3. `/` 페이지의 **Logout** → 헤더가 "비로그인" 으로 돌아옴
4. 두 서버 로그 확인 — sample-rp 와 passkey-app 양쪽에 동일한 `X-Trace-Id` 가 찍힘

## Virtual authenticator (실 인증기 없는 환경)

Chrome devtools → `⋮` → More tools → **WebAuthn** → "Enable virtual authenticator" 체크. 데모 동안만 활성화.

## 알려진 한계

- `localhost` 전용 (WebAuthn HTTPS 강제 예외). `127.0.0.1` 로 접근하면 RP id 매칭 실패.
- in-memory 세션·user store — sample-rp 재시작 시 등록 데이터 유실.
- 운영 환경에서는 `application.yml` 의 `logging.level.com.crosscert.passkey.sdk` 를 `INFO` 이하로 (DEBUG 로그가 마스킹은 되지만 안전 기본값은 INFO).

## passkey-app 응답 envelope 이 바뀌면

`sdk-java/src/test/java/.../PasskeyClientContractIT.java` 가 가장 먼저 깨진다. passkey-app phase PR 에 contract fixture (`src/test/resources/contract/*.json`) 갱신이 함께 들어가야 한다.
```

- [ ] **Step 2: Commit**

```bash
git add examples/README.md
git commit -m "docs(examples): README 7-step quickstart + manual smoke + virtual authenticator (T16)"
```

---

## Task 17: SampleRpSmokeIT (TDD — 자동 테스트 2번)

webauthn4j-test 의 `ClientPlatform` + `PackedAuthenticator` 로 브라우저 시뮬레이션. sample-rp 가 부팅하고 passkey-app 이 떠있는 환경 (Testcontainers Oracle/Redis + passkey-app JAR) 에서 등록 → 로그인 happy path 1개.

**Files:**
- Create: `examples/sample-rp/src/test/java/com/crosscert/passkey/samplerp/SampleRpSmokeIT.java`
- Create: `examples/sample-rp/src/test/resources/application-test.yml`

- [ ] **Step 1: test application config**

`examples/sample-rp/src/test/resources/application-test.yml`:
```yaml
passkey:
  base-url: ${test.passkey.base-url}
  api-key:  ${test.passkey.api-key}
  tenant-id: ${test.passkey.tenant-id}
  connect-timeout: 5s
  read-timeout: 30s
  jwks-cache-ttl: 30s
spring:
  thymeleaf:
    cache: false
```

- [ ] **Step 2: SampleRpSmokeIT 작성 (실패 상태로 먼저)**

`examples/sample-rp/src/test/java/com/crosscert/passkey/samplerp/SampleRpSmokeIT.java`:
```java
package com.crosscert.passkey.samplerp;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke IT — sample-rp 가 떠 있고 passkey-app:8080 + admin-app:8081 + docker compose 인프라가
 * 가동된 상태에서 등록 → 로그인 happy path 가 풀 스택으로 통과하는지 검증.
 *
 * 실행 전제 (CI 미구축이므로 local-only):
 *   1. cd ../../Passkey2 && docker compose up -d
 *   2. cd ../../Passkey2 && ./gradlew :passkey-app:bootRun  (별 터미널)
 *   3. cd ../../Passkey2 && ./gradlew :admin-app:bootRun    (별 터미널)
 *   4. cd examples/sdk-java && ./gradlew publishToMavenLocal
 *   5. cd examples/sample-rp && ./scripts/bootstrap-sample-rp.sh
 *   6. set -a && source examples/sample-rp/.env && set +a
 *   7. ./gradlew :sample-rp:test --tests SampleRpSmokeIT
 *
 * 위 전제가 만족되지 않으면 @Disabled 마커가 켜져 있도록 설계.
 * passkey-app + admin-app 의 자동 부팅을 IT 가 책임지지 않는다 (spec § 8.1 — 개발 속도 우선).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Disabled("Manual run only. See class javadoc for prerequisites.")
class SampleRpSmokeIT {

    @Test
    void registrationAndLoginHappyPath_dryPlaceholder() {
        // 이 테스트는 의도적으로 빈 상태로 시작한다.
        // Step 4 에서 webauthn4j-test 의 ClientPlatform 으로 ceremony 시뮬레이션을 채운다.
        org.junit.jupiter.api.Assertions.assertTrue(true);
    }
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
cd examples/sample-rp
./gradlew compileTestJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 실제 ceremony 시뮬레이션으로 본문 채우기**

webauthn4j-test 의 `ClientPlatform` 사용 패턴은 Passkey2 의 `passkey-app/src/test/java/.../Fido2EndToEndIT.java` 와 동일. 이 IT 는 다음 흐름을 검증:

`SampleRpSmokeIT.java` (본문 교체):
```java
package com.crosscert.passkey.samplerp;

import com.crosscert.passkey.samplerp.session.SessionKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.test.client.ClientPlatform;
import com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.converter.util.ObjectConverter;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Disabled("Manual run only — requires passkey-app + admin-app + docker compose + bootstrap. See javadoc.")
class SampleRpSmokeIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;

    @Test
    void registrationAndLoginHappyPath() throws Exception {
        RestClient http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        URI rpOrigin = URI.create("http://localhost:" + port);
        ClientPlatform client = new ClientPlatform(rpOrigin, new PackedAuthenticator());

        // ── 1. /webauthn/register/options ──
        JsonNode regOptionsEnv = http.post().uri("/webauthn/register/options")
                .body(Map.of("username", "alice", "displayName", "Alice"))
                .retrieve().body(JsonNode.class);
        assertThat(regOptionsEnv.get("success").asBoolean()).isTrue();

        JsonNode optsJson = regOptionsEnv.get("data").get("publicKeyCredentialCreationOptions");
        PublicKeyCredentialCreationOptions opts = new ObjectConverter()
                .getJsonConverter()
                .readValue(om.writeValueAsString(optsJson), PublicKeyCredentialCreationOptions.class);

        var attestationCred = client.create(opts);
        JsonNode attestationJson = om.readTree(new ObjectConverter().getJsonConverter()
                .writeValueAsString(attestationCred));

        // ── 2. /webauthn/register/complete ──
        JsonNode regFinishEnv = http.post().uri("/webauthn/register/complete")
                .body(Map.of("publicKeyCredential", attestationJson))
                .retrieve().body(JsonNode.class);
        assertThat(regFinishEnv.get("success").asBoolean()).isTrue();
        assertThat(regFinishEnv.get("data").get("credentialId").asText()).isNotBlank();

        // ── 3. /webauthn/login/options ──
        JsonNode loginOptionsEnv = http.post().uri("/webauthn/login/options")
                .body(Map.of("username", "alice"))
                .retrieve().body(JsonNode.class);
        JsonNode reqOptsJson = loginOptionsEnv.get("data").get("publicKeyCredentialRequestOptions");
        PublicKeyCredentialRequestOptions reqOpts = new ObjectConverter().getJsonConverter()
                .readValue(om.writeValueAsString(reqOptsJson), PublicKeyCredentialRequestOptions.class);

        var assertionCred = client.get(reqOpts);
        JsonNode assertionJson = om.readTree(new ObjectConverter().getJsonConverter()
                .writeValueAsString(assertionCred));

        // ── 4. /webauthn/login/complete ──
        JsonNode loginFinishEnv = http.post().uri("/webauthn/login/complete")
                .body(Map.of("publicKeyCredential", assertionJson))
                .retrieve().body(JsonNode.class);
        assertThat(loginFinishEnv.get("success").asBoolean()).isTrue();
    }
}
```

(주: webauthn4j-test 의 ClientPlatform/PackedAuthenticator API 는 0.29.x 기준. Passkey2 의 `Fido2EndToEndIT` 와 동일 패턴이므로 차이 발생 시 그쪽을 참조해 조정.)

- [ ] **Step 5: 컴파일 확인 + Commit**

```bash
cd examples/sample-rp
./gradlew compileTestJava
```

Expected: BUILD SUCCESSFUL. `@Disabled` 이므로 `./gradlew test` 는 통과하되 실제 시나리오는 수동 실행.

```bash
git add examples/sample-rp/src/test/
git commit -m "test(sample-rp): SampleRpSmokeIT (manual run, @Disabled) (T17)"
```

---

## Task 18: End-to-end manual smoke 검증

이 task 는 코드 변경 없음. 7-step quickstart 를 수동 실행해서 묶음이 동작하는지 확인.

- [ ] **Step 1: Passkey2 인프라·서버 가동 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker compose ps          # oracle + redis Up
lsof -nP -iTCP -sTCP:LISTEN | grep -E ":(8080|8081)"   # 각각 떠있는지
```

Expected: oracle/redis 컨테이너 Up + 두 Java 프로세스가 8080, 8081 에 LISTEN.

(만일 떠있지 않으면 → `./gradlew :passkey-app:bootRun` / `:admin-app:bootRun` 를 별 터미널에서 띄움.)

- [ ] **Step 2: sdk-java 퍼블리시**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/sdk-and-sample-rp/examples/sdk-java
./gradlew publishToMavenLocal
ls ~/.m2/repository/com/crosscert/passkey/passkey-sdk-java/0.1.0-SNAPSHOT/
```

Expected: SUCCESS + jar/pom 게시.

- [ ] **Step 3: bootstrap-sample-rp.sh 실행**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/sdk-and-sample-rp/examples/sample-rp
./scripts/bootstrap-sample-rp.sh
cat .env       # 4개 키 채워졌는지
```

Expected: "✓ sample-rp/.env written" + "✓ X-API-Key validated against passkey-app".

- [ ] **Step 4: sample-rp 부팅**

```bash
set -a; source .env; set +a
./gradlew bootRun
```

Expected: "Started SampleRpApplication" + 9090 LISTEN.

- [ ] **Step 5: 브라우저에서 4단계 manual smoke**

브라우저 (Chrome) 로 `http://localhost:9090` 열고:

1. `/register` → username "alice" + displayName "Alice" → **Register passkey** → Chrome devtools WebAuthn 패널의 virtual authenticator 가 응답 → result 박스에 `{credentialId, aaguid, attestationFormat}` JSON.
2. `/login` → username "alice" → **Login with passkey** → 자동 `/` 로 이동, 헤더 "로그인됨: alice".
3. `Logout` → 헤더 "비로그인" 으로 돌아옴.
4. sample-rp 로그와 passkey-app 로그 둘 다에 동일한 `X-Trace-Id` 가 보이는지 grep.

```bash
# 다른 터미널
grep traceId /Users/jhyun/Git/10-work/crosscert/Passkey2/passkey-app-bootRun.log | head -5
grep traceId examples/sample-rp/logs/*.log | head -5   # 또는 stdout
```

Expected: 같은 16-hex traceId 가 양쪽에 보임.

- [ ] **Step 6: 결과를 followup 으로 기록**

```bash
cat > docs/superpowers/followups/2026-05-27-sdk-and-sample-rp-followups.md <<'EOF'
# SDK + sample-rp followups

Spec: docs/superpowers/specs/2026-05-27-sdk-and-sample-rp-design.md
Plan: docs/superpowers/plans/2026-05-27-sdk-and-sample-rp.md

## Manual smoke result (T18)

- [ ] T18.1 docker compose ps 통과
- [ ] T18.2 sdk-java publishToMavenLocal 통과
- [ ] T18.3 bootstrap-sample-rp.sh 통과
- [ ] T18.4 sample-rp bootRun 통과
- [ ] T18.5 브라우저 register → login → logout 4단계 통과
- [ ] T18.6 traceId 양쪽 로그 매칭 통과

## Deferred (spec § 10)

1. Maven Central 배포 + group/artifact 확정
2. SDK 자동 재시도/서킷브레이커 (Resilience4j 옵션)
3. JWKS pre-warm 옵션
4. sample-rp 영구 저장소 (H2 file / Postgres)
5. 멀티 tenant sample-rp 시연
6. Kotlin/Scala 친화 표면 (DSL 빌더)
7. WebAuthn level 3 마이그레이션
8. admin 2FA via passkey (admin dogfood) — 원래 Phase 4 합의의 두 번째 절반

## SampleRpSmokeIT 자동 부팅 (T17 보강)

@Disabled 마커 제거 + Testcontainers 로 passkey-app + admin-app + Oracle + Redis 부팅하는
패턴은 다음 작업으로. 현재는 수동 실행만.
EOF
git add docs/superpowers/followups/2026-05-27-sdk-and-sample-rp-followups.md
git commit -m "docs(followups): SDK + sample-rp manual smoke 기록 + deferred 8개 (T18)"
```

(체크박스는 실제 통과 시점에 사용자가 직접 표시.)

---

## Task 19: Plan finalization + merge 준비

이 task 는 worktree 안에서의 최종 정리.

- [ ] **Step 1: 전체 빌드 한 번 더**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/sdk-and-sample-rp/examples/sdk-java
./gradlew clean build publishToMavenLocal

cd ../sample-rp
./gradlew clean build -x test
```

Expected: 둘 다 BUILD SUCCESSFUL.

- [ ] **Step 2: codex review (메모리 feedback_codex_review_before_commit 에 따라)**

merge 전 staged diff 없이도 `git log feature/sdk-and-sample-rp ^main` 으로 본 branch 의 commit 전체를 codex 에게 보여 review 요청.

```bash
git log --stat feature/sdk-and-sample-rp ^main
```

이 출력과 함께 `/codex:review` 또는 codex rescue 호출.

- [ ] **Step 3: worktree → main merge**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff feature/sdk-and-sample-rp \
  -m "Merge feature/sdk-and-sample-rp — SDK + sample-rp + 테스트 페이지 dogfood 묶음"
```

Expected: merge commit 생성, fast-forward 아님.

- [ ] **Step 4: examples/ → sibling 으로 이동 (spec § 2 "sibling 디렉토리")**

```bash
cd /Users/jhyun/Git/10-work/crosscert
git -C Passkey2 mv examples/sdk-java   ../sdk-java     # ← git mv 는 .git 경계 못 넘음
```

위 한 줄은 실패할 것이다 — git mv 는 같은 repo 안에서만 동작. 대신:

```bash
cd /Users/jhyun/Git/10-work/crosscert

# Passkey2 안의 examples/ 를 sibling 으로 복사
mv Passkey2/examples/sdk-java   ./sdk-java
mv Passkey2/examples/sample-rp  ./sample-rp

# Passkey2 의 빈 examples/ 디렉토리 정리
rmdir Passkey2/examples 2>/dev/null || true

# Passkey2 에서 examples/ 삭제를 commit
cd Passkey2
git add -A examples/ 2>/dev/null || true
git rm -r --cached examples/ 2>/dev/null || true
git commit -m "chore: examples/ 이동 완료 — sdk-java/, sample-rp/ 가 sibling 디렉토리로 분리됨" || true

# 새 sibling 들 각각 별 git 저장소로 init (Phase 0 합의: 자체 gradlew, 메인 빌드 그래프 밖)
cd ../sdk-java   && git init && git add -A && git commit -m "Initial commit — extracted from Passkey2 examples/"
cd ../sample-rp  && git init && git add -A && git commit -m "Initial commit — extracted from Passkey2 examples/"
```

(주의: 이 단계는 의도적으로 자동 commit 까지만 하고 push 안 함. 추후 외부 repo 만들면 그쪽으로 remote 설정.)

- [ ] **Step 5: 최종 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert
ls -la sdk-java sample-rp Passkey2/examples 2>&1 | head -20
```

Expected: `sdk-java/`, `sample-rp/` 가 sibling 으로 보이고 `Passkey2/examples/` 는 존재하지 않음.

- [ ] **Step 6: worktree 정리**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git worktree remove .claude/worktrees/sdk-and-sample-rp
git branch -d feature/sdk-and-sample-rp   # merge 됐으니 -d
```

Expected: worktree 제거, branch 정리.

---

## Self-Review

플랜 작성 후 fresh eyes 체크:

**1. Spec coverage**
- § 1.1 DoD 3가지 (sdk-java, sample-rp, 테스트 페이지) → T1-T7 (sdk), T8-T16 (sample-rp 본체+테스트 페이지), T18 (manual smoke)
- § 2 디렉토리 구조 → T1, T8 (자체 wrapper, settings.gradle.kts) + T19 (sibling 분리)
- § 3 SDK 8 패키지 → T2 envelope+exception, T3 dto, T4 idtoken, T5 client+config+internal
- § 4 sample-rp 패키지 → T9 common/response·exception, T10 filter+handler, T11 user+session, T12 config, T13 web, T14 resources
- § 5 envelope (ErrorCode 16개 + handler 12개) → T9, T10
- § 6 helpers.js + 3 templates → T14
- § 7 bootstrap + .env + README → T15, T16
- § 8 자동 테스트 2개 → T6 ContractIT, T17 SmokeIT (@Disabled, manual)
- § 9 위험 대응 → DEBUG 로깅 redaction (T5 interceptor), traceId 키 일치 (T10, T11 SessionKeys + T5 MDC_TRACE_ID_KEY 상수), API key gitignore (T8), localhost 강제 (T14 helpers.js 주석)
- § 10 후속 8개 → T18 followup 파일

**2. Placeholder scan**
- "TBD", "TODO" 없음 — 검색 결과 0
- "implement later" 없음
- 모든 step 에 actual code 또는 actual 명령
- T17 의 ceremony 시뮬레이션은 Step 4 에서 실 코드 제시 (webauthn4j-test API)

**3. Type consistency**
- `PasskeyClient.registrationStart(RegistrationStartRequest)` ↔ T3 dto ↔ T6 test 호출부 — 일치
- `SessionKeys.PENDING_REG_TOKEN` (T11) ↔ WebAuthnController 사용 (T13) — 일치
- `ErrorCode.PASSKEY_ID_TOKEN` (T9) ↔ GlobalExceptionHandler 변환 (T10) ↔ WebAuthnController throw (T13) — 일치
- `PasskeyClientConfig.MDC_TRACE_ID_KEY="traceId"` (T5) ↔ TraceIdFilter MDC_KEY="traceId" (T10) — 명시적 일치

**4. Ambiguity**
- T1 Step 5 의 "gradle wrapper 가 없을 때 Passkey2 wrapper 복사" — fallback 명시
- T19 Step 4 의 git mv 한계 — 명시적으로 "실패할 것 → 대신" 으로 안내
- T17 의 webauthn4j-test API 버전 차이 시 `Fido2EndToEndIT` 참조하라고 안내
