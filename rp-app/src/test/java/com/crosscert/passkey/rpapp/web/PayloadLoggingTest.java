package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadLoggingTest {

    private final Logger payloadLogger =
            (Logger) LoggerFactory.getLogger("com.crosscert.passkey.payload");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private void attach(Level level) {
        appender.list.clear();
        appender.start();
        payloadLogger.setLevel(level);
        payloadLogger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        payloadLogger.detachAppender(appender);
        appender.stop();
    }

    private void runFilter(String body) throws Exception {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/passkey/register/finish");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) ->
            // 다운스트림이 요청 본문을 소비해야 ContentCachingRequestWrapper 가 캐싱한다.
            ((HttpServletRequest) rq).getInputStream().readAllBytes());
    }

    @Test
    void debugLevel_logsRequestBody() throws Exception {
        attach(Level.DEBUG);
        runFilter("{\"hello\":\"world\"}");
        List<String> msgs = appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
        assertThat(msgs).anyMatch(m -> m.contains("hello"));
    }

    @Test
    void infoLevel_doesNotLogRequestBody() throws Exception {
        attach(Level.INFO);
        runFilter("{\"hello\":\"world\"}");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void longBody_isCappedTo2kb() throws Exception {
        attach(Level.DEBUG);
        runFilter("x".repeat(5000));
        List<String> payloadMsgs = appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("req body"))
            .toList();
        assertThat(payloadMsgs).isNotEmpty();
        assertThat(payloadMsgs.get(0).length()).isLessThan(2200);
    }
}
