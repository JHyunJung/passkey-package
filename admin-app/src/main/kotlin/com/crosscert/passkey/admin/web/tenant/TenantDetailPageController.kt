package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.activity.ActivityController
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditLogController
import com.crosscert.passkey.admin.funnel.FunnelController
import com.crosscert.passkey.admin.funnel.FunnelDto
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.policy.AaguidPolicyDto
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.format.ApiKeyExpiry
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.Josa
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.shell.Crumbs
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.Htmx
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(TenantDetailPageController::class.java)

/**
 * Tenant 상세 틀과 개요 탭(← pages/TenantDetailPage.tsx, pages/tenant/TenantOverview.tsx, tenantSignals.ts, recentActivityAdapter.ts).
 *
 * 전체 페이지 `GET /admin/tenants/{idOrSlug}?tab=` 는 헤더·탭 줄·개요 탭을 서버에서 그린다(R3'). 다른 탭이 골라지면 탭 컨테이너가
 * `hx-trigger="load"` 로 `GET /admin/_/tenants/{id}/tab/{tab}` 를 불러온다. 이 일반 매핑은 개요를 돌려주고 모르는 탭 키도 개요로
 * 떨어진다. 이후 탭 task 는 같은 URL 규약의 **리터럴 경로**(`/admin/_/tenants/{id}/tab/webauthn` 등)를 각자의 컨트롤러에 매핑한다 —
 * Spring 은 `{tab}` 변수 패턴보다 리터럴 패턴을 우선하므로 이 매핑이 막지 않는다(테스트 `literalTabMappingWinsOverGeneric`).
 *
 * 접근 경계: 테넌트는 `TenantAdminController.get` 으로 읽는다(서비스가 TenantBoundary 를 적용). RP_ADMIN 이 다른 테넌트를 열면
 * 403(`WebExceptionAdvice`: 직접 접근은 오류 화면, htmx 는 토스트 + 본문 없음)이며 어떤 데이터도 그리지 않는다.
 * 정지·정지 해제는 PLATFORM 전용이다 — REST 메서드의 `@PreAuthorize` 는 Spring 프록시를 거치는 호출이라 직접 호출에도 적용되지만,
 * 그 기대에 기대지 않고 여기서도 역할을 검사한다.
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 `requirePlatform`(403)·테넌트 조회가 먼저다. 넘기는 자리의 `!!` 는 원 Java 에서
 * Kotlin 피호출자의 파라미터 검사 NPE 가 나던 자리.
 */
