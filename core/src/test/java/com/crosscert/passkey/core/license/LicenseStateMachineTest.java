package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseStateMachineTest {

    private final LicenseToken farFuture = token(Duration.ofDays(365));
    private final LicenseToken expiringSoon = token(Duration.ofDays(5));
    private final LicenseToken expired = token(Duration.ofDays(-1));

    @Test
    void initialState_valid_whenFarFromExpiry() {
        LicenseStateMachine sm = newStateMachine(farFuture, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.VALID);
    }

    @Test
    void initialState_warning_whenExpiringWithinWindow() {
        LicenseStateMachine sm = newStateMachine(expiringSoon, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.WARNING);
    }

    @Test
    void initialState_dead_whenAlreadyExpired() {
        LicenseStateMachine sm = newStateMachine(expired, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.DEAD);
    }

    @Test
    void heartbeatFailure_movesToNetworkGrace() {
        LicenseStateMachine sm = newStateMachine(farFuture, Instant.now());
        sm.onHeartbeatFailure("timeout");
        assertThat(sm.state()).isEqualTo(LicenseState.NETWORK_GRACE);
    }

    @Test
    void heartbeatSuccessAfterGrace_restoresValid() {
        LicenseStateMachine sm = newStateMachine(farFuture, Instant.now());
        sm.onHeartbeatFailure("timeout");
        sm.onHeartbeatSuccess(farFuture, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.VALID);
    }

    @Test
    void networkGraceExceeded_movesToDead() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(base);
        LicenseStateMachine sm = new LicenseStateMachine(
                farFuture, base, now::get);
        sm.onHeartbeatFailure("timeout");
        assertThat(sm.state()).isEqualTo(LicenseState.NETWORK_GRACE);

        // Advance past the grace window (default 72h).
        now.set(base.plus(Duration.ofHours(73)));
        sm.recompute();
        assertThat(sm.state()).isEqualTo(LicenseState.DEAD);
    }

    @Test
    void clockRollback_movesToDead() {
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(base);
        LicenseStateMachine sm = new LicenseStateMachine(
                farFuture, base, now::get);
        assertThat(sm.state()).isEqualTo(LicenseState.VALID);

        // Operator rolls system clock back 1 hour before lastVerifiedAt.
        now.set(base.minus(Duration.ofHours(1)));
        sm.recompute();
        assertThat(sm.state()).isEqualTo(LicenseState.DEAD);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private LicenseStateMachine newStateMachine(LicenseToken token, Instant verifiedAt) {
        return new LicenseStateMachine(token, verifiedAt, Instant::now);
    }

    private static LicenseToken token(Duration validFor) {
        Instant now = Instant.now();
        return new LicenseToken(
                "test", "lic-test-001",
                now, now,
                now.plus(validFor),
                "00000000-0000-0000-0000-000000000001",
                Set.of("mds"),
                LicenseLimits.DEFAULTS);
    }
}
