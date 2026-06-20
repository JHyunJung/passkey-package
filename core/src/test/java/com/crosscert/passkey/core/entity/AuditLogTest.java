package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    private static final UUID ACTOR_UUID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void constructorPopulatesAllRequiredFields() {
        byte[] prev = new byte[]{1,2,3};
        byte[] hash = new byte[]{4,5,6};
        OffsetDateTime ts  = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        AuditLog row = new AuditLog(
                prev, hash, ACTOR_UUID, "alice@example.com",
                "TENANT_CREATE", "TENANT", "T_A",
                null, null, null,
                "{\"id\":\"T_A\"}", ts);

        assertThat(row.getPrevHash()).containsExactly(1,2,3);
        assertThat(row.getHash()).containsExactly(4,5,6);
        assertThat(row.getActorId()).isEqualTo(ACTOR_UUID);
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
                null, new byte[]{0}, ACTOR_UUID, "alice@example.com",
                "ADMIN_LOGIN", null, null, null, null, null, "{}", OffsetDateTime.now());
        assertThat(row.getPrevHash()).isNull();
    }

    @Test
    void nullActorIdAllowedForSystemAuditEntries() {
        AuditLog row = new AuditLog(
                null, new byte[]{0}, null, "system",
                "SYSTEM_ACTION", null, null, null, null, null, "{}", OffsetDateTime.now());
        assertThat(row.getActorId()).isNull();
    }
}
