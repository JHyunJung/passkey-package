package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwksControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mvc;
    private SigningKeyProvider keys;

    @BeforeEach
    void setUp() throws Exception {
        var repo = org.mockito.Mockito.mock(
                com.crosscert.passkey.core.repository.SigningKeyRepository.class);
        var envelope = new com.crosscert.passkey.core.jwt.KeyEnvelope(
                java.util.Base64.getEncoder().encodeToString(new byte[32]),
                new java.security.SecureRandom());
        var clock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-06-01T00:00:00Z"),
                java.time.ZoneOffset.UTC);

        // Build a pre-existing ACTIVE key so we don't need the PL/SQL bootstrap path
        // (which requires a real JdbcTemplate). The 4-arg test constructor sets jdbc=null.
        var gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        com.nimbusds.jose.jwk.RSAKey rsa = new com.nimbusds.jose.jwk.RSAKey.Builder(
                (java.security.interfaces.RSAPublicKey) pair.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate())
                .keyID("test-kid")
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();
        String publicJwk = rsa.toPublicJWK().toJSONString();
        byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
        var activeRow = new com.crosscert.passkey.core.entity.SigningKey(
                "test-kid", "RS256", publicJwk, sealed);

        org.mockito.Mockito.when(
                repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(java.util.Optional.of(activeRow));
        org.mockito.Mockito.when(repo.findAllByStatusIn(
                java.util.List.of("ACTIVE", "ROTATED")))
                .thenReturn(java.util.List.of(activeRow));

        keys = new com.crosscert.passkey.core.jwt.SigningKeyProvider(
                repo, envelope, new com.fasterxml.jackson.databind.ObjectMapper(), clock);
        keys.init();
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new JwksController(keys)).build();
    }

    @Test
    void returnsJwksWithSinglePublicKey() throws Exception {
        JsonNode root = fetchJwks();
        JsonNode keysNode = root.get("keys");
        assertThat(keysNode).isNotNull();
        assertThat(keysNode.isArray()).isTrue();
        assertThat(keysNode).hasSize(1);

        JsonNode jwk = keysNode.get(0);
        assertThat(jwk.get("kty").asText()).isEqualTo("RSA");
        assertThat(jwk.get("kid").asText()).isEqualTo(keys.signingKey().getKeyID());
        assertThat(jwk.get("use").asText()).isEqualTo("sig");
        // codex P2: spec requires alg=RS256 in the published JWKS.
        assertThat(jwk.get("alg").asText()).isEqualTo("RS256");
        // RSA public components must be present so RPs can verify.
        assertThat(jwk.get("n")).isNotNull();
        assertThat(jwk.get("e")).isNotNull();
    }

    @Test
    void jwksOmitsAllPrivateRsaComponents() throws Exception {
        // Defense-in-depth: parse the JSON and assert each private RSA
        // field is genuinely ABSENT, not merely formatted away. Nimbus
        // toPublicJWK() drops them, but a future refactor could leak
        // them and a substring check would miss padded or reordered
        // output.
        JsonNode jwk = fetchJwks().get("keys").get(0);
        for (String privateField : new String[]{"d", "p", "q", "dp", "dq", "qi", "oth"}) {
            assertThat(jwk.has(privateField))
                    .as("JWKS leaked private RSA component '%s'", privateField)
                    .isFalse();
        }
    }

    private JsonNode fetchJwks() throws Exception {
        MvcResult res = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }
}
