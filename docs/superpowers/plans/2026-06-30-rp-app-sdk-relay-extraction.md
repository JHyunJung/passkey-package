# rp-app → sdk 보안 프리미티브 추출 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app의 연동 필수 보안 프리미티브(HMAC relay 코덱, ID Token iss/aud 시맨틱 검증, UUID 정규화)를 sdk-java로 추출하고 rp-app은 SDK를 호출하도록 재배선한다.

**Architecture:** SDK에 Spring 비의존 순수 클래스 `RegistrationRelayCodec`와 `verifyIdToken` 3-인자 오버로드를 추가한다. rp-app은 자체 relay 코덱·iss/aud 인라인 검증·`normalizeTenantId`를 제거하고, SDK 코덱을 `@Bean`으로 주입하며, 신규 verifyIdToken을 호출한다. 외부 응답·에러코드는 변환 래핑으로 보존한다.

**Tech Stack:** Java 17, Spring Boot(rp-app), 순수 Java 라이브러리(sdk-java), Jackson, nimbus-jose-jwt, JUnit5, AssertJ, Mockito, WireMock.

## Global Constraints

- sdk-java는 **Spring 비의존 순수 Java 라이브러리**다. SDK 신규 코드에 Spring 어노테이션/타입(`@Component`, `@ConfigurationProperties` 등)을 쓰지 않는다.
- sdk-java는 **순수 Java**(Kotlin 아님). 기존 패턴 유지.
- 시각/시계는 **주입형 `java.time.Clock`** 사용(`Instant.now()` 직접 호출 금지 — SDK 표준).
- 기존 SDK public API는 **하위호환 유지**: `verifyIdToken(String)` 1-인자 메서드를 제거/변경하지 않는다.
- relay decode 실패 예외는 **`IllegalArgumentException`** 유지(rp-app 기존 catch 호환).
- 패키지/네이밍: SDK relay는 `com.crosscert.passkey.sdk.relay`, 클래스 `RegistrationRelayCodec`, 중첩 record `RegistrationRelay(registrationToken, userHandle, username, displayName)`.
- HMAC 알고리즘은 현행과 동일: HmacSHA256, `base64url(payloadJson).base64url(hmac)`, 짧은 필드명 rt/uh/un/dn/exp, `MessageDigest.isEqual` 상수시간 비교.
- 빌드 게이트: `./gradlew :sdk-java:test :rp-app:test`만 사용. `./gradlew build` 전체는 pre-existing 실패(SliceConfig/Oracle 경합)로 머지 게이트 부적합 — base 대조로 회귀 판정.

---

### Task 1: SDK `RegistrationRelayCodec` (순수 클래스 + 테스트)

rp-app의 `RegRelayCodec`를 Spring/rp-app 의존을 벗긴 순수 SDK 클래스로 신규 작성한다. 기존 rp-app `RegRelayCodecTest`의 모든 케이스를 SDK 생성자 형태로 이식한다.

**Files:**
- Create: `sdk-java/src/main/java/com/crosscert/passkey/sdk/relay/RegistrationRelayCodec.java`
- Create: `sdk-java/src/test/java/com/crosscert/passkey/sdk/relay/RegistrationRelayCodecTest.java`

**Interfaces:**
- Consumes: 없음(SDK 내부 jackson `ObjectMapper`, `java.time.Clock`).
- Produces:
  - `public final class RegistrationRelayCodec`
    - `public RegistrationRelayCodec(byte[] secret, java.time.Duration ttl, java.time.Clock clock)`
    - `public String encode(String registrationToken, String userHandle, String username, String displayName)`
    - `public RegistrationRelay decode(String token)` — 실패 시 `IllegalArgumentException`
  - `public record RegistrationRelay(String registrationToken, String userHandle, String username, String displayName)` (중첩 record)

- [ ] **Step 1: 실패하는 테스트 작성**

`sdk-java/src/test/java/com/crosscert/passkey/sdk/relay/RegistrationRelayCodecTest.java`:

