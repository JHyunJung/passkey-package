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
 * G20 — id-token verify가 exp만 검사하고 nbf/iat를 검증하지 않던 결함의 회귀 가드.
 *
 * <p>서명은 항상 유효한 RS256으로 만들되(문자열 치환이 아니라 nimbus 빌더로 클레임
 * 자체를 조작 — 서명 위조가 아니므로 바이트 레벨 변조가 필요 없는 케이스), nbf/iat
 * 클레임만 미래로 설정해 검증 로직이 그 클레임을 실제로 보는지 확인한다.
 */
class IdTokenVerifierNbfIatTest {

    static final String KID = "nbfiat-test-kid";
    static final String ISS = "https://issuer.example.com";

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

    private static String signedToken(Instant iat, Instant nbf, Instant exp) throws Exception {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(ISS)
                .subject("dXNlckhhbmRsZQ")
                .audience("ck_test_apikey")
                .expirationTime(Date.from(exp));
        if (iat != null) b.issueTime(Date.from(iat));
        if (nbf != null) b.notBeforeTime(Date.from(nbf));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), b.build());
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    @Test
    void rejects_whenNbfIsInTheFuture() throws Exception {
        Instant now = Instant.now();
        String jwt = signedToken(now, now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("nbf");
    }

    @Test
    void rejects_whenIatIsFarInTheFuture() throws Exception {
        Instant now = Instant.now();
        // iat 가 exp보다도 이전이라 exp 검사는 통과하지만, 발급 시각 자체가 미래.
        String jwt = signedToken(now.plus(Duration.ofMinutes(10)), null, now.plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("iat");
    }

    @Test
    void accepts_whenNbfIsPastAndIatIsNow() throws Exception {
        Instant now = Instant.now();
        String jwt = signedToken(now, now.minus(Duration.ofSeconds(5)), now.plus(Duration.ofMinutes(15)));

        IdTokenClaims out = verifier.verify(jwt);
        assertThat(out.sub()).isEqualTo("dXNlckhhbmRsZQ");
    }

    @Test
    void accepts_whenNbfAbsent_unchanged() throws Exception {
        // nbf 클레임 자체가 없는 토큰(현재 프로덕션 발급 형태) — 회귀 없이 통과해야 한다.
        Instant now = Instant.now();
        String jwt = signedToken(now, null, now.plus(Duration.ofMinutes(15)));

        IdTokenClaims out = verifier.verify(jwt);
        assertThat(out.sub()).isEqualTo("dXNlckhhbmRsZQ");
    }

    @Test
    void accepts_whenIatWithinClockSkewLeeway() throws Exception {
        // 양성 clock skew(예: 30초) 는 허용되어야 한다 — 과도하게 엄격한 leeway=0 은
        // 정상 토큰까지 거부하는 부작용이 있으므로 소액의 허용 폭을 둔다.
        Instant now = Instant.now();
        String jwt = signedToken(now.plus(Duration.ofSeconds(30)), null, now.plus(Duration.ofMinutes(15)));

        IdTokenClaims out = verifier.verify(jwt);
        assertThat(out.sub()).isEqualTo("dXNlckhhbmRsZQ");
    }
}
