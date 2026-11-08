package com.crosscert.passkey.admin.web.insights

import com.crosscert.passkey.admin.insights.PlatformInsightsController
import com.crosscert.passkey.admin.insights.PlatformInsightsDto
import com.crosscert.passkey.admin.web.HomeRedirectController
import com.crosscert.passkey.admin.web.format.CsvWriter
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.shell.Crumbs
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.tenant.FunnelHelpers
import com.crosscert.passkey.admin.web.tenant.FunnelTabController
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Comparator
import java.util.Locale
import java.util.function.Function
import java.util.function.ToDoubleFunction

private val log = LoggerFactory.getLogger(InsightsPageController::class.java)

private const val PAGE_VIEW = "admin/insights/page"
private const val ROOT_VIEW = "admin/insights/page :: root"

private val WINDOWS: List<Int> = java.util.List.of(1, 7, 30)
private val WIN_LABEL: Map<Int, String> = java.util.Map.of(1, "24h", 7, "7d", 30, "30d")
private val PERIOD_LABEL: Map<Int, String> = java.util.Map.of(1, "최근 24시간", 7, "최근 7일", 30, "최근 30일")

/** 정렬 키 → [머리글, 값]. 모두 큰 값 먼저. */
private val SORT_LABEL: Map<String, String> = linked(
    "users", "활성 사용자", "auth", "인증 성공", "rate", "인증 성공률", "reg", "등록 성공률",
    "risk", "위험 인증", "creds", "패스키", "single", "1개뿐 비율",
)

/**
 * 플랫폼 전체 현황 화면. PLATFORM 전용 — RP_ADMIN 은 전체 페이지에서 자기 홈으로 보내고(활동 화면과 같은 규칙) 조각·CSV 는 403.
 * 전체 페이지 `GET /admin/insights`, 기간·정렬 전환 `GET /admin/_/insights`(#insights-root 교체),
 * CSV `GET /admin/insights.csv?kind=tenants|monthly|devices`. 카드 모양·계산은 테넌트 인증 현황 탭과 같은 조각·도우미를 쓴다.
 */
