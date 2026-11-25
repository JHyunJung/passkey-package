package com.crosscert.passkey.admin.usage

import org.assertj.core.api.Assertions.assertThat
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

class UsagePeriodsTest {

    @Test
    fun parseLedgerStart_valid() {
        assertThat(UsagePeriods.parseLedgerStart("2026-11-26")).isEqualTo(LocalDate.of(2026, 11, 26))
    }

    @Test
    fun parseLedgerStart_unsetOrMalformed_returnsSentinel() {
        assertThat(UsagePeriods.parseLedgerStart(null)).isEqualTo(UsagePeriods.NO_LEDGER)
        assertThat(UsagePeriods.parseLedgerStart("")).isEqualTo(UsagePeriods.NO_LEDGER)
        assertThat(UsagePeriods.parseLedgerStart("2026/11/26")).isEqualTo(UsagePeriods.NO_LEDGER)
        assertThat(UsagePeriods.parseLedgerStart("내일")).isEqualTo(UsagePeriods.NO_LEDGER)
    }

    @Test
    fun dayStartAndMonthStart_areKstMidnight() {
        assertThat(UsagePeriods.dayStart(LocalDate.of(2026, 11, 25)))
            .isEqualTo(OffsetDateTime.parse("2026-11-25T00:00+09:00"))
        assertThat(UsagePeriods.monthStart(YearMonth.of(2026, 10)))
            .isEqualTo(OffsetDateTime.parse("2026-10-01T00:00+09:00"))
    }

    @Test
    fun periodOf_formatsYearMonth() {
        assertThat(UsagePeriods.periodOf(YearMonth.of(2026, 10))).isEqualTo("2026-10")
    }

    @Test
    fun parsePeriod_invalidIsNull() {
        assertThat(UsagePeriods.parsePeriod("2026-13")).isNull()
        assertThat(UsagePeriods.parsePeriod("abc")).isNull()
        assertThat(UsagePeriods.parsePeriod(null)).isNull()
        assertThat(UsagePeriods.parsePeriod("2026-10")).isEqualTo(YearMonth.of(2026, 10))
    }

    private fun warnings(raw: String?): List<String> {
        val logger = LoggerFactory.getLogger(UsagePeriods::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            UsagePeriods.parseLedgerStart(raw)
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun parseLedgerStart_unsetWarnsOnce_namingProperty() {
        for (raw in listOf(null, "", "  ")) {
            val msgs = warnings(raw)
            assertThat(msgs).hasSize(1)
            assertThat(msgs[0]).contains("PASSKEY_USAGE_LEDGER_START_DATE").contains("이전 방식")
        }
    }

    @Test
    fun parseLedgerStart_malformedWarnsOnce() {
        assertThat(warnings("내일")).hasSize(1)
    }

    @Test
    fun parseLedgerStart_validDoesNotWarn() {
        assertThat(warnings("2026-11-26")).isEmpty()
    }
}
