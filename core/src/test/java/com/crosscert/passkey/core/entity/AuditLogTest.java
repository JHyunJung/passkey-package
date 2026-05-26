package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    @Test
    void constructorPopulatesAllRequiredFields() {
        byte[] prev = new byte[]{1,2,3};
        byte[] hash = new byte[]{4,5,6};
        Instant ts  = Instant.parse("2026-06-01T00:00:00Z");
        AuditLog row = new AuditLog(
                prev, hash, 42L, "alice@example.com",
                "TENANT_CREATE", "TENANT", "T_A",
                "{\"id\":\"T_A\"}", ts);

        assertThat(row.getPrevHash()).containsExactly(1,2,3);
        assertThat(row.getHash()).containsExactly(4,5,6);
        assertThat(row.getActorId()).isEqualTo(42L);
        assertThat(row.getActorEmail()).isEqualTo("alice@example.com");
        assertThat(row.getAction()).isEqualTo("TENANT_CREATE");
        assertThat(row.getTargetType()).isEqualTo("TENANT");
        assertThat(row.getTargetId()).isEqualTo("T_A");
        assertThat(row.getPayload()).isEqualTo("{\"id\":\"T_A\"}");
        assertThat(row.getCreatedAt()).isEqualTo(ts);
    }

    @Test
    void prevHashMayBeNullForGenesisRow() {
        AuditLog row = new AuditLog(
                null, new byte[]{0}, 1L, "alice@example.com",
                "ADMIN_LOGIN", null, null, "{}", Instant.now());
        assertThat(row.getPrevHash()).isNull();
    }
}