@Controller
class InsightsPageController(
    private val insights: PlatformInsightsController,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    data class AlertView(val kindLabel: String?, val badgeClass: String?, val name: String?, val href: String?, val message: String?)

    data class TenantView(
        val name: String?,
        val slug: String?,
        val href: String?,
        val statusLabel: String?,
        val statusClass: String?,
        val usersText: String?,
        val authText: String?,
        val authRateText: String?,
        val regRateText: String?,
        val riskText: String?,
        val riskWarn: Boolean,
        val credsText: String?,
        val singleText: String?,
    )

    data class SortHeader(val key: String?, val label: String?, val active: Boolean, val attrs: Map<String, Any?>?)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(PAGE_PATH)
    fun page(
        @RequestParam(name = "win", defaultValue = "7") win: String,
        @RequestParam(name = "sort", defaultValue = "users") sort: String,
        model: Model,
    ): String {
        val me = me(model)
        model.addAttribute("breadcrumbs", Crumbs.insights())
        if (!me.platform) return "redirect:" + HomeRedirectController.homePath(me)
        populate(model, window(win), sort(sort))
        return PAGE_VIEW
    }

    @GetMapping(FRAG_PATH)
    fun root(
        @RequestParam(name = "win", defaultValue = "7") win: String,
        @RequestParam(name = "sort", defaultValue = "users") sort: String,
        model: Model,
    ): String {
        if (!me(model).platform) throw AccessDeniedException("PLATFORM_OPERATOR only")
        populate(model, window(win), sort(sort))
        return ROOT_VIEW
    }

    private fun populate(model: Model, w: Int, sort: String) {
        model.addAttribute("win", w.toString())
        model.addAttribute("sort", sort)
        val options = ArrayList<Map<String, Any?>>()
        for (k in WINDOWS) {
            val o = LinkedHashMap<String, Any?>()
            o["value"] = k.toString()
            o["label"] = WIN_LABEL[k]
            o["title"] = null
            o["attrs"] = get("$FRAG_PATH?win=$k&sort=$sort")
            options.add(o)
        }
        model.addAttribute("segOptions", options)
        model.addAttribute("retryAttrs", get("$FRAG_PATH?win=$w&sort=$sort"))

        val v: PlatformInsightsDto.View
        try {
            v = insights.get(w)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[insights] load failed: {}", e.javaClass.simpleName)
            model.addAttribute("loadError", e)
            model.addAttribute("hasData", false)
            return
        }
        model.addAttribute("loadError", null)
        model.addAttribute("hasData", true)
        val period = PERIOD_LABEL[w]
        model.addAttribute("period", period)

        // 원 Java 는 totals 를 바로 역참조 — null 이면 같은 지점에서 NPE.
        val t = v.totals!!
        val authRate = FunnelHelpers.rate(t.authSuccess, t.authAttempts)
        val regRate = FunnelHelpers.rate(t.regSuccess, t.regAttempts)
        val single: Double? = if (t.users > 0) t.singlePasskeyUsers.toDouble() / t.users else null
        model.addAttribute(
            "kpis",
            java.util.List.of(
                FunnelTabController.AdoptKpi("활성 테넌트", num(t.activeTenants) + "개", "전체 " + num(t.tenants) + "개", false),
                FunnelTabController.AdoptKpi("활성 사용자", num(t.activeUsers) + "명", "$period 인증 성공 사용자", false),
                FunnelTabController.AdoptKpi("인증 성공", num(t.authSuccess) + "건", "시도 " + num(t.authAttempts) + "건", false),
                FunnelTabController.AdoptKpi(
                    "인증 성공률", FunnelHelpers.fmtPct(authRate),
                    "등록 성공률 " + FunnelHelpers.fmtPct(regRate), false,
                ),
                FunnelTabController.AdoptKpi(
                    "차단된 위험 인증", num(t.risk) + "건",
                    if (t.risk == 0L) "위험 인증 없음" else "$period 검증 단계에서 거절", t.risk > 0,
                ),
                FunnelTabController.AdoptKpi(
                    "누적 패스키", num(t.credentials) + "개",
                    "사용자 " + num(t.users) + "명 · 1개뿐 " + FunnelHelpers.fmtPct(single), false,
                ),
            ),
        )

        val alerts = ArrayList<AlertView>()
        // 원 Java 의 for-each·switch(String)·toString() 역참조 — null 이면 같은 지점에서 NPE.
        for (a in v.alerts!!) {
            val k: Array<String> = when (a.kind!!) {
                "RISK" -> arrayOf("위험 인증", "badge--danger")
                "RATE_DROP" -> arrayOf("성공률 하락", "badge--warning")
                else -> arrayOf("패스키 1개뿐", "badge--info")
            }
            alerts.add(AlertView(k[0], k[1], a.name, tenantHref(a.tenantId!!.toString()), a.message))
        }
        model.addAttribute("alerts", alerts)
        // 원 Java 는 thresholds 를 바로 역참조 — null 이면 같은 지점에서 NPE.
        val th = v.thresholds!!
        model.addAttribute(
            "thresholdText",
            String.format(
                Locale.ROOT,
                "기준(최근 7일): 위험 인증 %d건 이상 · 인증 성공률 %s%%p 이상 하락(시도 %d건 이상) · 패스키 1개뿐 %s%% 이상(사용자 %d명 이상)",
                th.riskMin, trim(th.rateDropPp), th.rateDropMinAttempts, trim(th.singleRatio * 100), th.singleMinUsers,
            ),
        )

        // 원 Java new ArrayList<>(null) 의 NPE 를 같은 지점에서.
        val rows = ArrayList<PlatformInsightsDto.TenantRow>(v.tenants!!)
        rows.sortWith(comparator(sort))
        val tenants = ArrayList<TenantView>()
        for (r in rows) {
            val active = "active".equals(r.status, ignoreCase = true)
            tenants.add(
                TenantView(
                    r.name, r.slug, tenantHref(r.tenantId!!.toString()), // 원 Java getTenantId().toString() NPE 지점
                    if (active) "활성" else (if ("suspended".equals(r.status, ignoreCase = true)) "정지" else r.status.toString()),
                    if (active) "badge--success" else "badge--warning",
                    num(r.activeUsers), num(r.authSuccess),
                    FunnelHelpers.fmtPct(FunnelHelpers.rate(r.authSuccess, r.authAttempts)),
                    FunnelHelpers.fmtPct(FunnelHelpers.rate(r.regSuccess, r.regAttempts)),
                    num(r.risk), r.risk > 0, num(r.credentials),
                    FunnelHelpers.fmtPct(if (r.users > 0) r.singlePasskeyUsers.toDouble() / r.users else null),
                ),
            )
        }
        model.addAttribute("tenants", tenants)
        val headers = ArrayList<SortHeader>()
        for ((key, label) in SORT_LABEL) {
            headers.add(SortHeader(key, label, key == sort, get("$FRAG_PATH?win=$w&sort=$key")))
        }
        model.addAttribute("sortHeaders", headers)

        val q = "?win=$w"
        model.addAttribute("seriesCard", FunnelTabController.seriesCard("auth", "플랫폼 일별 인증 시도와 성공", v.authSeries, "$CSV_PATH$q&kind=daily"))
        model.addAttribute("seriesColumns", FunnelTabController.SERIES_COLUMNS)
        model.addAttribute("months", FunnelTabController.monthViews(v.months))
        model.addAttribute("monthColumns", FunnelTabController.MONTH_COLUMNS)
        model.addAttribute("devices", FunnelTabController.deviceViews(v.devices))
        model.addAttribute("csvTenants", "$CSV_PATH$q&kind=tenants")
        model.addAttribute("csvMonthly", "$CSV_PATH$q&kind=monthly")
        model.addAttribute("csvDevices", "$CSV_PATH$q&kind=devices")
    }

    /** CSV: 테넌트별 비교(과금·라이선스 자료), 일별 인증, 월간 사용량, 기기 분포. */
    @GetMapping(CSV_PATH)
    fun export(
        @RequestParam(name = "win", defaultValue = "7") win: String,
        @RequestParam(name = "kind", defaultValue = "tenants") kind: String,
        model: Model,
    ): ResponseEntity<ByteArray> {
        if (!me(model).platform) throw AccessDeniedException("PLATFORM_OPERATOR only")
        val w = window(win)
        val v = insights.get(w)
        val today = KstFormat.kstToday(clock.instant())
        val rows = ArrayList<List<String?>>()
        val csv: ByteArray
        val filename: String
        // 행은 java.util.List.of — 원 Java 와 같이 null 칸(slug·day·month 등)은 NPE. 목록 null 도 for-each 의 NPE 지점에서 `!!`.
        when (kind) {
            "tenants" -> {
                for (r in v.tenants!!) {
                    rows.add(
                        java.util.List.of(
                            r.slug, r.name.toString(), r.status.toString(), r.activeUsers.toString(),
                            r.authAttempts.toString(), r.authSuccess.toString(), rate4(r.authSuccess, r.authAttempts),
                            r.regAttempts.toString(), r.regSuccess.toString(), rate4(r.regSuccess, r.regAttempts),
                            r.risk.toString(), r.credentials.toString(), r.users.toString(), r.singlePasskeyUsers.toString(),
                        ),
                    )
                }
                csv = CsvWriter.write(
                    java.util.List.of(
                        "slug", "name", "status", "activeUsers", "authAttempts", "authSuccess", "authSuccessRate",
                        "regAttempts", "regSuccess", "regSuccessRate", "riskBlocked", "passkeys", "users", "singlePasskeyUsers",
                    ),
                    rows,
                )
                filename = "platform-tenants-${w}d-$today.csv"
            }
            "daily" -> {
                for (p in v.authSeries!!) {
                    rows.add(java.util.List.of(p.day, p.attempts.toString(), p.success.toString(), rate4(p.success, p.attempts)))
                }
                csv = CsvWriter.write(java.util.List.of("day", "attempts", "success", "successRate"), rows)
                filename = "platform-auth-daily-${w}d-$today.csv"
            }
            "monthly" -> {
                for (m in v.months!!) {
                    rows.add(
                        java.util.List.of(
                            m.month, if (m.current) "Y" else "N", m.authSuccess.toString(), m.activeUsers.toString(), m.newCredentials.toString(),
                        ),
                    )
                }
                csv = CsvWriter.write(java.util.List.of("month", "inProgress", "authSuccess", "activeUsers", "newPasskeys"), rows)
                filename = "platform-monthly-usage-$today.csv"
            }
            "devices" -> {
                for (d in v.devices!!) rows.add(java.util.List.of(d.name, d.credentials.toString(), d.authSuccess.toString()))
                csv = CsvWriter.write(java.util.List.of("authenticator", "passkeys", "authSuccessInWindow"), rows)
                filename = "platform-devices-${w}d-$today.csv"
            }
            else -> throw BusinessException(ErrorCode.ENTITY_NOT_FOUND, "알 수 없는 내보내기")
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv)
    }

    companion object {
        internal const val PAGE_PATH = "/admin/insights"
        internal const val FRAG_PATH = "/admin/_/insights"
        internal const val CSV_PATH = "/admin/insights.csv"

        // ── 도우미 ──────────────────────────────────────────────

        private fun window(s: String): Int {
            return try {
                val n = Integer.parseInt(s.javaTrim())
                if (WINDOWS.contains(n)) n else 7
            } catch (e: RuntimeException) {
                7
            }
        }

        private fun sort(s: String): String = if (SORT_LABEL.containsKey(s)) s else "users"

        /** 고른 값이 큰 순, 같으면 이름 순. 비율이 없는(시도·사용자 0) 테넌트는 뒤로. */
        private fun comparator(sort: String): Comparator<PlatformInsightsDto.TenantRow> {
            val key: ToDoubleFunction<PlatformInsightsDto.TenantRow> = when (sort) {
                "auth" -> ToDoubleFunction { r -> r.authSuccess.toDouble() }
                "rate" -> ToDoubleFunction { r -> if (r.authAttempts > 0) r.authSuccess.toDouble() / r.authAttempts else -1.0 }
                "reg" -> ToDoubleFunction { r -> if (r.regAttempts > 0) r.regSuccess.toDouble() / r.regAttempts else -1.0 }
                "risk" -> ToDoubleFunction { r -> r.risk.toDouble() }
                "creds" -> ToDoubleFunction { r -> r.credentials.toDouble() }
                "single" -> ToDoubleFunction { r -> if (r.users > 0) r.singlePasskeyUsers.toDouble() / r.users else -1.0 }
                else -> ToDoubleFunction { r -> r.activeUsers.toDouble() }
            }
            return Comparator.comparingDouble(key).reversed()
                .thenComparing(Function<PlatformInsightsDto.TenantRow, String> { r -> r.name ?: "" })
        }

        private fun rate4(success: Long, attempts: Long): String {
            val r = FunnelHelpers.rate(success, attempts)
            return if (r == null) "" else FunnelHelpers.toFixed(r, 4)
        }

        private fun trim(d: Double): String = if (d == Math.rint(d)) d.toLong().toString() else d.toString()

        private fun tenantHref(id: String): String = "/admin/tenants/$id?tab=funnel"

        /**
         * #insights-root 를 교체하는 요청(기간·정렬·다시 시도). 대상은 요소마다 직접 준다 — 루트에 hx-target 을 두면 안의 링크(boost)가
         * 그것을 물려받아 이동한 페이지 전체가 루트 안에 그려진다(테넌트 링크 클릭 시 페이지 안에 페이지).
         */
        private fun get(url: String): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = url
            m["hx-target"] = "#insights-root"
            m["hx-swap"] = "outerHTML"
            m["hx-sync"] = "#insights-root:replace"
            return m
        }

        private fun denied(e: RuntimeException): Boolean =
            e is AccessDeniedException || (e is BusinessException && e.errorCode == ErrorCode.ACCESS_DENIED)

        private fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")

        private fun num(n: Long): String = String.format(Locale.KOREA, "%,d", n)
    }
}

private fun linked(vararg kv: String): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    var i = 0
    while (i < kv.size) {
        m[kv[i]] = kv[i + 1]
        i += 2
    }
    return m
}
