package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.funnel.FunnelController;
import com.crosscert.passkey.admin.funnel.FunnelDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.CsvWriter;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason;
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

/**
 * Tenant 상세의 "인증 현황" 탭(URL 키는 기존 funnel 그대로). 등록·인증 성공률, 일별 시도와 성공, 인증 실패 원인을 보여준다.
 *
 * <p>등록과 인증은 서로 다른 흐름이라(한 번 등록한 사용자가 여러 번 인증) 한 줄 퍼널로 잇지 않고 따로 계산한다.
 * 성공하지 못한 시도에는 사용자 취소·시간 초과도 섞여 있으므로 절대값보다 추이를 보는 지표다.
 *
 * <p>모든 엔드포인트가 {@link TenantTabs#load} 를 먼저 부른다. 막대는 서버가 그린 div 이고 높이는 숫자만 th:style 에 들어간다
 * — 색은 CSS 클래스. 실패 원인 코드·이름은 th:text/th:title 로만 쓴다.
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
    private static final List<Map<String, Object>> FAILURE_COLUMNS = List.of(
            col("reason", "원인", false), col("meaning", "의미", false), col("n", "건수", true));

    /** credential_auth_event.failure_reason 코드 → [운영자용 이름, 의미]. 모르는 코드는 코드 그대로 보여준다. */
    private static final Map<String, String[]> FAILURE_LABELS = Map.of(
            CredentialAuthFailureReason.SIGNATURE_INVALID, new String[] {"서명 검증 실패",
                    "등록된 공개키로 서명을 확인하지 못했어요. 위조 시도나 데이터 손상일 수 있어요."},
            CredentialAuthFailureReason.SIGN_COUNT_REPLAY, new String[] {"서명 카운터 재사용",
                    "인증기 카운터가 이전 값보다 작거나 같아요. 패스키 복제가 의심돼요."});

    // ── 모델 ────────────────────────────────────────────────

    /** 성공률 지표 카드 1장(등록·인증). */
    public record RateKpi(String label, String value, String hint) {}

    /** dateText·tipText 는 막대에 마우스를 올리면 뜨는 말풍선 문구(예: "10월 29일", "시도 10 · 성공 9 · 90.0%"). */
    public record DayView(String day, long attempts, long success, String attemptsText, String successText, String rateText,
                          double attemptsPct, double successPct, String dateText, String tipText) {}

    /** 일별 시도·성공 카드 1장(등록·인증). key 는 CSV kind·요소 구분용. */
    public record SeriesCard(String key, String title, List<DayView> days, List<String> axis, boolean dense, String aria, String csv) {}

    /** 실패 원인 1줄. code 는 원래 코드(title 로만 노출). */
    public record FailureView(String code, String label, String meaning, String nText) {}

    static String failureLabel(String code) {
        if (code == null) return "원인 미기록";
        String[] l = FAILURE_LABELS.get(code);
        return l == null ? code : l[0];
    }

    static String failureMeaning(String code) {
        String[] l = code == null ? null : FAILURE_LABELS.get(code);
        return l == null ? "" : l[1];
    }

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

        // 직전 기간 대비: 7d 에서만. 30일 series 로 최근 7일 vs 그 앞 7일. 실패하면 비교 문구만 생략.
        Double regDelta = null;
        Double authDelta = null;
        if (w == 7) {
            try {
                FunnelDto.View d30 = funnel.get(tid, 30);
                regDelta = FunnelHelpers.priorPeriodDelta(orEmpty(d30.registrationSeries()), 7);
                authDelta = FunnelHelpers.priorPeriodDelta(orEmpty(d30.authenticationSeries()), 7);
            } catch (RuntimeException e) {
                if (denied(e)) throw e;
                log.debug("[funnel-tab] 30d comparison skipped: {}", e.getClass().getSimpleName());
            }
        }
        model.addAttribute("rateKpis", List.of(
                rateKpi("등록 성공률", data.registration(), regDelta),
                rateKpi("인증 성공률", data.authentication(), authDelta)));

        List<FunnelDto.FailureCount> fails = orEmpty(data.authFailures());
        long failTotal = 0;
        for (FunnelDto.FailureCount f : fails) failTotal += f.n();
        List<FailureView> failures = new ArrayList<>();
        List<String> failParts = new ArrayList<>();
        for (FunnelDto.FailureCount f : fails) {
            failures.add(new FailureView(f.reason() == null ? "" : f.reason(), failureLabel(f.reason()), failureMeaning(f.reason()), num(f.n())));
            failParts.add(failureLabel(f.reason()) + " " + num(f.n()));
        }
        model.addAttribute("failTotal", num(failTotal) + "건");
        model.addAttribute("failHint", failTotal == 0 ? "실패 없음" : String.join(" · ", failParts));
        model.addAttribute("failAlert", failTotal > 0);
        model.addAttribute("failures", failures);
        model.addAttribute("failureColumns", FAILURE_COLUMNS);

        model.addAttribute("period", PERIOD_LABEL.get(w));
        long startMs = now.toEpochMilli() - (w == 1 ? 1 : w - 1) * 86_400_000L;
        model.addAttribute("periodHint", KstFormat.kstDate(Instant.ofEpochMilli(startMs)) + " – " + KstFormat.kstDate(now));

        String q = "?win=" + w;
        model.addAttribute("seriesCards", List.of(
                seriesCard("auth", "일별 인증 시도와 성공", orEmpty(data.authenticationSeries()), PAGE + tid + "/funnel.csv" + q + "&kind=auth"),
                seriesCard("reg", "일별 등록 시도와 성공", orEmpty(data.registrationSeries()), PAGE + tid + "/funnel.csv" + q + "&kind=reg")));
        model.addAttribute("seriesColumns", SERIES_COLUMNS);
        model.addAttribute("csvSummary", PAGE + tid + "/funnel.csv" + q + "&kind=summary");
        model.addAttribute("csvFailures", PAGE + tid + "/funnel.csv" + q + "&kind=failures");
        return TAB_VIEW;
    }

    private static RateKpi rateKpi(String label, FunnelDto.Stage s, Double delta) {
        if (s.attempts() == 0) return new RateKpi(label, "—", "시도 없음");
        String hint = "성공 " + num(s.success()) + " / 시도 " + num(s.attempts());
        if (delta != null) hint += " · 직전 7일 대비 " + FunnelHelpers.fmtDeltaPp(delta);
        return new RateKpi(label, FunnelHelpers.fmtPct(FunnelHelpers.rate(s.success(), s.attempts())), hint);
    }

    private static SeriesCard seriesCard(String key, String title, List<FunnelDto.DailyPoint> series, String csv) {
        long smax = 1;
        for (FunnelDto.DailyPoint p : series) smax = Math.max(smax, Math.max(p.attempts(), p.success()));
        List<DayView> days = new ArrayList<>();
        for (FunnelDto.DailyPoint p : series) {
            String rate = FunnelHelpers.fmtPct(FunnelHelpers.rate(p.success(), p.attempts()));
            days.add(new DayView(p.day(), p.attempts(), p.success(), num(p.attempts()), num(p.success()), rate,
                    pct((double) p.attempts() / smax * 100), pct((double) p.success() / smax * 100),
                    dateText(p.day()), "시도 " + num(p.attempts()) + " · 성공 " + num(p.success()) + " · " + rate));
        }
        return new SeriesCard(key, title, days, axisLabels(series), series.size() > 14,
                title + " 막대 차트, " + series.size() + "일", csv);
    }

    /** "MM-DD" → "M월 D일". 모양이 다르면 그대로. */
    static String dateText(String day) {
        if (day == null || !day.matches("\\d{2}-\\d{2}")) return day;
        return Integer.parseInt(day.substring(0, 2)) + "월 " + Integer.parseInt(day.substring(3)) + "일";
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    /** CSV 4종: 요약(내보내기), 일별 인증, 일별 등록, 인증 실패 원인. 코드는 그대로 두고 한글 이름 열을 함께 쓴다. */
    @GetMapping(PAGE + "{id}/funnel.csv")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(name = "win", defaultValue = "7") String win,
                                         @RequestParam(name = "kind", defaultValue = "summary") String kind) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        int w = window(win);
        FunnelDto.View data = funnel.get(v.id(), w);
        String today = KstFormat.kstToday(clock.instant());
        byte[] csv;
        String filename;
        switch (kind) {
            case "summary" -> {
                List<List<String>> rows = new ArrayList<>();
                rows.add(summaryRow("registration", "등록", data.registration()));
                rows.add(summaryRow("authentication", "인증", data.authentication()));
                csv = CsvWriter.write(List.of("ceremony", "label", "attempts", "success", "successRate"), rows);
                filename = "auth-status-" + v.slug() + "-" + w + "d-" + today + ".csv";
            }
            case "auth", "reg" -> {
                List<List<String>> rows = new ArrayList<>();
                for (FunnelDto.DailyPoint p : orEmpty("auth".equals(kind) ? data.authenticationSeries() : data.registrationSeries())) {
                    Double r = FunnelHelpers.rate(p.success(), p.attempts());
                    rows.add(List.of(p.day(), String.valueOf(p.attempts()), String.valueOf(p.success()), r == null ? "" : FunnelHelpers.toFixed(r, 4)));
                }
                csv = CsvWriter.write(List.of("day", "attempts", "success", "successRate"), rows);
                filename = ("auth".equals(kind) ? "auth-daily-" : "registration-daily-") + v.slug() + "-" + w + "d-" + today + ".csv";
            }
            case "failures" -> {
                List<List<String>> rows = new ArrayList<>();
                for (FunnelDto.FailureCount f : orEmpty(data.authFailures())) {
                    rows.add(List.of(f.reason() == null ? "" : f.reason(), failureLabel(f.reason()), String.valueOf(f.n())));
                }
                csv = CsvWriter.write(List.of("reason", "label", "n"), rows);
                filename = "auth-failures-" + v.slug() + "-" + w + "d-" + today + ".csv";
            }
            default -> throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "알 수 없는 내보내기");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    private static List<String> summaryRow(String code, String label, FunnelDto.Stage s) {
        Double r = FunnelHelpers.rate(s.success(), s.attempts());
        return List.of(code, label, String.valueOf(s.attempts()), String.valueOf(s.success()), r == null ? "" : FunnelHelpers.toFixed(r, 4));
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
