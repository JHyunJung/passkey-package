# 재기동 없는 동적 API Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app(SDK 클라이언트)이 재기동 없이 API Key 를 교체/회수 반영하도록, SDK 의 키를 `String` → `Supplier<String>` 으로 전면 교체하고 rp-app 에 파일 mtime 핫리로드 Supplier 를 레퍼런스로 제공한다.

**Architecture:** SDK 의 `RedactingRequestInterceptor` 가 부팅 시 1회 캡처하던 키를, 요청마다 `Supplier.get()` 으로 다시 묻도록 바꾼다(핵심). rp-app 은 파일 mtime 을 폴링(메모리 캐시)하는 `ReloadableApiKeySupplier` 를 SDK 에 주입한다. 서버(passkey-app)는 이미 요청마다 DB 조회 + grace rotation 을 하므로 무변경.

**Tech Stack:** Java 21, Spring Boot, Spring `RestClient` interceptor, WireMock(SDK contract test), JUnit5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-09-dynamic-api-key-design.md`

---

## File Structure

**SDK (`sdk-java`) — 핵심 (breaking change):**
- Modify `sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java`
  — `String apiKey` → `Supplier<String> apiKeySupplier`; `defaults()` 시그니처 변경
- Modify `sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java`
  — 생성자 캡처 → 요청마다 `apiKeySupplier.get()` + null/blank fail-fast
- Modify `sdk-java/src/test/java/com/crosscert/passkey/sdk/PasskeyClientContractIT.java`
  — `defaults(uri, "ck_test_apikey")` → `defaults(uri, () -> "ck_test_apikey")`
- Create `sdk-java/src/test/java/com/crosscert/passkey/sdk/DynamicApiKeyHeaderIT.java`
  — Supplier 가 호출마다 다른 값 → 헤더가 따라 바뀌는지 / null·blank → 예외

**rp-app — 레퍼런스 구현:**
- Modify `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyProperties.java`
  — `apiKeyFile`, `apiKeyReload` 필드 추가 (`apiKey` 는 폴백으로 유지)
- Create `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplier.java`
  — mtime 폴링 + 메모리 캐시 + env 폴백 + 읽기실패 시 직전 키 유지
- Modify `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.java`
  — `ReloadableApiKeySupplier` 빈 생성 + SDK config 주입
- Create `rp-app/src/test/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplierTest.java`
  — 파일 변경 픽업 / 읽기실패 직전키 유지 / 파일 미설정 env 폴백 / 빈 파일 폴백
- Modify `rp-app/src/main/resources/application.yml`
  — `api-key-file`, `api-key-reload` 프로퍼티 추가

---

## Task 1: SDK — `PasskeyClientConfig` 를 Supplier 로 전환

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java`

이 Task 는 컴파일 깨짐을 동반한다(호출부는 Task 2~4 에서 고친다). SDK 모듈은 단독
빌드하지 말고 Task 4 까지 묶어 검증한다. 그래서 이 Task 의 "검증"은 컴파일이 아니라
파일 내용 일치다.

- [ ] **Step 1: `PasskeyClientConfig` 를 Supplier 시그니처로 교체**

