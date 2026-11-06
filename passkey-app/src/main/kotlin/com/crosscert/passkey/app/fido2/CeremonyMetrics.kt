package com.crosscert.passkey.app.fido2

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** WebAuthn ceremony 메트릭 (P1-2). counter 이름·태그 키를 한 곳에 응집. */
@Component
class CeremonyMetrics(private val registry: MeterRegistry) {

    fun recordSuccess(type: String, phase: String) = record(type, phase, "success")

    fun recordFailure(type: String, phase: String) = record(type, phase, "failure")

    private fun record(type: String, phase: String, result: String) {
        registry.counter("passkey_ceremony_total", "type", type, "phase", phase, "result", result).increment()
    }
}
