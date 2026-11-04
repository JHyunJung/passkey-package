package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.system.SystemInfoController;
import com.crosscert.passkey.admin.system.SystemInfoView;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 설정 › 시스템 탭(← pages/settings/SystemInfoTab.tsx, api/systemInfo.ts). PLATFORM 전용 — {@code SystemInfoController.get()}.
 * 일반 탭 매핑({@code /admin/_/settings/tab/{tab}})보다 이 리터럴 경로가 우선한다. RP_ADMIN 이 직접 부르면 403(원본엔 탭 자체가 없다).
 * 조회 실패는 200 + LoadError(다시 시도 = 이 조각을 탭 영역에 다시 받기). 알 수 없는 예외의 원문은 싣지 않는다(ErrorMessages).
 */
@Slf4j
@Controller
public class SystemInfoTabController {

    static final String TAB_PATH = "/admin/_/settings/tab/system";
    static final String VIEW = "admin/settings/system :: content";

    private final SystemInfoController systemInfo;

    public SystemInfoTabController(SystemInfoController systemInfo) {
        this.systemInfo = systemInfo;
    }

    /** 상태 표시(STATUS): 배지 톤·라벨·점 색. */
    public record Status(String tone, String label, String dot) {
        static Status of(String s) {
            if ("DOWN".equals(s)) return new Status("bad", "중단", "var(--bad)");
            if ("DEGRADED".equals(s)) return new Status("warn", "저하", "var(--warn-dot)");
            return new Status("ok", "정상", "var(--ok)");
        }
    }

    public record Comp(String name, String version, String note, Status status) {}

    public record Info(Status overall, String overallHint, String serverVersion, String deployedHint, String deployed, String uptime,
                       List<Comp> components, String environment, List<String> profiles) {}

    @GetMapping(TAB_PATH)
    public String tab(Model model) {
        CurrentAdmin me = AccountTabController.me(model);
        if (!me.platform()) throw new AccessDeniedException("platform only");
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH));
        SystemInfoView v;
        try {
            v = systemInfo.get().data();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[settings] system info load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("loadError", e);
            model.addAttribute("info", null);
            return VIEW;
        }
        model.addAttribute("loadError", null);
        model.addAttribute("info", toInfo(v));
        return VIEW;
    }

    static Info toInfo(SystemInfoView v) {
        List<SystemInfoView.Component> raw = v.components() == null ? List.of() : v.components();
        List<Comp> comps = new ArrayList<>();
        long ok = 0;
        boolean down = false, degraded = false;
        for (SystemInfoView.Component c : raw) {
            if ("OK".equals(c.status())) ok++;
            if ("DOWN".equals(c.status())) down = true;
            if ("DEGRADED".equals(c.status())) degraded = true;
            comps.add(new Comp(c.name(), c.version(), c.note() == null || c.note().isEmpty() ? null : c.note(), Status.of(c.status())));
        }
        // 전체 상태 = 가장 나쁜 컴포넌트 상태.
        Status overall = Status.of(down ? "DOWN" : degraded ? "DEGRADED" : "OK");
        String deployed = deployedKst(v.deployedAt());
        String env = v.host() == null || v.host().environment() == null ? "" : v.host().environment();
        List<String> profiles = Arrays.stream(env.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Info(overall, "컴포넌트 " + ok + " / " + raw.size(), v.serverVersion(),
                deployed != null ? deployed + " 배포" : "배포 시각 정보 없음", deployed,
                v.uptimeDays() != null ? v.uptimeDays() + "일" : "—", comps, env, profiles);
    }

    /** 해석 가능한 시각만 KST 로. 없거나 깨진 값은 null. */
    static String deployedKst(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        Instant t = KstFormat.parse(iso);
        return t == null ? null : KstFormat.kstDateTimeMin(t) + " KST";
    }
}