전체 파일을 아래로 교체:

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
        Supplier<String> apiKeySupplier,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl,
        Clock clock,
        Supplier<String> traceIdProvider
) {
    public static final String MDC_TRACE_ID_KEY = "traceId";

    public PasskeyClientConfig {
        Objects.requireNonNull(baseUrl);
        // Supplier 인스턴스는 non-null. 반환되는 키 문자열의 null/blank 검증은
        // 매 요청 시점(RedactingRequestInterceptor)에서 — 키가 도중에 비워질 수
        // 있으므로 생성 시점 1회 검증으로는 부족하다.
        Objects.requireNonNull(apiKeySupplier);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null)    readTimeout    = Duration.ofSeconds(10);
        if (jwksCacheTtl == null)   jwksCacheTtl   = Duration.ofMinutes(5);
        if (clock == null)          clock          = Clock.systemUTC();
        if (traceIdProvider == null) traceIdProvider = () -> MDC.get(MDC_TRACE_ID_KEY);
    }

    public static PasskeyClientConfig defaults(URI baseUrl, Supplier<String> apiKeySupplier) {
        return new PasskeyClientConfig(baseUrl, apiKeySupplier, null, null, null, null, null);
    }
}
```

- [ ] **Step 2: 파일 내용 확인 (컴파일 아님)**

Run: `grep -n "Supplier<String> apiKeySupplier" sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java`
Expected: 필드 선언 + `defaults()` 파라미터 두 곳 매칭

커밋은 Task 2 와 함께(컴파일 가능 상태에서).

---

## Task 2: SDK — `RedactingRequestInterceptor` 가 요청마다 키를 다시 묻게

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java`

- [ ] **Step 1: 필드와 생성자, intercept 본문 교체**

`private final String apiKey;` 필드를 `Supplier<String>` 으로 바꾸고, 생성자/intercept 를 수정한다.

import 에 추가:
```java
import java.util.function.Supplier;
```

필드 교체 (line 22):
```java
    private final Supplier<String> apiKeySupplier;
```

생성자 교체 (line 24-26):
```java
    public RedactingRequestInterceptor(PasskeyClientConfig config) {
        this.apiKeySupplier = config.apiKeySupplier();
    }
```

intercept 본문에서 헤더 set 부분 교체 (line 31 `request.getHeaders().set("X-API-Key", apiKey);` 를 아래로):
```java
        // 매 요청마다 현재 유효 키를 다시 묻는다 — "재기동 없는 교체"의 심장.
        // 부팅 시 1회 캡처가 아니라 호출 시점 조회라, Supplier 뒤편(파일/시크릿
        // 매니저 등)에서 키가 바뀌면 다음 요청부터 자동 반영된다.
        String currentKey = apiKeySupplier.get();
        if (currentKey == null || currentKey.isBlank()) {
            // fail-fast: null/blank 키로 호출하면 서버가 401 을 주고 그 원인이
            // SDK 설정 문제임이 드러나지 않는다. 여기서 명확히 끊는다.
            throw new IllegalStateException(
                    "API key supplier returned null/blank — check api key source");
        }
        request.getHeaders().set("X-API-Key", currentKey);
```

마스킹 정규식(`API_KEY_HEADER`)과 `redact()` 는 변경 없음 — 헤더 렌더링 형태가 동일하므로 그대로 유효.

- [ ] **Step 2: SDK 모듈 컴파일 (메인 소스만)**

Run: `./gradlew :sdk-java:compileJava`
Expected: BUILD SUCCESSFUL (PasskeyClient, 두 interceptor 모두 새 시그니처로 컴파일)

- [ ] **Step 3: 커밋**

```bash
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClientConfig.java \
        sdk-java/src/main/java/com/crosscert/passkey/sdk/internal/RedactingRequestInterceptor.java
git commit -m "feat(sdk): apiKey String→Supplier<String> 전면 교체, 요청마다 키 재조회

부팅 시 1회 캡처를 호출 시점 Supplier.get() 으로 바꿔 재기동 없는 키 교체를
가능하게 한다. null/blank 키는 fail-fast. breaking change(외부 배포 전).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: SDK — 기존 ContractIT 호출부를 Supplier 람다로 갱신

**Files:**
- Modify: `sdk-java/src/test/java/com/crosscert/passkey/sdk/PasskeyClientContractIT.java:43-45`

- [ ] **Step 1: `defaults()` 호출을 람다로 교체**

기존 (line 43-45):
```java
        client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()),
                "ck_test_apikey"));
```

교체:
```java
        client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()),
                () -> "ck_test_apikey"));
