package com.crosscert.passkey.core.license;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component("license")
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseHealthIndicator implements HealthIndicator {

    private final LicenseStateMachine stateMachine;

    public LicenseHealthIndicator(LicenseStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @Override
    public Health health() {
        LicenseStateMachine.Snapshot snap = stateMachine.snapshot();
        Health.Builder b = (snap.state() == LicenseState.DEAD) ? Health.down() : Health.up();
        b.withDetail("state", snap.state().name());
        b.withDetail("jti", snap.token().jti());
        b.withDetail("daysUntilExpiry",
                Math.max(0, Duration.between(Instant.now(), snap.token().expiresAt()).toDays()));
        return b.build();
    }
}
