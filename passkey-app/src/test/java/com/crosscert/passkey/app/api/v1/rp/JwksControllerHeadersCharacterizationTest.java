package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * JwksController 의 컨트롤러 레벨 응답 헤더·메서드 특성 (passkey-app Kotlin 전환 안전망).
 * 컨트롤러는 캐시 헤더를 직접 달지 않고, produces=application/json 만 선언한다.
 */
class JwksControllerHeadersCharacterizationTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) gen.generateKeyPair().getPublic())
                .keyID("kid-1").keyUse(KeyUse.SIGNATURE).build();
        SigningKeyProvider keys = mock(SigningKeyProvider.class);
        when(keys.publicJwkSet()).thenReturn(new JWKSet(rsa));
        when(keys.signingKey()).thenReturn(rsa);
        mvc = MockMvcBuilders.standaloneSetup(new JwksController(keys)).build();
    }

    @Test
    void get_returnsExactJsonContentTypeAndNoControllerCacheHeaders() throws Exception {
        MvcResult res = mvc.perform(get("/.well-known/jwks.json")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(res.getResponse().getHeaderNames()).containsExactly("Content-Type");
    }

    @Test
    void get_withIncompatibleAccept_isNotAcceptable() throws Exception {
        MvcResult res = mvc.perform(get("/.well-known/jwks.json").accept("text/plain")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(406);
    }

    @Test
    void post_isMethodNotAllowed() throws Exception {
        MvcResult res = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/.well-known/jwks.json"))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(405);
    }
}
