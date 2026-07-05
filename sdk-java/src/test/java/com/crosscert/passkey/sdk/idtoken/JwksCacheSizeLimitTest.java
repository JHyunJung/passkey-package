package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * G19 — JWKS 응답 본문이 크기 상한({@code JWKS_SIZE_LIMIT_BYTES}=50KB)을 넘으면
 * {@code JWKSet.load}가 예외를 던지고, JwksCache의 fetch-failure 경로(F27)로 수렴해
 * stale-if-error 폴백/fail-closed가 정상 개입하는지 검증한다.
 *
 * <p>크기 상한이 없으면(또는 무시되면) 악의적이거나 오동작하는 JWKS 오리진이 과도하게
 * 큰 응답으로 파서에 불필요한 메모리/CPU를 소모시킬 수 있다.
 */
class JwksCacheSizeLimitTest {

    private WireMockServer wm;

    @AfterEach
    void tearDown() {
        if (wm != null) wm.stop();
    }

    /** 50KB 상한을 확실히 넘는 JWKS 응답 본문(padding claim으로 부풀림). */
    private static String oversizedJwksBody() {
        String padding = "x".repeat(60 * 1024);
        return "{\"keys\":[],\"padding\":\"" + padding + "\"}";
    }

    @Test
    void get_oversizedJwksResponse_firstBoot_failsClosed() throws Exception {
        // given: 최초 부팅(폴백 스냅샷 없음) 상태에서 JWKS 응답이 크기 상한을 초과.
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(oversizedJwksBody())));

        JwksCache cache = new JwksCache(
                URI.create("http://localhost:" + wm.port()), Duration.ofMinutes(5), Clock.systemUTC());

        // then: 폴백할 스냅샷이 없으므로 size-limit 예외가 그대로 fail-closed 전파된다
        // (F27의 "첫 부팅 fetch 실패 → 예외 전파" 계약과 동일).
        assertThatThrownBy(cache::get)
                .isInstanceOf(PasskeyIdTokenException.class);
    }

    @Test
    void get_oversizedJwksResponse_afterValidSnapshot_returnsStale() throws Exception {
        // given: 1회 정상 fetch로 유효 스냅샷을 확보한 뒤,
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").algorithm(JWSAlgorithm.RS256).generate();
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json")).inScenario("oversize")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}")));

        JwksCache cache = new JwksCache(
                URI.create("http://localhost:" + wm.port()), Duration.ofMinutes(5), Clock.systemUTC());
        JWKSet firstSnapshot = cache.get();
        assertThat(firstSnapshot.getKeyByKeyId("k1")).isNotNull();

        // when: 그 다음 응답이 크기 상한을 초과하도록 오리진이 바뀐다(TTL 만료 후 재확인 위해
        // 강제 refetch 경로로 트리거 — F26 진입점을 재사용해 fetch-failure를 유도).
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(oversizedJwksBody())));

        // then: size-limit 예외가 catch되어 stale-if-error(F27)로 직전 유효 스냅샷을 반환한다
        // (fail-closed로 죽지 않고 가용성 보존).
        JWKSet stale = cache.getForceRefresh();
        assertThat(stale.getKeyByKeyId("k1")).isNotNull();
    }
}