```

- [ ] **Step 2: ContractIT 실행해 회귀 없음 확인**

Run: `./gradlew :sdk-java:test --tests "com.crosscert.passkey.sdk.PasskeyClientContractIT"`
Expected: PASS (정적 키를 람다로 감싼 것뿐이라 동작 불변)

- [ ] **Step 3: 커밋**

```bash
git add sdk-java/src/test/java/com/crosscert/passkey/sdk/PasskeyClientContractIT.java
git commit -m "test(sdk): ContractIT 의 defaults() 호출을 Supplier 람다로 갱신

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: SDK — 동적 키 동작 검증 테스트 신규 작성 (TDD)

**Files:**
- Create: `sdk-java/src/test/java/com/crosscert/passkey/sdk/DynamicApiKeyHeaderIT.java`

`RedactingRequestInterceptor` 가 private 이라 단위 테스트가 까다롭다. ContractIT 와
동일하게 WireMock 으로 실제 HTTP 헤더를 관찰하는 통합 테스트로 검증한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.crosscert.passkey.sdk;

import com.crosscert.passkey.sdk.dto.AuthenticationStartRequest;
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest;
import com.crosscert.passkey.sdk.exception.PasskeyConfigurationException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 핵심 회귀 가드: SDK 가 부팅 시 키를 캡처하지 않고 요청마다 Supplier.get() 을
 * 호출하는지를 실제 outgoing HTTP 헤더로 검증한다.
 */
class DynamicApiKeyHeaderIT {

    static WireMockServer wm;

    @BeforeAll
    static void start() throws Exception {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        WireMock.configureFor("localhost", wm.port());
        wm.stubFor(post(urlEqualTo("/api/v1/rp/registration/start"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(read("contract/registration-start-success.json"))));
        wm.stubFor(post(urlEqualTo("/api/v1/rp/authentication/start"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(read("contract/authentication-start-success.json"))));
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @Test
    void headerFollowsSupplierBetweenCalls() {
        // Supplier 가 호출마다 다른 값을 돌려주도록 구성
        AtomicReference<String> key = new AtomicReference<>("pk_aaaaaaaaFIRSTsecret");
        PasskeyClient client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()), key::get));

        client.registrationStart(new RegistrationStartRequest("u1", "User One", null));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/rp/registration/start"))
                .withHeader("X-API-Key", equalTo("pk_aaaaaaaaFIRSTsecret")));

        // 키를 "회수 후 교체"하듯 바꾼다 — 재기동 없이
        key.set("pk_bbbbbbbbSECONDsecret");

