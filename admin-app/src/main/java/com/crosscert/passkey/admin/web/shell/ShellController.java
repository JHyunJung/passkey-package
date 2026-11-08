package com.crosscert.passkey.admin.web.shell;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditChainOverview;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.Htmx;
import com.crosscert.passkey.admin.web.support.Toasts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공통 틀의 htmx 조각: 라이선스 배너(폴링), 감사 체인 카드(폴링), RP 전환 목록, RP 전환 제출.
 * 기존 REST 컨트롤러 빈을 Java 메서드로 호출한다(HTTP 아님). 비즈니스 규칙은 새로 만들지 않는다.
 */
@Slf4j
@Controller
public class ShellController {

    static final String NONE = "admin/fragments/shell/empty :: none";

    public record SwitcherItem(UUID id, String name, boolean active) {}

    public record Switcher(String label, List<SwitcherItem> items) {}

    private final LicenseController license;
    private final AuditChainMonitorController chain;
    private final ActiveTenantController activeTenants;
    private final Clock clock;

    public ShellController(LicenseController license, AuditChainMonitorController chain,
                           ActiveTenantController activeTenants, Clock clock) {
        this.clock = clock;
        this.license = license;
        this.chain = chain;
        this.activeTenants = activeTenants;
    }

    /**
     * 라이선스 배너. 온프렘의 WARNING / NETWORK_GRACE / DEAD 만 그린다. SaaS·VALID 는 빈 응답.
     * 조회 실패는 비 2xx(503, 본문·토스트 없음)라 htmx 가 스왑하지 않는다 — 마지막 상태를 유지한다(주변 UI 는 실패를 알리지 않는다).
     * 링크는 PLATFORM 에게만. 배너는 hx-preserve 로 이동 사이에 유지되므로 "/license 화면이면 숨김"은 app.js 가 현재 주소로 처리한다.
     */
    @GetMapping("/admin/_/shell/license-banner")
    public String licenseBanner(Model model, HttpServletResponse res) {
        LicenseView view;
        try {
            view = license.get().data();
        } catch (RuntimeException e) {
            log.debug("[shell] license banner lookup failed: {}", e.toString());
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return NONE;
        }
        if (view == null || !"onprem".equals(view.getDeploymentMode()) || view.getState() == null || "VALID".equals(view.getState())) {
            return NONE;
        }
        CurrentAdmin me = (CurrentAdmin) model.getAttribute("me");
        model.addAttribute("license", view);
        model.addAttribute("showLink", me != null && me.platform());
        return "admin/fragments/shell/license-banner :: banner";
    }

    /** 감사 체인 카드. 조회 실패·빈 응답은 503(본문 없음) — 사이드바가 "검증 상태 확인 불가"를 보인다. */
    @GetMapping("/admin/_/shell/chain-card")
    public String chainCard(Model model, HttpServletResponse res) {
        AuditChainOverview overview;
        try {
            overview = chain.overview(24);
        } catch (AccessDeniedException e) {
            throw e; // RP 는 PLATFORM 전용 엔드포인트를 폴링하지 않는다 — 403 은 그대로 알린다.
        } catch (RuntimeException e) {
            log.warn("[shell] audit chain overview failed: {}", e.toString());
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return NONE;
        }
        if (overview == null || overview.totals() == null) {
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return NONE;
        }
        model.addAttribute("overview", overview);
        model.addAttribute("ago", timeAgo(overview.verifiedAt(), clock.instant()));
        return "admin/fragments/shell/chain-card :: card";
    }

    /** Sidebar.tsx 의 timeAgo: "방금" / "N분 전" / "N시간 전"(일 단위 없음). */
    static String timeAgo(Instant t, Instant now) {
        if (t == null) return "—";
        long mins = (now.toEpochMilli() - t.toEpochMilli()) / 60_000;
        if (mins < 1) return "방금";
        if (mins < 60) return mins + "분 전";
        return (mins / 60) + "시간 전";
    }

