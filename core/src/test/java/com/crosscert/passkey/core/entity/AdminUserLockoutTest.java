package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserLockoutTest {

    private static final Instant NOW = Instant.parse("2026-05-30T00:00:00Z");
    private static final int MAX = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private AdminUser user() {
        return new AdminUser("op@crosscert.com", "hash", "PLATFORM_OPERATOR");
    }

    @Test
    void below_threshold_not_locked() {
        AdminUser u = user();
        for (int i = 0; i < MAX - 1; i++) u.recordFailedLogin(NOW, MAX, LOCK);
        assertThat(u.isLocked(NOW)).isFalse();
    }

    @Test
    void reaching_threshold_locks_for_duration() {
        AdminUser u = user();
        for (int i = 0; i < MAX; i++) u.recordFailedLogin(NOW, MAX, LOCK);
        assertThat(u.isLocked(NOW)).isTrue();
        assertThat(u.isLocked(NOW.plus(LOCK).minusSeconds(1))).isTrue();
        assertThat(u.isLocked(NOW.plus(LOCK).plusSeconds(1))).isFalse();
    }

    @Test
    void successful_login_resets_counter_and_lock() {
        AdminUser u = user();
        for (int i = 0; i < MAX; i++) u.recordFailedLogin(NOW, MAX, LOCK);
        u.recordSuccessfulLogin();
        assertThat(u.isLocked(NOW)).isFalse();
        assertThat(u.getLockedUntil()).isNull();
    }
}