        client.authenticationStart(new AuthenticationStartRequest("u1", null));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/rp/authentication/start"))
                .withHeader("X-API-Key", equalTo("pk_bbbbbbbbSECONDsecret")));
    }

    @Test
    void blankKeyFailsFast() {
        PasskeyClient client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()), () -> "  "));

        assertThatThrownBy(() ->
                client.registrationStart(new RegistrationStartRequest("u1", "User One", null)))
                .isInstanceOf(PasskeyConfigurationException.class)
                .hasMessageContaining("null/blank");
    }

    private static String read(String cp) throws Exception {
        return Files.readString(Path.of(
                DynamicApiKeyHeaderIT.class.getClassLoader().getResource(cp).toURI()));
    }
}
```

> **주의:** `RegistrationStartRequest` / `AuthenticationStartRequest` 의 생성자
> 인자는 실제 DTO 정의에 맞춰야 한다. 구현 직전 다음으로 확인하고 인자를 맞춘다:
> `sed -n '1,40p' sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/RegistrationStartRequest.java`
> `sed -n '1,40p' sdk-java/src/main/java/com/crosscert/passkey/sdk/dto/AuthenticationStartRequest.java`
> (record 필드 순서/개수가 위 샘플과 다르면 호출 인자를 그에 맞게 교체.)
> 또한 `contract/*.json` fixture 가 존재하는지 ContractIT 가 쓰는 것과 동일하므로
> `ls sdk-java/src/test/resources/contract/` 로 확인.

- [ ] **Step 2: 테스트 실행 — 통과 확인**

Run: `./gradlew :sdk-java:test --tests "com.crosscert.passkey.sdk.DynamicApiKeyHeaderIT"`
Expected: 2개 테스트 PASS. (Task 2 구현이 이미 됐으므로 통과해야 함. 만약 DTO
생성자 불일치로 컴파일 실패하면 Step 1 주의사항대로 인자 교정 후 재실행.)

- [ ] **Step 3: SDK 전체 테스트 회귀 확인**

Run: `./gradlew :sdk-java:test`
Expected: BUILD SUCCESSFUL (ContractIT 포함 전부 PASS)

- [ ] **Step 4: 커밋**

```bash
git add sdk-java/src/test/java/com/crosscert/passkey/sdk/DynamicApiKeyHeaderIT.java
git commit -m "test(sdk): 동적 키 헤더 회귀 가드 — 요청마다 Supplier 재조회 + blank fail-fast

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: rp-app — `PasskeyProperties` 에 파일/리로드 프로퍼티 추가

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyProperties.java`

- [ ] **Step 1: record 에 두 필드 추가**

import 에 추가 (이미 `java.time.Duration` 있음 — 없으면 추가):
```java
import java.nio.file.Path;
```

record 파라미터 목록에 `apiKey` 다음 위치에 추가. 전체 record 를 아래로 교체:

```java
public record PasskeyProperties(
        URI baseUrl,
        String apiKey,
        /**
         * 설정 시 이 파일에서 API Key 를 핫리로드한다(env 보다 우선). 미설정이면
         * {@link #apiKey()} 를 폴백으로 쓴다(기존 동작 보존). 파일 내용은 키 평문 한 줄.
         */
        Path apiKeyFile,
        /** apiKeyFile mtime 폴링 주기. 기본 10s. */
        Duration apiKeyReload,
        String tenantId,
        /**
         * ID Token 의 `iss` claim 비교용 prefix. passkey-app 의 `IdTokenIssuer` 가
         * `passkey.id-token.issuer-base:https://passkey.crosscert.com` + "/" + tenantId 로 발급한다.
         * 로컬 데모에서는 passkey-app 의 `application-local.yml` 등에서 issuer-base 를
         * baseUrl 과 동일하게 override 하거나, 본 프로퍼티를 그 값과 맞춰야 한다.
         */
        URI issuerBase,
        Duration connectTimeout,
        Duration readTimeout,
        Duration jwksCacheTtl
) {}
```

> **주의:** record 필드 순서를 바꿨다. `PasskeyClientConfiguration` 과
> `WebAuthnController` 는 접근자 메서드(`props.apiKey()` 등)로만 접근하므로 순서
> 변경의 영향이 없다(생성자 직접 호출 없음 — Spring 바인딩). 확인:
> `grep -rn "new PasskeyProperties" rp-app/src` → 비어 있어야 함.

- [ ] **Step 2: 컴파일 확인**

Run: `grep -rn "new PasskeyProperties" rp-app/src`
Expected: 출력 없음 (생성자 직접 호출 없음 → 필드 추가/순서변경 안전)

커밋은 Task 7 과 함께(빈 주입까지 끝난 컴파일 가능 상태에서).

---

## Task 6: rp-app — `ReloadableApiKeySupplier` 작성 (TDD)

**Files:**
- Create: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplier.java`
- Create: `rp-app/src/test/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplierTest.java`

- [ ] **Step 1: 실패하는 테스트 먼저 작성**

