package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
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

class IdTokenVerifierAlgTest {

    static final String KID = "alg-test-kid";

    static WireMockServer wm;
    static RSAKey rsaKey;          // includes the private key, for signing in-test
    static IdTokenVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        // Generate an RSA keypair and publish its public half as a JWKS via WireMock,
        // mirroring PasskeyClientContractIT's /.well-known/jwks.json stub (but with a
        // key we hold the private half of, so we can sign tokens in-test).
        rsaKey = new RSAKeyGenerator(2048)
                .keyID(KID)
                .algorithm(JWSAlgorithm.RS256)
                .generate();

        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"keys\":[" + rsaKey.toPublicJWK().toJSONString() + "]}")));

        Clock clock = Clock.systemUTC();
        JwksCache jwks = new JwksCache(
                URI.create("http://localhost:" + wm.port()), Duration.ofMinutes(5), clock);
        verifier = new IdTokenVerifier(jwks, clock);
    }

    @AfterAll
    static void tearDown() {
        if (wm != null) wm.stop();
    }

    private static JWTClaimsSet claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://issuer.example.com")
                .subject("dXNlckhhbmRsZQ")
                .audience("ck_test_apikey")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .build();
    }

    @Test
    void acceptsRs256Token() throws Exception {
        // given: a legitimate RS256 token issued exactly as production issues them.
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(),
                claims());
        jwt.sign(new RSASSASigner(rsaKey));

        // when / then: verifies cleanly and returns the claims (no behavior change).
        IdTokenClaims out = verifier.verify(jwt.serialize());
        assertThat(out).isNotNull();
        assertThat(out.iss()).isEqualTo("https://issuer.example.com");
        assertThat(out.sub()).isEqualTo("dXNlckhhbmRsZQ");
    }

    @Test
    void rejectsNonRs256Token() throws Exception {
        // given: a structurally valid token signed with HS256 but carrying the same
        // kid — the classic alg-confusion downgrade. The alg pin must reject it
        // BEFORE any signature/kid lookup happens.
        byte[] secret = new byte[32]; // 256-bit HMAC key (HS256 minimum)
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i + 1);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(),
                claims());
        jwt.sign(new MACSigner(secret));

        // when / then: rejected with the alg-pin message.
        assertThatThrownBy(() -> verifier.verify(jwt.serialize()))
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("Unexpected JWS algorithm");
    }
}
