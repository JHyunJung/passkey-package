package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.funnel.FunnelDto
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 인증 현황 탭의 성공률 계산. 0/0 은 null(비율 없음)이며 숫자 모양은 원본 JS 와 같다
 * (toFixed 는 double 의 정확한 이진 값을 반올림하므로 [BigDecimal] (double) 생성자로 맞춘다).
 */
object FunnelHelpers {

    /** 성공률(0~1). 시도 0 이면 null(비율 없음). 기간 경계에서 성공이 시도보다 많아질 수 있어 1 로 자른다. */
    fun rate(success: Long, attempts: Long): Double? =
        if (attempts > 0) Math.min(1.0, success.toDouble() / attempts) else null

    private fun successRate(s: List<FunnelDto.DailyPoint>): Double? {
        var attempts = 0L
        var success = 0L
        for (d in s) {
            attempts += d.attempts
            success += d.success
        }
        return rate(success, attempts)
    }

    /** series(오래된 날 → 오늘)의 마지막 windowDays 일 vs 그 앞 windowDays 일 성공률 차이(%p). */
    fun priorPeriodDelta(series: List<FunnelDto.DailyPoint>, windowDays: Int): Double? {
        if (windowDays <= 0 || series.size < windowDays * 2) return null
        val n = series.size
        val cur = successRate(series.subList(n - windowDays, n))
        val prior = successRate(series.subList(n - windowDays * 2, n - windowDays))
        if (cur == null || prior == null) return null
        return (cur - prior) * 100
    }

    /** `(n).toFixed(digits)` */
    fun toFixed(x: Double, digits: Int): String {
        if (x < 0) return "-" + toFixed(-x, digits)
        return BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    }

    fun fmtPct(r: Double?, digits: Int): String = if (r == null) "—" else toFixed(r * 100, digits) + "%"

    fun fmtPct(r: Double?): String = fmtPct(r, 1)

    fun fmtDeltaPp(d: Double): String {
        val v = toFixed(Math.abs(d), 1)
        if ("0.0" == v) return "±0.0%p"
        return (if (d > 0) "+" else "-") + v + "%p"
    }
}
