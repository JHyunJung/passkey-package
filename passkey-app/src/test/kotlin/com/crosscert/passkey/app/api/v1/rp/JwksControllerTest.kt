package com.crosscert.passkey.app.api.v1.rp

import com.crosscert.passkey.core.entity.SigningKey
import com.crosscert.passkey.core.jwt.KeyEnvelope
import com.crosscert.passkey.core.jwt.SigningKeyProvider
import com.crosscert.passkey.core.repository.SigningKeyRepository
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional

private val MAPPER = ObjectMapper()

/**
 * Builds a provider over a pre-existing ACTIVE key so we don't need the PL/SQL bootstrap path
 * (which requires a real JdbcTemplate). The 4-arg test constructor sets jdbc=null.
 */
private fun activeKeyProvider(): SigningKeyProvider {
    val repo: SigningKeyRepository = mock()
    val envelope = KeyEnvelope(Base64.getEncoder().encodeToString(ByteArray(32)), SecureRandom())
    val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val rsa = RSAKey.Builder(pair.public as RSAPublicKey)
        .privateKey(pair.private as RSAPrivateKey)
        .keyID("test-kid")
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256)
        .build()
    val publicJwk = rsa.toPublicJWK().toJSONString()
    val sealed = envelope.seal(pair.private.encoded)
    val activeRow = SigningKey("test-kid", "RS256", publicJwk, sealed)

    whenever(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(Optional.of(activeRow))
    whenever(repo.findAllByStatusIn(listOf("ACTIVE", "ROTATED"))).thenReturn(listOf(activeRow))

    return SigningKeyProvider(repo, envelope, ObjectMapper(), clock).apply { init() }
}

class JwksControllerTest {

    private val keys = activeKeyProvider()
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(JwksController(keys)).build()

    @Test
    fun returnsJwksWithSinglePublicKey() {
        val root = fetchJwks()
        val keysNode = root.get("keys")
        assertThat(keysNode).isNotNull()
        assertThat(keysNode.isArray).isTrue()
        assertThat(keysNode).hasSize(1)

        val jwk = keysNode.get(0)
        assertThat(jwk.get("kty").asString()).isEqualTo("RSA")
        assertThat(jwk.get("kid").asString()).isEqualTo(keys.signingKey().keyID)
        assertThat(jwk.get("use").asString()).isEqualTo("sig")
        // codex P2: spec requires alg=RS256 in the published JWKS.
        assertThat(jwk.get("alg").asString()).isEqualTo("RS256")
        // RSA public components must be present so RPs can verify.
        assertThat(jwk.get("n")).isNotNull()
        assertThat(jwk.get("e")).isNotNull()
    }

    @Test
    fun jwksOmitsAllPrivateRsaComponents() {
        // Defense-in-depth: parse the JSON and assert each private RSA
        // field is genuinely ABSENT, not merely formatted away. Nimbus
        // toPublicJWK() drops them, but a future refactor could leak
        // them and a substring check would miss padded or reordered
        // output.
        val jwk = fetchJwks().get("keys").get(0)
        for (privateField in listOf("d", "p", "q", "dp", "dq", "qi", "oth")) {
            assertThat(jwk.has(privateField))
                .`as`("JWKS leaked private RSA component '%s'", privateField)
                .isFalse()
        }
    }

    private fun fetchJwks(): JsonNode {
        val res = mvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andReturn()
        return MAPPER.readTree(res.response.contentAsString)
    }
}
