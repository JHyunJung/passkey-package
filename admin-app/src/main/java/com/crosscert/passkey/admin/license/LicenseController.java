package com.crosscert.passkey.admin.license;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.license.LicenseProperties;
import com.crosscert.passkey.core.license.LicenseState;
import com.crosscert.passkey.core.license.LicenseStateMachine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * GET /admin/api/license — returns the current LicenseView.
 *
 * Always available; in SaaS mode it returns a sentinel view
 * (deploymentMode=saas, state=null) so the SPA can use the same
 * fetch on both modes.
 */
@RestController
@RequestMapping("/admin/api/license")
public class LicenseController {

    private final String deploymentMode;
    private final ObjectProvider<LicenseStateMachine> stateMachine;
    private final LicenseProperties props;
    private final Clock clock;

    public LicenseController(
            @org.springframework.beans.factory.annotation.Value("${passkey.deployment.mode:saas}")
            String deploymentMode,
            ObjectProvider<LicenseStateMachine> stateMachine,
            LicenseProperties props,
            Clock clock) {
        this.deploymentMode = deploymentMode;
        this.stateMachine = stateMachine;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<LicenseView> get() {
        LicenseStateMachine sm = stateMachine.getIfAvailable();
        if (sm == null) {
            return ApiResponse.ok(new LicenseView(
                    deploymentMode, null, null, null, null, 0L, List.of(), null, null, null));
        }
        LicenseStateMachine.Snapshot s = sm.snapshot();
        Instant now = clock.instant();
        long daysLeft = Math.max(0, Duration.between(now, s.token().expiresAt()).toDays());
        Long graceLeft = null;
        if (s.state() == LicenseState.NETWORK_GRACE && s.lastVerifiedAt() != null) {
            long max = s.token().limits().graceHoursWhenOffline();
            long elapsed = Duration.between(s.lastVerifiedAt(), now).toHours();
            graceLeft = Math.max(0, max - elapsed);
        }
        Instant nextBeat = s.lastVerifiedAt() == null
                ? null
                : s.lastVerifiedAt().plus(props.heartbeatInterval());
        return ApiResponse.ok(new LicenseView(
                deploymentMode,
                s.state().name(),
                s.token().sub(),
                s.token().jti(),
                s.token().expiresAt(),
                daysLeft,
                List.copyOf(s.token().features()),
                s.lastVerifiedAt(),
                graceLeft,
                nextBeat));
    }
}
