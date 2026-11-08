package com.crosscert.passkey.admin.web.license;

import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.web.HomeRedirectController;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.shell.Crumbs;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 라이선스 화면(← pages/LicensePage.tsx, api/license.ts). PLATFORM 전용(App.tsx RequirePlatform): RP_ADMIN 은 홈(자기 tenant)으로 보낸다.
 * 데이터는 {@code LicenseController.get()} 하나. 설치 방식은 응답의 {@code deploymentMode} 를 따른다 — onprem 이 아니면(SaaS) 온프레미스 전용 안내만.
 *
 * <p>"지금 다시 확인"은 {@code GET /admin/_/license/recheck} 로 상세 영역(#license-body)을 새 값으로 교체한다. 실패하면 원본처럼 이전 정보를 그대로 두고
 * 경고만 보인다(200 + HX-Retarget #license-recheck-alert). 처음 불러오기 실패는 LoadError(다시 시도 = 화면 다시 받기). 알 수 없는 예외의 원문은 싣지 않는다.
 */
@Slf4j
@Controller
public class LicensePageController {

    static final String PAGE_PATH = "/admin/license";
    static final String RECHECK_PATH = "/admin/_/license/recheck";
    static final String PAGE_VIEW = "admin/license/page";
    static final String BODY_VIEW = "admin/license/page :: licenseBody";
    static final String ALERT_VIEW = "admin/license/page :: recheckAlert";

    private final LicenseController license;
    private final Clock clock;

    public LicensePageController(LicenseController license, Clock clock) {
        this.license = license;
        this.clock = clock;
    }

    public record Badge(String tone, String label) {}

    public record Row(String k, String v, boolean mono, String copy, List<String> tags) {}

    /** 다음 할 일 카드(VALID 는 없음). */
    public record Action(String title, String lead, List<String> steps) {}

    public record View(boolean onprem, String state, Badge badge, String heartbeat, String days, String daysColor,
                       String expiresLine, Action action, List<Row> rows) {}

    static final String RECHECK_STEP = "이 화면에서 \"지금 다시 확인\"을 누릅니다. 상태가 \"정상\"으로 바뀌면 끝입니다.";
    static final String REQUEST_STEP = "영업 담당자에게 갱신을 요청합니다. 아래 상세의 라이선스 ID와 고객사 이름을 함께 보내 주세요.";
    static final String APPLY_STEP = "받은 라이선스 파일을 서버에 적용합니다.";

    @GetMapping(PAGE_PATH)
    public String page(Model model) {
        CurrentAdmin me = me(model);
        model.addAttribute("breadcrumbs", Crumbs.license());
        if (!me.getPlatform()) return "redirect:" + HomeRedirectController.homePath(me);
        model.addAttribute("retryAttrs", Map.of("hx-get", PAGE_PATH, "hx-target", "body", "hx-swap", "innerHTML", "hx-push-url", "false"));
        model.addAttribute("recheckAttrs", recheckAttrs());
        try {
            model.addAttribute("view", toView(license.get().data(), clock.instant()));
            model.addAttribute("loadError", null);
        } catch (RuntimeException e) {
            log.warn("[license] load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("view", null);
            model.addAttribute("loadError", e);
        }
        return PAGE_VIEW;
    }

    @GetMapping(RECHECK_PATH)
    public String recheck(HttpServletResponse res, Model model) {
        if (!me(model).getPlatform()) throw new AccessDeniedException("platform only");
        model.addAttribute("recheckAttrs", recheckAttrs());
        LicenseView v;
        try {
            v = license.get().data();
        } catch (RuntimeException e) {
            log.warn("[license] recheck failed: {}", e.getClass().getSimpleName());
            res.setHeader("HX-Retarget", "#license-recheck-alert");
            res.setHeader("HX-Reswap", "innerHTML");
            model.addAttribute("recheckError", ErrorMessages.describe(e).getTitle() + ". 아래는 마지막으로 확인한 정보입니다.");
            return ALERT_VIEW;
        }
        model.addAttribute("view", toView(v, clock.instant()));
        return BODY_VIEW;
    }

    private static Map<String, Object> recheckAttrs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", RECHECK_PATH);
        m.put("hx-target", "#license-body");
        m.put("hx-swap", "outerHTML");
        m.put("hx-sync", "this:drop");
        return m;
    }

    static View toView(LicenseView v, Instant now) {
        boolean onprem = "onprem".equals(v.getDeploymentMode());
        if (!onprem) return new View(false, null, null, null, null, null, null, null, List.of());
        String state = v.getState();
        Badge badge = badge(state);
        List<String> hb = new ArrayList<>();
        if (v.getLastVerifiedAt() != null) hb.add("마지막 확인 " + KstFormat.relative(v.getLastVerifiedAt(), now));
        if (v.getNextHeartbeatAt() != null) hb.add("다음 확인 " + KstFormat.relative(v.getNextHeartbeatAt(), now));
        String daysColor = "WARNING".equals(state) ? "var(--warn)" : "DEAD".equals(state) ? "var(--bad)" : null;
        String expires = v.getExpiresAt() == null ? null : "만료일 " + KstFormat.formatDateTime(v.getExpiresAt());

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("고객사", v.getSub() == null ? "—" : v.getSub(), false, null, null));
        rows.add(new Row("라이선스 ID", v.getJti() == null ? "—" : v.getJti(), v.getJti() != null, v.getJti(), null));
        rows.add(new Row("만료일", v.getExpiresAt() == null ? "—" : KstFormat.formatDateTime(v.getExpiresAt()), false, null, null));
        rows.add(new Row("남은 일수", String.valueOf(v.getDaysUntilExpiry()), false, null, null));
        List<String> features = v.getFeatures() == null ? List.of() : v.getFeatures();
        rows.add(new Row("사용 가능한 기능", features.isEmpty() ? "—" : null, false, null, features.isEmpty() ? null : features));
        rows.add(new Row("마지막 확인", v.getLastVerifiedAt() == null ? "—" : KstFormat.formatDateTime(v.getLastVerifiedAt()), false, null, null));
        rows.add(new Row("다음 확인 예정", v.getNextHeartbeatAt() == null ? "—" : KstFormat.formatDateTime(v.getNextHeartbeatAt()), false, null, null));
        if (v.getGraceRemainingHours() != null) rows.add(new Row("유예 기간 남은 시간", v.getGraceRemainingHours() + "시간", false, null, null));

        return new View(true, state, badge, String.join(" · ", hb), v.getDaysUntilExpiry() + "일", daysColor, expires, action(v), rows);
    }

    static Badge badge(String state) {
        if (state == null) return null;
        return switch (state) {
            case "VALID" -> new Badge("ok", "정상");
            case "WARNING" -> new Badge("warn", "만료 임박");
            case "NETWORK_GRACE" -> new Badge("warn", "서버 연결 불가");
            case "DEAD" -> new Badge("bad", "만료");
            default -> null;
        };
    }

    /** 상태만 알리지 않고 다음 행동을 함께 보인다. VALID 는 할 일이 없으므로 카드 없음. */
    static Action action(LicenseView v) {
        String s = v.getState();
        if ("WARNING".equals(s)) {
            return new Action("지금 해야 할 일 — 라이선스 갱신",
                    "라이선스가 " + v.getDaysUntilExpiry() + "일 후 만료됩니다. 만료되면 패스키 등록·인증이 중단됩니다.",
                    List.of(REQUEST_STEP, APPLY_STEP, RECHECK_STEP));
        }
        if ("NETWORK_GRACE".equals(s)) {
            String left = v.getGraceRemainingHours() != null ? v.getGraceRemainingHours() + "시간 안에" : "곧";
            return new Action("지금 해야 할 일 — 라이선스 서버 연결 복구",
                    "라이선스 서버에 연결할 수 없습니다. " + left + " 연결되지 않으면 서비스가 중단됩니다.",
                    List.of("방화벽에서 라이선스 서버(443 포트)로 나가는 연결을 허용했는지 확인합니다.",
                            "프록시를 쓰는 경우 프록시 설정을 확인합니다.",
                            RECHECK_STEP));
        }
        if ("DEAD".equals(s)) {
            return new Action("지금 해야 할 일 — 새 라이선스 적용",
                    "라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다. 새 라이선스 파일을 적용하면 바로 복구됩니다.",
                    List.of(REQUEST_STEP, APPLY_STEP, RECHECK_STEP));
        }
        return null;
    }

    private static CurrentAdmin me(Model model) {
        if (!(model.getAttribute("me") instanceof CurrentAdmin me)) throw new AccessDeniedException("no authenticated admin");
        return me;
    }
}
