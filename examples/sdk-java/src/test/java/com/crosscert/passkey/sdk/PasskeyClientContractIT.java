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

    // ── 4 ceremony × success ─────────────────────────────────────────

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

        try {
            assertThatThrownBy(() ->
                    client.registrationStart(new RegistrationStartRequest("u", "A", "a@b.c")))
                    .isInstanceOf(PasskeyAuthException.class)
                    .satisfies(e -> {
                        var ae = (PasskeyAuthException) e;
                        // passkey-app's ApiKeyAuthFilter returns RFC 7807 problem+json, not envelope
                        assertThat(ae.getCode()).isEqualTo("C999");      // SDK fallback when upstream returns non-envelope error
                        assertThat(ae.getHttpStatus()).isEqualTo(401);
                    });
        } finally {
            // Reset scenario so this test's state change doesn't affect other tests
            wm.resetScenarios();
        }
    }
}
