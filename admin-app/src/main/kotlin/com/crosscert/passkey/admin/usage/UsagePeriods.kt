package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.core.config.KstTime
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeParseException

private val log = LoggerFactory.getLogger(UsagePeriods::class.java)

/** 사용량 정산의 일·월 경계(KST)와 기간 문자열("YYYY-MM") 변환. */
object UsagePeriods {

    /** 장부 시작일이 없다는 뜻의 표식. 어떤 날짜도 이 날 이후가 아니므로 전부 "이전 방식" 이 된다. */
    val NO_LEDGER: LocalDate = LocalDate.of(9999, 12, 31)

    /** null·빈 값·형식 오류는 [NO_LEDGER]. 형식 오류만 경고를 남긴다(호출자가 한 번만 파싱한다). */
    fun parseLedgerStart(raw: String?): LocalDate {
        if (raw.isNullOrBlank()) return NO_LEDGER
        return try {
            LocalDate.parse(raw.trim())
        } catch (e: DateTimeParseException) {
            log.warn("passkey.usage.ledger-start-date 형식 오류(YYYY-MM-DD 필요): value={} — 장부 시작일 없음으로 처리", raw)
            NO_LEDGER
        }
    }

    /** KST 00:00. */
    fun dayStart(day: LocalDate): OffsetDateTime = day.atStartOfDay(KstTime.ZONE).toOffsetDateTime()

    fun monthStart(m: YearMonth): OffsetDateTime = dayStart(m.atDay(1))

    fun periodOf(m: YearMonth): String = m.toString()

    /** "2026-10" → YearMonth. 형식 오류·null 은 null. */
    fun parsePeriod(s: String?): YearMonth? {
        if (s == null) return null
        return try {
            YearMonth.parse(s)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