```java
package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class ReloadableApiKeySupplierTest {

    /** 폴링 간격을 0 으로 줘서 매 get() 마다 mtime 을 다시 보게 한다(테스트 결정성). */
    private static final Duration NO_THROTTLE = Duration.ZERO;

    @Test
    void readsKeyFromFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "pk_fileFIRSTsecret\n");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-fallback");

        assertThat(s.get()).isEqualTo("pk_fileFIRSTsecret"); // trailing newline 제거
    }

    @Test
    void picksUpFileChangeWithoutRestart(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "pk_old");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-fallback");
        assertThat(s.get()).isEqualTo("pk_old");

        // mtime 이 확실히 바뀌도록 갱신(같은 ms 충돌 회피: 명시적으로 미래 시각 설정)
        Files.writeString(f, "pk_new");
        Files.setLastModifiedTime(f,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertThat(s.get()).isEqualTo("pk_new"); // 재기동 없이 새 키 픽업
    }

    @Test
    void fallsBackToEnvWhenFileNotConfigured() {
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(null, NO_THROTTLE, "env-key");
        assertThat(s.get()).isEqualTo("env-key");
    }

    @Test
    void fallsBackToEnvWhenFileEmpty(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "   \n");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-key");
        assertThat(s.get()).isEqualTo("env-key"); // 빈/공백 파일 → 폴백
    }

    @Test
    void keepsLastGoodKeyWhenFileBecomesUnreadable(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key.txt");
        Files.writeString(f, "pk_good");
        ReloadableApiKeySupplier s = new ReloadableApiKeySupplier(f, NO_THROTTLE, "env-key");
        assertThat(s.get()).isEqualTo("pk_good"); // 1회 성공 캐시

        Files.delete(f); // 이후 읽기 실패

        assertThat(s.get()).isEqualTo("pk_good"); // 직전 유효 키 유지(fail-safe), env 폴백 아님
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.config.ReloadableApiKeySupplierTest"`
Expected: FAIL — `ReloadableApiKeySupplier` 클래스 없음(컴파일 에러)

- [ ] **Step 3: `ReloadableApiKeySupplier` 구현**

```java
package com.crosscert.passkey.rpapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * API Key 를 파일에서 핫리로드하는 Supplier. SDK 의 RedactingRequestInterceptor
 * 가 요청마다 {@link #get()} 을 호출하므로, 운영자가 키 파일만 바꾸면 재기동 없이
 * 다음 요청부터 새 키가 반영된다. 서버의 grace rotation 과 짝지으면 무중단 교체.
 *
 * <p>동작:
 * <ul>
 *   <li>파일 미설정({@code keyFile == null}) → 항상 env 폴백({@code envFallback}).</li>
 *   <li>파일 설정 + 폴링 주기 경과 시에만 mtime 검사 → 변경됐을 때만 재읽기(hot path
 *       에서 매 요청 디스크 IO 회피).</li>
 *   <li>파일이 비었거나 공백뿐 → env 폴백.</li>
 *   <li>읽기 실패(삭제/권한) → 직전 유효 키 유지(fail-safe) + WARN. 단 한 번도 못
 *       읽었으면 env 폴백.</li>
 * </ul>
 *
 * <p>스레드 안전: 캐시 필드는 volatile. 여러 worker 스레드가 동시에 재읽기를
 * 시도할 수 있으나 결과가 동일하므로 lost-update 가 무해(idempotent reload).
 */
public class ReloadableApiKeySupplier implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ReloadableApiKeySupplier.class);

    private final Path keyFile;          // null 이면 항상 env 폴백
    private final long pollMillis;       // mtime 검사 최소 간격
    private final String envFallback;    // 파일 미설정/빈파일/최초읽기실패 시 사용

    private volatile String cachedKey;   // 마지막으로 파일에서 성공적으로 읽은 키
    private volatile long lastModified = Long.MIN_VALUE;  // 마지막으로 읽은 파일 mtime
    private volatile long lastPollAt = Long.MIN_VALUE;    // 마지막 mtime 검사 시각

    public ReloadableApiKeySupplier(Path keyFile, Duration pollInterval, String envFallback) {
        this.keyFile = keyFile;
        this.pollMillis = pollInterval == null ? 0L : Math.max(0L, pollInterval.toMillis());
        this.envFallback = envFallback;
    }

    @Override
    public String get() {
        if (keyFile == null) {
            return envFallback;
        }
        maybeReload();
        // 파일에서 한 번이라도 유효 키를 읽었으면 그 캐시를, 아니면 env 폴백.
        return cachedKey != null ? cachedKey : envFallback;
    }

    private void maybeReload() {
        long now = System.currentTimeMillis();
        // 폴링 throttle: 마지막 검사 후 pollMillis 안 지났으면 디스크 안 본다.
        if (now - lastPollAt < pollMillis && cachedKey != null) {
            return;
        }
        lastPollAt = now;
        try {
            long mtime = Files.getLastModifiedTime(keyFile).toMillis();
            if (mtime == lastModified && cachedKey != null) {
                return; // 안 바뀌었고 이미 읽은 캐시 있음
            }
            String raw = Files.readString(keyFile).trim();
            lastModified = mtime;
            if (raw.isEmpty()) {
                // 빈/공백 파일: 캐시를 비워 env 폴백으로. (실수로 파일을 비웠을 때
                // 직전 키를 계속 쓰면 디버깅이 혼란 — 명시적으로 폴백 신호)
                cachedKey = null;
                log.warn("api-key file is empty/blank, falling back to env: {}", keyFile);
            } else {
                cachedKey = raw;
            }
        } catch (Exception e) {
            // 읽기 실패(삭제/권한/IO): 직전 유효 키를 유지(fail-safe). 단 한 번도
            // 못 읽었으면 cachedKey==null → get() 이 env 폴백.
            log.warn("api-key file reload failed (keeping last good key): {} cause={}",
                    keyFile, e.toString());
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.config.ReloadableApiKeySupplierTest"`
Expected: 5개 테스트 PASS

