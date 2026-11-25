package com.crosscert.passkey.admin.usage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
}
