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
        AtomicReference<String> key = new AtomicReference<>("pk_aaaaaaaaFIRSTsecret");
        PasskeyClient client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()), key::get));

        client.registrationStart(new RegistrationStartRequest("u1", "User One", "user.one"));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/rp/registration/start"))
                .withHeader("X-API-Key", equalTo("pk_aaaaaaaaFIRSTsecret")));

        key.set("pk_bbbbbbbbSECONDsecret");

        client.authenticationStart(new AuthenticationStartRequest("u1"));
        wm.verify(postRequestedFor(urlEqualTo("/api/v1/rp/authentication/start"))
                .withHeader("X-API-Key", equalTo("pk_bbbbbbbbSECONDsecret")));
    }

    @Test
    void blankKeyFailsFast() {
        PasskeyClient client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()), () -> "  "));

        assertThatThrownBy(() ->
                client.registrationStart(new RegistrationStartRequest("u1", "User One", "user.one")))
                .isInstanceOf(PasskeyConfigurationException.class)
                .hasMessageContaining("null/blank");
    }

    private static String read(String cp) throws Exception {
        return Files.readString(Path.of(
                DynamicApiKeyHeaderIT.class.getClassLoader().getResource(cp).toURI()));
    }
}
