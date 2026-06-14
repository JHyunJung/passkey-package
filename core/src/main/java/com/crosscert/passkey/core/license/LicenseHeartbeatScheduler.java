package com.crosscert.passkey.core.license;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Periodically calls the license server to refresh the cached token.
 * Active only when passkey.deployment.mode=onprem.
 *
 * Expected response: { "status": "active|revoked", "latestToken": "<JWS>" }.
 * - status=active + valid latestToken -> StateMachine.onHeartbeatSuccess
 * - non-2xx, transport error, parse failure, signature failure
 *   -> StateMachine.onHeartbeatFailure(reason)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseHeartbeatScheduler {

    private final LicenseStateMachine stateMachine;
    private final LicenseVerifier verifier;
    private final LicenseCache cache;
    private final LicenseProperties props;
    private final Clock clock;
    private final RestClient http;

    public LicenseHeartbeatScheduler(LicenseStateMachine stateMachine,
                                     LicenseVerifier verifier,
                                     LicenseCache cache,
                                     LicenseProperties props,
                                     Clock clock) {
        this.stateMachine = stateMachine;
        this.verifier = verifier;
        this.cache = cache;
        this.props = props;
        this.clock = clock;
        this.http = RestClient.builder()
                .baseUrl(props.heartbeatUrl())
                .build();
    }

    @Scheduled(
            fixedDelayString = "${passkey.license.heartbeat-interval:PT1H}",
            initialDelayString = "PT10S"
    )
    public void heartbeat() {
        String jti = stateMachine.token().jti();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.get()
                    .uri("/{jti}/verify", jti)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                stateMachine.onHeartbeatFailure("empty response");
                return;
            }
            Object status = body.get("status");
            if (!"active".equals(status)) {
                stateMachine.onHeartbeatFailure("status=" + status);
                return;
            }
            Object latest = body.get("latestToken");
            if (!(latest instanceof String jws)) {
                stateMachine.onHeartbeatFailure("missing latestToken");
                return;
            }
            LicenseToken refreshed = verifier.verify(jws);
            stateMachine.onHeartbeatSuccess(refreshed, clock.instant());
            Path cachePath = props.cachePath();
            cache.write(cachePath, new LicenseCache.Entry(jws, clock.instant()));
            log.debug("license heartbeat ok: jti={}", refreshed.jti());
        } catch (RestClientException e) {
            stateMachine.onHeartbeatFailure("http: " + e.getClass().getSimpleName());
        } catch (LicenseVerificationException e) {
            stateMachine.onHeartbeatFailure("verify: " + e.getMessage());
        } catch (Exception e) {
            stateMachine.onHeartbeatFailure("unexpected: " + e.getClass().getSimpleName());
            log.warn("license heartbeat unexpected error", e);
        }
    }
}
