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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * F26 — 키 회전 직후, TTL 이내 stale 캐시가 새 kid를 갖지 못해 유효 토큰이 kid-miss로
 * 거부되는 결함의 회귀 가드. IdTokenVerifier가 unknown-kid 시 JwksCache.getForceRefresh()로
 * 1회 강제 refetch 후 재조회하는지 실제 HTTP(WireMock) 경로로 검증한다.
 */
class IdTokenVerifierKidMissTest {

    static final String OLD_KID = "old-kid";
    static final String NEW_KID = "new-kid";
    static final String ISS = "https://issuer.example.com";

    WireMockServer wm;
    AtomicInteger jwksRequestCount;

    @AfterEach
    void tearDown() {
        if (wm != null) wm.stop();
    }

    private static String signedToken(RSAKey signingKey, String kid) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet c = new JWTClaimsSet.Builder()
                .issuer(ISS).subject("dXNlckhhbmRsZQ").audience("ck_test_apikey")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), c);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void kidMiss_forcesRefetch_andAcceptsTokenSignedWithRotatedKey() throws Exception {
        // given: JWKS 엔드포인트가 처음엔 old-kid만 발행한다(회전 전 상태를 캐시가 fetch).
        RSAKey oldKey = new RSAKeyGenerator(2048).keyID(OLD_KID).algorithm(JWSAlgorithm.RS256).generate();
        RSAKey newKey = new RSAKeyGenerator(2048).keyID(NEW_KID).algorithm(JWSAlgorithm.RS256).generate();

        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json")).inScenario("rotation")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[" + oldKey.toPublicJWK().toJSONString() + "]}")));

        Clock clock = Clock.systemUTC();
        // TTL을 넉넉히(5분) 둬서, 회전이 TTL 만료를 기다리지 않고도 강제 refetch로만
        // 인식되는지 확인한다(happy-path get()만 썼다면 TTL 이내라 새 kid를 못 봤을 것).
        JwksCache jwks = new JwksCache(URI.create("http://localhost:" + wm.port()), Duration.ofMinutes(5), clock);
        IdTokenVerifier verifier = new IdTokenVerifier(jwks, clock);

        // when: old-kid로 캐시를 최초 채운다(스냅샷 fetchedAt=지금, TTL 5분 유효).
        String oldToken = signedToken(oldKey, OLD_KID);
        verifier.verify(oldToken);

        // and: 서버가 키 회전을 완료해 이제는 new-kid(+old-kid 잔존)를 발행한다.
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[" + oldKey.toPublicJWK().toJSONString() + ","
                                + newKey.toPublicJWK().toJSONString() + "]}")));

        // then: TTL이 아직 유효한데도(회전은 TTL 윈도우 중간에 일어났다) new-kid로 서명된
        // 토큰이 kid-miss로 거부되지 않고 강제 refetch를 거쳐 수용되어야 한다.
        String newToken = signedToken(newKey, NEW_KID);
        IdTokenClaims out = verifier.verify(newToken);
        assertThat(out.sub()).isEqualTo("dXNlckhhbmRsZQ");
    }

    @Test
    void trulyUnknownKid_stillRejectedAfterForceRefresh() throws Exception {
        // given: JWKS 서버가 정상 응답하지만 검증 대상 kid를 절대 발행하지 않는다
        // (실제로 존재하지 않는 kid — 강제 refetch를 거쳐도 여전히 못 찾아야 한다).
        RSAKey serverKey = new RSAKeyGenerator(2048).keyID("server-only-kid").algorithm(JWSAlgorithm.RS256).generate();
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("nonexistent-kid").algorithm(JWSAlgorithm.RS256).generate();

        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[" + serverKey.toPublicJWK().toJSONString() + "]}")));

        Clock clock = Clock.systemUTC();
        JwksCache jwks = new JwksCache(URI.create("http://localhost:" + wm.port()), Duration.ofMinutes(5), clock);
        IdTokenVerifier verifier = new IdTokenVerifier(jwks, clock);

        String token = signedToken(attackerKey, "nonexistent-kid");

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("Unknown or non-RSA kid");
    }
}
