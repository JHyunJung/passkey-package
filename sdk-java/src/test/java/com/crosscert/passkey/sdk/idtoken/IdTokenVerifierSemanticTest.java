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
