package com.crosscert.passkey.admin.web.format

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/** ← admin-ui/src/lib/kst.test.ts (+ formatDateTime 표기 확인) */
class KstFormatTest {

    private data class Item(val id: String, val ts: String)

    companion object {
        // now = KST 2026-10-12 00:30
        private val NOW_BOUNDARY: Instant = Instant.parse("2026-10-11T15:30:00Z")
    }

    // ── kst formatters ──
    @Test
    @DisplayName("UTC 15:10 은 KST 다음날 00:10")
    fun utc1510IsNextDayKst0010() {
        assertThat(KstFormat.kstDate("2026-10-11T15:10:00Z")).isEqualTo("2026-10-12")
        assertThat(KstFormat.kstTime("2026-10-11T15:10:00Z")).isEqualTo("00:10")
    }

    @Test
    @DisplayName("kstDateTime 은 초까지 24시간제")
    fun kstDateTimeIs24HourWithSeconds() {
        assertThat(KstFormat.kstDateTime("2026-10-11T05:02:03Z")).isEqualTo("2026-10-11 14:02:03")
    }

    @Test
    @DisplayName("+09:00 오프셋 입력도 같은 시각으로 해석")
    fun offsetInputIsSameInstant() {
        assertThat(KstFormat.kstDateTime("2026-10-11T14:02:03+09:00")).isEqualTo("2026-10-11 14:02:03")
    }

    @Test
    @DisplayName("utcIso 는 밀리초 없는 Z 표기")
    fun utcIsoHasNoMillis() {
        assertThat(KstFormat.utcIso("2026-10-11T14:02:03+09:00")).isEqualTo("2026-10-11T05:02:03Z")
    }

    @Test
    @DisplayName("파싱 불가 입력은 원문 반환")
    fun unparseableInputIsReturnedAsIs() {
        assertThat(KstFormat.kstDate("not-a-date")).isEqualTo("not-a-date")
        assertThat(KstFormat.utcIso("not-a-date")).isEqualTo("not-a-date")
    }

    // ── dayLabel (KST 자정 경계) ──

    @Test
    @DisplayName("같은 KST 날짜는 오늘")
    fun sameKstDateIsToday() {
        assertThat(KstFormat.dayLabel("2026-10-11T15:10:00Z", NOW_BOUNDARY)).isEqualTo("오늘") // KST 10-12 00:10
    }

    @Test
    @DisplayName("UTC 로는 같은 날이어도 KST 전날이면 어제")
    fun kstPreviousDayIsYesterday() {
        assertThat(KstFormat.dayLabel("2026-10-11T14:50:00Z", NOW_BOUNDARY)).isEqualTo("어제") // KST 10-11 23:50
    }

    @Test
    @DisplayName("그 이전은 KST 날짜")
    fun olderIsKstDate() {
        assertThat(KstFormat.dayLabel("2026-10-10T14:50:00Z", NOW_BOUNDARY)).isEqualTo("2026-10-10") // KST 10-10 23:50
    }

    // ── groupByDay ──
    @Test
    @DisplayName("최신순 입력을 연속 구간으로 묶고 순서를 유지한다")
    fun groupsConsecutiveDaysKeepingOrder() {
        val now = Instant.parse("2026-10-11T03:00:00Z") // KST 10-11 12:00
        val items = listOf(
            Item("a", "2026-10-11T02:00:00Z"),
            Item("b", "2026-10-11T00:00:00Z"),
            Item("c", "2026-10-10T09:00:00Z"),
            Item("d", "2026-10-08T01:00:00Z"),
        )
        val g = KstFormat.groupByDay(items, Item::ts, now)
        assertThat(g.stream().map { it.label }).containsExactly("오늘", "어제", "2026-10-08")
        assertThat(g[0].items.stream().map { it.id }).containsExactly("a", "b")
    }

    @Test
    @DisplayName("빈 입력은 빈 배열")
    fun emptyInputIsEmpty() {
        assertThat(KstFormat.groupByDay(listOf<Item>(), Item::ts, Instant.now())).isEmpty()
    }

    // ── kstToday / kstPrevMonthRange ──
    @Test
    @DisplayName("KST 00:00–09:00 사이에도 KST 날짜(UTC 전날이 아님)")
    fun kstTodayInEarlyMorning() {
        assertThat(KstFormat.kstToday(Instant.parse("2026-10-10T16:30:00Z"))).isEqualTo("2026-10-11")
        assertThat(KstFormat.kstToday(Instant.parse("2026-10-11T14:59:00Z"))).isEqualTo("2026-10-11")
    }

    @Test
    @DisplayName("직전 달 1일 ~ 말일을 KST 기준으로")
    fun prevMonthRangeInKst() {
        // KST 2026-11-01 01:00 = UTC 2026-10-31 16:00 — 직전 달은 10월
        assertThat(KstFormat.kstPrevMonthRange(Instant.parse("2026-10-31T16:00:00Z"))).isEqualTo(KstFormat.DateRange("2026-10-01", "2026-10-31"))
        assertThat(KstFormat.kstPrevMonthRange(Instant.parse("2026-03-15T00:00:00Z"))).isEqualTo(KstFormat.DateRange("2026-02-01", "2026-02-28"))
        assertThat(KstFormat.kstPrevMonthRange(Instant.parse("2026-01-10T00:00:00Z"))).isEqualTo(KstFormat.DateRange("2025-12-01", "2025-12-31"))
    }

    // ── formatDateTime.ts (원본에 vitest 없음 — 주석의 예시를 확인) ──
    @Test
    @DisplayName("formatDateTime ko-KR 표기, null 은 em-dash, 날짜만은 시간 계산 없음")
    fun formatDateTimeKoKr() {
        assertThat(KstFormat.formatDateTime("2026-05-27T12:00:00+09:00")).isEqualTo("2026. 05. 27. 12:00:00")
        assertThat(KstFormat.formatDateTime("2026-05-27T12:00:00Z")).isEqualTo("2026. 05. 27. 21:00:00")
        assertThat(KstFormat.formatDateTime("2026-05-26T15:00:00Z")).isEqualTo("2026. 05. 27. 00:00:00")
        assertThat(KstFormat.formatDateTime(null as String?)).isEqualTo("—")
        assertThat(KstFormat.formatDateTime("garbage")).isEqualTo("garbage")
        assertThat(KstFormat.formatDate("2026-05-27")).isEqualTo("2026. 05. 27.")
        assertThat(KstFormat.formatDate("")).isEqualTo("—")
    }

    @Test
    @DisplayName("relative 는 주입된 now 기준 전/후")
    fun relativeUsesGivenNow() {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        assertThat(KstFormat.relative(now.minusSeconds(10), now)).isEqualTo("방금")
        assertThat(KstFormat.relative(now.plusSeconds(10), now)).isEqualTo("곧")
        assertThat(KstFormat.relative(now.minusSeconds((5 * 60).toLong()), now)).isEqualTo("5분 전")
        assertThat(KstFormat.relative(now.plusSeconds((55 * 60).toLong()), now)).isEqualTo("55분 후")
        assertThat(KstFormat.relative(now.minusSeconds((3 * 3600).toLong()), now)).isEqualTo("3시간 전")
        assertThat(KstFormat.relative(now.plusSeconds((2 * 86400 + 60).toLong()), now)).isEqualTo("2일 후")
        assertThat(KstFormat.timeAgo(now.minusSeconds(40L * 86400), now)).isEqualTo("1개월 전")
    }
}
