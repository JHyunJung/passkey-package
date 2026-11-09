package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.web.shell.Crumbs
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

private val log = LoggerFactory.getLogger(SettingsPageController::class.java)

/**
 * 설정 화면 틀(← pages/SettingsPage.tsx): 머리글 + 탭 줄 + 탭 내용.
 *
 * 탭 접근은 원본 `resolveTab(raw, platform)` 그대로 — PLATFORM 전용 탭(admins·mds·system·security)은 PLATFORM 일 때만,
 * 그 밖(RP_ADMIN, 모르는 값, 없음)은 늘 '내 계정'. 전체 페이지 `GET /admin/settings?tab=` 는 내 계정 탭을 인라인으로 그리고, 다른 탭이
 * 골라지면 탭 영역이 `hx-trigger="load"` 로 `GET /admin/_/settings/tab/{tab}` 를 불러온다(Task 9 의 테넌트 탭과 같은 틀).
 *
 * 일반 탭 매핑 `/admin/_/settings/tab/{tab}` 은 변수 패턴이라 각 탭 컨트롤러의 리터럴 경로(…/tab/account, …/tab/system, 이후 admins·mds·security)
 * 가 우선한다. 일반 매핑은 resolveTab 이 '내 계정'으로 떨어뜨리는 요청(RP_ADMIN·모르는 탭)에 내 계정 탭을 돌려주고, 아직 리터럴 컨트롤러가 없는
 * PLATFORM 탭은 404 다. 리터럴 탭 컨트롤러는 스스로 PLATFORM 을 확인해야 한다(일반 매핑의 판정이 리터럴 경로엔 적용되지 않는다).
 *
 * Admin 사용자 탭 배지 = 대기 중 가입 요청 수(PLATFORM 만, `SignupRequestController.list()`; 실패하면 숨김).
 */
@Controller
class SettingsPageController(
    private val account: AccountTabController,
    private val signups: SignupRequestController,
) {

    @GetMapping(PAGE_PATH)
    fun page(@RequestParam(name = "tab", required = false) tab: String?, model: Model): String {
        val me = AccountTabController.me(model)
        val active = resolveTab(tab, me.platform)
        model.addAttribute("breadcrumbs", Crumbs.settings())
        model.addAttribute("tabs", tabItems(me.platform, if (me.platform) pendingSignups() else null))
        model.addAttribute("activeTab", active)
        val lazy = "account" != active
        model.addAttribute("tabLazy", lazy)
        if (lazy) {
            model.addAttribute(
                "tabAttrsLoad",
                java.util.Map.of(
                    "hx-get", FRAG_BASE + active,
                    "hx-trigger", "load",
                    "hx-swap", "innerHTML",
                    "hx-sync", SYNC,
                ),
            )
        } else {
            account.populate(model, me)
        }
        return PAGE_VIEW
    }

    /**
     * 일반 탭 조각. resolveTab 이 '내 계정'으로 떨어뜨리면 내 계정 탭, 아직 리터럴 매핑이 없는 PLATFORM 탭은 404(토스트, 본문 없음).
     */
    @GetMapping(FRAG_BASE + "{tab}")
    fun tab(@PathVariable tab: String, model: Model): String {
        val me = AccountTabController.me(model)
        if ("account" != resolveTab(tab, me.platform)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "settings tab not available")
        }
        account.populate(model, me)
        return AccountTabController.CONTENT_VIEW
    }

    private fun pendingSignups(): Int? {
        try {
            return signups.list().size
        } catch (e: RuntimeException) {
            log.debug("[settings] signup request count failed: {}", e.javaClass.simpleName)
            return null // 배지는 보조 정보 — 실패 시 숨김
        }
    }

    companion object {
        internal const val PAGE_PATH = "/admin/settings"
        internal const val FRAG_BASE = "/admin/_/settings/tab/"
        internal const val PAGE_VIEW = "admin/settings/page"
        internal const val TARGET_ID = "settings-tab"
        internal const val SYNC = "#$TARGET_ID:replace"

        /** SettingsPage.tsx 의 PLATFORM_TABS. */
        private val PLATFORM_TABS: List<String> = java.util.List.of("admins", "mds", "system", "security")

        /** 탭 줄 (id, 라벨) — 원본 items 순서. */
        private val TABS: List<Array<String>> = java.util.List.of(
            arrayOf("account", "내 계정"),
            arrayOf("admins", "Admin 사용자"),
            arrayOf("mds", "MDS Status"),
            arrayOf("system", "시스템"),
            arrayOf("security", "보안 정책"),
        )

        /** `?tab=` 값을 탭으로. PLATFORM 전용 탭은 PLATFORM 일 때만 허용, 그 외는 항상 'account'. */
        fun resolveTab(raw: String?, platform: Boolean): String =
            if (platform && raw != null && PLATFORM_TABS.contains(raw)) raw else "account"

        /** 탭 화면 주소: 내 계정은 `?tab` 없이(원본 onTabChange 가 tab 을 지운다). */
        private fun pageUrl(tab: String): String = if ("account" == tab) PAGE_PATH else "$PAGE_PATH?tab=$tab"

        /** 탭 영역(#settings-tab)을 이 주소의 조각으로 바꾸는 요청 속성(다시 시도 버튼 등). 탭 영역을 바꾸는 요청은 모두 같은 hx-sync 그룹이다. */
        fun tabRequesterAttrs(getUrl: String): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = getUrl
            m["hx-target"] = "#$TARGET_ID"
            m["hx-swap"] = "innerHTML"
            m["hx-sync"] = SYNC
            m["hx-push-url"] = "false"
            return m
        }

        private fun tabItems(platform: Boolean, pending: Int?): List<Map<String, Any?>> {
            val items = ArrayList<Map<String, Any?>>()
            for (def in TABS) {
                val id = def[0]
                if (!platform && "account" != id) continue
                val m = LinkedHashMap<String, Any?>()
                m["id"] = id
                m["label"] = def[1]
                m["href"] = pageUrl(id)
                m["elId"] = "settings-tab-$id"
                // 탭 클릭 = 조각 교체 + 주소 갱신. 활성 표시는 ui.js(htmx:afterRequest)가 data-tab 으로 바꾼다.
                // data-label 은 두지 않는다: 원본 브레드크럼은 탭과 무관하게 '설정' 하나다.
                val attrs = LinkedHashMap<String, Any?>()
                attrs["hx-get"] = FRAG_BASE + id
                attrs["hx-target"] = "#$TARGET_ID"
                attrs["hx-swap"] = "innerHTML"
                attrs["hx-push-url"] = pageUrl(id)
                attrs["hx-sync"] = SYNC
                attrs["data-guard-dirty"] = "true"
                attrs["data-tab"] = id
                m["attrs"] = attrs
                if ("admins" == id && pending != null) m["count"] = pending
                items.add(m)
            }
            return items
        }
    }
}
