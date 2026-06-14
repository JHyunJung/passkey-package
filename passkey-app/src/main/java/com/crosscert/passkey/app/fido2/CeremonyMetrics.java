package com.crosscert.passkey.app.fido2;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** WebAuthn ceremony 메트릭 (P1-2). counter 이름·태그 키를 한 곳에 응집. */
@Component
@RequiredArgsConstructor
public class CeremonyMetrics {
    private final MeterRegistry registry;

    public void recordSuccess(String type, String phase) {
        record(type, phase, "success");
    }

    public void recordFailure(String type, String phase) {
        record(type, phase, "failure");
    }

    private void record(String type, String phase, String result) {
        registry.counter("passkey_ceremony_total",
                "type", type, "phase", phase, "result", result).increment();
    }
}
