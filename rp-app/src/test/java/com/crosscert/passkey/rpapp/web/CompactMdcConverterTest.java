package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactMdcConverterTest {

    private String convert(Map<String, String> mdc) {
        CompactMdcConverter c = new CompactMdcConverter();
        c.start();
        LoggingEvent ev = new LoggingEvent();
        ev.setLevel(Level.INFO);
        ev.setMessage("x");
        ev.setMDCPropertyMap(mdc);
        return c.convert(ev);
    }

    @Test
    void emptyMdc_returnsEmptyString() {
        assertThat(convert(Map.of())).isEmpty();
    }

    @Test
    void onlyTraceId_returnsOnlyThatKey() {
        assertThat(convert(Map.of("traceId", "fae20e9bec04")))
            .isEqualTo("[traceId=fae20e9bec04]");
    }

    @Test
    void multipleKeys_renderInFixedOrder() {
        assertThat(
            convert(
                Map.of(
                    "actorEmail", "a@x.com",
                    "traceId", "abc"
                )
            )
        ).isEqualTo("[traceId=abc actorEmail=a@x.com]");
    }

    /** KEYS 에 없는 MDC 키는 출력하지 않는다(rp-app 은 traceId·actorEmail 만 넣는다). */
    @Test
    void unknownKeys_areNotRendered() {
        assertThat(
            convert(
                Map.of(
                    "traceId", "abc",
                    "tenantId", "t1",
                    "apiKeyPrefix", "pk_dev"
                )
            )
        ).isEqualTo("[traceId=abc]");
    }

    @Test
    void longValue_isCappedTo16Chars() {
        assertThat(convert(Map.of("traceId", "0123456789abcdefGHIJ")))
            .isEqualTo("[traceId=0123456789abcdef]");
    }

    @Test
    void blankValue_isTreatedAsAbsent() {
        assertThat(convert(Map.of("traceId", "", "actorEmail", "a@x.com")))
            .isEqualTo("[actorEmail=a@x.com]");
    }
}
