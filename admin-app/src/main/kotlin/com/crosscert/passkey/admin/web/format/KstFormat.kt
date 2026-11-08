package com.crosscert.passkey.admin.web.format

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.function.Function
import java.util.regex.Pattern

private const val DASH = "—"

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val DATE_TIME_MIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** ko-KR 표기. "2026. 05. 27. 12:00:00" (자정은 00). */
private val KO_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy. MM. dd. HH:mm:ss")
private val UTC_ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
private val LOCAL_DATE: Pattern = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$")
private const val DAY_SECONDS = 86_400L

/**
 * 화면 표시용 KST(Asia/Seoul) 포매터(← admin-ui lib/kst.ts, lib/formatDateTime.ts).
 * 시간대는 항상 Asia/Seoul 을 명시한다(JVM 기본 시간대 사용 안 함). 문자열 입력은 ISO(Z 또는 +09:00),
 * 파싱할 수 없으면 원문을 그대로 돌려준다.
 */
object KstFormat {

    @JvmField
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    // ── 파싱 ────────────────────────────────────────────────

    /** ISO 문자열 → Instant. 파싱할 수 없으면 null. */
    @JvmStatic
    fun parse(iso: String?): Instant? {
        if (iso == null) return null
        return try {
            OffsetDateTime.parse(iso.javaStrip()).toInstant()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(iso.javaStrip())
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    private fun kst(t: Instant): ZonedDateTime = t.atZone(KST)

    // ── kst.ts ───────────────────────────────────────────────

    /** KST 날짜 `YYYY-MM-DD`. */
    @JvmStatic
    fun kstDate(iso: String?): String? {
        val t = parse(iso)
        return if (t == null) iso else kstDate(t)
    }

    @JvmStatic
    fun kstDate(t: Instant?): String = if (t == null) DASH else DATE.format(kst(t))

    @JvmStatic
    fun kstDate(t: OffsetDateTime?): String = if (t == null) DASH else kstDate(t.toInstant())

    /** KST 시각 `HH:mm`. */
    @JvmStatic
    fun kstTime(iso: String?): String? {
        val t = parse(iso)
        return if (t == null) iso else kstTime(t)
    }

    @JvmStatic
    fun kstTime(t: Instant?): String = if (t == null) DASH else TIME.format(kst(t))

    @JvmStatic
    fun kstTime(t: OffsetDateTime?): String = if (t == null) DASH else kstTime(t.toInstant())

    /** KST `YYYY-MM-DD HH:mm:ss`. */
    @JvmStatic
    fun kstDateTime(iso: String?): String? {
        val t = parse(iso)
        return if (t == null) iso else kstDateTime(t)
    }

    @JvmStatic
    fun kstDateTime(t: Instant?): String = if (t == null) DASH else DATE_TIME.format(kst(t))

    @JvmStatic
    fun kstDateTime(t: OffsetDateTime?): String = if (t == null) DASH else kstDateTime(t.toInstant())

    /** KST `YYYY-MM-DD HH:mm`. */
    @JvmStatic
    fun kstDateTimeMin(iso: String?): String? {
        val t = parse(iso)
        return if (t == null) iso else kstDateTimeMin(t)
    }

    @JvmStatic
    fun kstDateTimeMin(t: Instant?): String = if (t == null) DASH else DATE_TIME_MIN.format(kst(t))

    @JvmStatic
    fun kstDateTimeMin(t: OffsetDateTime?): String = if (t == null) DASH else kstDateTimeMin(t.toInstant())

    /** 지금의 KST 날짜 `YYYY-MM-DD`. */
    @JvmStatic
    fun kstToday(now: Instant?): String = kstDate(now)

    /** KST 기준 오늘 날짜(파일 이름 등). */
    @JvmStatic
    fun kstTodayDate(now: Instant): LocalDate = kst(now).toLocalDate()

    data class DateRange(val from: String, val to: String)

    /** KST 기준 직전 달 1일 ~ 말일(`YYYY-MM-DD`). */
    @JvmStatic
    fun kstPrevMonthRange(now: Instant): DateRange {
        val first = kst(now).toLocalDate().withDayOfMonth(1).minusMonths(1)
        return DateRange(DATE.format(first), DATE.format(first.withDayOfMonth(first.lengthOfMonth())))
    }

    /** UTC ISO `YYYY-MM-DDTHH:mm:ssZ`(밀리초 제거). 파싱할 수 없으면 원문. */
    @JvmStatic
    fun utcIso(iso: String?): String? {
        val t = parse(iso)
        return if (t == null) iso else UTC_ISO.format(t.atOffset(ZoneOffset.UTC))
    }

    /** 피드 날짜 그룹 라벨. KST 기준 오늘/어제, 그 외 `YYYY-MM-DD`. KST 는 DST 가 없어 24h 빼기로 전날을 구한다. */
    @JvmStatic
    fun dayLabel(iso: String?, now: Instant): String {
        // !! : 원 Java 도 iso 가 null 이면(kstDate 가 원문 null 을 돌려줌) key.equals 에서 NPE.
        val key = kstDate(iso)!!
        if (key == kstDate(now)) return "오늘"
        if (key == kstDate(now.minusSeconds(DAY_SECONDS))) return "어제"
        return key
    }

    data class DayGroup<T>(val label: String, val items: MutableList<T>)

    /** 입력 순서를 유지하며 같은 dayLabel 의 연속 구간을 하나의 그룹으로 묶는다(피드는 최신순). */
    @JvmStatic
    fun <T> groupByDay(items: List<T>, isoOf: Function<T, String?>, now: Instant): List<DayGroup<T>> {
        val groups = ArrayList<DayGroup<T>>()
        for (item in items) {
            val label = dayLabel(isoOf.apply(item), now)
            val last = if (groups.isEmpty()) null else groups[groups.size - 1]
            if (last != null && last.label == label) {
                last.items.add(item)
            } else {
                val g = ArrayList<T>()
                g.add(item)
                groups.add(DayGroup(label, g))
            }
        }
        return groups
    }

    // ── formatDateTime.ts ───────────────────────────────────

    /** ko-KR 날짜·시각 `2026. 05. 27. 12:00:00`. null·빈 값은 em-dash, 파싱 실패는 원문. */
    @JvmStatic
    fun formatDateTime(iso: String?): String? {
        if (iso == null || iso.isEmpty()) return DASH
        val t = parse(iso)
        return if (t == null) iso else formatDateTime(t)
    }

    @JvmStatic
    fun formatDateTime(t: Instant?): String = if (t == null) DASH else KO_DATE_TIME.format(kst(t))

    @JvmStatic
    fun formatDateTime(t: OffsetDateTime?): String = if (t == null) DASH else formatDateTime(t.toInstant())

    /** LocalDate(`YYYY-MM-DD`) 값을 시간 계산 없이 `2026. 05. 27.` 로. null·빈 값은 em-dash, 형식이 다르면 원문. */
    @JvmStatic
    fun formatDate(value: String?): String {
        if (value == null || value.isEmpty()) return DASH
        val m = LOCAL_DATE.matcher(value)
        return if (m.matches()) m.group(1) + ". " + m.group(2) + ". " + m.group(3) + "." else value
    }

    @JvmStatic
    fun formatDate(d: LocalDate?): String = if (d == null) DASH else formatDate(d.toString())

    // ── 상대 시각 ────────────────────────────────────────────

    /** "5분 전" / "55분 후" / "3시간 전" / "2일 후" (← LicensePage.relativeTime). */
    @JvmStatic
    fun relative(t: Instant?, now: Instant): String {
        if (t == null) return DASH
        val diffMs = t.toEpochMilli() - now.toEpochMilli()
        val future = diffMs > 0
        val suffix = if (future) "후" else "전"
        val mins = Math.abs(diffMs) / 60_000
        if (mins < 1) return if (future) "곧" else "방금"
        if (mins < 60) return "${mins}분 $suffix"
        val hours = mins / 60
        if (hours < 48) return "${hours}시간 $suffix"
        return "${hours / 24}일 $suffix"
    }

    /** "방금 전" / "5분 전" / "3시간 전" / "2일 전" / "1개월 전" (← AuditChainPage.timeAgo). */
    @JvmStatic
    fun timeAgo(t: Instant?, now: Instant): String {
        if (t == null) return DASH
        val m = (now.toEpochMilli() - t.toEpochMilli()) / 60_000
        if (m < 1) return "방금 전"
        if (m < 60) return "${m}분 전"
        val h = m / 60
        if (h < 24) return "${h}시간 전"
        val d = h / 24
        if (d < 30) return "${d}일 전"
        return "${d / 30}개월 전"
    }
}
