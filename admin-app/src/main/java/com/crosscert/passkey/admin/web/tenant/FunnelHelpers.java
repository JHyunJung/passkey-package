package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.funnel.FunnelDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 퍼널 탭의 비율 계산(← pages/tenant/funnelHelpers.ts). 0/0 은 null(비율 없음)이며 숫자 모양은 JS 와 같다
 * (toFixed 는 double 의 정확한 이진 값을 반올림하므로 {@link BigDecimal#BigDecimal(double)} 로 맞춘다).
 */
public final class FunnelHelpers {

    private FunnelHelpers() {}

    private static final String[] LABELS = {"등록 시도", "등록 성공", "인증 시도", "인증 성공"};

    /** 비율은 값 없음이면 null. */
    public record Step(String label, long value, Double pctOfFirst, Double convFromPrev, long dropFromPrev) {}

    public record Drop(String from, String to, long lost, double rate) {}

    public record Summary(List<Step> steps, Double overallRate, Drop biggestDrop) {}

    public static Summary summary(FunnelDto.View f) {
        long[] values = {f.registration().attempts(), f.registration().success(), f.authentication().attempts(), f.authentication().success()};
        long first = values[0];
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            long v = values[i];
            Long prev = i > 0 ? values[i - 1] : null;
            steps.add(new Step(LABELS[i], v,
                    first > 0 ? (double) v / first : null,
                    prev != null && prev > 0 ? (double) v / prev : null,
                    prev != null ? Math.max(0, prev - v) : 0));
        }
        Drop biggest = null;
        for (int i = 1; i < steps.size(); i++) {
            long lost = steps.get(i).dropFromPrev();
            if (lost > 0 && (biggest == null || lost > biggest.lost())) {
                biggest = new Drop(steps.get(i - 1).label(), steps.get(i).label(), lost, (double) lost / values[i - 1]);
            }
        }
        return new Summary(steps, first > 0 ? (double) values[3] / first : null, biggest);
    }

    private static Double successRate(List<FunnelDto.DailyPoint> s) {
        long attempts = 0;
        long success = 0;
        for (FunnelDto.DailyPoint d : s) {
            attempts += d.attempts();
            success += d.success();
        }
        return attempts > 0 ? (double) success / attempts : null;
    }

    /** series(오래된 날 → 오늘)의 마지막 windowDays 일 vs 그 앞 windowDays 일 인증 성공률 차이(%p). */
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
