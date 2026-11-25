package com.crosscert.passkey.admin.web.insights

import com.crosscert.passkey.admin.usage.UsageDto
import com.crosscert.passkey.admin.usage.UsageSettlementController
import com.crosscert.passkey.admin.web.HomeRedirectController
import com.crosscert.passkey.admin.web.format.CsvWriter
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.shell.Crumbs
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.tenant.FunnelHelpers
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
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
import java.util.Locale

private const val VIEW = "admin/insights/usage"

/**
 * 사용량 정산 화면. PLATFORM 전용 — RP_ADMIN 은 화면에서 자기 홈으로 보내고(현황과 같은 규칙) CSV 는 403.
 * 화면 `GET /admin/insights/usage?period=YYYY-MM`, CSV `GET /admin/insights/usage.csv?period=YYYY-MM&kind=tenants|trend`.
 * 기간 대체(형식 오류·미래 달 → 이번 달)는 REST 컨트롤러가 한다. 숫자는 그대로 보여 주고 여기서 새로 계산하지 않는다
 * (성공률 참고 열만 시도 건수로 나눈다).
 */
@Controller
class UsageSettlementPageController(
    private val usage: UsageSettlementController,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    data class TenantView(val name: String, val slug: String?, val regText: String, val authText: String, val totalText: String)

    data class TrendView(val period: String, val regText: String, val authText: String, val tenantsText: String, val rateText: String)

    data class PeriodOption(val value: String, val selected: Boolean)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(PAGE_PATH)
    fun page(@RequestParam(name = "period", required = false) period: String?, model: Model): String {
        val me = me(model)
        model.addAttribute("breadcrumbs", Crumbs.usageSettlement())
        if (!me.platform) return "redirect:" + HomeRedirectController.homePath(me)
        populate(model, usage.get(period))
        return VIEW
    }

    private fun populate(model: Model, v: UsageDto.View) {
        model.addAttribute("tabItems", InsightsPageController.tabItems())
        model.addAttribute("tabActive", InsightsPageController.TAB_USAGE)
        model.addAttribute("period", v.period)
        model.addAttribute("statusLabel", statusLabel(v.status))
        model.addAttribute("statusClass", if (v.status == UsageDto.Status.IN_PROGRESS) "badge--info" else "badge--success")
        model.addAttribute("basisLabel", if (v.basis == "LEDGER") "장부" else "이전 방식")
        // 진행 중인 달은 대조할 기록이 없어 결과를 보이지 않는다.
        model.addAttribute(
            "verificationLabel",
            when (v.verification) {
                UsageDto.Verification.MATCH -> "감사 로그와 일치"
                UsageDto.Verification.MISMATCH -> "감사 로그와 불일치"
                UsageDto.Verification.NOT_APPLICABLE -> null
            },
        )
        model.addAttribute("verificationClass", if (v.verification == UsageDto.Verification.MISMATCH) "badge--danger" else "badge--success")
        model.addAttribute("asOfLabel", if (v.status == UsageDto.Status.IN_PROGRESS) "집계 기준" else "마감")
        model.addAttribute("asOfText", if (v.asOf == null) null else KstFormat.kstDateTimeMin(v.asOf))

        model.addAttribute(
            "tenants",
            v.tenants.map { TenantView(it.name, it.slug, num(it.regSuccess), num(it.authSuccess), num(it.regSuccess + it.authSuccess)) },
        )
        model.addAttribute("totalReg", num(v.totalReg))
        model.addAttribute("totalAuth", num(v.totalAuth))
        model.addAttribute("totalAll", num(v.totalReg + v.totalAuth))
        model.addAttribute(
            "trend",
            v.trend.map {
                TrendView(it.period, num(it.regSuccess), num(it.authSuccess), num(it.activeTenants.toLong()), FunnelHelpers.fmtPct(FunnelHelpers.rate(it.authSuccess, it.authAttempt)))
            },
        )
        // 목록 밖의 과거 달을 직접 열었어도 선택값이 보이도록 덧붙인다(0 으로 표시되는 달).
        val options = v.periods.map { PeriodOption(it, it == v.period) }
        model.addAttribute("periodOptions", if (v.period in v.periods) options else options + PeriodOption(v.period, true))
        model.addAttribute("csvTenants", "$CSV_PATH?period=${v.period}&kind=tenants")
        model.addAttribute("csvTrend", "$CSV_PATH?period=${v.period}&kind=trend")
    }

    /** CSV: 선택한 달의 테넌트별 숫자(청구 근거), 최근 12개월 추이. */
    @GetMapping(CSV_PATH)
    fun export(
        @RequestParam(name = "period", required = false) period: String?,
        @RequestParam(name = "kind", defaultValue = "tenants") kind: String,
        model: Model,
    ): ResponseEntity<ByteArray> {
        if (!me(model).platform) throw AccessDeniedException("PLATFORM_OPERATOR only")
        val v = usage.get(period)
        val rows = ArrayList<List<String?>>()
        val csv: ByteArray
        val filename: String
        when (kind) {
            "tenants" -> {
                // 진행 중인 달은 마감 시각이 없다(빈 칸).
                val closedAt = if (v.status == UsageDto.Status.IN_PROGRESS) "" else KstFormat.kstDateTime(v.asOf)
                for (t in v.tenants) {
                    rows.add(
                        listOf(
                            v.period, t.tenantId.toString(), t.slug, t.name, t.regSuccess.toString(), t.authSuccess.toString(),
                            (t.regSuccess + t.authSuccess).toString(), v.basis, v.status.name, closedAt,
                        ),
                    )
                }
                csv = CsvWriter.write(
                    listOf("period", "tenantId", "slug", "name", "registrationSuccess", "authenticationSuccess", "total", "basis", "status", "closedAt"),
                    rows,
                )
                filename = "usage-settlement-${v.period}.csv"
            }
            "trend" -> {
                for (m in v.trend) {
                    rows.add(
                        listOf(
                            m.period, m.status.name, m.regSuccess.toString(), m.authSuccess.toString(), m.activeTenants.toString(),
                            m.authAttempt.toString(), rate4(m.authSuccess, m.authAttempt),
                        ),
                    )
                }
                csv = CsvWriter.write(
                    listOf("period", "status", "registrationSuccess", "authenticationSuccess", "activeTenants", "authenticationAttempts", "authSuccessRate"),
                    rows,
                )
                filename = "usage-trend-${KstFormat.kstToday(clock.instant())}.csv"
            }
            else -> throw BusinessException(ErrorCode.ENTITY_NOT_FOUND, "알 수 없는 내보내기")
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv)
    }

    companion object {
        internal const val PAGE_PATH = "/admin/insights/usage"
        internal const val CSV_PATH = "/admin/insights/usage.csv"

        private fun statusLabel(s: UsageDto.Status): String = when (s) {
            UsageDto.Status.IN_PROGRESS -> "진행 중"
            UsageDto.Status.CLOSED -> "마감"
            UsageDto.Status.PARTIAL -> "부분(원본 삭제됨)"
        }

        /** 시도가 0 이면 빈 칸(기존 현황 CSV 와 같은 형식). */
        private fun rate4(success: Long, attempts: Long): String {
            val r = FunnelHelpers.rate(success, attempts)
            return if (r == null) "" else FunnelHelpers.toFixed(r, 4)
        }

        private fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")

        private fun num(n: Long): String = String.format(Locale.KOREA, "%,d", n)
    }
}
