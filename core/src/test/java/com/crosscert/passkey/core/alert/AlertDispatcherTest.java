package com.crosscert.passkey.core.alert;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AlertDispatcherTest {

    private SecurityAlertEvent event(SecurityAlertEvent.Severity sev) {
        return new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.MDS_SYNC_FAILURE, sev, "summary", Map.of("k", "v"));
    }

    @Test
    void fans_out_to_supporting_channels_only() {
        AtomicInteger lowGot = new AtomicInteger();
        AtomicInteger highGot = new AtomicInteger();
        AlertChannel all = channel(s -> true, e -> lowGot.incrementAndGet());
        AlertChannel highOnly = channel(s -> s.atLeast(SecurityAlertEvent.Severity.HIGH), e -> highGot.incrementAndGet());
        AlertDispatcher d = new AlertDispatcher(List.of(all, highOnly));

        d.onAlert(event(SecurityAlertEvent.Severity.MEDIUM));
        assertThat(lowGot.get()).isEqualTo(1);
        assertThat(highGot.get()).isEqualTo(0);
    }

    @Test
    void one_channel_failure_does_not_block_others() {
        AtomicInteger got = new AtomicInteger();
        AlertChannel boom = channel(s -> true, e -> { throw new RuntimeException("smtp down"); });
        AlertChannel ok = channel(s -> true, e -> got.incrementAndGet());
        AlertDispatcher d = new AlertDispatcher(List.of(boom, ok));

        d.onAlert(event(SecurityAlertEvent.Severity.HIGH));
        assertThat(got.get()).isEqualTo(1);
    }

    private AlertChannel channel(java.util.function.Predicate<SecurityAlertEvent.Severity> supports,
                                 java.util.function.Consumer<SecurityAlertEvent> send) {
        return new AlertChannel() {
            public boolean supports(SecurityAlertEvent.Severity s) { return supports.test(s); }
            public void send(SecurityAlertEvent e) { send.accept(e); }
        };
    }
}
