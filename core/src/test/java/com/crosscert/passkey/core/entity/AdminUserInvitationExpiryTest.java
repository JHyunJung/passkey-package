package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AdminUserInvitationExpiryTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    private AdminUserInvitation newInvitation(OffsetDateTime expiresAt) {
        return new AdminUserInvitation(UUID.randomUUID(), "hash", "prefix12", "admin@x", NOW, expiresAt);
    }

    @Test
    void pendingWhenNotAcceptedAndNotExpired() {
        AdminUserInvitation inv = newInvitation(NOW.plusHours(1));
        assertThat(inv.isPending(NOW)).isTrue();
        assertThat(inv.isExpired(NOW)).isFalse();
    }

    @Test
    void expiredWhenPastExpiry() {
        AdminUserInvitation inv = newInvitation(NOW.minusSeconds(1));
        assertThat(inv.isExpired(NOW)).isTrue();
        assertThat(inv.isPending(NOW)).isFalse();
    }

    @Test
    void acceptStampsAcceptedAt() {
        AdminUserInvitation inv = newInvitation(NOW.plusHours(1));
        inv.accept(NOW);
        assertThat(inv.getAcceptedAt()).isEqualTo(NOW);
        assertThat(inv.isAccepted()).isTrue();
    }

    @Test
    void markRevokedForcesPastExpiry() {
        AdminUserInvitation inv = newInvitation(NOW.plusHours(1));
        inv.markRevoked(NOW);
        assertThat(inv.isExpired(NOW)).isTrue();
    }

    /**
     * codex P2: createdAt 과 expiresAt 은 호출자의 단일 now 출처에서 나와야 한다.
     * 생성자가 createdAt 을 self-source(.now())하면 fixed-clock 테스트나 clock override
     * 시 두 시각이 서로 다른 순간을 가리키고, 극단적으로 createdAt > expiresAt 도 가능.
     */
    @Test
    void createdAtUsesCallerSuppliedNow_andPrecedesExpiry() {
        OffsetDateTime now = NOW;
        OffsetDateTime exp = now.plus(Duration.ofDays(7));
        AdminUserInvitation inv =
                new AdminUserInvitation(UUID.randomUUID(), "h", "p12", "a@x", now, exp);
        assertThat(inv.getCreatedAt()).isEqualTo(now);
        assertThat(inv.getCreatedAt()).isBefore(inv.getExpiresAt());
    }
}
