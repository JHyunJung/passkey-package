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
        keys = new SigningKeyProvider();
        keys.init();
        mvc = MockMvcBuilders.standaloneSetup(new JwksController(keys)).build();
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
