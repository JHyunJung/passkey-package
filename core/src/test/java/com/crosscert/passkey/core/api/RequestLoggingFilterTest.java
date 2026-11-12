package com.crosscert.passkey.core.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** actorEmail MDC 는 평문이 아닌 마스킹 값으로만 실려야 한다(%compactMdc·JSON MDC 키는 %msg 마스킹을 거치지 않는다). */
class RequestLoggingFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private ILoggingEvent run(Object actor) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/api/x");
        if (actor != null) {
            req.setAttribute(RequestLoggingFilter.ACTOR_EMAIL_ATTR, actor);
        }
        new RequestLoggingFilter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @Test
    void actorEmail_mdc_isMasked() throws Exception {
        ILoggingEvent e = run("alice@example.com");
        assertThat(e.getMDCPropertyMap()).containsEntry("actorEmail", "a***@example.com");
        assertThat(e.getMDCPropertyMap().toString()).doesNotContain("alice");
    }

    @Test
    void actorEmail_withoutAt_isFullyMasked() throws Exception {
        ILoggingEvent e = run("operator");
        assertThat(e.getMDCPropertyMap()).containsEntry("actorEmail", "***");
    }

    @Test
    void noAttribute_leavesMdcSlotEmpty() throws Exception {
        ILoggingEvent e = run(null);
        assertThat(e.getMDCPropertyMap()).doesNotContainKey("actorEmail");
    }

    @Test
    void mdc_isClearedAfterRequest() throws Exception {
        run("alice@example.com");
        assertThat(MDC.get("actorEmail")).isNull();
    }
}