> **주의(throttle 과 테스트):** 테스트는 `NO_THROTTLE`(0ms)을 쓰므로 `maybeReload`
> 의 throttle 가드(`now - lastPollAt < pollMillis`)가 `< 0` → 항상 false 라 매번
> mtime 을 본다. `picksUpFileChange` 는 mtime 을 명시적으로 +5s 로 밀어 변경 감지를
> 결정적으로 만든다. 만약 `keepsLastGoodKey` 가 throttle 로 실패하면(0ms 라 안 그래야
> 정상) `cachedKey != null` 단축평가가 throttle 을 우회하므로 의도대로 동작.

- [ ] **Step 5: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplier.java \
        rp-app/src/test/java/com/crosscert/passkey/rpapp/config/ReloadableApiKeySupplierTest.java
git commit -m "feat(rp-app): API Key 파일 핫리로드 Supplier (mtime 폴링+캐시+env 폴백+fail-safe)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: rp-app — 빈 배선 + application.yml + 스모크

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.java`
- Modify: `rp-app/src/main/resources/application.yml`

- [ ] **Step 1: `PasskeyClientConfiguration` 에 Supplier 빈 + 주입**

전체 파일을 아래로 교체:

```java
package com.crosscert.passkey.rpapp.config;

import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
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
        Duration reload = props.apiKeyReload() == null
                ? Duration.ofSeconds(10) : props.apiKeyReload();
        return new ReloadableApiKeySupplier(props.apiKeyFile(), reload, props.apiKey());
    }

    @Bean
    public PasskeyClient passkeyClient(PasskeyProperties props, Supplier<String> apiKeySupplier) {
        return PasskeyClient.of(new PasskeyClientConfig(
                props.baseUrl(),
                apiKeySupplier,
                props.connectTimeout(),
                props.readTimeout(),
                props.jwksCacheTtl(),
                Clock.systemUTC(),
                null   // SDK 가 MDC.get("traceId") 기본값 사용
        ));
    }
}
```

- [ ] **Step 2: `application.yml` 에 프로퍼티 추가**

`passkey:` 블록의 `api-key:` 줄 바로 아래에 추가:

```yaml
  # 재기동 없는 키 교체용. 설정 시 이 파일을 핫리로드(api-key env 보다 우선).
  # 미설정이면 위 api-key 를 그대로 쓴다(기존 동작 보존). 파일 내용은 키 평문 한 줄.
  api-key-file:   ${PASSKEY_API_KEY_FILE:}
  # api-key-file mtime 폴링 주기. 기본 10s.
  api-key-reload: ${PASSKEY_API_KEY_RELOAD:10s}