```java
package com.crosscert.passkey.sdk.relay;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RegistrationRelayCodec 단위 테스트. HMAC-SHA256 무상태 relay 토큰의
 * round-trip·서명/페이로드 변조거부·만료거부·키불일치거부를 검증한다.
 * 만료는 주입형 Clock 으로 결정적으로 테스트한다.
 */
class RegistrationRelayCodecTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);

    private static RegistrationRelayCodec codec(String secret, Duration ttl) {
        return new RegistrationRelayCodec(secret.getBytes(StandardCharsets.UTF_8), ttl, FIXED);
    }

    private static RegistrationRelayCodec codec(String secret, Duration ttl, Clock clock) {
        return new RegistrationRelayCodec(secret.getBytes(StandardCharsets.UTF_8), ttl, clock);
    }

    private static String hmacB64(String secret, String payloadB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void encodeThenDecode_roundTrips() {
        RegistrationRelayCodec codec = codec("test-secret-1", Duration.ofMinutes(5));
        String token = codec.encode("reg-token-xyz", "user-handle-abc", "alice", "Alice Example");
        RegistrationRelayCodec.RegistrationRelay d = codec.decode(token);
        assertThat(d.registrationToken()).isEqualTo("reg-token-xyz");
        assertThat(d.userHandle()).isEqualTo("user-handle-abc");
        assertThat(d.username()).isEqualTo("alice");
        assertThat(d.displayName()).isEqualTo("Alice Example");
    }

    @Test
    void decode_rejectsTamperedSignature() {
        RegistrationRelayCodec codec = codec("test-secret-2", Duration.ofMinutes(5));
        String token = codec.encode("rt", "uh", "un", "dn");
        int dot = token.lastIndexOf('.');
        String p64 = token.substring(0, dot);
        byte[] sig = B64D.decode(token.substring(dot + 1));
        sig[0] ^= 0x01;
        String tampered = p64 + "." + B64.encodeToString(sig);
        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    @Test
    void decode_rejectsTamperedPayloadWithOriginalSignature() {
        String secret = "test-secret-payload";
        RegistrationRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        String token = codec.encode("rt", "victim-handle", "victim-user", "Victim");
        int dot = token.lastIndexOf('.');
        String origSig = token.substring(dot + 1);
        String payloadJson = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("victim-handle");
        String tamperedJson = payloadJson.replace("victim-handle", "attacker-handl"); // 같은 길이
        String forged = B64.encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8)) + "." + origSig;
        assertThatThrownBy(() -> codec.decode(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    @Test
    void decode_rejectsTamperedUsernameWithOriginalSignature() {
        String secret = "test-secret-username";
        RegistrationRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        String token = codec.encode("rt", "uh", "victim-user", "Victim");
        int dot = token.lastIndexOf('.');
        String origSig = token.substring(dot + 1);
        String payloadJson = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("victim-user");
        String tamperedJson = payloadJson.replace("victim-user", "attackr-user"); // 같은 길이
        String forged = B64.encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8)) + "." + origSig;
        assertThatThrownBy(() -> codec.decode(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    @Test
    void decode_rejectsExpiredToken() {
        String secret = "test-secret-3";
        // exp 는 FIXED+5분. decode 를 FIXED+6분 시계로 수행해 만료를 결정적으로 검증.
        RegistrationRelayCodec encoder = codec(secret, Duration.ofMinutes(5), FIXED);
        String token = encoder.encode("rt", "uh", "un", "dn");
        Clock later = Clock.fixed(FIXED.instant().plusSeconds(360), ZoneOffset.UTC);
        RegistrationRelayCodec decoder = codec(secret, Duration.ofMinutes(5), later);
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void decode_rejectsLegacyPayloadMissingUsernameAndDisplayName() {
        String secret = "test-secret-legacy";
        RegistrationRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        long futureExp = FIXED.instant().getEpochSecond() + 300;
        String payloadJson = "{\"rt\":\"rt\",\"uh\":\"uh\",\"exp\":" + futureExp + "}";
        String p64 = B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String legacy = p64 + "." + hmacB64(secret, p64);
        assertThatThrownBy(() -> codec.decode(legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete payload");
    }

    @Test
    void decode_rejectsTokenSignedWithDifferentKey() {
        RegistrationRelayCodec a = codec("secret-A", Duration.ofMinutes(5));
        RegistrationRelayCodec b = codec("secret-B", Duration.ofMinutes(5));
        String token = a.encode("rt", "uh", "un", "dn");
        assertThatThrownBy(() -> b.decode(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :sdk-java:test --tests "*RegistrationRelayCodecTest"`
Expected: FAIL — `RegistrationRelayCodec` 클래스 없음(compile error).

- [ ] **Step 3: `RegistrationRelayCodec` 구현**

`sdk-java/src/main/java/com/crosscert/passkey/sdk/relay/RegistrationRelayCodec.java`:

```java
package com.crosscert.passkey.sdk.relay;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 등록 릴레이 토큰 코덱. {registrationToken, userHandle, username, displayName, exp} 를
 * HMAC-SHA256 으로 서명해 "base64url(payloadJson).base64url(hmac)" 형태의 불투명 토큰을
 * 만들고 검증한다.
 *
 * <p>서명이 맞아야 payload 를 신뢰하므로 클라이언트가 userHandle 을 조작할 수 없다. 토큰이
 * 자기완결적이라 서버에 pending 상태를 두지 않고도 finish 단계에서 사용자를 확정할 수 있다
 * (무상태 설계). passkey-app 의 challenge 만료(기본 5분)에 맞춰 ttl 을 설정하라.
 *
 * <p>Spring 비의존 순수 클래스다. secret 의 출처·보호(데모키 거부 등)는 호출자(RP) 책임이다.
 */
public final class RegistrationRelayCodec {

    /** 복원된 relay payload. */
    public record RegistrationRelay(
            String registrationToken,
            String userHandle,
            String username,
            String displayName
    ) {}

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] key;
    private final long ttlSeconds;
    private final Clock clock;

    public RegistrationRelayCodec(byte[] secret, Duration ttl, Clock clock) {
        this.key = secret.clone();
        this.ttlSeconds = ttl.toSeconds();
        this.clock = clock;
    }

    /** {rt, uh, un, dn, exp} 를 서명한 relay 토큰 생성. */
    public String encode(String registrationToken, String userHandle, String username, String displayName) {
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        Payload p = new Payload(registrationToken, userHandle, username, displayName, exp);
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
    public RegistrationRelay decode(String token) {
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
        Payload p;
        try {
            p = mapper.readValue(B64D.decode(p64), Payload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("relay token bad payload");
        }
        if (p.exp() < clock.instant().getEpochSecond()) {
            throw new IllegalArgumentException("relay token expired");
        }
        // 필수 4필드 검증. 값이 빠진 토큰을 upstream 호출 전에 거부해 매핑 누락으로 인한 오류를 막는다.
        if (p.rt() == null || p.uh() == null || p.un() == null || p.dn() == null) {
            throw new IllegalArgumentException("relay token incomplete payload");
        }
        return new RegistrationRelay(p.rt(), p.uh(), p.un(), p.dn());
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

    /** 직렬화 payload. 필드명을 짧게(rt/uh/un/dn/exp) 유지하고 @JsonProperty 로 매핑을 명시한다. */
    record Payload(
            @JsonProperty("rt") String rt,
            @JsonProperty("uh") String uh,
            @JsonProperty("un") String un,
            @JsonProperty("dn") String dn,
            @JsonProperty("exp") long exp
    ) {
        @JsonCreator
        Payload {}
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :sdk-java:test --tests "*RegistrationRelayCodecTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: 커밋**

```bash
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/relay/RegistrationRelayCodec.java \
        sdk-java/src/test/java/com/crosscert/passkey/sdk/relay/RegistrationRelayCodecTest.java
git commit -m "feat(sdk): add RegistrationRelayCodec — Spring-free HMAC relay primitive"
```

---

### Task 2: SDK `verifyIdToken` 3-인자 오버로드 (iss/aud 검증 + 정규화)

`IdTokenVerifier`에 iss/aud 시맨틱 검증을 추가하고, `PasskeyClient`에 3-인자 `verifyIdToken` 오버로드를 노출한다. UUID 정규화는 `IdTokenVerifier` 내부 private 유틸로 흡수(public 미노출).

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java`
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java:93-95`
- Create: `sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifierSemanticTest.java`

**Interfaces:**
- Consumes: 기존 `IdTokenVerifier.verify(String)`, `IdTokenClaims`, `JwksCache`(WireMock 패턴은 `IdTokenVerifierAlgTest` 참조).
- Produces:
  - `IdTokenVerifier`: `public IdTokenClaims verify(String compactJwt, String expectedIssuer, String expectedAudience)`
    - 기존 1-인자 `verify`로 서명+exp 검증 후, iss(prefix 정확 일치 + tenant 정규화)·aud(정규화) 검증. 실패 시 `PasskeyIdTokenException`.
    - `expectedIssuer`는 `<issuerBase>/<tenantId>` 전체 문자열. 마지막 `/` 기준으로 prefix(issuerBase)·tenant 분해.
  - `PasskeyClient`: `public IdTokenClaims verifyIdToken(String compactJwt, String expectedIssuer, String expectedAudience)`

- [ ] **Step 1: 실패하는 테스트 작성**

`sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifierSemanticTest.java`:

```java
package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * verifyIdToken(jwt, expectedIssuer, expectedAudience) 의 iss/aud 시맨틱 검증을
 * 검증한다. iss 는 issuerBase prefix 정확일치 + tenant UUID 정규화 비교, aud 는
 * tenant 정규화 비교. hex32↔대시 동치를 포함한다.
 */
class IdTokenVerifierSemanticTest {

