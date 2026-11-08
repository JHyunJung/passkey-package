package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.web.shell.Crumbs;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설정 화면 틀(← pages/SettingsPage.tsx): 머리글 + 탭 줄 + 탭 내용.
 *
 * <p>탭 접근은 원본 {@code resolveTab(raw, platform)} 그대로 — PLATFORM 전용 탭(admins·mds·system·security)은 PLATFORM 일 때만,
 * 그 밖(RP_ADMIN, 모르는 값, 없음)은 늘 '내 계정'. 전체 페이지 {@code GET /admin/settings?tab=} 는 내 계정 탭을 인라인으로 그리고, 다른 탭이
 * 골라지면 탭 영역이 {@code hx-trigger="load"} 로 {@code GET /admin/_/settings/tab/{tab}} 를 불러온다(Task 9 의 테넌트 탭과 같은 틀).
 *
 * <p>일반 탭 매핑 {@code /admin/_/settings/tab/{tab}} 은 변수 패턴이라 각 탭 컨트롤러의 리터럴 경로(…/tab/account, …/tab/system, 이후 admins·mds·security)
 * 가 우선한다. 일반 매핑은 resolveTab 이 '내 계정'으로 떨어뜨리는 요청(RP_ADMIN·모르는 탭)에 내 계정 탭을 돌려주고, 아직 리터럴 컨트롤러가 없는
 * PLATFORM 탭은 404 다. 리터럴 탭 컨트롤러는 스스로 PLATFORM 을 확인해야 한다(일반 매핑의 판정이 리터럴 경로엔 적용되지 않는다).
 *
 * <p>Admin 사용자 탭 배지 = 대기 중 가입 요청 수(PLATFORM 만, {@code SignupRequestController.list()}; 실패하면 숨김).
 */
@Slf4j
@Controller
public class SettingsPageController {

    static final String PAGE_PATH = "/admin/settings";
    static final String FRAG_BASE = "/admin/_/settings/tab/";
    static final String PAGE_VIEW = "admin/settings/page";
    static final String TARGET_ID = "settings-tab";
    static final String SYNC = "#" + TARGET_ID + ":replace";

    /** SettingsPage.tsx 의 PLATFORM_TABS. */
    static final List<String> PLATFORM_TABS = List.of("admins", "mds", "system", "security");

    /** 탭 줄 (id, 라벨) — 원본 items 순서. */
    static final List<String[]> TABS = List.of(
            new String[] {"account", "내 계정"},
            new String[] {"admins", "Admin 사용자"},
            new String[] {"mds", "MDS Status"},
            new String[] {"system", "시스템"},
            new String[] {"security", "보안 정책"});

    private final AccountTabController account;
    private final SignupRequestController signups;

    public SettingsPageController(AccountTabController account, SignupRequestController signups) {
        this.account = account;
        this.signups = signups;
    }

    /** {@code ?tab=} 값을 탭으로. PLATFORM 전용 탭은 PLATFORM 일 때만 허용, 그 외는 항상 'account'. */
    public static String resolveTab(String raw, boolean platform) {
        return platform && raw != null && PLATFORM_TABS.contains(raw) ? raw : "account";
    }

    /** 탭 화면 주소: 내 계정은 {@code ?tab} 없이(원본 onTabChange 가 tab 을 지운다). */
    static String pageUrl(String tab) {
        return "account".equals(tab) ? PAGE_PATH : PAGE_PATH + "?tab=" + tab;
    }

    /** 탭 영역(#settings-tab)을 이 주소의 조각으로 바꾸는 요청 속성(다시 시도 버튼 등). 탭 영역을 바꾸는 요청은 모두 같은 hx-sync 그룹이다. */
    public static Map<String, Object> tabRequesterAttrs(String getUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", getUrl);
        m.put("hx-target", "#" + TARGET_ID);
        m.put("hx-swap", "innerHTML");
        m.put("hx-sync", SYNC);
        m.put("hx-push-url", "false");
        return m;
    }

    @GetMapping(PAGE_PATH)
    public String page(@RequestParam(name = "tab", required = false) String tab, Model model) {
        CurrentAdmin me = AccountTabController.me(model);
        String active = resolveTab(tab, me.getPlatform());
        model.addAttribute("breadcrumbs", Crumbs.settings());
        model.addAttribute("tabs", tabItems(me.getPlatform(), me.getPlatform() ? pendingSignups() : null));
        model.addAttribute("activeTab", active);
        boolean lazy = !"account".equals(active);
        model.addAttribute("tabLazy", lazy);
        if (lazy) {
            model.addAttribute("tabAttrsLoad", Map.of(
                    "hx-get", FRAG_BASE + active,
                    "hx-trigger", "load",
                    "hx-swap", "innerHTML",
                    "hx-sync", SYNC));
        } else {
            account.populate(model, me);
        }
        return PAGE_VIEW;
    }

    /**
     * 일반 탭 조각. resolveTab 이 '내 계정'으로 떨어뜨리면 내 계정 탭, 아직 리터럴 매핑이 없는 PLATFORM 탭은 404(토스트, 본문 없음).
     */
    @GetMapping(FRAG_BASE + "{tab}")
    public String tab(@PathVariable String tab, Model model) {
        CurrentAdmin me = AccountTabController.me(model);
        if (!"account".equals(resolveTab(tab, me.getPlatform()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "settings tab not available");
        }
        account.populate(model, me);
        return AccountTabController.CONTENT_VIEW;
    }

    private Integer pendingSignups() {
        try {
            return signups.list().size();
        } catch (RuntimeException e) {
            log.debug("[settings] signup request count failed: {}", e.getClass().getSimpleName());
            return null; // 배지는 보조 정보 — 실패 시 숨김
        }
    }

    static List<Map<String, Object>> tabItems(boolean platform, Integer pending) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String[] def : TABS) {
            String id = def[0];
            if (!platform && !"account".equals(id)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", def[1]);
            m.put("href", pageUrl(id));
            m.put("elId", "settings-tab-" + id);
            // 탭 클릭 = 조각 교체 + 주소 갱신. 활성 표시는 ui.js(htmx:afterRequest)가 data-tab 으로 바꾼다.
            // data-label 은 두지 않는다: 원본 브레드크럼은 탭과 무관하게 '설정' 하나다.
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("hx-get", FRAG_BASE + id);
            attrs.put("hx-target", "#" + TARGET_ID);
            attrs.put("hx-swap", "innerHTML");
            attrs.put("hx-push-url", pageUrl(id));
            attrs.put("hx-sync", SYNC);
            attrs.put("data-guard-dirty", "true");
            attrs.put("data-tab", id);
            m.put("attrs", attrs);
            if ("admins".equals(id) && pending != null) m.put("count", pending);
            items.add(m);
        }
        return items;
    }
}
