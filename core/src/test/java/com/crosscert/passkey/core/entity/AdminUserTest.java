package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserTest {

    @Test
    void constructorSetsEnabledFlagAndDefersTimestamps() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        assertThat(u.getEmail()).isEqualTo("alice@example.com");
        assertThat(u.getBcryptHash()).isEqualTo("$2a$12$abc");
        assertThat(u.getRole()).isEqualTo("ADMIN");
        assertThat(u.isEnabled()).isTrue();
        // Phase 8 T3: createdAt/updatedAt are populated by BaseEntity's
        // @PrePersist callback at insert time, not by the constructor.
        // Pre-persist they are null — verified end-to-end in BaseEntityCallbackIT.
        assertThat(u.getCreatedAt()).isNull();
        assertThat(u.getUpdatedAt()).isNull();
        assertThat(u.getLastLoginAt()).isNull();
    }

    @Test
    void recordLoginUpdatesLastLoginAt() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.parse("2026-06-01T00:00:00Z");
        u.recordLogin(now);
        assertThat(u.getLastLoginAt()).isEqualTo(now);
    }
}
