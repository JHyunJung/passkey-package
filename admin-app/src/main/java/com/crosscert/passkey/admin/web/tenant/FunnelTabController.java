package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.funnel.AdoptionController;
import com.crosscert.passkey.admin.funnel.AdoptionDto;
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
    private final AdoptionController adoption;
    private final Clock clock;

    public FunnelTabController(TenantAdminController tenants, FunnelController funnel, AdoptionController adoption, Clock clock) {
        this.tenants = tenants;
        this.funnel = funnel;
        this.adoption = adoption;
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

    public static final List<Map<String, Object>> SERIES_COLUMNS = List.of(
            col("day", "날짜", false), col("a", "시도", true), col("s", "성공", true), col("r", "성공률", true));
    private static final List<Map<String, Object>> FAILURE_COLUMNS = List.of(
            col("reason", "원인", false), col("meaning", "의미", false), col("n", "건수", true));

    /** credential_auth_event.failure_reason 코드 → [운영자용 이름, 의미]. 모르는 코드는 코드 그대로 보여준다. */
    private static final Map<String, String[]> FAILURE_LABELS = Map.of(
            CredentialAuthFailureReason.SIGNATURE_INVALID, new String[] {"서명 검증 실패",
                    "등록된 공개키로 서명을 확인하지 못했어요. 위조 시도나 데이터 손상일 수 있어요."},
            CredentialAuthFailureReason.SIGN_COUNT_REPLAY, new String[] {"서명 카운터 재사용",
                    "인증기 카운터가 이전 값보다 작거나 같아요. 패스키 복제가 의심돼요."});

    public static final List<Map<String, Object>> MONTH_COLUMNS = List.of(
            col("month", "월", false), col("auth", "인증 성공", true), col("mau", "활성 사용자", true), col("new", "신규 패스키", true));

    // ── 모델 ────────────────────────────────────────────────

    /** 도입·사용 지표 카드 1장. warn 이면 힌트를 경고색으로. */
    public record AdoptKpi(String label, String value, String hint, boolean warn) {}

    public record MonthView(String label, String authText, String mauText, String newText) {}

    /** 기기 분포 1줄. widthPct 는 패스키 수 / 전체 패스키 퍼센트 숫자(막대 너비). */
    public record DeviceView(String name, String countText, String shareText, double widthPct, String authText) {}

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
                regDelta = FunnelHelpers.priorPeriodDelta(orEmpty(d30.getRegistrationSeries()), 7);
                authDelta = FunnelHelpers.priorPeriodDelta(orEmpty(d30.getAuthenticationSeries()), 7);
            } catch (RuntimeException e) {
                if (denied(e)) throw e;
                log.debug("[funnel-tab] 30d comparison skipped: {}", e.getClass().getSimpleName());
            }
        }
        model.addAttribute("rateKpis", List.of(
                rateKpi("등록 성공률", data.getRegistration(), regDelta),
                rateKpi("인증 성공률", data.getAuthentication(), authDelta)));

        List<FunnelDto.FailureCount> fails = orEmpty(data.getAuthFailures());
        long failTotal = 0;
        for (FunnelDto.FailureCount f : fails) failTotal += f.getN();
        List<FailureView> failures = new ArrayList<>();
        List<String> failParts = new ArrayList<>();
        for (FunnelDto.FailureCount f : fails) {
            failures.add(new FailureView(f.getReason() == null ? "" : f.getReason(), failureLabel(f.getReason()), failureMeaning(f.getReason()), num(f.getN())));
            failParts.add(failureLabel(f.getReason()) + " " + num(f.getN()));
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
                seriesCard("auth", "일별 인증 시도와 성공", orEmpty(data.getAuthenticationSeries()), PAGE + tid + "/funnel.csv" + q + "&kind=auth"),
                seriesCard("reg", "일별 등록 시도와 성공", orEmpty(data.getRegistrationSeries()), PAGE + tid + "/funnel.csv" + q + "&kind=reg")));
        model.addAttribute("seriesColumns", SERIES_COLUMNS);
        model.addAttribute("csvSummary", PAGE + tid + "/funnel.csv" + q + "&kind=summary");
        model.addAttribute("csvFailures", PAGE + tid + "/funnel.csv" + q + "&kind=failures");
        addAdoption(model, tid, w, PAGE + tid + "/funnel.csv" + q);
        return TAB_VIEW;
    }

    /** 패스키 도입·사용 지표. 조회가 실패해도 탭의 나머지(성공률)는 그대로 보이고 이 영역만 오류 + 다시 시도. */
    private void addAdoption(Model model, UUID tid, int w, String csvBase) {
        AdoptionDto.View a;
        try {
            a = adoption.get(tid, w);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[funnel-tab] adoption load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("adoptError", e);
            return;
        }
        model.addAttribute("adoptError", null);
        Double single = a.getTotalUsers() > 0 ? (double) a.getSinglePasskeyUsers() / a.getTotalUsers() : null;
        model.addAttribute("adoptKpis", List.of(
                new AdoptKpi("활성 사용자", num(a.getActiveUsers()) + "명", "기간 중 인증 성공 · 사용된 패스키 " + num(a.getActiveCredentials()) + "개", false),
                new AdoptKpi("신규 패스키", num(a.getNewCredentials()) + "개", "기간 중 새로 등록", false),
                new AdoptKpi("누적 패스키", num(a.getTotalCredentials()) + "개", "사용자 " + num(a.getTotalUsers()) + "명", false),
                new AdoptKpi("패스키 1개뿐인 사용자", FunnelHelpers.fmtPct(single),
                        a.getTotalUsers() == 0 ? "사용자 없음" : num(a.getSinglePasskeyUsers()) + "명 / " + num(a.getTotalUsers()) + "명 · 기기를 잃으면 로그인할 수 없어요",
                        single != null && single >= 0.5)));

        model.addAttribute("months", monthViews(a.getMonths()));
        model.addAttribute("monthColumns", MONTH_COLUMNS);
        model.addAttribute("devices", deviceViews(a.getDevices()));
        model.addAttribute("csvMonthly", csvBase + "&kind=monthly");
        model.addAttribute("csvDevices", csvBase + "&kind=devices");
    }

    /** 월간 사용량 표의 줄(플랫폼 현황도 같은 모양으로 쓴다). */
    public static List<MonthView> monthViews(List<AdoptionDto.Month> list) {
        List<MonthView> months = new ArrayList<>();
        for (AdoptionDto.Month m : orEmpty(list)) {
            months.add(new MonthView(monthLabel(m), num(m.getAuthSuccess()), num(m.getActiveUsers()) + "명", num(m.getNewCredentials())));
        }
        return months;
    }

    /** 기기 분포 줄: 비율은 전체 패스키 대비(플랫폼 현황도 같은 모양으로 쓴다). */
    public static List<DeviceView> deviceViews(List<AdoptionDto.Device> list) {
        List<DeviceView> devices = new ArrayList<>();
        long total = 0;
        for (AdoptionDto.Device d : orEmpty(list)) total += d.getCredentials();
        for (AdoptionDto.Device d : orEmpty(list)) {
            Double share = total > 0 ? (double) d.getCredentials() / total : null;
            devices.add(new DeviceView(d.getName(), num(d.getCredentials()) + "개", FunnelHelpers.fmtPct(share),
                    pct(share == null ? 0 : share * 100), "기간 인증 " + num(d.getAuthSuccess()) + "건"));
        }
        return devices;
    }

    /** "2026-11" → "2026년 11월", 이번 달은 "(진행 중)". */
    public static String monthLabel(AdoptionDto.Month m) {
        String s = m.getMonth();
        String label = s != null && s.matches("\\d{4}-\\d{2}")
                ? s.substring(0, 4) + "년 " + Integer.parseInt(s.substring(5)) + "월" : String.valueOf(s);
        return m.getCurrent() ? label + " (진행 중)" : label;
    }

    private static RateKpi rateKpi(String label, FunnelDto.Stage s, Double delta) {
        if (s.getAttempts() == 0) return new RateKpi(label, "—", "시도 없음");
        String hint = "성공 " + num(s.getSuccess()) + " / 시도 " + num(s.getAttempts());
        if (delta != null) hint += " · 직전 7일 대비 " + FunnelHelpers.fmtDeltaPp(delta);
        return new RateKpi(label, FunnelHelpers.fmtPct(FunnelHelpers.rate(s.getSuccess(), s.getAttempts())), hint);
    }

    public static SeriesCard seriesCard(String key, String title, List<FunnelDto.DailyPoint> series, String csv) {
        long smax = 1;
        for (FunnelDto.DailyPoint p : series) smax = Math.max(smax, Math.max(p.getAttempts(), p.getSuccess()));
        List<DayView> days = new ArrayList<>();
        for (FunnelDto.DailyPoint p : series) {
            String rate = FunnelHelpers.fmtPct(FunnelHelpers.rate(p.getSuccess(), p.getAttempts()));
            days.add(new DayView(p.getDay(), p.getAttempts(), p.getSuccess(), num(p.getAttempts()), num(p.getSuccess()), rate,
                    pct((double) p.getAttempts() / smax * 100), pct((double) p.getSuccess() / smax * 100),
                    dateText(p.getDay()), "시도 " + num(p.getAttempts()) + " · 성공 " + num(p.getSuccess()) + " · " + rate));
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
        String today = KstFormat.kstToday(clock.instant());
        if ("monthly".equals(kind) || "devices".equals(kind)) return adoptionCsv(v, w, kind, today);
        FunnelDto.View data = funnel.get(v.id(), w);
        byte[] csv;
        String filename;
        switch (kind) {
            case "summary" -> {
                List<List<String>> rows = new ArrayList<>();
                rows.add(summaryRow("registration", "등록", data.getRegistration()));
                rows.add(summaryRow("authentication", "인증", data.getAuthentication()));
                csv = CsvWriter.write(List.of("ceremony", "label", "attempts", "success", "successRate"), rows);
                filename = "auth-status-" + v.slug() + "-" + w + "d-" + today + ".csv";
            }
            case "auth", "reg" -> {
                List<List<String>> rows = new ArrayList<>();
                for (FunnelDto.DailyPoint p : orEmpty("auth".equals(kind) ? data.getAuthenticationSeries() : data.getRegistrationSeries())) {
                    Double r = FunnelHelpers.rate(p.getSuccess(), p.getAttempts());
                    rows.add(List.of(p.getDay(), String.valueOf(p.getAttempts()), String.valueOf(p.getSuccess()), r == null ? "" : FunnelHelpers.toFixed(r, 4)));
                }
                csv = CsvWriter.write(List.of("day", "attempts", "success", "successRate"), rows);
                filename = ("auth".equals(kind) ? "auth-daily-" : "registration-daily-") + v.slug() + "-" + w + "d-" + today + ".csv";
            }
            case "failures" -> {
                List<List<String>> rows = new ArrayList<>();
                for (FunnelDto.FailureCount f : orEmpty(data.getAuthFailures())) {
                    rows.add(List.of(f.getReason() == null ? "" : f.getReason(), failureLabel(f.getReason()), String.valueOf(f.getN())));
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

    /** 도입·사용 CSV 2종: 월간 사용량(최근 3개월), 기기 분포. */
    private ResponseEntity<byte[]> adoptionCsv(TenantAdminDto.TenantView v, int w, String kind, String today) {
        AdoptionDto.View a = adoption.get(v.id(), w);
        List<List<String>> rows = new ArrayList<>();
        byte[] csv;
        String filename;
        if ("monthly".equals(kind)) {
            for (AdoptionDto.Month m : orEmpty(a.getMonths())) {
                rows.add(List.of(m.getMonth(), m.getCurrent() ? "Y" : "N", String.valueOf(m.getAuthSuccess()), String.valueOf(m.getActiveUsers()), String.valueOf(m.getNewCredentials())));
            }
            csv = CsvWriter.write(List.of("month", "inProgress", "authSuccess", "activeUsers", "newPasskeys"), rows);
            filename = "monthly-usage-" + v.slug() + "-" + today + ".csv";
        } else {
            for (AdoptionDto.Device d : orEmpty(a.getDevices())) {
                rows.add(List.of(d.getName(), String.valueOf(d.getCredentials()), String.valueOf(d.getAuthSuccess())));
            }
            csv = CsvWriter.write(List.of("authenticator", "passkeys", "authSuccessInWindow"), rows);
            filename = "devices-" + v.slug() + "-" + w + "d-" + today + ".csv";
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    private static List<String> summaryRow(String code, String label, FunnelDto.Stage s) {
        Double r = FunnelHelpers.rate(s.getSuccess(), s.getAttempts());
        return List.of(code, label, String.valueOf(s.getAttempts()), String.valueOf(s.getSuccess()), r == null ? "" : FunnelHelpers.toFixed(r, 4));
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
        if (s.size() == 2) return List.of(s.get(0).getDay(), "오늘");
        return List.of(s.get(0).getDay(), s.get((s.size() - 1) / 2).getDay(), "오늘");
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
