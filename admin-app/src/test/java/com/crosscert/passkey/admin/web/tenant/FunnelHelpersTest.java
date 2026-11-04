package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.funnel.FunnelDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 인증 현황 탭 성공률 계산. */
class FunnelHelpersTest {

    static List<FunnelDto.DailyPoint> series(int n, IntToLongFunction success, IntToLongFunction attempts) {
        List<FunnelDto.DailyPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new FunnelDto.DailyPoint("D" + i, attempts.applyAsLong(i), success.applyAsLong(i)));
        return out;
    }

    static List<FunnelDto.DailyPoint> series(int n, IntToLongFunction success) {
        return series(n, success, i -> 10);
    }

    @Test
    @DisplayName("rate: 시도 0 이면 null, 성공이 시도보다 많으면 100% 로 자른다")
    void rate() {
        assertThat(FunnelHelpers.rate(28, 37)).isCloseTo(28.0 / 37, within(1e-12));
        assertThat(FunnelHelpers.rate(0, 0)).isNull();
        assertThat(FunnelHelpers.rate(5, 3)).isEqualTo(1.0);
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
