package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class PayloadLoggingTest {

    private val payloadLogger = LoggerFactory.getLogger("com.crosscert.passkey.payload") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    private fun attach(level: Level) {
        appender.list.clear()
        appender.start()
        payloadLogger.level = level
        payloadLogger.addAppender(appender)
    }

    @AfterEach
    fun cleanup() {
        payloadLogger.detachAppender(appender)
        appender.stop()
    }

    private fun runFilter(body: String) {
        val filter = RequestLoggingFilter()
        val req = MockHttpServletRequest("POST", "/passkey/register/finish")
        req.setContent(body.toByteArray())
        req.contentType = "application/json"
        val res = MockHttpServletResponse()
        val chain = FilterChain { rq, rs ->
            // 다운스트림이 요청 본문을 소비해야 ContentCachingRequestWrapper 가 캐싱한다.
            (rq as jakarta.servlet.http.HttpServletRequest).inputStream.readAllBytes()
        }
        filter.doFilter(req, res, chain)
    }

    @Test
    fun debugLevel_logsRequestBody() {
        attach(Level.DEBUG)
        runFilter("{\"hello\":\"world\"}")
        val msgs = appender.list.map { it.formattedMessage }
        assertThat(msgs).anyMatch { it.contains("hello") }
    }

    @Test
    fun infoLevel_doesNotLogRequestBody() {
        attach(Level.INFO)
        runFilter("{\"hello\":\"world\"}")
        assertThat(appender.list).isEmpty()
    }

    @Test
    fun longBody_isCappedTo2kb() {
        attach(Level.DEBUG)
        runFilter("x".repeat(5000))
        val payloadMsgs = appender.list.map { it.formattedMessage }.filter { it.contains("req body") }
        assertThat(payloadMsgs).isNotEmpty
        assertThat(payloadMsgs.first().length).isLessThan(2200)
    }
}
