package com.crosscert.passkey.core.alert;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * async 배선 통합 검증 (I1). 최소 컨텍스트({@link AlertConfig} + {@link AlertDispatcher} +
 * 스텁 채널)만 띄워 — Oracle/web 컨텍스트 없이 — 이벤트가 ApplicationEventPublisher 로
 * 발행되면 {@code @Async("alertExecutor")} 로 별도 {@code alert-} 스레드에서 채널이
 * 호출되는지(발행 스레드와 다른 스레드)를 확인한다.
 */
class AlertAsyncWiringTest {

    @Configuration
    static class TestContext {
        @Bean
        AlertDispatcher alertDispatcher(java.util.List<AlertChannel> channels) {
            return new AlertDispatcher(channels);
        }

        @Bean
        CapturingChannel capturingChannel() {
            return new CapturingChannel();
        }
    }

    static class CapturingChannel implements AlertChannel {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> threadName = new AtomicReference<>();

        @Override
        public boolean supports(SecurityAlertEvent.Severity severity) {
            return true;
        }

        @Override
        public void send(SecurityAlertEvent event) {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        }
    }

    @Test
    void event_is_dispatched_on_alert_executor_thread() throws Exception {
        try (var ctx = new AnnotationConfigApplicationContext(AlertConfig.class, TestContext.class)) {
            String publisherThread = Thread.currentThread().getName();
            CapturingChannel channel = ctx.getBean(CapturingChannel.class);

            ctx.publishEvent(
                    new SecurityAlertEvent(
                            SecurityAlertEvent.AlertType.MDS_SYNC_FAILURE,
                            SecurityAlertEvent.Severity.HIGH,
                            "summary",
                            Map.of("k", "v")));

            assertThat(channel.latch.await(5, TimeUnit.SECONDS))
                    .as("channel should be invoked asynchronously within timeout")
                    .isTrue();
            assertThat(channel.threadName.get())
                    .as("dispatch must run on the dedicated alert executor thread, not the publisher thread")
                    .startsWith("alert-")
                    .isNotEqualTo(publisherThread);
        }
    }
}
