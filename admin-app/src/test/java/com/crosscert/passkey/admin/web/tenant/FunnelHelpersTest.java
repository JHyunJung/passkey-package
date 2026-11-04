package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.funnel.FunnelDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 원본: pages/tenant/funnelHelpers.test.ts 의 모든 케이스. */
class FunnelHelpersTest {

    static FunnelDto.View data(long[] reg, long[] auth) {
        return new FunnelDto.View(7, new FunnelDto.Stage(reg[0], reg[1], 0), new FunnelDto.Stage(auth[0], auth[1], 0), 0, List.of(), List.of());
    }

    static List<FunnelDto.DailyPoint> series(int n, IntToLongFunction success, IntToLongFunction attempts) {
        List<FunnelDto.DailyPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new FunnelDto.DailyPoint("D" + i, attempts.applyAsLong(i), success.applyAsLong(i)));
        return out;
    }

    static List<FunnelDto.DailyPoint> series(int n, IntToLongFunction success) {
        return series(n, success, i -> 10);
    }

    @Test
    @DisplayName("funnelSummary: 단계·전환율·이탈·최대 이탈 단계·전체 전환율")
    void summary() {
        FunnelHelpers.Summary s = FunnelHelpers.summary(data(new long[] {100, 80}, new long[] {70, 63}));
        assertThat(s.steps()).extracting(FunnelHelpers.Step::label).containsExactly("등록 시도", "등록 성공", "인증 시도", "인증 성공");
        assertThat(s.steps()).extracting(FunnelHelpers.Step::value).containsExactly(100L, 80L, 70L, 63L);
        assertThat(s.steps()).extracting(FunnelHelpers.Step::pctOfFirst).containsExactly(1.0, 0.8, 0.7, 0.63);
        assertThat(s.steps()).extracting(FunnelHelpers.Step::convFromPrev).containsExactly(null, 0.8, 0.875, 0.9);
        assertThat(s.steps()).extracting(FunnelHelpers.Step::dropFromPrev).containsExactly(0L, 20L, 10L, 7L);
        assertThat(s.overallRate()).isCloseTo(0.63, within(1e-9));
        assertThat(s.biggestDrop()).isEqualTo(new FunnelHelpers.Drop("등록 시도", "등록 성공", 20, 0.2));
    }

    @Test
    @DisplayName("단계가 늘어나는 구간은 이탈 0, 감소가 없으면 biggestDrop=null")
    void noDrop() {
        FunnelHelpers.Summary s = FunnelHelpers.summary(data(new long[] {10, 10}, new long[] {40, 40}));
        assertThat(s.steps()).extracting(FunnelHelpers.Step::dropFromPrev).containsExactly(0L, 0L, 0L, 0L);
        assertThat(s.biggestDrop()).isNull();
        assertThat(s.overallRate()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("모두 0 이면 비율은 null (0/0 금지)")
    void allZero() {
        FunnelHelpers.Summary s = FunnelHelpers.summary(data(new long[] {0, 0}, new long[] {0, 0}));
        assertThat(s.overallRate()).isNull();
        assertThat(s.steps()).allMatch(x -> x.pctOfFirst() == null);
        assertThat(s.steps().get(1).convFromPrev()).isNull();
        assertThat(s.biggestDrop()).isNull();
    }

    @Test
    @DisplayName("priorPeriodDelta: 마지막 N일 vs 그 앞 N일 성공률 차이(%p)")
    void delta() {
        // 30일: 앞쪽 23일 성공 5/10, 마지막 7일 성공 8/10
        assertThat(FunnelHelpers.priorPeriodDelta(series(30, i -> i >= 23 ? 8 : 5), 7)).isCloseTo(30.0, within(1e-9));
    }

    @Test
    @DisplayName("하락은 음수")
    void deltaNegative() {
        assertThat(FunnelHelpers.priorPeriodDelta(series(14, i -> i >= 7 ? 4 : 6), 7)).isCloseTo(-20.0, within(1e-9));
    }

    @Test
    @DisplayName("데이터 부족·시도 0건이면 null")
    void deltaNull() {
        assertThat(FunnelHelpers.priorPeriodDelta(series(10, i -> 5), 7)).isNull();
        assertThat(FunnelHelpers.priorPeriodDelta(series(14, i -> 0, i -> i < 7 ? 0 : 10), 7)).isNull();
        assertThat(FunnelHelpers.priorPeriodDelta(List.of(), 7)).isNull();
    }

    @Test
    @DisplayName("fmtPct / fmtDeltaPp (toFixed 와 같은 반올림)")
    void format() {
        assertThat(FunnelHelpers.fmtPct(0.63)).isEqualTo("63.0%");
        assertThat(FunnelHelpers.fmtPct(0.875, 0)).isEqualTo("88%");
        assertThat(FunnelHelpers.fmtPct(null)).isEqualTo("—");
        assertThat(FunnelHelpers.fmtDeltaPp(30.0000001)).isEqualTo("+30.0%p");
        assertThat(FunnelHelpers.fmtDeltaPp(-2.46)).isEqualTo("-2.5%p");
        assertThat(FunnelHelpers.fmtDeltaPp(0)).isEqualTo("±0.0%p");
        // JS: (1.005).toFixed(2) === "1.00" — 이진 값 기준 반올림
        assertThat(FunnelHelpers.toFixed(1.005, 2)).isEqualTo("1.00");
        assertThat(FunnelHelpers.toFixed(0.8750, 4)).isEqualTo("0.8750");
    }
}
