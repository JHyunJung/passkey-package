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

    // ---- G05: MFA lockout counter must be independent of password login ---

    @Test
    void recordSuccessfulLogin_doesNotResetMfaFailedCount() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.parse("2026-06-01T00:00:00Z");
        java.time.Duration lockDuration = java.time.Duration.ofMinutes(15);

        // 4 MFA failures (sub-threshold, max-attempts=5) accumulate...
        for (int i = 0; i < 4; i++) {
            u.recordFailedMfa(now, 5, lockDuration);
        }
        assertThat(u.getMfaFailedCount()).isEqualTo(4);

        // ...a subsequent PASSWORD login success must not wipe that progress.
        u.recordSuccessfulLogin();
        assertThat(u.getMfaFailedCount()).isEqualTo(4);
    }

    @Test
    void recordFailedMfa_locksAccountAtThreshold_andResetsMfaCounter() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.parse("2026-06-01T00:00:00Z");
        java.time.Duration lockDuration = java.time.Duration.ofMinutes(15);

        for (int i = 0; i < 5; i++) {
            u.recordFailedMfa(now, 5, lockDuration);
        }

        assertThat(u.getMfaFailedCount()).isEqualTo(0); // reset after tripping the lock
        assertThat(u.isLocked(now)).isTrue();
        assertThat(u.getLockedUntil()).isEqualTo(now.plus(lockDuration));
    }

    @Test
    void recordFailedMfa_doesNotIncrementPasswordFailedLoginCount() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.parse("2026-06-01T00:00:00Z");
        u.recordFailedMfa(now, 5, java.time.Duration.ofMinutes(15));

        assertThat(u.getFailedLoginCount()).isEqualTo(0);
        assertThat(u.getMfaFailedCount()).isEqualTo(1);
    }

    @Test
    void recordSuccessfulMfa_resetsOnlyMfaCounter_notPasswordCounter() {
        AdminUser u = new AdminUser("alice@example.com", "$2a$12$abc", "ADMIN");
        java.time.OffsetDateTime now = java.time.OffsetDateTime.parse("2026-06-01T00:00:00Z");
        u.recordFailedMfa(now, 5, java.time.Duration.ofMinutes(15));
        u.recordFailedLogin(now, 5, java.time.Duration.ofMinutes(15));

        u.recordSuccessfulMfa();

        assertThat(u.getMfaFailedCount()).isEqualTo(0);
        assertThat(u.getFailedLoginCount()).isEqualTo(1); // untouched by MFA success
    }
}
