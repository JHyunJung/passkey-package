package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.funnel.FunnelDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.function.IntToLongFunction

/** 인증 현황 탭 성공률 계산. */
class FunnelHelpersTest {

    companion object {
        fun series(n: Int, success: IntToLongFunction, attempts: IntToLongFunction): List<FunnelDto.DailyPoint> {
            val out = ArrayList<FunnelDto.DailyPoint>()
            for (i in 0 until n) out.add(FunnelDto.DailyPoint("D$i", attempts.applyAsLong(i), success.applyAsLong(i)))
            return out
        }

        fun series(n: Int, success: IntToLongFunction): List<FunnelDto.DailyPoint> =
            series(n, success) { 10 }
    }

    @Test
    @DisplayName("rate: 시도 0 이면 null, 성공이 시도보다 많으면 100% 로 자른다")
    fun rate() {
        assertThat(FunnelHelpers.rate(28, 37)).isCloseTo(28.0 / 37, within(1e-12))
        assertThat(FunnelHelpers.rate(0, 0)).isNull()
        assertThat(FunnelHelpers.rate(5, 3)).isEqualTo(1.0)
    }

    @Test
    @DisplayName("priorPeriodDelta: 마지막 N일 vs 그 앞 N일 성공률 차이(%p)")
    fun delta() {
        // 30일: 앞쪽 23일 성공 5/10, 마지막 7일 성공 8/10
        assertThat(FunnelHelpers.priorPeriodDelta(series(30) { i -> if (i >= 23) 8 else 5 }, 7)).isCloseTo(30.0, within(1e-9))
    }

    @Test
    @DisplayName("하락은 음수")
    fun deltaNegative() {
        assertThat(FunnelHelpers.priorPeriodDelta(series(14) { i -> if (i >= 7) 4 else 6 }, 7)).isCloseTo(-20.0, within(1e-9))
    }

    @Test
    @DisplayName("데이터 부족·시도 0건이면 null")
    fun deltaNull() {
        assertThat(FunnelHelpers.priorPeriodDelta(series(10) { 5 }, 7)).isNull()
        assertThat(FunnelHelpers.priorPeriodDelta(series(14, { 0 }, { i -> if (i < 7) 0 else 10 }), 7)).isNull()
        assertThat(FunnelHelpers.priorPeriodDelta(listOf(), 7)).isNull()
    }

    @Test
    @DisplayName("fmtPct / fmtDeltaPp (toFixed 와 같은 반올림)")
    fun format() {
        assertThat(FunnelHelpers.fmtPct(0.63)).isEqualTo("63.0%")
        assertThat(FunnelHelpers.fmtPct(0.875, 0)).isEqualTo("88%")
        assertThat(FunnelHelpers.fmtPct(null)).isEqualTo("—")
        assertThat(FunnelHelpers.fmtDeltaPp(30.0000001)).isEqualTo("+30.0%p")
        assertThat(FunnelHelpers.fmtDeltaPp(-2.46)).isEqualTo("-2.5%p")
        assertThat(FunnelHelpers.fmtDeltaPp(0.0)).isEqualTo("±0.0%p")
        // JS: (1.005).toFixed(2) === "1.00" — 이진 값 기준 반올림
        assertThat(FunnelHelpers.toFixed(1.005, 2)).isEqualTo("1.00")
        assertThat(FunnelHelpers.toFixed(0.8750, 4)).isEqualTo("0.8750")
    }
}
