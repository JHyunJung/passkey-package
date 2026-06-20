package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard test for the KST migration (Task 7): SchedulerLease expiry/created
 * timestamps must be OffsetDateTime, and the single-source-of-time constructor
 * must keep createdAt == the caller-supplied now (not a second self-sourced
 * .now()), so a fixed-clock caller cannot produce createdAt &gt; expiresAt.
 */
class SchedulerLeaseExpiryTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void getExpiresAtReturnsOffsetDateTimeWithKstOffset() {
        SchedulerLease lease =
                new SchedulerLease(UUID.randomUUID(), "mds-sync", "node-1", NOW.plusMinutes(5));
        assertThat(lease.getExpiresAt()).isInstanceOf(OffsetDateTime.class);
        assertThat(lease.getExpiresAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(lease.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void singleSourceConstructorSeedsCreatedAtFromCallerNow() {
        // expiresAt = now + ttl; createdAt must equal the same `now` source, never after expiresAt.
        OffsetDateTime expiresAt = NOW.plusMinutes(10);
        SchedulerLease lease =
                new SchedulerLease(UUID.randomUUID(), "mds-sync", "node-1", expiresAt, NOW);
        assertThat(lease.getCreatedAt()).isEqualTo(NOW);
        assertThat(lease.getUpdatedAt()).isEqualTo(NOW);
        assertThat(lease.getCreatedAt()).isBeforeOrEqualTo(lease.getExpiresAt());
    }
}
