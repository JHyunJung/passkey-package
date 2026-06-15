package com.crosscert.passkey.rpapp.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompactMdcConverterTest {

    private fun convert(mdc: Map<String, String>): String {
        val c = CompactMdcConverter()
        c.start()
        val ev = LoggingEvent()
        ev.level = Level.INFO
        ev.message = "x"
        ev.setMDCPropertyMap(mdc)
        return c.convert(ev)
    }

    @Test
    fun emptyMdc_returnsEmptyString() {
        assertThat(convert(emptyMap())).isEmpty()
    }

    @Test
    fun onlyTraceId_returnsOnlyThatKey() {
        assertThat(convert(mapOf("traceId" to "fae20e9bec04")))
            .isEqualTo("[traceId=fae20e9bec04]")
    }

    @Test
    fun multipleKeys_renderInFixedOrder() {
        assertThat(
            convert(
                mapOf(
                    "actorEmail" to "a@x.com",
                    "traceId" to "abc",
                    "tenantId" to "t1",
                ),
            ),
        ).isEqualTo("[traceId=abc tenantId=t1 actorEmail=a@x.com]")
    }

    @Test
    fun longValue_isCappedTo16Chars() {
        assertThat(convert(mapOf("traceId" to "0123456789abcdefGHIJ")))
            .isEqualTo("[traceId=0123456789abcdef]")
    }

    @Test
    fun blankValue_isTreatedAsAbsent() {
        assertThat(convert(mapOf("traceId" to "", "tenantId" to "t1")))
            .isEqualTo("[tenantId=t1]")
    }
}