    static final String KID = "sem-test-kid";
    static final String TENANT_DASH = "7f00dead-0000-0000-0000-000000000001";
    static final String TENANT_HEX = "7F00DEAD000000000000000000000001";
    static final String ISSUER_BASE = "https://issuer.example.com";

    static WireMockServer wm;
    static RSAKey rsaKey;
    static IdTokenVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID(KID).algorithm(JWSAlgorithm.RS256).generate();
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[" + rsaKey.toPublicJWK().toJSONString() + "]}")));
        Clock clock = Clock.systemUTC();
        JwksCache jwks = new JwksCache(URI.create("http://localhost:" + wm.port()), Duration.ofMinutes(5), clock);
        verifier = new IdTokenVerifier(jwks, clock);
    }

    @AfterAll
    static void tearDown() {
        if (wm != null) wm.stop();
    }

    private static String token(String iss, String aud) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet c = new JWTClaimsSet.Builder()
                .issuer(iss).subject("dXNlckhhbmRsZQ").audience(aud)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), c);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    @Test
    void accepts_whenIssAndAudMatch() throws Exception {
        String jwt = token(ISSUER_BASE + "/" + TENANT_DASH, TENANT_DASH);
        IdTokenClaims out = verifier.verify(jwt, ISSUER_BASE + "/" + TENANT_DASH, TENANT_DASH);
        assertThat(out.sub()).isEqualTo("dXNlckhhbmRsZQ");
    }

    @Test
    void accepts_whenTenantFormatDiffers_hexVsDash() throws Exception {
        // 토큰 iss/aud 는 대시 형식, 기대값은 hex32 — 정규화로 동치 처리되어 통과.
        String jwt = token(ISSUER_BASE + "/" + TENANT_DASH, TENANT_DASH);
        IdTokenClaims out = verifier.verify(jwt, ISSUER_BASE + "/" + TENANT_HEX, TENANT_HEX);
        assertThat(out.sub()).isEqualTo("dXNlckhhbmRsZQ");
    }

    @Test
    void rejects_whenIssuerBasePrefixDiffers() throws Exception {
        String jwt = token("https://evil.example.com/" + TENANT_DASH, TENANT_DASH);
        assertThatThrownBy(() -> verifier.verify(jwt, ISSUER_BASE + "/" + TENANT_DASH, TENANT_DASH))
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void rejects_whenIssTenantDiffers() throws Exception {
        String other = "7f00dead-0000-0000-0000-000000000002";
        String jwt = token(ISSUER_BASE + "/" + other, TENANT_DASH);
        assertThatThrownBy(() -> verifier.verify(jwt, ISSUER_BASE + "/" + TENANT_DASH, TENANT_DASH))
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void rejects_whenAudDiffers() throws Exception {
        String jwt = token(ISSUER_BASE + "/" + TENANT_DASH, "7f00dead-0000-0000-0000-000000000099");
        assertThatThrownBy(() -> verifier.verify(jwt, ISSUER_BASE + "/" + TENANT_DASH, TENANT_DASH))
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("aud");
    }

    @Test
    void rejects_whenExpectedIssuerBlank() throws Exception {
        String jwt = token(ISSUER_BASE + "/" + TENANT_DASH, TENANT_DASH);
        assertThatThrownBy(() -> verifier.verify(jwt, "  ", TENANT_DASH))
                .isInstanceOf(PasskeyIdTokenException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :sdk-java:test --tests "*IdTokenVerifierSemanticTest"`
Expected: FAIL — `verify(String, String, String)` 메서드 없음(compile error).

- [ ] **Step 3: `IdTokenVerifier`에 3-인자 verify + 정규화 유틸 추가**

`IdTokenVerifier.java` — 기존 1-인자 `verify(String compactJwt)` 메서드는 그대로 두고, 그 아래에 추가. import에 `java.util.UUID`, `java.util.regex.Pattern` 추가.

클래스 상단 필드 영역(`private final Clock clock;` 아래)에 추가:

```java
    private static final java.util.regex.Pattern HEX32 = java.util.regex.Pattern.compile("(?i)[0-9a-f]{32}");
```

`verify(String compactJwt)` 메서드 닫는 `}` 다음에 추가:

```java
    /**
     * 서명·exp 검증(1-인자 verify) 후 iss/aud 시맨틱 검증까지 수행한다.
     *
     * <p>expectedIssuer 는 {@code <issuerBase>/<tenantId>} 전체 문자열이다. 마지막 '/'
     * 기준으로 issuerBase prefix(정확 일치 요구)와 tenant(UUID 정규화 비교)로 분해한다.
     * expectedAudience(tenantId)는 토큰 aud 와 UUID 정규화 비교한다. hex32↔대시 표기
     * 차이는 정규화로 동치 처리된다.
     */
    public IdTokenClaims verify(String compactJwt, String expectedIssuer, String expectedAudience) {
        IdTokenClaims claims = verify(compactJwt);

        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            log.warn("id-token verify failed: reason=config expectedIssuer-blank");
            throw new PasskeyIdTokenException("expectedIssuer must not be blank");
        }
        if (expectedAudience == null || expectedAudience.isBlank()) {
            log.warn("id-token verify failed: reason=config expectedAudience-blank");
            throw new PasskeyIdTokenException("expectedAudience must not be blank");
        }

        // expectedIssuer 를 prefix(issuerBase) + tenant 로 분해.
        int slash = expectedIssuer.lastIndexOf('/');
        if (slash < 0) {
            throw new PasskeyIdTokenException("expectedIssuer must be <issuerBase>/<tenantId>");
        }
        String issuerBase = expectedIssuer.substring(0, slash);
        String expectedTenant = normalizeTenantId(expectedIssuer.substring(slash + 1));

        String tokenIss = claims.iss();
        String prefix = issuerBase + "/";
        boolean issOk = tokenIss != null
                && tokenIss.startsWith(prefix)
                && java.util.Objects.equals(
                        normalizeTenantId(tokenIss.substring(prefix.length())), expectedTenant);
        if (!issOk) {
            log.warn("id-token verify failed: reason=iss-mismatch expectedPrefix={} got={}", prefix, tokenIss);
            throw new PasskeyIdTokenException("iss mismatch");
        }

        if (!java.util.Objects.equals(normalizeTenantId(expectedAudience), normalizeTenantId(claims.aud()))) {
            log.warn("id-token verify failed: reason=aud-mismatch expected={} got={}",
                    expectedAudience, claims.aud());
            throw new PasskeyIdTokenException("aud mismatch");
        }
        return claims;
    }

    /**
     * tenantId 를 표준 UUID(소문자+대시)로 정규화한다. hex32(대시 없음) 또는 대시 UUID
     * 모두 허용. 파싱 불가하면 입력을 그대로 반환(원본 비교에 맡김).
     */
    private static String normalizeTenantId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (HEX32.matcher(s).matches()) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        try {
            return java.util.UUID.fromString(s).toString();
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }
```

- [ ] **Step 4: `PasskeyClient`에 3-인자 오버로드 추가**

`PasskeyClient.java`의 기존 `verifyIdToken(String)`(93-95줄) 바로 아래에 추가:

```java
    public IdTokenClaims verifyIdToken(String compactJwt, String expectedIssuer, String expectedAudience) {
        return idTokenVerifier.verify(compactJwt, expectedIssuer, expectedAudience);
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :sdk-java:test --tests "*IdTokenVerifierSemanticTest" --tests "*IdTokenVerifierAlgTest"`
Expected: PASS (신규 6 + 기존 2, 기존 회귀 없음).

- [ ] **Step 6: 커밋**

```bash
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java \
        sdk-java/src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java \
        sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifierSemanticTest.java
git commit -m "feat(sdk): add verifyIdToken(jwt, expectedIssuer, expectedAudience) with iss/aud + UUID normalization"
```

---

### Task 3: SDK 모듈 전체 테스트 그린 확인 (게이트)

Task 1·2가 SDK 모듈을 깨지 않았는지 확정한다.

**Files:** 없음(검증 전용).

- [ ] **Step 1: SDK 전체 테스트**

Run: `./gradlew :sdk-java:test`
Expected: PASS — 신규 2개 테스트 클래스 포함 전부 그린.

- [ ] **Step 2: (회귀 시) base 대조**

실패가 있으면 변경 전 커밋에서 동일 명령으로 pre-existing 여부 확인. 신규 회귀만 수정. base에서도 실패하면 그 실패는 본 작업 범위 밖(메모리: full build pre-existing 함정).

---

### Task 4: rp-app — SDK relay 코덱 `@Bean` 주입 + 기존 코덱 제거

rp-app이 자체 `RegRelayCodec` 대신 SDK `RegistrationRelayCodec`를 빈으로 쓰도록 배선하고, 기존 코덱·테스트를 제거한다. `WebAuthnController`는 Task 5에서 함께 수정하므로 이 task에서는 컴파일을 위해 import/타입만 함께 교체한다.

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.java`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java`(relay 타입만)
- Delete: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodec.java`
- Delete: `rp-app/src/test/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodecTest.java`

**Interfaces:**
- Consumes: `RegistrationRelayCodec(byte[], Duration, Clock)`(Task 1), `RelayProperties.secret()/ttl()`.
- Produces: rp-app 컨텍스트에 `RegistrationRelayCodec` 빈 1개. `WebAuthnController`가 이를 주입받아 `encode(...)`/`decode(...)` 호출.

- [ ] **Step 1: `RegistrationRelayCodec` 빈 추가**

`PasskeyClientConfiguration.java`에 import 추가 후 `@Bean` 메서드 추가:

```java
import com.crosscert.passkey.rpapp.config.RelayProperties;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
```

클래스 본문에:

```java
    /**
     * 등록 릴레이 토큰 코덱. SDK 의 Spring 비의존 프리미티브에 rp.relay.* 설정(secret, ttl)을
     * 주입한다. secret 의 출처·데모키 거부는 RelayProperties/RelayKeyGuard(RP 책임)가 담당한다.
     */
    @Bean
    public RegistrationRelayCodec registrationRelayCodec(RelayProperties relayProps) {
        return new RegistrationRelayCodec(
                relayProps.secret().getBytes(StandardCharsets.UTF_8),
                relayProps.ttl(),
                Clock.systemUTC());
    }
```

- [ ] **Step 2: `WebAuthnController` relay 타입 교체**

`WebAuthnController.java`에서:
- import 교체: `import com.crosscert.passkey.rpapp.web.relay.RegRelayCodec;` → `import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;`
- 필드: `private final RegRelayCodec relay;` → `private final RegistrationRelayCodec relay;`
- 생성자 파라미터: `RegRelayCodec relay` → `RegistrationRelayCodec relay`
- `register/finish`의 지역변수 타입: `RegRelayCodec.RegRelay r;` → `RegistrationRelayCodec.RegistrationRelay r;`

(`relay.encode(...)`/`relay.decode(...)` 호출과 `r.userHandle()/r.username()/r.displayName()/r.registrationToken()` accessor는 record 컴포넌트명이 동일하므로 변경 없음.)

- [ ] **Step 3: 기존 코덱·테스트 삭제**

```bash
git rm rp-app/src/main/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodec.java \
       rp-app/src/test/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodecTest.java
```

- [ ] **Step 4: rp-app 컴파일 확인**

Run: `./gradlew :rp-app:compileJava`
Expected: PASS. (테스트는 Task 5에서 갱신하므로 `compileTestJava`는 아직 실패할 수 있음 — `WebAuthnControllerTest`가 `RegRelayCodec`를 참조.)

- [ ] **Step 5: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/config/PasskeyClientConfiguration.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java
git rm rp-app/src/main/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodec.java \
       rp-app/src/test/java/com/crosscert/passkey/rpapp/web/relay/RegRelayCodecTest.java
git commit -m "refactor(rp-app): use SDK RegistrationRelayCodec, drop local relay codec"
```

> 주의: Task 4 종료 시점엔 `compileTestJava`가 깨진 상태일 수 있다(WebAuthnControllerTest 미갱신). Task 5와 한 묶음으로 실행·리뷰하는 것을 권장한다.

---

### Task 5: rp-app — `WebAuthnController` iss/aud 검증을 SDK 호출로 대체 + 테스트 갱신

`loginComplete()`의 인라인 iss/aud 검증과 `normalizeTenantId`를 제거하고 SDK 3-인자 `verifyIdToken`을 호출한다. `PasskeyIdTokenException`을 기존 `PASSKEY_ID_TOKEN` 에러코드로 변환해 응답을 보존한다. 슬라이스 테스트를 신규 API에 맞춘다.

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java`
- Modify: `rp-app/src/test/java/com/crosscert/passkey/rpapp/web/WebAuthnControllerTest.java`

**Interfaces:**
- Consumes: `PasskeyClient.verifyIdToken(String, String, String)`(Task 2), `PasskeyProperties.issuerBase()/tenantId()`, `BusinessException`, `ErrorCode.PASSKEY_ID_TOKEN`.
- Produces: 없음(최종 컨트롤러).

- [ ] **Step 1: 슬라이스 테스트 갱신(실패 상태로)**

`WebAuthnControllerTest.java` 수정:

- import 교체: `import com.crosscert.passkey.rpapp.web.relay.RegRelayCodec;` → `import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;`
- `@MockBean RegRelayCodec relay;` → `@MockBean RegistrationRelayCodec relay;`
- `authenticateFinish_doesNotExposeIdTokenOrJwt` 테스트의 stub 교체:

```java
        // 기존: given(passkey.verifyIdToken(jwt)).willReturn(...);  (1-인자)
        // 변경: 신규 3-인자 시그니처로 stub. iss/aud 검증은 SDK 책임이므로 컨트롤러는
        // 검증 통과 claims 를 받는다고 가정한다.
        given(passkey.verifyIdToken(eq(jwt), any(), any())).willReturn(new IdTokenClaims(
                "https://issuer.example.com/7f00dead-0000-0000-0000-000000000001",
                "handle-alice",
                "7f00dead-0000-0000-0000-000000000001",
                Instant.now(),
                Instant.now().plusSeconds(300),
                List.of("user_verified"),
                "cred-1",
                "aaguid-1"));
```

(`props.tenantId()`·`props.issuerBase()` stub은 컨트롤러가 expectedIssuer 조립에 쓰므로 유지.)

- 신규 테스트 추가(iss/aud mismatch → PASSKEY_ID_TOKEN 응답, 5-C 변환 검증):

```java
    // ── 4. id-token iss/aud mismatch → PASSKEY_ID_TOKEN (SDK 예외 변환 보존) ────
    @Test
    void authenticateFinish_idTokenInvalid_mapsToPasskeyIdTokenError() throws Exception {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJoIn0.SIG";
        given(passkey.authenticationFinish(any())).willReturn(
                new AuthenticationFinishResponse(jwt, "Bearer", 300L));
        given(props.tenantId()).willReturn("7f00dead-0000-0000-0000-000000000001");
        given(props.issuerBase()).willReturn(URI.create("https://issuer.example.com"));
        // SDK 가 iss/aud 불일치로 던지는 예외를 흉내낸다.
        given(passkey.verifyIdToken(eq(jwt), any(), any()))
                .willThrow(new com.crosscert.passkey.sdk.exception.PasskeyIdTokenException("aud mismatch"));

        mvc.perform(post("/passkey/authenticate/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKeyCredential\":{\"id\":\"x\"},\"authenticationToken\":\"auth-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.PASSKEY_ID_TOKEN.code()));
    }
```

(import 추가: `import com.crosscert.passkey.rpapp.common.exception.ErrorCode;`. 확정값: `PASSKEY_ID_TOKEN` = HTTP 401(UNAUTHORIZED), 코드 "P004", accessor는 `code()`. 응답 JSON 키가 `$.code`인지 `$.error.code`인지는 Step 직전 `GlobalExceptionHandler`/`ApiResponse` 형태로 확인해 jsonPath 를 맞춘다.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :rp-app:test --tests "*WebAuthnControllerTest"`
Expected: FAIL — 컨트롤러가 아직 1-인자 `verifyIdToken` + 인라인 iss/aud 검증을 쓰고 `PasskeyIdTokenException` 변환이 없음.

- [ ] **Step 3: `loginComplete()` 재작성**

`WebAuthnController.java`의 `loginComplete()`에서 ID Token 검증 블록(현행 187-227줄, `claims = passkey.verifyIdToken(...)`부터 `findByUserHandle` 직전까지)을 교체:

```java
        IdTokenClaims claims;
        try {
            // issuerBase 가 설정돼 있어야 expectedIssuer 를 조립할 수 있으므로 누락 시 즉시 실패.
            if (props.issuerBase() == null) {
                throw new NullPointerException("passkey.issuer-base 미설정");
            }
            String expectedIssuer = props.issuerBase() + "/" + props.tenantId();
            // 서명·exp + iss(prefix+tenant 정규화)·aud(정규화) 검증을 SDK 가 한 번에 수행.
            claims = passkey.verifyIdToken(fin.idToken(), expectedIssuer, props.tenantId());
        } catch (com.crosscert.passkey.sdk.exception.PasskeyIdTokenException e) {
            // SDK 의 검증 실패(서명/exp/iss/aud)를 기존 RP 에러코드로 변환해 응답 호환을 보존한다.
            log.warn("login/complete failed: reason=id-token-invalid cause={}", e.getMessage());
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("login/complete failed: reason=id-token-verify-failed cause={}", e.toString());
            throw e;
        }
```

그리고 `loginComplete()`에서 제거할 것:
- 기존 인라인 iss/aud 검증 블록 전체(현행 195-220줄: `expectedTenant` 계산, `issPrefix`/`tokenIss`/`issOk` 검사, aud 비교).
- `normalizeTenantId(String raw)` static 메서드(78-91줄).
- `HEX32` static 필드(57줄), 이제 미사용이면 `import java.util.regex.Pattern;`·`import java.util.UUID;` 정리. `Objects` import는 남는 사용처 있으면 유지, 없으면 제거.

`findByUserHandle(claims.sub())` 이후 로직은 그대로 유지.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :rp-app:test --tests "*WebAuthnControllerTest"`
Expected: PASS — 기존 노출 가드 + 신규 mismatch 변환 테스트 모두 그린.

- [ ] **Step 5: rp-app 전체 테스트**

Run: `./gradlew :rp-app:test`
Expected: PASS. 실패 시 base 대조로 pre-existing 여부 확인(메모리: 슬라이스 SecurityPolicyService 누락·core ORA-01031은 pre-existing).

- [ ] **Step 6: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java \
        rp-app/src/test/java/com/crosscert/passkey/rpapp/web/WebAuthnControllerTest.java
git commit -m "refactor(rp-app): delegate ID Token iss/aud validation to SDK verifyIdToken"
```

---

### Task 6: 최종 통합 검증 + 문서 갱신

두 모듈 동시 그린과 rp-app README의 책임 경계 서술을 실제와 맞춘다.

**Files:**
- Modify: `rp-app/README.md`(SDK/rp-app 책임 경계 서술이 relay·iss/aud 검증을 rp-app 구현으로 적었다면 "SDK 제공"으로 갱신). 해당 서술이 없으면 변경 없음.

- [ ] **Step 1: 두 모듈 동시 테스트**

Run: `./gradlew :sdk-java:test :rp-app:test`
Expected: PASS(또는 실패가 base에서도 동일한 pre-existing임을 확인).

- [ ] **Step 2: 잔존 참조 스캔**

Run: `git grep -n "RegRelayCodec\|normalizeTenantId" -- rp-app sdk-java`
Expected: 결과 없음(또는 주석/문서의 의도적 언급만). 코드 참조가 남으면 정리.

- [ ] **Step 3: README 책임 경계 확인·갱신**

`rp-app/README.md`에서 relay 토큰·iss/aud 검증을 "rp-app이 구현"으로 설명한 부분을 찾아, "SDK가 제공하는 프리미티브(`RegistrationRelayCodec`, `verifyIdToken(jwt, issuer, audience)`)를 rp-app이 설정 주입·호출"로 정정. InMemoryUserStore 교체 안내 등 나머지 서술은 유지.

- [ ] **Step 4: 커밋(README 변경 시)**

```bash
git add rp-app/README.md
git commit -m "docs(rp-app): align README with SDK-provided relay/ID-token primitives"
```

- [ ] **Step 5: (선택) 로컬 부팅 dogfooding**

Docker/passkey-app 가용 시 rp-app 로컬 부팅 후 등록·인증 1회 왕복으로 실증. 불가하면 CI 검증 대기로 명시. (메모리: 부팅 시 passkey-oracle 컨테이너·redis 기동, flyway repair 필요할 수 있음.)

---

## Self-Review

**Spec coverage:**
- §3 #1 relay 코덱 → Task 1(SDK 신규) + Task 4(rp-app 배선/삭제). ✓
- §3 #2 iss/aud 검증 → Task 2(SDK verify 3-인자) + Task 5(컨트롤러 위임). ✓
- §3 #3 UUID 정규화 → Task 2에 private 흡수, public 미노출. ✓
- §4-A 예외 정책(IllegalArgumentException), 주입 Clock → Task 1. ✓
- §4-B iss prefix+tenant 정규화, aud 정규화, null/blank 거부, sub 미검증 → Task 2. ✓
- §5-A relay @Bean, RelayProperties/RelayKeyGuard 유지 → Task 4. ✓
- §5-B 컨트롤러 타입/호출 교체, normalizeTenantId 삭제 → Task 4·5. ✓
- §5-C PasskeyIdTokenException → PASSKEY_ID_TOKEN 변환 → Task 5 Step 3 + 테스트. ✓
- §6 테스트 전략(변조 바이트레벨, 정규화 동치, 회귀 보존, base 대조) → Task 1·2·5·6. ✓
- §8 비범위(파사드/API Key 이동 안 함) → 계획에 미포함(준수). ✓

**Placeholder scan:** 모든 코드 step에 완전한 코드 제공. Task 5 Step 1의 ErrorCode accessor/status는 "직전에 ErrorCode.java 확인" 지시로 명시 — 실제 enum accessor명이 코드베이스에 의존하므로 구현자가 한 줄 확인하도록 안내(placeholder 아님, 검증 지시).

**Type consistency:** `RegistrationRelayCodec`/중첩 `RegistrationRelay`(컴포넌트 registrationToken/userHandle/username/displayName), `verify(String, String, String)`/`verifyIdToken(String, String, String)`, `PasskeyIdTokenException`, `BusinessException(ErrorCode, String)` — 모든 task에서 일관. record accessor명이 기존 `RegRelay`와 동일해 rp-app 호출부 무변경. ✓