```

- [ ] **Step 3: rp-app 컴파일 + 스모크 테스트**

Run: `./gradlew :rp-app:compileJava && ./gradlew :rp-app:test --tests "com.crosscert.passkey.rpapp.RpAppSmokeIT"`
Expected: BUILD SUCCESSFUL — 컨텍스트 로드 시 빈 배선(`apiKeySupplier`, `passkeyClient`)이
정상 생성됨. (api-key-file 미설정이므로 env 폴백 경로, 기존과 동일 동작)

- [ ] **Step 4: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyProperties.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.java \
        rp-app/src/main/resources/application.yml
git commit -m "feat(rp-app): 동적 키 Supplier 배선 + api-key-file/reload 프로퍼티

PasskeyProperties 에 apiKeyFile/apiKeyReload 추가, PasskeyClientConfiguration 이
ReloadableApiKeySupplier 를 SDK 에 주입. 미설정 시 env 폴백으로 기존 동작 보존.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 전체 회귀 + codex 리뷰

**Files:** 없음(검증 단계)

- [ ] **Step 1: 영향 모듈 전체 빌드/테스트**

Run: `./gradlew :sdk-java:test :rp-app:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: codex 리뷰 (staged diff)**

메모리 규칙(커밋 전 독립 codex 리뷰)에 따라, 머지 직전 누적 diff 를 codex 로 리뷰한다.

Run (skill): `/codex:review` — main 대비 worktree 브랜치 전체 diff 대상.
P0/P1 지적이 있으면 해당 Task 로 돌아가 수정 후 재실행. P2 이하는 판단해서 반영/기록.

- [ ] **Step 3: 최종 확인 로그**

Run: `git log --oneline main..HEAD`
Expected: Task 2,3,4,6,7 의 커밋 5개가 보임. 빌드 그린.

---

## Self-Review (작성자 체크 결과)

**1. Spec coverage:**
- §4 SDK(Config+Interceptor 전환) → Task 1,2 ✅
- §4.3 영향받는 호출부(defaults/ContractIT) → Task 3 ✅
- §5 rp-app(Properties/Supplier/Config/yml) → Task 5,6,7 ✅
- §6 무중단 흐름 → 코드 메커니즘(Task 2 요청마다 조회 + Task 6 파일 픽업)로 충족, 별도 런타임 시나리오 검증은 Task 4(키 교체 시 헤더 추종) + Task 6(파일 변경 픽업)이 대표 ✅
- §7 테스트(SDK Supplier 추종/blank, rp-app 변경/실패/폴백/빈파일) → Task 4,6 전부 ✅
- §8 YAGNI 제외(scope 풀/풀/외부SM) → 계획에 미포함(의도) ✅
- §9 호환성(env 폴백 동작 불변) → Task 6 폴백 + Task 7 Step 3 스모크 ✅

**2. Placeholder scan:** 모든 코드 스텝에 실제 코드 포함. DTO 생성자 인자만 "구현 직전
실제 record 확인" 가드를 명시(가짜 인자 박지 않도록) — 이는 placeholder 가 아니라
환경 검증 지시.

**3. Type consistency:** `apiKeySupplier`(Config 필드/접근자) ↔ `config.apiKeySupplier()`
(Interceptor/Configuration) 일치. `ReloadableApiKeySupplier(Path, Duration, String)`
생성자 시그니처가 테스트(Task 6 Step 1)·빈 배선(Task 7 Step 1) 양쪽에서 동일. `defaults(URI, Supplier<String>)`
가 ContractIT(Task 3)·DynamicApiKeyHeaderIT(Task 4) 양쪽 동일.