    /** RP 전환 목록. 허용 RP 가 2개 미만이거나 조회에 실패하면 빈 응답(스위처 없음, 실패는 삼킨다 — TSX). */
    @GetMapping("/admin/_/shell/tenant-switcher")
    public String tenantSwitcher(Model model) {
        ActiveTenantController.ActiveTenantView view;
        try {
            view = activeTenants.current();
        } catch (RuntimeException e) {
            return NONE;
        }
        if (view == null || view.allowedTenantIds() == null || view.allowedTenantIds().size() < 2) return NONE;
        Map<UUID, String> names = names(view);
        List<SwitcherItem> items = view.allowedTenantIds().stream()
                .map(id -> new SwitcherItem(id, names.getOrDefault(id, shortId(id)), id.equals(view.activeTenantId())))
                .toList();
        model.addAttribute("switcher", new Switcher(label(view, names), items));
        return "admin/fragments/shell/tenant-switcher :: switcher";
    }

    /**
     * RP 전환. 성공이면 현재 화면을 새로 고친다(React 의 window.location.reload()): htmx 는 {@code HX-Refresh: true},
     * 그 밖의 요청은 같은 출처 /admin 경로의 Referer 로, 없으면 선택한 RP 상세로 리다이렉트. 실패는 실제 오류 상태 + 오류 토스트(본문 없음)이고,
     * 활성 RP 는 바뀌지 않았으므로 화면도 그대로다.
     */
    @PostMapping("/admin/_/active-tenant")
    public void switchTenant(@RequestParam UUID tenantId, HttpServletRequest req, HttpServletResponse res) throws IOException {
        try {
            activeTenants.switchTenant(new ActiveTenantController.SwitchRequest(tenantId));
        } catch (RuntimeException e) {
            failSwitch(e, res);
            return;
        }
        if (Htmx.isHtmx(req)) {
            res.setStatus(HttpServletResponse.SC_OK);
            res.setHeader("HX-Refresh", "true");
        } else {
            String back = sameOriginAdminReferer(req);
            res.sendRedirect(back != null ? back : "/admin/tenants/" + tenantId);
        }
    }

    /** Referer 가 이 서버의 /admin 경로면 그 경로(+쿼리), 아니면 null. 열린 리다이렉트를 막는다. */
    static String sameOriginAdminReferer(HttpServletRequest req) {
        String ref = req.getHeader("Referer");
        if (ref == null || ref.isBlank()) return null;
        try {
            URI u = new URI(ref);
            if (u.getHost() == null || !u.getHost().equalsIgnoreCase(req.getServerName())) return null;
            int port = u.getPort() != -1 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            if (!req.getScheme().equalsIgnoreCase(u.getScheme()) || port != req.getServerPort()) return null;
            String path = u.getRawPath();
            if (path == null || !(path.equals("/admin") || path.startsWith("/admin/"))) return null;
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void failSwitch(RuntimeException e, HttpServletResponse res) {
        ErrorMessages.Info info = ErrorMessages.describe(e);
        int status = info.httpStatus() != null ? info.httpStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        log.warn("[shell] active tenant switch failed: status={} cause={}", status, e.toString());
        String current = "";
        try {
            ActiveTenantController.ActiveTenantView view = activeTenants.current();
            current = label(view, names(view));
        } catch (RuntimeException ignored) {
            // 현재 RP 이름을 못 구해도 오류 안내는 한다.
        }
        String message = info.title() + ". 지금 보고 있는 RP(" + current + ")는 그대로입니다.";
        Toasts.errorTitled(res, "RP를 전환하지 못했습니다", message, ErrorMessages.rawDetail(e, status, info), MDC.get("traceId"));
        res.setStatus(status);
    }

    private static Map<UUID, String> names(ActiveTenantController.ActiveTenantView view) {
        if (view.allowedTenants() == null) return Map.of();
        return view.allowedTenants().stream().collect(Collectors.toMap(
                ActiveTenantController.TenantRef::id, ActiveTenantController.TenantRef::name, (a, b) -> a));
    }

    /** 현재 RP 이름. 이름이 없으면 id 끝 8자, 활성 RP 가 없으면 "—". */
    private static String label(ActiveTenantController.ActiveTenantView view, Map<UUID, String> names) {
        UUID id = view.activeTenantId();
        return id == null ? "—" : names.getOrDefault(id, shortId(id));
    }

    private static String shortId(UUID id) {
        String s = id.toString();
        return s.substring(s.length() - 8);
    }
}
