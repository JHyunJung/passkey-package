package com.crosscert.passkey.admin.web.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ← admin-ui/src/lib/kst.test.ts (+ formatDateTime 표기 확인) */
class KstFormatTest {

    private record Item(String id, String ts) {}

    // ── kst formatters ──
    @Test
    @DisplayName("UTC 15:10 은 KST 다음날 00:10")
    void utc1510IsNextDayKst0010() {
        assertThat(KstFormat.kstDate("2026-10-11T15:10:00Z")).isEqualTo("2026-10-12");
        assertThat(KstFormat.kstTime("2026-10-11T15:10:00Z")).isEqualTo("00:10");
    }

    @Test
    @DisplayName("kstDateTime 은 초까지 24시간제")
    void kstDateTimeIs24HourWithSeconds() {
        assertThat(KstFormat.kstDateTime("2026-10-11T05:02:03Z")).isEqualTo("2026-10-11 14:02:03");
    }

    @Test
    @DisplayName("+09:00 오프셋 입력도 같은 시각으로 해석")
    void offsetInputIsSameInstant() {
        assertThat(KstFormat.kstDateTime("2026-10-11T14:02:03+09:00")).isEqualTo("2026-10-11 14:02:03");
    }

    @Test
    @DisplayName("utcIso 는 밀리초 없는 Z 표기")
    void utcIsoHasNoMillis() {
        assertThat(KstFormat.utcIso("2026-10-11T14:02:03+09:00")).isEqualTo("2026-10-11T05:02:03Z");
    }

    @Test
    @DisplayName("파싱 불가 입력은 원문 반환")
    void unparseableInputIsReturnedAsIs() {
        assertThat(KstFormat.kstDate("not-a-date")).isEqualTo("not-a-date");
        assertThat(KstFormat.utcIso("not-a-date")).isEqualTo("not-a-date");
    }

    // ── dayLabel (KST 자정 경계) ──
    // now = KST 2026-10-12 00:30
    private static final Instant NOW_BOUNDARY = Instant.parse("2026-10-11T15:30:00Z");

    @Test
    @DisplayName("같은 KST 날짜는 오늘")
    void sameKstDateIsToday() {
        assertThat(KstFormat.dayLabel("2026-10-11T15:10:00Z", NOW_BOUNDARY)).isEqualTo("오늘"); // KST 10-12 00:10
    }

    @Test
    @DisplayName("UTC 로는 같은 날이어도 KST 전날이면 어제")
    void kstPreviousDayIsYesterday() {
        assertThat(KstFormat.dayLabel("2026-10-11T14:50:00Z", NOW_BOUNDARY)).isEqualTo("어제"); // KST 10-11 23:50
    }

    @Test
    @DisplayName("그 이전은 KST 날짜")
    void olderIsKstDate() {
        assertThat(KstFormat.dayLabel("2026-10-10T14:50:00Z", NOW_BOUNDARY)).isEqualTo("2026-10-10"); // KST 10-10 23:50
    }

    // ── groupByDay ──
    @Test
    @DisplayName("최신순 입력을 연속 구간으로 묶고 순서를 유지한다")
    void groupsConsecutiveDaysKeepingOrder() {
        Instant now = Instant.parse("2026-10-11T03:00:00Z"); // KST 10-11 12:00
        List<Item> items = List.of(
                new Item("a", "2026-10-11T02:00:00Z"),
                new Item("b", "2026-10-11T00:00:00Z"),
                new Item("c", "2026-10-10T09:00:00Z"),
                new Item("d", "2026-10-08T01:00:00Z"));
        List<KstFormat.DayGroup<Item>> g = KstFormat.groupByDay(items, Item::ts, now);
        assertThat(g.stream().map(KstFormat.DayGroup::getLabel)).containsExactly("오늘", "어제", "2026-10-08");
        assertThat(g.get(0).getItems().stream().map(Item::id)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("빈 입력은 빈 배열")
    void emptyInputIsEmpty() {
        assertThat(KstFormat.groupByDay(List.<Item>of(), Item::ts, Instant.now())).isEmpty();
    }

    // ── kstToday / kstPrevMonthRange ──
    @Test
    @DisplayName("KST 00:00–09:00 사이에도 KST 날짜(UTC 전날이 아님)")
    void kstTodayInEarlyMorning() {
        assertThat(KstFormat.kstToday(Instant.parse("2026-10-10T16:30:00Z"))).isEqualTo("2026-10-11");
        assertThat(KstFormat.kstToday(Instant.parse("2026-10-11T14:59:00Z"))).isEqualTo("2026-10-11");
    }

    @Test
    @DisplayName("직전 달 1일 ~ 말일을 KST 기준으로")
    void prevMonthRangeInKst() {
        // KST 2026-11-01 01:00 = UTC 2026-10-31 16:00 — 직전 달은 10월
        assertThat(KstFormat.kstPrevMonthRange(Instant.parse("2026-10-31T16:00:00Z"))).isEqualTo(new KstFormat.DateRange("2026-10-01", "2026-10-31"));
        assertThat(KstFormat.kstPrevMonthRange(Instant.parse("2026-03-15T00:00:00Z"))).isEqualTo(new KstFormat.DateRange("2026-02-01", "2026-02-28"));
        assertThat(KstFormat.kstPrevMonthRange(Instant.parse("2026-01-10T00:00:00Z"))).isEqualTo(new KstFormat.DateRange("2025-12-01", "2025-12-31"));
    }

    // ── formatDateTime.ts (원본에 vitest 없음 — 주석의 예시를 확인) ──
    @Test
    @DisplayName("formatDateTime ko-KR 표기, null 은 em-dash, 날짜만은 시간 계산 없음")
    void formatDateTimeKoKr() {
        assertThat(KstFormat.formatDateTime("2026-05-27T12:00:00+09:00")).isEqualTo("2026. 05. 27. 12:00:00");
        assertThat(KstFormat.formatDateTime("2026-05-27T12:00:00Z")).isEqualTo("2026. 05. 27. 21:00:00");
        assertThat(KstFormat.formatDateTime("2026-05-26T15:00:00Z")).isEqualTo("2026. 05. 27. 00:00:00");
        assertThat(KstFormat.formatDateTime((String) null)).isEqualTo("—");
        assertThat(KstFormat.formatDateTime("garbage")).isEqualTo("garbage");
        assertThat(KstFormat.formatDate("2026-05-27")).isEqualTo("2026. 05. 27.");
        assertThat(KstFormat.formatDate("")).isEqualTo("—");
    }

    @Test
    @DisplayName("relative 는 주입된 now 기준 전/후")
    void relativeUsesGivenNow() {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");
        assertThat(KstFormat.relative(now.minusSeconds(10), now)).isEqualTo("방금");
        assertThat(KstFormat.relative(now.plusSeconds(10), now)).isEqualTo("곧");
        assertThat(KstFormat.relative(now.minusSeconds(5 * 60), now)).isEqualTo("5분 전");
        assertThat(KstFormat.relative(now.plusSeconds(55 * 60), now)).isEqualTo("55분 후");
        assertThat(KstFormat.relative(now.minusSeconds(3 * 3600), now)).isEqualTo("3시간 전");
        assertThat(KstFormat.relative(now.plusSeconds(2 * 86400 + 60), now)).isEqualTo("2일 후");
        assertThat(KstFormat.timeAgo(now.minusSeconds(40L * 86400), now)).isEqualTo("1개월 전");
    }
}
