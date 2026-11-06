package com.crosscert.passkey.app.api.v1.rp

import com.crosscert.passkey.core.jwt.SigningKeyProvider
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/**
 * JwksController 의 컨트롤러 레벨 응답 헤더·메서드 특성 (passkey-app Kotlin 전환 안전망).
 * 컨트롤러는 캐시 헤더를 직접 달지 않고, produces=application/json 만 선언한다.
 */
class JwksControllerHeadersCharacterizationTest {

    private val mvc: MockMvc = run {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val rsa = RSAKey.Builder(gen.generateKeyPair().public as RSAPublicKey)
            .keyID("kid-1").keyUse(KeyUse.SIGNATURE).build()
        val keys: SigningKeyProvider = mock()
        whenever(keys.publicJwkSet()).thenReturn(JWKSet(rsa))
        whenever(keys.signingKey()).thenReturn(rsa)
        MockMvcBuilders.standaloneSetup(JwksController(keys)).build()
    }

    @Test
    fun get_returnsExactJsonContentTypeAndNoControllerCacheHeaders() {
        val res = mvc.perform(get("/.well-known/jwks.json")).andReturn()
        assertThat(res.response.status).isEqualTo(200)
        assertThat(res.response.contentType).isEqualTo("application/json")
        assertThat(res.response.headerNames).containsExactly("Content-Type")
    }

    @Test
    fun get_withIncompatibleAccept_isNotAcceptable() {
        val res = mvc.perform(get("/.well-known/jwks.json").accept("text/plain")).andReturn()
        assertThat(res.response.status).isEqualTo(406)
    }

    @Test
    fun post_isMethodNotAllowed() {
        val res = mvc.perform(post("/.well-known/jwks.json")).andReturn()
        assertThat(res.response.status).isEqualTo(405)
    }
}
