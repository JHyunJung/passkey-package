package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.funnel.FunnelDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 인증 현황 탭의 성공률 계산. 0/0 은 null(비율 없음)이며 숫자 모양은 원본 JS 와 같다
 * (toFixed 는 double 의 정확한 이진 값을 반올림하므로 {@link BigDecimal#BigDecimal(double)} 로 맞춘다).
 */
public final class FunnelHelpers {

    private FunnelHelpers() {}

    /** 성공률(0~1). 시도 0 이면 null(비율 없음). 기간 경계에서 성공이 시도보다 많아질 수 있어 1 로 자른다. */
    public static Double rate(long success, long attempts) {
        return attempts > 0 ? Math.min(1.0, (double) success / attempts) : null;
    }

    private static Double successRate(List<FunnelDto.DailyPoint> s) {
        long attempts = 0;
        long success = 0;
        for (FunnelDto.DailyPoint d : s) {
            attempts += d.attempts();
            success += d.success();
        }
        return rate(success, attempts);
    }

    /** series(오래된 날 → 오늘)의 마지막 windowDays 일 vs 그 앞 windowDays 일 성공률 차이(%p). */
    public static Double priorPeriodDelta(List<FunnelDto.DailyPoint> series, int windowDays) {
        if (windowDays <= 0 || series.size() < windowDays * 2) return null;
        int n = series.size();
        Double cur = successRate(series.subList(n - windowDays, n));
        Double prior = successRate(series.subList(n - windowDays * 2, n - windowDays));
        if (cur == null || prior == null) return null;
        return (cur - prior) * 100;
    }

    /** {@code (n).toFixed(digits)} */
    public static String toFixed(double x, int digits) {
        if (x < 0) return "-" + toFixed(-x, digits);
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }

    public static String fmtPct(Double r, int digits) {
        return r == null ? "—" : toFixed(r * 100, digits) + "%";
    }

    public static String fmtPct(Double r) {
        return fmtPct(r, 1);
    }

    public static String fmtDeltaPp(double d) {
        String v = toFixed(Math.abs(d), 1);
        if ("0.0".equals(v)) return "±0.0%p";
        return (d > 0 ? "+" : "-") + v + "%p";
    }
}