@Controller
class TenantDetailPageController(
    private val tenants: TenantAdminController,
    private val funnel: FunnelController,
    private val activity: ActivityController,
    private val audit: AuditLogController,
    private val chain: AuditChainMonitorController,
    private val apiKeys: ApiKeyAdminController,
    private val aaguid: AaguidPolicyController,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** 화면에 쓰는 Tenant(원본 adaptTenant: name=displayName, status 는 ACTIVE 아니면 SUSPENDED). */
    data class TenantDetail(
        val id: String,
        val name: String,
        val slug: String,
        val rpId: String?,
        val status: String,
        val credentials: Long,
        val apiKeys: Long,
        val createdAt: OffsetDateTime?,
        val origins: List<String>?,
        val userVerification: String,
        val attestation: String?,
        val timeoutMs: Int,
    ) {
        /** `tenant.name.slice(0, 1).toUpperCase()` */
        fun initial(): String = if (name.isEmpty()) "" else name.substring(0, 1).uppercase(Locale.ROOT)

        fun active(): Boolean = "ACTIVE" == status

        /** 헤더 "RP 사이트 열기" 주소. rpId 가 없으면 null. */
        fun rpSiteUrl(): String? = if (rpId == null || rpId.isEmpty()) null else "https://$rpId"

        /** fmtDateKst */
        fun createdKst(): String = KstFormat.kstDate(createdAt)

        /** 정지 확인 제목: `${name}${eulReul(name)} 정지할까요?` */
        fun suspendTitle(): String = name + Josa.eulReul(name) + " 정지할까요?"

        companion object {
            internal fun from(v: TenantAdminDto.TenantView): TenantDetail {
                val st = v.status
                return TenantDetail(
                    v.id.toString(), // 원 Java String.valueOf — null 이면 "null"
                    v.displayName ?: "", v.slug ?: "", v.rpId,
                    if (st != null && st.equals("ACTIVE", ignoreCase = true)) "ACTIVE" else "SUSPENDED",
                    v.credentials, v.apiKeys, v.createdAt,
                    v.allowedOrigins, if (v.requireUserVerification) "REQUIRED" else "PREFERRED",
                    v.attestationConveyance, v.webauthnTimeoutMs,
                )
            }
        }
    }

    /** 개요 KPI 한 칸. */
    data class Kpi(val value: String?, val hint: String?, val tone: String?)

    /** 최근 활동 한 줄. */
    data class RecentEvent(val type: String?, val subject: String?, val ago: String?, val color: String?)

    /** 감사 체인 카드 상태: checking | done | unavailable. */
    data class ChainView(val state: String?, val reason: String?, val intact: Boolean, val ago: String?, val tamperedTail: String?) {
        companion object {
            internal fun checking(): ChainView = ChainView("checking", "", true, "", null)

            internal fun unavailable(reason: String?): ChainView = ChainView("unavailable", reason, true, "", null)
        }
    }

    // ── 화면 ────────────────────────────────────────────────

    /**
     * 전체 페이지. 로드 실패는 boost 이동이 4xx/5xx 를 스왑하지 않으므로(htmx 규칙 A) htmx 요청이면 200 + 안내 카드로 그린다
     * (Task 8 과 같은 규칙). 직접 접근이면 실제 상태(404)를 쓴다. 403 은 그대로 `WebExceptionAdvice` 가 맡는다.
     */
    @GetMapping(PAGE_BASE + "{idOrSlug}")
    fun page(
        @PathVariable idOrSlug: String,
        @RequestParam(name = "tab", required = false) tab: String?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val me = me(model)
        val requested = if (tab == null || tab.isEmpty()) "overview" else tab
        val active = if (knownTab(requested)) requested else "overview"
        model.addAttribute("platform", me.platform)
        model.addAttribute("notFound", false)
        model.addAttribute("loadError", null)
        model.addAttribute("t", null)

        val t = try {
            load(idOrSlug)
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.TENANT_NOT_FOUND) {
                model.addAttribute("breadcrumbs", Crumbs.tenant(me.platform, idOrSlug, null, active))
                model.addAttribute("notFound", true)
                res.status = if (Htmx.isHtmx(req)) HttpServletResponse.SC_OK else HttpServletResponse.SC_NOT_FOUND
                return PAGE_VIEW
            }
            return loadFailed(e, idOrSlug, tab, active, me, req, res, model)
        } catch (e: RuntimeException) {
            return loadFailed(e, idOrSlug, tab, active, me, req, res, model)
        }

        model.addAttribute("t", t)
        model.addAttribute("breadcrumbs", Crumbs.tenant(me.platform, t.id, t.name, active))
        model.addAttribute("activeTab", active)
        model.addAttribute("tabUrl", FRAG_BASE + t.id + "/tab/" + active)
        model.addAttribute(
            "tabAttrsLoad",
            java.util.Map.of(
                "hx-get", FRAG_BASE + t.id + "/tab/" + active,
                "hx-trigger", "load",
                "hx-swap", "innerHTML",
                "hx-sync", TenantTabs.SYNC,
            ),
        )
        model.addAttribute("tabLazy", "overview" != active)
        val tid = UUID.fromString(t.id)
        val expiring = expiringKeys(tid)
        val unverified = unverifiedAaguid(tid)
        model.addAttribute("tabs", tabItems(t.id, unverified, expiring))
        if (me.platform) {
            model.addAttribute("suspendAttrs", java.util.Map.of("hx-post", FRAG_BASE + t.id + "/suspend", "hx-swap", "none"))
            model.addAttribute("activateAttrs", java.util.Map.of("hx-post", FRAG_BASE + t.id + "/activate", "hx-swap", "none"))
            model.addAttribute("menuSuspend", java.util.List.of(menuItem("테넌트 정지", "suspend-tenant", true)))
            model.addAttribute("menuActivate", java.util.List.of(menuItem("정지 해제", "activate-tenant", false)))
        }
        if ("overview" != active) return PAGE_VIEW // 다른 탭의 내용은 탭 조각이 읽는다(개요 데이터를 미리 읽지 않는다)
        populateOverview(model, t, me, expiring)
        return PAGE_VIEW
    }

    /**
     * 404 가 아닌 모든 조회 실패(403·5xx 포함)는 TSX 처럼 인라인 LoadError 로 그린다(데이터 없음, 다시 시도). 문구는 `ErrorMessages`
     * (알 수 없는 예외의 원문은 싣지 않는다). 상태: 직접 접근의 403 은 403, 그 밖에는 200(boost 가 4xx/5xx 를 스왑하지 않으므로).
     */
    private fun loadFailed(
        e: RuntimeException,
        idOrSlug: String,
        tab: String?,
        active: String,
        me: CurrentAdmin,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        log.warn("[tenant-detail] load failed: {}", e.toString())
        val status = ErrorMessages.describe(e).httpStatus
        if (status != null && status == HttpServletResponse.SC_FORBIDDEN && !Htmx.isHtmx(req)) res.status = HttpServletResponse.SC_FORBIDDEN
        model.addAttribute("breadcrumbs", Crumbs.tenant(me.platform, idOrSlug, null, active))
        model.addAttribute("loadError", e)
        val url = PAGE_BASE + UriUtils.encodePathSegment(idOrSlug, StandardCharsets.UTF_8) + (if (tab == null) "" else "?tab=$active")
        val retry = LinkedHashMap<String, Any?>()
        retry["hx-get"] = url
        retry["hx-target"] = "body"
        retry["hx-swap"] = "innerHTML"
        retry["hx-push-url"] = "false"
        model.addAttribute("retryAttrs", retry)
        return PAGE_VIEW
    }

    /**
     * 탭 조각. 이 일반 매핑은 개요를 돌려주고 모르는 탭 키도 개요로 떨어진다. 다른 탭 task 가 같은 URL 의 리터럴 경로를 각자 매핑한다.
     * 조회 실패(403·404·5xx)는 `WebExceptionAdvice` 의 실제 상태 + 토스트(본문 없음) — 데이터가 노출되지 않는다.
     */
    @GetMapping(FRAG_BASE + "{id}/tab/{tab}")
    fun tab(@PathVariable id: String, @PathVariable tab: String, model: Model): String {
        val me = me(model)
        val t = load(id)
        model.addAttribute("platform", me.platform)
        populateOverview(model, t, me, expiringKeys(UUID.fromString(t.id)))
        return OVERVIEW_VIEW
    }

    /**
     * 감사 체인 카드(PLATFORM 전용: verifyTenant 는 PLATFORM API). 자동 검증은 실패해도 200 + "확인 불가" 카드, 수동 검증(`manual=1`)은
     * 실패하면 실제 오류 상태 + 오류 토스트(본문 없음)라 이전 결과가 그대로 남는다.
     */
    @GetMapping(FRAG_BASE + "{id}/overview/chain")
    fun chainCard(
        @PathVariable id: String,
        @RequestParam(name = "manual", required = false) manual: String?,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val me = me(model)
        requirePlatform(me)
        val t = load(id)
        val isManual = "1" == manual
        try {
            val r = chain.verifyTenant(t.id)
            val at = r.verifiedAt
            val tampered = r.tamperedEntryId
            val v = ChainView(
                "done", "", r.intact, if (at == null) "—" else KstFormat.timeAgo(at, clock.instant()),
                if (tampered == null) null else tail(tampered.toString(), 8),
            )
            model.addAttribute("chainView", v)
            if (isManual) {
                if (r.intact) Toasts.successTitled(res, "수동 검증 완료", "체인 무결")
                else Toasts.warnTitled(res, "수동 검증 완료", "위변조 감지")
            }
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[tenant-detail] chain verify failed (manual={}): {}", isManual, e.toString())
            val info = ErrorMessages.describe(e)
            if (isManual) {
                val status = info.httpStatus ?: HttpServletResponse.SC_INTERNAL_SERVER_ERROR // 원 Java: 상태를 모르면 500
                Toasts.errorTitled(res, "수동 검증 실패", info.toastMessage(), "HTTP $status", MDC.get("traceId"))
                res.status = status
                return EMPTY_VIEW
            }
            model.addAttribute("chainView", ChainView.unavailable(info.title + "."))
        }
        populateChainModel(model, t)
        return CHAIN_VIEW
    }

    /** 정지(L3 확인 뒤). PLATFORM 전용 — `TenantAdminController.suspend`. 성공하면 같은 탭으로 다시 그린다(원본 onReload). */
    @PostMapping(FRAG_BASE + "{id}/suspend")
    fun suspend(
        @PathVariable id: String,
        @RequestHeader(name = "HX-Current-URL", required = false) currentUrl: String?,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(me(model))
        val t = load(id)
        tenants.suspend(t.id, auth!!)
        Toasts.warnTitled(res, "테넌트가 정지되었습니다.", t.slug + " · 모든 API 키 회수됨")
        Htmx.redirect(req, res, PAGE_BASE + t.id + "?tab=" + tabFrom(currentUrl))
        return EMPTY_VIEW
    }

    /** 정지 해제(L1 확인 뒤). PLATFORM 전용 — `TenantAdminController.activate`. */
    @PostMapping(FRAG_BASE + "{id}/activate")
    fun activate(
        @PathVariable id: String,
        @RequestHeader(name = "HX-Current-URL", required = false) currentUrl: String?,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(me(model))
        val t = load(id)
        tenants.activate(t.id, auth!!)
        Toasts.successTitled(res, "정지 해제됨", "")
        Htmx.redirect(req, res, PAGE_BASE + t.id + "?tab=" + tabFrom(currentUrl))
        return EMPTY_VIEW
    }

    // ── 개요 ────────────────────────────────────────────────

    private fun populateOverview(model: Model, t: TenantDetail, me: CurrentAdmin, expiring: Int?) {
        val tid = UUID.fromString(t.id)
        model.addAttribute("t", t)
        model.addAttribute("platform", me.platform)
        model.addAttribute("credKpi", Kpi(fmt(t.credentials), null, null))
        // 7일 증감(credentialsDelta7d)은 서버 TenantView 에 없어 원본도 항상 미표시다.
        model.addAttribute(
            "keysKpi",
            Kpi(t.apiKeys.toString(), if (expiring == null) null else "만료 임박 $expiring", if (expiring != null && expiring > 0) "warn" else "default"),
        )

        // 퍼널: 실패는 "—" + 원인, 로딩은 없다(서버 렌더). "시도 없음"은 조회가 성공했을 때만.
        var f: FunnelDto.View? = null
        var funnelError: Exception? = null
        try {
            f = funnel.get(tid, 7)
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[tenant-detail] funnel failed: {}", e.toString())
            funnelError = e
        }
        model.addAttribute("funnelError", funnelError)
        model.addAttribute("regKpi", rateKpi(f, funnelError, true))
        model.addAttribute("authKpi", rateKpi(f, funnelError, false))
        val retry = TenantTabs.requesterAttrs(FRAG_BASE + t.id + "/tab/overview")
        model.addAttribute("funnelRetry", retry)

        model.addAttribute("events", recentEvents(tid, me))
        model.addAttribute("allEventsUrl", if (me.platform) "/admin/activity?tenantId=" + t.id else PAGE_BASE + t.id + "?tab=audit")
        model.addAttribute("webauthnUrl", PAGE_BASE + t.id + "?tab=webauthn")
        model.addAttribute("auditUrl", PAGE_BASE + t.id + "?tab=audit")
        if (me.platform) {
            // 첫 화면은 "확인 중" 카드 + load 트리거(검증은 느릴 수 있다). 실제 검증은 chainCard 가 한다.
            model.addAttribute("chainView", ChainView.checking())
            populateChainModel(model, t)
        }
    }

    private fun populateChainModel(model: Model, t: TenantDetail) {
        model.addAttribute("t", t)
        model.addAttribute("chainUrl", FRAG_BASE + t.id + "/overview/chain")
        model.addAttribute("auditUrl", PAGE_BASE + t.id + "?tab=audit")
        model.addAttribute("chainTimeoutText", CHAIN_TIMEOUT_SECONDS.toString() + "초 안에 응답이 없었습니다.")
    }

    private fun rateKpi(f: FunnelDto.View?, error: Exception?, registration: Boolean): Kpi {
        val none = if (registration) "최근 7일 등록 시도 없음" else "최근 7일 인증 시도 없음"
        if (error != null) return Kpi("—", "불러오지 못했습니다", "warn")
        // !!: 오류가 없으면 f 는 funnel.get 의 반환값 — 원 Java f.getRegistration() 의 NPE 자리. 단계가 null 이면 s.getAttempts() 의 NPE 자리.
        val view = f!!
        val s = (if (registration) view.registration else view.authentication)!!
        if (s.attempts > 0) {
            return Kpi(String.format(Locale.ROOT, "%.1f%%", s.ratio * 100), fmt(s.success) + " / " + fmt(s.attempts) + " 시도", "default")
        }
        return Kpi("—", none, "default")
    }

    /**
     * 최근 5건. PLATFORM: 활동 피드(PLATFORM 전용) 중 이 tenant 만 — 원본은 전체 피드를 받아 클라이언트에서 걸렀지만 REST 가
     * tenantId 필터를 지원하므로 서버에서 건다(이 tenant 의 이벤트가 전체 피드의 최근 N건 밖이어도 보인다).
     * RP_ADMIN: 이 tenant 의 감사 로그. 실패는 빈 목록(원본 catch).
     */
    private fun recentEvents(tid: UUID, me: CurrentAdmin): List<RecentEvent> {
        val now = clock.instant()
        val out = ArrayList<RecentEvent>()
        try {
            if (me.platform) {
                val view = activity.activity(null, null, null, null, tid).data()
                // !!: 원 Java for-each 의 NPE 자리(try 안 — 빈 목록으로).
                for (e in view.feed!!) {
                    if (tid != e.tenantId) continue
                    out.add(event(e.action, e.targetId, e.createdAt, now))
                    if (out.size == 5) break
                }
            } else {
                for (e in audit.list(null, null, tid, null, null, 0, 5).data()) {
                    out.add(event(e.action, e.targetId, e.createdAt, now))
                    if (out.size == 5) break
                }
            }
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[tenant-detail] recent events failed: {}", e.toString())
            return java.util.List.of()
        }
        return out
    }

    // ── 탭 신호(tenantSignals.ts) ─────────────────────────

    /** 30일 안에 만료되는 ACTIVE 키 수. 조회 실패는 null(원본: 조용히 무시). */
    private fun expiringKeys(tid: UUID): Int? {
        try {
            val now = clock.instant()
            val keys = apiKeys.list(tid.toString()).data()
            return keys.count { k -> ApiKeyExpiry.isExpiringSoon(k.revokedAt, k.expiresAt, now) }
        } catch (e: RuntimeException) {
            return null
        }
    }

    /** mode≠ANY 이고 mdsName==null 인 AAGUID 수(목록에 보이는 항목만). 조회 실패는 null. */
    private fun unverifiedAaguid(tid: UUID): Int? {
        try {
            // nullable 로 받는다: 원 Java 의 p == null 검사(목 등에서 null) 를 그대로 둔다.
            val p: AaguidPolicyDto.View? = aaguid.get(tid)
            val mode = p?.mode
            if (p == null || mode == null || "ANY" == mode.name) return 0
            return p.entries.count { e -> e.aaguid != null && e.mdsName == null }
        } catch (e: RuntimeException) {
            return null
        }
    }

    private fun tabItems(tenantId: String, unverified: Int?, expiring: Int?): List<Map<String, Any?>> {
        val items = ArrayList<Map<String, Any?>>()
        for (def in TABS) {
            val id = def[0]
            val m = LinkedHashMap<String, Any?>()
            m["id"] = id
            m["label"] = def[1]
            m["href"] = "$PAGE_BASE$tenantId?tab=$id"
            m["elId"] = "tenant-tab-$id"
            // 탭 클릭 = 조각 교체 + 주소 갱신. 활성 표시·마지막 브레드크럼은 ui.js(htmx:afterRequest)가 data-* 로 바꾼다.
            val attrs = LinkedHashMap<String, Any?>()
            attrs["hx-get"] = "$FRAG_BASE$tenantId/tab/$id"
            attrs["hx-target"] = "#" + TenantTabs.TARGET_ID
            attrs["hx-swap"] = "innerHTML"
            attrs["hx-push-url"] = "$PAGE_BASE$tenantId?tab=$id"
            attrs["hx-sync"] = TenantTabs.SYNC
            attrs["data-guard-dirty"] = "true" // 저장 안 한 변경이 있으면 app.js 가 요청을 취소하고 이동 확인을 띄운다
            attrs["data-tab"] = id
            attrs["data-label"] = def[1]
            m["attrs"] = attrs
            if ("aaguid" == id && unverified != null && unverified > 0) {
                m["badgeText"] = "확인 필요 $unverified"
                m["badgeReason"] = "MDS 미확인 AAGUID " + unverified + "건"
            }
            if ("apikeys" == id && expiring != null && expiring > 0) {
                m["badgeText"] = "확인 필요 $expiring"
                m["badgeReason"] = "30일 안에 만료되는 키 " + expiring + "개"
            }
            items.add(m)
        }
        return items
    }

    // ── 도우미 ──────────────────────────────────────────────

    private fun load(idOrSlug: String): TenantDetail = TenantDetail.from(TenantTabs.load(tenants, idOrSlug))

    companion object {
        private const val PAGE_VIEW = "admin/tenant/detail"
        private const val OVERVIEW_VIEW = "admin/tenant/overview :: content"
        private const val CHAIN_VIEW = "admin/tenant/overview :: chainCard"
        private const val EMPTY_VIEW = "admin/fragments/shell/empty :: none"

        private const val PAGE_BASE = "/admin/tenants/"
        private const val FRAG_BASE = "/admin/_/tenants/"

        /** TenantDetailPage.tsx 의 TENANT_TABS (id, 라벨). 첫 항목이 기본이다. */
        private val TABS: List<Array<String>> = java.util.List.of(
            arrayOf("overview", "개요"),
            arrayOf("webauthn", "WebAuthn"),
            arrayOf("aaguid", "AAGUID 정책"),
            arrayOf("apikeys", "API 키"),
            arrayOf("credentials", "Credentials"),
            arrayOf("audit", "감사 로그"),
            arrayOf("funnel", "인증 현황"),
        )

        /** 체인 검증 응답을 이 시간(초)까지 기다린 뒤 "확인 불가"로 바꾼다(CHAIN_TIMEOUT_MS = 15_000). 템플릿의 hx-request 와 같은 값. */
        private const val CHAIN_TIMEOUT_SECONDS = 15

        private val FAILURE_TYPE: Pattern = Pattern.compile("FAIL|REVOKED|REGRESSION|TAMPER")
        private val INT: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

        private fun event(type: String?, subjectId: String?, at: OffsetDateTime?, now: Instant): RecentEvent {
            val t = type ?: ""
            val subject = if (subjectId == null || subjectId.isEmpty()) "—" else subjectId
            return RecentEvent(t, "…" + tail(subject, 16), if (at == null) "—" else KstFormat.timeAgo(at.toInstant(), now), eventColor(t))
        }

        /**
         * 이벤트 유형 점 색. 유형 이름 태그가 함께 표시되므로 색만으로 의미를 전달하지 않는다.
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(TenantDetailPageControllerTest)가 부른다.
         */
        @JvmStatic
        fun eventColor(type: String): String {
            if (FAILURE_TYPE.matcher(type).find()) return "var(--bad)"
            if (type.startsWith("API_KEY") || type.endsWith("_UPDATED")) return "var(--warn-dot)"
            return "var(--brand-700)"
        }

        /** 더보기 메뉴 항목. 클릭하면 확인 다이얼로그를 연다(메뉴 자체의 click 처리는 그대로 두려고 .capture 로 따로 붙인다). */
        private fun menuItem(label: String, dialogId: String, danger: Boolean): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["label"] = label
            m["danger"] = danger
            m["attrs"] = java.util.Map.of("x-on:click.capture", "\$dispatch('dialog:open', { id: '$dialogId' })")
            return m
        }

        private fun knownTab(key: String?): Boolean = TABS.any { t -> t[0] == key }

        /**
         * 다이얼로그를 연 화면의 탭(HX-Current-URL 의 tab). 모르는 값이면 개요.
         * public `@JvmStatic`: 같은 패키지 Java 테스트(TenantDetailPageControllerTest)가 부른다.
         */
        @JvmStatic
        fun tabFrom(currentUrl: String?): String {
            if (currentUrl == null) return "overview"
            return try {
                val tab: String? = UriComponentsBuilder.fromUriString(currentUrl).build().queryParams.getFirst("tab")
                if (tab != null && knownTab(tab)) tab else "overview"
            } catch (e: RuntimeException) {
                "overview"
            }
        }

        private fun tail(s: String, n: Int): String = if (s.length <= n) s else s.substring(s.length - n)

        private fun fmt(n: Long): String = INT.format(n)

        private fun requirePlatform(me: CurrentAdmin) {
            if (!me.platform) throw AccessDeniedException("platform operator only")
        }

        private fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")
    }
}
