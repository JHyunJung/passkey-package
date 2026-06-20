package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SigningKeyTest {

    @Test
    void constructorPopulatesActiveStatusAndCreatedAt() {
        SigningKey k = new SigningKey(
                "thumbprint-abc", "RS256",
                "{\"kty\":\"RSA\",\"n\":\"...\",\"e\":\"AQAB\"}",
                new byte[]{1,2,3});
        assertThat(k.getKid()).isEqualTo("thumbprint-abc");
        assertThat(k.getAlg()).isEqualTo("RS256");
        assertThat(k.getStatus()).isEqualTo("ACTIVE");
        assertThat(k.getPublicJwk()).contains("\"n\":\"...\"");
        assertThat(k.getPrivatePkcs8()).containsExactly(1,2,3);
        assertThat(k.getCreatedAt()).isNull(); // set by @PrePersist, not constructor
        assertThat(k.getRotatedAt()).isNull();
        assertThat(k.getRevokedAt()).isNull();
    }

    @Test
    void rotateTransitionsToRotated() {
        SigningKey k = new SigningKey("kid","RS256","{}",new byte[]{0});
        OffsetDateTime now = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        k.rotate(now);
        assertThat(k.getStatus()).isEqualTo("ROTATED");
        assertThat(k.getRotatedAt()).isEqualTo(now);
        assertThat(k.getRevokedAt()).isNull();
    }

    @Test
    void revokeTransitionsToRevoked() {
        SigningKey k = new SigningKey("kid","RS256","{}",new byte[]{0});
        OffsetDateTime t1 = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-06-01T00:30:00Z");
        k.rotate(t1);
        k.revoke(t2);
        assertThat(k.getStatus()).isEqualTo("REVOKED");
        assertThat(k.getRotatedAt()).isEqualTo(t1);
        assertThat(k.getRevokedAt()).isEqualTo(t2);
    }
}
