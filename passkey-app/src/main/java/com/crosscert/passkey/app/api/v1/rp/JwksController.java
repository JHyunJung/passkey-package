package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class JwksController {

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/jwks.json",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        // publicJwkSet().toJSONObject() never includes the "d" private
        // exponent — Nimbus's toPublicJWK() in SigningKeyProvider drops
        // all private fields before this serializer runs.
        return keys.publicJwkSet().toJSONObject();
    }
}
