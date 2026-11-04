package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.funnel.FunnelController;
import com.crosscert.passkey.admin.funnel.FunnelDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.CsvWriter;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant 상세의 퍼널 탭(← pages/tenant/FunnelTab.tsx, funnelHelpers.ts, api/funnel.ts).
 *
 * <p>모든 엔드포인트가 {@link TenantTabs#load} 를 먼저 부른다. 막대는 서버가 그린 div 이고 너비·높이는 {@link FunnelHelpers} 의 비율에서 온 숫자
 * (th:style 에는 숫자만)다 — 색은 CSS 클래스. 이벤트 타입 이름은 th:text 로만 쓴다.
 * 기간 전환·다시 시도는 {@code #funnel-root} 만 교체한다.
 */
@Slf4j
@Controller
public class FunnelTabController {

    static final String FRAG = "/admin/_/tenants/";
    static final String PAGE = "/admin/tenants/";
    static final String TAB_VIEW = "admin/tenant/funnel :: tab";

    static final List<Integer> WINDOWS = List.of(1, 7, 30);
    static final Map<Integer, String> WIN_LABEL = Map.of(1, "24h", 7, "7d", 30, "30d");
    static final Map<Integer, String> PERIOD_LABEL = Map.of(1, "최근 24시간", 7, "최근 7일", 30, "최근 30일");
    static final int DIST_COLORS = 5;
    private static final Pattern FAIL = Pattern.compile("FAIL");

    private final TenantAdminController tenants;
    private final FunnelController funnel;
    private final Clock clock;

    public FunnelTabController(TenantAdminController tenants, FunnelController funnel, Clock clock) {
        this.tenants = tenants;
        this.funnel = funnel;
        this.clock = clock;
    }

    private static Map<String, Object> col(String key, String header, boolean right) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("header", header);
        m.put("align", right ? "right" : null);
        m.put("width", null);
        m.put("srOnly", null);
        return m;
    }

    private static final List<Map<String, Object>> SERIES_COLUMNS = List.of(
            col("day", "날짜", false), col("a", "시도", true), col("s", "성공", true), col("r", "성공률", true));
    private static final List<Map<String, Object>> EVENT_COLUMNS = List.of(
            col("type", "이벤트 타입", false), col("n", "건수", true), col("p", "비율", true));

    // ── 모델 ────────────────────────────────────────────────

    /** 단계 막대. heightPct 는 숫자(소수 둘째 자리까지). */
    public record StepView(String label, String valueText, String pctText, String convText, String dropText, double heightPct, String colorClass) {}

    public record DayView(String day, long attempts, long success, String attemptsText, String successText, String rateText,
                          double attemptsPct, double successPct, String title) {}

    public record EventView(String type, String nText, String pctText, double widthPct, String colorClass) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/funnel")
    public String tab(@PathVariable String id, @RequestParam(name = "win", defaultValue = "7") String win, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        UUID tid = v.id();
        int w = window(win);
        Instant now = clock.instant();
        model.addAttribute("tid", tid.toString());
        model.addAttribute("win", String.valueOf(w));

        List<Map<String, Object>> options = new ArrayList<>();
        for (int k : WINDOWS) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", String.valueOf(k));
            o.put("label", WIN_LABEL.get(k));
            o.put("title", null);
            o.put("attrs", get(FRAG + tid + "/tab/funnel?win=" + k));
            options.add(o);
        }
        model.addAttribute("segOptions", options);
        Map<String, Object> retry = get(FRAG + tid + "/tab/funnel?win=" + w);
        model.addAttribute("retryAttrs", retry);

        FunnelDto.View data;
        try {
            data = funnel.get(tid, w);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[funnel-tab] load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("loadError", e);
            model.addAttribute("hasData", false);
            return TAB_VIEW;
        }
        model.addAttribute("loadError", null);
        model.addAttribute("hasData", true);

        FunnelHelpers.Summary s = FunnelHelpers.summary(data);
        // 직전 기간 대비: 7d 에서만. 30일 series 로 최근 7일 vs 그 앞 7일. 실패하면 비교 줄만 생략.
        Double delta = null;
        if (w == 7) {
            try {
                delta = FunnelHelpers.priorPeriodDelta(funnel.get(tid, 30).series(), 7);
            } catch (RuntimeException e) {
                if (denied(e)) throw e;
                log.debug("[funnel-tab] 30d comparison skipped: {}", e.getClass().getSimpleName());
            }
        }

        model.addAttribute("kpiRate", FunnelHelpers.fmtPct(s.overallRate()));
        model.addAttribute("kpiRateHint", delta == null ? null : "직전 7일 대비 " + FunnelHelpers.fmtDeltaPp(delta) + " (인증 성공률)");
        FunnelHelpers.Drop d = s.biggestDrop();
        model.addAttribute("kpiDrop", d == null ? "—" : d.from() + " → " + d.to());
        model.addAttribute("kpiDropHint", d == null ? "이탈 없음" : "이탈 " + num(d.lost()) + "건 · " + FunnelHelpers.fmtPct(d.rate()));
        model.addAttribute("period", PERIOD_LABEL.get(w));
        long startMs = now.toEpochMilli() - (w == 1 ? 1 : w - 1) * 86_400_000L;
        model.addAttribute("periodHint", KstFormat.kstDate(Instant.ofEpochMilli(startMs)) + " – " + KstFormat.kstDate(now));

        long max = 1;
        for (FunnelHelpers.Step st : s.steps()) max = Math.max(max, st.value());
        List<StepView> steps = new ArrayList<>();
        for (int i = 0; i < s.steps().size(); i++) {
            FunnelHelpers.Step st = s.steps().get(i);
            steps.add(new StepView(st.label(), num(st.value()) + "건 · " + FunnelHelpers.fmtPct(st.pctOfFirst(), 0),
                    FunnelHelpers.fmtPct(st.pctOfFirst(), 0), FunnelHelpers.fmtPct(st.convFromPrev()),
                    i > 0 && st.dropFromPrev() > 0 ? "이탈 " + num(st.dropFromPrev()) + "건" : null,
                    pct((double) st.value() / max * 100), "fn-s" + i));
        }
        model.addAttribute("steps", steps);

        // 일별 인증 시도 vs 성공
        List<FunnelDto.DailyPoint> series = data.series() == null ? List.of() : data.series();
        long smax = 1;
        for (FunnelDto.DailyPoint p : series) smax = Math.max(smax, p.attempts());
        List<DayView> days = new ArrayList<>();
        for (FunnelDto.DailyPoint p : series) {
            days.add(new DayView(p.day(), p.attempts(), p.success(), num(p.attempts()), num(p.success()),
                    FunnelHelpers.fmtPct(p.attempts() > 0 ? (double) p.success() / p.attempts() : null),
                    pct((double) p.attempts() / smax * 100), pct((double) p.success() / smax * 100),
                    p.day() + " · 시도 " + p.attempts() + " · 성공 " + p.success()));
        }
        model.addAttribute("days", days);
        model.addAttribute("seriesColumns", SERIES_COLUMNS);
        model.addAttribute("eventColumns", EVENT_COLUMNS);
        model.addAttribute("dense", series.size() > 14);
        model.addAttribute("axis", axisLabels(series));
        model.addAttribute("seriesAria", "일별 인증 시도와 성공 막대 차트, " + series.size() + "일");

        // 이벤트 타입별 분포
        List<FunnelDto.EventCount> by = data.byEventType() == null ? List.of() : data.byEventType();
        long total = 0;
        for (FunnelDto.EventCount e : by) total += e.n();
        long safeTotal = total > 0 ? total : 1;
        int k = 0;
        List<EventView> events = new ArrayList<>();
        for (FunnelDto.EventCount e : by) {
            String color = FAIL.matcher(e.type()).find() ? "fn-bad" : "fn-c" + (k++ % DIST_COLORS);
            events.add(new EventView(e.type(), num(e.n()), FunnelHelpers.fmtPct(total > 0 ? (double) e.n() / total : null),
                    pct((double) e.n() / safeTotal * 100), color));
        }
        model.addAttribute("events", events);
        model.addAttribute("eventsAria", "이벤트 타입 분포, 총 " + num(total) + "건");

        String q = "?win=" + w;
        model.addAttribute("csvSteps", PAGE + tid + "/funnel.csv" + q + "&kind=steps");
        model.addAttribute("csvSeries", PAGE + tid + "/funnel.csv" + q + "&kind=series");
        model.addAttribute("csvEvents", PAGE + tid + "/funnel.csv" + q + "&kind=events");
        return TAB_VIEW;
    }

    /** 원본 downloadCsv 3종: 단계(내보내기), 일별 시리즈, 이벤트 분포. */
    @GetMapping(PAGE + "{id}/funnel.csv")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(name = "win", defaultValue = "7") String win,
                                         @RequestParam(name = "kind", defaultValue = "steps") String kind) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        int w = window(win);
        FunnelDto.View data = funnel.get(v.id(), w);
        String today = KstFormat.kstToday(clock.instant());
        byte[] csv;
        String filename;
        switch (kind) {
            case "steps" -> {
                List<List<String>> rows = new ArrayList<>();
                for (FunnelHelpers.Step st : FunnelHelpers.summary(data).steps()) {
                    rows.add(List.of(st.label(), String.valueOf(st.value()),
                            st.convFromPrev() == null ? "" : FunnelHelpers.toFixed(st.convFromPrev(), 4), String.valueOf(st.dropFromPrev())));
                }
                csv = CsvWriter.write(List.of("step", "count", "conversionFromPrev", "dropFromPrev"), rows);
                filename = "funnel-" + v.slug() + "-" + w + "d-" + today + ".csv";
            }
            case "series" -> {
                List<List<String>> rows = new ArrayList<>();
                for (FunnelDto.DailyPoint p : data.series() == null ? List.<FunnelDto.DailyPoint>of() : data.series()) rows.add(List.of(p.day(), String.valueOf(p.attempts()), String.valueOf(p.success())));
                csv = CsvWriter.write(List.of("day", "attempts", "success"), rows);
                filename = "funnel-series-" + v.slug() + "-" + today + ".csv";
            }
            case "events" -> {
                List<List<String>> rows = new ArrayList<>();
                for (FunnelDto.EventCount e : data.byEventType() == null ? List.<FunnelDto.EventCount>of() : data.byEventType()) rows.add(List.of(e.type(), String.valueOf(e.n())));
                csv = CsvWriter.write(List.of("type", "n"), rows);
                filename = "funnel-events-" + v.slug() + "-" + today + ".csv";
            }
            default -> throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "알 수 없는 내보내기");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // ── 도우미 ──────────────────────────────────────────────

    static int window(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            return WINDOWS.contains(n) ? n : 7;
        } catch (RuntimeException e) {
            return 7;
        }
    }

    /** SeriesCard axisLabels */
    static List<String> axisLabels(List<FunnelDto.DailyPoint> s) {
        if (s.isEmpty()) return List.of();
        if (s.size() == 1) return List.of("오늘");
        if (s.size() == 2) return List.of(s.get(0).day(), "오늘");
        return List.of(s.get(0).day(), s.get((s.size() - 1) / 2).day(), "오늘");
    }

    /** CSS 에 쓰는 퍼센트 숫자(소수 둘째 자리, 지수 표기 없음). */
    static double pct(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static boolean denied(RuntimeException e) {
        return e instanceof AccessDeniedException
                || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.ACCESS_DENIED);
    }

    private static Map<String, Object> get(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", url);
        return m;
    }

    private static String num(long n) {
        return String.format(Locale.KOREA, "%,d", n);
    }
}
