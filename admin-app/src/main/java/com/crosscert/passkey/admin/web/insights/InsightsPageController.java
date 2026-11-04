package com.crosscert.passkey.admin.web.insights;

import com.crosscert.passkey.admin.funnel.AdoptionDto;
import com.crosscert.passkey.admin.funnel.FunnelDto;
import com.crosscert.passkey.admin.insights.PlatformInsightsController;
import com.crosscert.passkey.admin.insights.PlatformInsightsDto;
import com.crosscert.passkey.admin.web.HomeRedirectController;
import com.crosscert.passkey.admin.web.format.CsvWriter;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.shell.Crumbs;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.tenant.FunnelHelpers;
import com.crosscert.passkey.admin.web.tenant.FunnelTabController;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 플랫폼 전체 현황 화면. PLATFORM 전용 — RP_ADMIN 은 전체 페이지에서 자기 홈으로 보내고(활동 화면과 같은 규칙) 조각·CSV 는 403.
 * 전체 페이지 {@code GET /admin/insights}, 기간·정렬 전환 {@code GET /admin/_/insights}(#insights-root 교체),
 * CSV {@code GET /admin/insights.csv?kind=tenants|monthly|devices}. 카드 모양·계산은 테넌트 인증 현황 탭과 같은 조각·도우미를 쓴다.
 */
@Slf4j
@Controller
public class InsightsPageController {

    static final String PAGE_PATH = "/admin/insights";
    static final String FRAG_PATH = "/admin/_/insights";
    static final String CSV_PATH = "/admin/insights.csv";
    static final String PAGE_VIEW = "admin/insights/page";
    static final String ROOT_VIEW = "admin/insights/page :: root";

    static final List<Integer> WINDOWS = List.of(1, 7, 30);
    static final Map<Integer, String> WIN_LABEL = Map.of(1, "24h", 7, "7d", 30, "30d");
    static final Map<Integer, String> PERIOD_LABEL = Map.of(1, "최근 24시간", 7, "최근 7일", 30, "최근 30일");

    /** 정렬 키 → [머리글, 값]. 모두 큰 값 먼저. */
    static final Map<String, String> SORT_LABEL = linked(
            "users", "활성 사용자", "auth", "인증 성공", "rate", "인증 성공률", "reg", "등록 성공률",
            "risk", "위험 인증", "creds", "패스키", "single", "1개뿐 비율");

    private final PlatformInsightsController insights;
    private final Clock clock;

    public InsightsPageController(PlatformInsightsController insights, Clock clock) {
        this.insights = insights;
        this.clock = clock;
    }

    // ── 모델 ────────────────────────────────────────────────

    public record AlertView(String kindLabel, String badgeClass, String name, String href, String message) {}

    public record TenantView(String name, String slug, String href, String statusLabel, String statusClass,
                             String usersText, String authText, String authRateText, String regRateText,
                             String riskText, boolean riskWarn, String credsText, String singleText) {}

    public record SortHeader(String key, String label, boolean active, Map<String, Object> attrs) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(PAGE_PATH)
    public String page(@RequestParam(name = "win", defaultValue = "7") String win,
                       @RequestParam(name = "sort", defaultValue = "users") String sort, Model model) {
        CurrentAdmin me = me(model);
        model.addAttribute("breadcrumbs", Crumbs.insights());
        if (!me.platform()) return "redirect:" + HomeRedirectController.homePath(me);
        populate(model, window(win), sort(sort));
        return PAGE_VIEW;
    }

    @GetMapping(FRAG_PATH)
    public String root(@RequestParam(name = "win", defaultValue = "7") String win,
                       @RequestParam(name = "sort", defaultValue = "users") String sort, Model model) {
        if (!me(model).platform()) throw new AccessDeniedException("PLATFORM_OPERATOR only");
        populate(model, window(win), sort(sort));
        return ROOT_VIEW;
    }

    private void populate(Model model, int w, String sort) {
        model.addAttribute("win", String.valueOf(w));
        model.addAttribute("sort", sort);
        List<Map<String, Object>> options = new ArrayList<>();
        for (int k : WINDOWS) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", String.valueOf(k));
            o.put("label", WIN_LABEL.get(k));
            o.put("title", null);
            o.put("attrs", get(FRAG_PATH + "?win=" + k + "&sort=" + sort));
            options.add(o);
        }
        model.addAttribute("segOptions", options);
        model.addAttribute("retryAttrs", get(FRAG_PATH + "?win=" + w + "&sort=" + sort));

        PlatformInsightsDto.View v;
        try {
            v = insights.get(w);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[insights] load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("loadError", e);
            model.addAttribute("hasData", false);
            return;
        }
        model.addAttribute("loadError", null);
        model.addAttribute("hasData", true);
        String period = PERIOD_LABEL.get(w);
        model.addAttribute("period", period);

        PlatformInsightsDto.Totals t = v.totals();
        Double authRate = FunnelHelpers.rate(t.authSuccess(), t.authAttempts());
        Double regRate = FunnelHelpers.rate(t.regSuccess(), t.regAttempts());
        Double single = t.users() > 0 ? (double) t.singlePasskeyUsers() / t.users() : null;
        model.addAttribute("kpis", List.of(
                new FunnelTabController.AdoptKpi("활성 테넌트", num(t.activeTenants()) + "개", "전체 " + num(t.tenants()) + "개", false),
                new FunnelTabController.AdoptKpi("활성 사용자", num(t.activeUsers()) + "명", period + " 인증 성공 사용자", false),
                new FunnelTabController.AdoptKpi("인증 성공", num(t.authSuccess()) + "건", "시도 " + num(t.authAttempts()) + "건", false),
                new FunnelTabController.AdoptKpi("인증 성공률", FunnelHelpers.fmtPct(authRate),
                        "등록 성공률 " + FunnelHelpers.fmtPct(regRate), false),
                new FunnelTabController.AdoptKpi("차단된 위험 인증", num(t.risk()) + "건", t.risk() == 0 ? "위험 인증 없음" : period + " 검증 단계에서 거절", t.risk() > 0),
                new FunnelTabController.AdoptKpi("누적 패스키", num(t.credentials()) + "개",
                        "사용자 " + num(t.users()) + "명 · 1개뿐 " + FunnelHelpers.fmtPct(single), false)));

        List<AlertView> alerts = new ArrayList<>();
        for (PlatformInsightsDto.Alert a : v.alerts()) {
            String[] k = switch (a.kind()) {
                case "RISK" -> new String[] {"위험 인증", "badge--danger"};
                case "RATE_DROP" -> new String[] {"성공률 하락", "badge--warning"};
                default -> new String[] {"패스키 1개뿐", "badge--info"};
            };
            alerts.add(new AlertView(k[0], k[1], a.name(), tenantHref(a.tenantId().toString()), a.message()));
        }
        model.addAttribute("alerts", alerts);
        PlatformInsightsDto.Thresholds th = v.thresholds();
        model.addAttribute("thresholdText", String.format(Locale.ROOT,
                "기준(최근 7일): 위험 인증 %d건 이상 · 인증 성공률 %s%%p 이상 하락(시도 %d건 이상) · 패스키 1개뿐 %s%% 이상(사용자 %d명 이상)",
                th.riskMin(), trim(th.rateDropPp()), th.rateDropMinAttempts(), trim(th.singleRatio() * 100), th.singleMinUsers()));

        List<PlatformInsightsDto.TenantRow> rows = new ArrayList<>(v.tenants());
        rows.sort(comparator(sort));
        List<TenantView> tenants = new ArrayList<>();
        for (PlatformInsightsDto.TenantRow r : rows) {
            boolean active = "active".equalsIgnoreCase(r.status());
            tenants.add(new TenantView(r.name(), r.slug(), tenantHref(r.tenantId().toString()),
                    active ? "활성" : ("suspended".equalsIgnoreCase(r.status()) ? "정지" : String.valueOf(r.status())),
                    active ? "badge--success" : "badge--warning",
                    num(r.activeUsers()), num(r.authSuccess()),
                    FunnelHelpers.fmtPct(FunnelHelpers.rate(r.authSuccess(), r.authAttempts())),
                    FunnelHelpers.fmtPct(FunnelHelpers.rate(r.regSuccess(), r.regAttempts())),
                    num(r.risk()), r.risk() > 0, num(r.credentials()),
                    FunnelHelpers.fmtPct(r.users() > 0 ? (double) r.singlePasskeyUsers() / r.users() : null)));
        }
        model.addAttribute("tenants", tenants);
        List<SortHeader> headers = new ArrayList<>();
        SORT_LABEL.forEach((key, label) -> headers.add(new SortHeader(key, label, key.equals(sort), get(FRAG_PATH + "?win=" + w + "&sort=" + key))));
        model.addAttribute("sortHeaders", headers);

        String q = "?win=" + w;
        model.addAttribute("seriesCard", FunnelTabController.seriesCard("auth", "플랫폼 일별 인증 시도와 성공", v.authSeries(), CSV_PATH + q + "&kind=daily"));
        model.addAttribute("seriesColumns", FunnelTabController.SERIES_COLUMNS);
        model.addAttribute("months", FunnelTabController.monthViews(v.months()));
        model.addAttribute("monthColumns", FunnelTabController.MONTH_COLUMNS);
        model.addAttribute("devices", FunnelTabController.deviceViews(v.devices()));
        model.addAttribute("csvTenants", CSV_PATH + q + "&kind=tenants");
        model.addAttribute("csvMonthly", CSV_PATH + q + "&kind=monthly");
        model.addAttribute("csvDevices", CSV_PATH + q + "&kind=devices");
    }

    /** CSV: 테넌트별 비교(과금·라이선스 자료), 일별 인증, 월간 사용량, 기기 분포. */
    @GetMapping(CSV_PATH)
    public ResponseEntity<byte[]> export(@RequestParam(name = "win", defaultValue = "7") String win,
                                         @RequestParam(name = "kind", defaultValue = "tenants") String kind, Model model) {
        if (!me(model).platform()) throw new AccessDeniedException("PLATFORM_OPERATOR only");
        int w = window(win);
        PlatformInsightsDto.View v = insights.get(w);
        String today = KstFormat.kstToday(clock.instant());
        List<List<String>> rows = new ArrayList<>();
        byte[] csv;
        String filename;
        switch (kind) {
            case "tenants" -> {
                for (PlatformInsightsDto.TenantRow r : v.tenants()) {
                    rows.add(List.of(r.slug(), String.valueOf(r.name()), String.valueOf(r.status()), String.valueOf(r.activeUsers()),
                            String.valueOf(r.authAttempts()), String.valueOf(r.authSuccess()), rate4(r.authSuccess(), r.authAttempts()),
                            String.valueOf(r.regAttempts()), String.valueOf(r.regSuccess()), rate4(r.regSuccess(), r.regAttempts()),
                            String.valueOf(r.risk()), String.valueOf(r.credentials()), String.valueOf(r.users()), String.valueOf(r.singlePasskeyUsers())));
                }
                csv = CsvWriter.write(List.of("slug", "name", "status", "activeUsers", "authAttempts", "authSuccess", "authSuccessRate",
                        "regAttempts", "regSuccess", "regSuccessRate", "riskBlocked", "passkeys", "users", "singlePasskeyUsers"), rows);
                filename = "platform-tenants-" + w + "d-" + today + ".csv";
            }
            case "daily" -> {
                for (FunnelDto.DailyPoint p : v.authSeries()) {
                    rows.add(List.of(p.day(), String.valueOf(p.attempts()), String.valueOf(p.success()), rate4(p.success(), p.attempts())));
                }
                csv = CsvWriter.write(List.of("day", "attempts", "success", "successRate"), rows);
                filename = "platform-auth-daily-" + w + "d-" + today + ".csv";
            }
            case "monthly" -> {
                for (AdoptionDto.Month m : v.months()) {
                    rows.add(List.of(m.month(), m.current() ? "Y" : "N", String.valueOf(m.authSuccess()), String.valueOf(m.activeUsers()), String.valueOf(m.newCredentials())));
                }
                csv = CsvWriter.write(List.of("month", "inProgress", "authSuccess", "activeUsers", "newPasskeys"), rows);
                filename = "platform-monthly-usage-" + today + ".csv";
            }
            case "devices" -> {
                for (AdoptionDto.Device d : v.devices()) rows.add(List.of(d.name(), String.valueOf(d.credentials()), String.valueOf(d.authSuccess())));
                csv = CsvWriter.write(List.of("authenticator", "passkeys", "authSuccessInWindow"), rows);
                filename = "platform-devices-" + w + "d-" + today + ".csv";
            }
            default -> throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "알 수 없는 내보내기");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
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

    static String sort(String s) {
        return SORT_LABEL.containsKey(s) ? s : "users";
    }

    /** 고른 값이 큰 순, 같으면 이름 순. 비율이 없는(시도·사용자 0) 테넌트는 뒤로. */
    static Comparator<PlatformInsightsDto.TenantRow> comparator(String sort) {
        ToDoubleFunction<PlatformInsightsDto.TenantRow> key = switch (sort) {
            case "auth" -> r -> r.authSuccess();
            case "rate" -> r -> r.authAttempts() > 0 ? (double) r.authSuccess() / r.authAttempts() : -1;
            case "reg" -> r -> r.regAttempts() > 0 ? (double) r.regSuccess() / r.regAttempts() : -1;
            case "risk" -> r -> r.risk();
            case "creds" -> r -> r.credentials();
            case "single" -> r -> r.users() > 0 ? (double) r.singlePasskeyUsers() / r.users() : -1;
            default -> r -> r.activeUsers();
        };
        return Comparator.comparingDouble(key).reversed().thenComparing(r -> r.name() == null ? "" : r.name());
    }

    private static String rate4(long success, long attempts) {
        Double r = FunnelHelpers.rate(success, attempts);
        return r == null ? "" : FunnelHelpers.toFixed(r, 4);
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String tenantHref(String id) {
        return "/admin/tenants/" + id + "?tab=funnel";
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /**
     * #insights-root 를 교체하는 요청(기간·정렬·다시 시도). 대상은 요소마다 직접 준다 — 루트에 hx-target 을 두면 안의 링크(boost)가
     * 그것을 물려받아 이동한 페이지 전체가 루트 안에 그려진다(테넌트 링크 클릭 시 페이지 안에 페이지).
     */
    private static Map<String, Object> get(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", url);
        m.put("hx-target", "#insights-root");
        m.put("hx-swap", "outerHTML");
        m.put("hx-sync", "#insights-root:replace");
        return m;
    }

    private static boolean denied(RuntimeException e) {
        return e instanceof AccessDeniedException
                || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.ACCESS_DENIED);
    }

    private static CurrentAdmin me(Model model) {
        if (!(model.getAttribute("me") instanceof CurrentAdmin me)) throw new AccessDeniedException("no authenticated admin");
        return me;
    }

    private static String num(long n) {
        return String.format(Locale.KOREA, "%,d", n);
    }
}
