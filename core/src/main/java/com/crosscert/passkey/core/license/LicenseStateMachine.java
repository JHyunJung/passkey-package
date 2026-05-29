package com.crosscert.passkey.core.license;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Singleton holder of the current license state, the verified token,
 * and the wall-clock timestamp of the last successful verification.
 * Thread-safe via synchronized methods — heartbeat thread + request
 * threads call recompute()/snapshot() concurrently.
 *
 * Lifecycle: instantiated by LicenseBootstrap (onprem mode only).
 * Tests construct directly via the public ctor.
 */
public class LicenseStateMachine {

    private static final Logger log = LoggerFactory.getLogger(LicenseStateMachine.class);

    private final Supplier<Instant> now;

    private volatile LicenseToken token;
    private volatile Instant lastVerifiedAt;
    private volatile LicenseState state;
    private volatile String lastFailureReason;

    public LicenseStateMachine(LicenseToken initialToken,
                               Instant initialVerifiedAt,
                               Supplier<Instant> nowSupplier) {
        this.now = nowSupplier;
        this.token = initialToken;
        this.lastVerifiedAt = initialVerifiedAt;
        this.state = computeState();
        log.info("license state initialized: state={} jti={} expiresAt={}",
                state, token.jti(), token.expiresAt());
    }

    public synchronized void onHeartbeatSuccess(LicenseToken refreshed, Instant verifiedAt) {
        this.token = refreshed;
        this.lastVerifiedAt = verifiedAt;
        this.lastFailureReason = null;
        LicenseState prev = this.state;
        this.state = computeState();
        if (state != prev) {
            log.info("license state transition: {} -> {} (heartbeat ok) jti={}",
                    prev, state, token.jti());
        }
    }

    public synchronized void onHeartbeatFailure(String reason) {
        this.lastFailureReason = reason;
        LicenseState prev = this.state;
        this.state = computeState();
        if (state != prev) {
            log.warn("license state transition: {} -> {} (heartbeat failed: {}) jti={}",
                    prev, state, reason, token.jti());
        } else {
            log.warn("license heartbeat failed: state={} reason={} jti={}",
                    state, reason, token.jti());
        }
    }

    public synchronized void recompute() {
        LicenseState prev = this.state;
        this.state = computeState();
        if (state != prev) {
            log.info("license state transition: {} -> {} (recompute) jti={}",
                    prev, state, token.jti());
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, token, lastVerifiedAt, lastFailureReason);
    }

    public LicenseState state() {
        return state;
    }

    public LicenseToken token() {
        return token;
    }

    /**
     * Computes the current state from the in-memory fields.
     * Must be called under the object monitor (all callers hold `this`).
     *
     * Priority order (first match wins):
     * 1. Clock rollback  → DEAD
     * 2. Token expired   → DEAD
     * 3. Failure reason set:
     *    a. grace exceeded  → DEAD
     *    b. within grace    → NETWORK_GRACE
     * 4. Within warning band → WARNING
     * 5. Normal              → VALID
     */
    private LicenseState computeState() {
        Instant t = now.get();

        // 1. Clock rollback protection
        if (lastVerifiedAt != null && t.isBefore(lastVerifiedAt)) {
            return LicenseState.DEAD;
        }

        // 2. Expired absolutely
        if (!t.isBefore(token.expiresAt())) {
            return LicenseState.DEAD;
        }

        // 3. Heartbeat failure branch
        if (lastFailureReason != null) {
            Duration since = Duration.between(lastVerifiedAt, t);
            if (since.toHours() > token.limits().graceHoursWhenOffline()) {
                return LicenseState.DEAD;
            }
            return LicenseState.NETWORK_GRACE;
        }

        // 4. Warning band check
        Duration toExpiry = Duration.between(t, token.expiresAt());
        if (toExpiry.toDays() <= token.limits().warningDaysBeforeExpiry()) {
            return LicenseState.WARNING;
        }

        return LicenseState.VALID;
    }

    public record Snapshot(
            LicenseState state,
            LicenseToken token,
            Instant lastVerifiedAt,
            String lastFailureReason
    ) {}
}
