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
    void setUp() {
        var repo = org.mockito.Mockito.mock(
                com.crosscert.passkey.core.repository.SigningKeyRepository.class);
        var envelope = new com.crosscert.passkey.core.jwt.KeyEnvelope(
                java.util.Base64.getEncoder().encodeToString(new byte[32]),
                new java.security.SecureRandom());
        var clock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-06-01T00:00:00Z"),
                java.time.ZoneOffset.UTC);

        // First findFirstByStatus call returns empty → provider creates initial key.
        org.mockito.Mockito.when(
                repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(java.util.Optional.empty());

        // Capture the saved row so we can return it from findAllByStatusIn.
        java.util.concurrent.atomic.AtomicReference<
                com.crosscert.passkey.core.entity.SigningKey> savedRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    var row = (com.crosscert.passkey.core.entity.SigningKey) inv.getArgument(0);
                    savedRef.set(row);
                    return row;
                });
        org.mockito.Mockito.when(repo.findAllByStatusIn(
                java.util.List.of("ACTIVE", "ROTATED")))
                .thenAnswer(inv -> savedRef.get() == null
                        ? java.util.List.of()
                        : java.util.List.of(savedRef.get()));

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
