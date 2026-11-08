package com.crosscert.passkey.admin.web.shell

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.Htmx
import com.crosscert.passkey.admin.web.support.Toasts
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.BinaryOperator
import java.util.function.Function
import java.util.stream.Collectors

private val log = LoggerFactory.getLogger(ShellController::class.java)

private const val NONE = "admin/fragments/shell/empty :: none"

/**
 * 공통 틀의 htmx 조각: 라이선스 배너(폴링), 감사 체인 카드(폴링), RP 전환 목록, RP 전환 제출.
 * 기존 REST 컨트롤러 빈을 메서드로 호출한다(HTTP 아님). 비즈니스 규칙은 새로 만들지 않는다.
 */
@Controller
class ShellController(
    private val license: LicenseController,
    private val chain: AuditChainMonitorController,
    private val activeTenants: ActiveTenantController,
    private val clock: Clock,
) {

    data class SwitcherItem(val id: UUID?, val name: String?, val active: Boolean)

    data class Switcher(val label: String?, val items: List<SwitcherItem>?)

    /**
     * 라이선스 배너. 온프렘의 WARNING / NETWORK_GRACE / DEAD 만 그린다. SaaS·VALID 는 빈 응답.
     * 조회 실패는 비 2xx(503, 본문·토스트 없음)라 htmx 가 스왑하지 않는다 — 마지막 상태를 유지한다(주변 UI 는 실패를 알리지 않는다).
     * 링크는 PLATFORM 에게만. 배너는 hx-preserve 로 이동 사이에 유지되므로 "/license 화면이면 숨김"은 app.js 가 현재 주소로 처리한다.
     */
    @GetMapping("/admin/_/shell/license-banner")
    fun licenseBanner(model: Model, res: HttpServletResponse): String {
        val view: LicenseView?
        try {
            view = license.get().data()
        } catch (e: RuntimeException) {
            log.debug("[shell] license banner lookup failed: {}", e.toString())
            res.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return NONE
        }
        if (view == null || "onprem" != view.deploymentMode || view.state == null || "VALID" == view.state) {
            return NONE
        }
        val me = model.getAttribute("me") as CurrentAdmin?
        model.addAttribute("license", view)
        model.addAttribute("showLink", me != null && me.platform)
        return "admin/fragments/shell/license-banner :: banner"
    }

    /** 감사 체인 카드. 조회 실패·빈 응답은 503(본문 없음) — 사이드바가 "검증 상태 확인 불가"를 보인다. */
    @GetMapping("/admin/_/shell/chain-card")
    fun chainCard(model: Model, res: HttpServletResponse): String {
        val overview: AuditChainOverview?
        try {
            overview = chain.overview(24)
        } catch (e: AccessDeniedException) {
            throw e // RP 는 PLATFORM 전용 엔드포인트를 폴링하지 않는다 — 403 은 그대로 알린다.
        } catch (e: RuntimeException) {
            log.warn("[shell] audit chain overview failed: {}", e.toString())
            res.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return NONE
        }
        if (overview == null || overview.totals == null) {
            res.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return NONE
        }
        model.addAttribute("overview", overview)
        model.addAttribute("ago", timeAgo(overview.verifiedAt, clock.instant()))
        return "admin/fragments/shell/chain-card :: card"
    }

    /** RP 전환 목록. 허용 RP 가 2개 미만이거나 조회에 실패하면 빈 응답(스위처 없음, 실패는 삼킨다 — TSX). */
    @GetMapping("/admin/_/shell/tenant-switcher")
    fun tenantSwitcher(model: Model): String {
        val view: ActiveTenantController.ActiveTenantView?
        try {
            view = activeTenants.current()
        } catch (e: RuntimeException) {
            return NONE
        }
        val ids = view?.allowedTenantIds
        if (view == null || ids == null || ids.size < 2) return NONE
        val names = names(view)
        val items: List<SwitcherItem> = ids.stream()
            .map { id -> SwitcherItem(id, names.getOrDefault(id, shortId(id)), id == view.activeTenantId) }
            .toList()
        model.addAttribute("switcher", Switcher(label(view, names), items))
        return "admin/fragments/shell/tenant-switcher :: switcher"
    }

    /**
     * RP 전환. 성공이면 현재 화면을 새로 고친다(React 의 window.location.reload()): htmx 는 `HX-Refresh: true`,
     * 그 밖의 요청은 같은 출처 /admin 경로의 Referer 로, 없으면 선택한 RP 상세로 리다이렉트. 실패는 실제 오류 상태 + 오류 토스트(본문 없음)이고,
     * 활성 RP 는 바뀌지 않았으므로 화면도 그대로다.
     */
    @PostMapping("/admin/_/active-tenant")
    @Throws(IOException::class)
    fun switchTenant(@RequestParam tenantId: UUID, req: HttpServletRequest, res: HttpServletResponse) {
        try {
            activeTenants.switchTenant(ActiveTenantController.SwitchRequest(tenantId))
        } catch (e: RuntimeException) {
            failSwitch(e, res)
            return
        }
        if (Htmx.isHtmx(req)) {
            res.status = HttpServletResponse.SC_OK
            res.setHeader("HX-Refresh", "true")
        } else {
            val back = sameOriginAdminReferer(req)
            res.sendRedirect(back ?: "/admin/tenants/$tenantId")
        }
    }

    private fun failSwitch(e: RuntimeException, res: HttpServletResponse) {
        val info = ErrorMessages.describe(e)
        val status = info.httpStatus ?: HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        log.warn("[shell] active tenant switch failed: status={} cause={}", status, e.toString())
        var current: String? = ""
        try {
            val view = activeTenants.current()
            current = label(view, names(view))
        } catch (ignored: RuntimeException) {
            // 현재 RP 이름을 못 구해도 오류 안내는 한다.
        }
        val message = info.title + ". 지금 보고 있는 RP(" + current + ")는 그대로입니다."
        Toasts.errorTitled(res, "RP를 전환하지 못했습니다", message, ErrorMessages.rawDetail(e, status, info), MDC.get("traceId"))
        res.status = status
    }

    companion object {
        /**
         * Sidebar.tsx 의 timeAgo: "방금" / "N분 전" / "N시간 전"(일 단위 없음).
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(ShellControllerTest)가 부른다.
         */
        @JvmStatic
        fun timeAgo(t: Instant?, now: Instant): String {
            if (t == null) return "—"
            val mins = (now.toEpochMilli() - t.toEpochMilli()) / 60_000
            if (mins < 1) return "방금"
            if (mins < 60) return "${mins}분 전"
            return "${mins / 60}시간 전"
        }

        /** Referer 가 이 서버의 /admin 경로면 그 경로(+쿼리), 아니면 null. 열린 리다이렉트를 막는다. */
        private fun sameOriginAdminReferer(req: HttpServletRequest): String? {
            val ref: String? = req.getHeader("Referer")
            if (ref == null || ref.javaIsBlank()) return null
            try {
                val u = URI(ref)
                val host: String? = u.host
                if (host == null || !host.equals(req.serverName, ignoreCase = true)) return null
                val port = if (u.port != -1) u.port else (if ("https".equals(u.scheme, ignoreCase = true)) 443 else 80)
                if (!req.scheme.equals(u.scheme, ignoreCase = true) || port != req.serverPort) return null
                val path: String? = u.rawPath
                if (path == null || !(path == "/admin" || path.startsWith("/admin/"))) return null
                return if (u.rawQuery == null) path else path + "?" + u.rawQuery
            } catch (e: URISyntaxException) {
                return null
            }
        }

        /** 원 Java Collectors.toMap 그대로 — 이름이 null 인 항목은 HashMap.merge 의 NPE(호출자의 catch 로 간다). */
        private fun names(view: ActiveTenantController.ActiveTenantView): Map<UUID?, String?> {
            val refs = view.allowedTenants ?: return java.util.Map.of()
            return refs.stream().collect(
                Collectors.toMap(
                    Function { r: ActiveTenantController.TenantRef -> r.id },
                    Function { r: ActiveTenantController.TenantRef -> r.name },
                    BinaryOperator { a, _ -> a },
                ),
            )
        }

        /** 현재 RP 이름. 이름이 없으면 id 끝 8자, 활성 RP 가 없으면 "—". */
        private fun label(view: ActiveTenantController.ActiveTenantView, names: Map<UUID?, String?>): String? {
            val id = view.activeTenantId ?: return "—"
            return names.getOrDefault(id, shortId(id))
        }

        private fun shortId(id: UUID): String {
            val s = id.toString()
            return s.substring(s.length - 8)
        }
    }
}
