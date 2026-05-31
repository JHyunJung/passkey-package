package com.crosscert.passkey.app.fido2;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대표 메트릭 검증 — ceremony counter 의 이름·태그 규약을 고정.
 * (개발 속도 우선: 대표 1개. 4개 서비스는 동일 패턴; 서비스별 호출은 컴파일 + 기존 테스트로 커버.)
 */
class CeremonyMetricsTest {

    @Test
    void ceremony_counter_increments_success_and_failure() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        reg.counter("passkey_ceremony_total", "type", "registration", "phase", "start", "result", "success").increment();
        reg.counter("passkey_ceremony_total", "type", "registration", "phase", "finish", "result", "failure").increment();

        double success = reg.find("passkey_ceremony_total")
                .tags("phase", "start", "result", "success").counter().count();
        double failure = reg.find("passkey_ceremony_total")
                .tags("phase", "finish", "result", "failure").counter().count();
        assertThat(success).isEqualTo(1.0);
        assertThat(failure).isEqualTo(1.0);
    }
}
