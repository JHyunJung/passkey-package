package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.funnel.AdoptionController
import com.crosscert.passkey.admin.funnel.AdoptionDto
import com.crosscert.passkey.admin.funnel.FunnelController
import com.crosscert.passkey.admin.funnel.FunnelDto
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.format.CsvWriter
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(FunnelTabController::class.java)

/**
 * Tenant 상세의 "인증 현황" 탭(URL 키는 기존 funnel 그대로). 등록·인증 성공률, 일별 시도와 성공, 인증 실패 원인을 보여준다.
 *
 * 등록과 인증은 서로 다른 흐름이라(한 번 등록한 사용자가 여러 번 인증) 한 줄 퍼널로 잇지 않고 따로 계산한다.
 * 성공하지 못한 시도에는 사용자 취소·시간 초과도 섞여 있으므로 절대값보다 추이를 보는 지표다.
 *
 * 모든 엔드포인트가 [TenantTabs.load] 를 먼저 부른다. 막대는 서버가 그린 div 이고 높이는 숫자만 th:style 에 들어간다
 * — 색은 CSS 클래스. 실패 원인 코드·이름은 th:text/th:title 로만 쓴다.
 * 기간 전환·다시 시도는 `#funnel-root` 만 교체한다.
 *
 * `v.id!!`·`s!!`·`series!!` 는 원 Java 의 NPE 자리(`toString()`·Kotlin non-null 파라미터로 넘김·역참조)다.
 */
@Controller
class FunnelTabController(
    private val tenants: TenantAdminController,
    private val funnel: FunnelController,
    private val adoption: AdoptionController,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** 도입·사용 지표 카드 1장. warn 이면 힌트를 경고색으로. */
    data class AdoptKpi(val label: String?, val value: String?, val hint: String?, val warn: Boolean)

    data class MonthView(val label: String?, val authText: String?, val mauText: String?, val newText: String?)

    /** 기기 분포 1줄. widthPct 는 패스키 수 / 전체 패스키 퍼센트 숫자(막대 너비). */
    data class DeviceView(val name: String?, val countText: String?, val shareText: String?, val widthPct: Double, val authText: String?)

    /** 성공률 지표 카드 1장(등록·인증). */
    data class RateKpi(val label: String?, val value: String?, val hint: String?)

    /** dateText·tipText 는 막대에 마우스를 올리면 뜨는 말풍선 문구(예: "10월 29일", "시도 10 · 성공 9 · 90.0%"). */
    data class DayView(
        val day: String?,
        val attempts: Long,
        val success: Long,
        val attemptsText: String?,
        val successText: String?,
        val rateText: String?,
        val attemptsPct: Double,
        val successPct: Double,
        val dateText: String?,
        val tipText: String?,
    )

    /** 일별 시도·성공 카드 1장(등록·인증). key 는 CSV kind·요소 구분용. */
    data class SeriesCard(
        val key: String?,
        val title: String?,
        val days: List<DayView>?,
        val axis: List<String?>?,
        val dense: Boolean,
        val aria: String?,
        val csv: String?,
    )

    /** 실패 원인 1줄. code 는 원래 코드(title 로만 노출). */
    data class FailureView(val code: String?, val label: String?, val meaning: String?, val nText: String?)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/funnel")
    fun tab(@PathVariable id: String, @RequestParam(name = "win", defaultValue = "7") win: String, model: Model): String {
        val v = TenantTabs.load(tenants, id)
        val w = window(win)
        val now = clock.instant()
        // !!: 원 Java tid.toString() 의 NPE 와 같은 자리.
        val tid = v.id!!
        model.addAttribute("tid", tid.toString())
        model.addAttribute("win", w.toString())

        val options = ArrayList<Map<String, Any?>>()
        for (k in WINDOWS) {
            val o = LinkedHashMap<String, Any?>()
            o["value"] = k.toString()
            o["label"] = WIN_LABEL[k]
            o["title"] = null
            o["attrs"] = get("$FRAG$tid/tab/funnel?win=$k")
            options.add(o)
        }
        model.addAttribute("segOptions", options)
        val retry = get("$FRAG$tid/tab/funnel?win=$w")
        model.addAttribute("retryAttrs", retry)

        val data = try {
            funnel.get(tid, w)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[funnel-tab] load failed: {}", e.javaClass.simpleName)
            model.addAttribute("loadError", e)
            model.addAttribute("hasData", false)
            return TAB_VIEW
        }
        model.addAttribute("loadError", null)
        model.addAttribute("hasData", true)

        // 직전 기간 대비: 7d 에서만. 30일 series 로 최근 7일 vs 그 앞 7일. 실패하면 비교 문구만 생략.
        var regDelta: Double? = null
        var authDelta: Double? = null
        if (w == 7) {
            try {
                val d30 = funnel.get(tid, 30)
                regDelta = FunnelHelpers.priorPeriodDelta(orEmpty(d30.registrationSeries), 7)
                authDelta = FunnelHelpers.priorPeriodDelta(orEmpty(d30.authenticationSeries), 7)
            } catch (e: RuntimeException) {
                if (denied(e)) throw e
                log.debug("[funnel-tab] 30d comparison skipped: {}", e.javaClass.simpleName)
            }
        }
        model.addAttribute(
            "rateKpis",
            java.util.List.of(
                rateKpi("등록 성공률", data.registration, regDelta),
                rateKpi("인증 성공률", data.authentication, authDelta),
            ),
        )

        val fails = orEmpty(data.authFailures)
        var failTotal = 0L
        for (f in fails) failTotal += f.n
        val failures = ArrayList<FailureView>()
        val failParts = ArrayList<String>()
        for (f in fails) {
            failures.add(FailureView(f.reason ?: "", failureLabel(f.reason), failureMeaning(f.reason), num(f.n)))
            failParts.add(failureLabel(f.reason) + " " + num(f.n))
        }
        model.addAttribute("failTotal", num(failTotal) + "건")
        model.addAttribute("failHint", if (failTotal == 0L) "실패 없음" else failParts.joinToString(" · "))
        model.addAttribute("failAlert", failTotal > 0)
        model.addAttribute("failures", failures)
        model.addAttribute("failureColumns", FAILURE_COLUMNS)

        model.addAttribute("period", PERIOD_LABEL[w])
        val startMs = now.toEpochMilli() - (if (w == 1) 1 else w - 1) * 86_400_000L
        model.addAttribute("periodHint", KstFormat.kstDate(Instant.ofEpochMilli(startMs)) + " – " + KstFormat.kstDate(now))

        val q = "?win=$w"
        model.addAttribute(
            "seriesCards",
            java.util.List.of(
                seriesCard("auth", "일별 인증 시도와 성공", orEmpty(data.authenticationSeries), "$PAGE$tid/funnel.csv$q&kind=auth"),
                seriesCard("reg", "일별 등록 시도와 성공", orEmpty(data.registrationSeries), "$PAGE$tid/funnel.csv$q&kind=reg"),
            ),
        )
        model.addAttribute("seriesColumns", SERIES_COLUMNS)
        model.addAttribute("csvSummary", "$PAGE$tid/funnel.csv$q&kind=summary")
        model.addAttribute("csvFailures", "$PAGE$tid/funnel.csv$q&kind=failures")
        addAdoption(model, tid, w, "$PAGE$tid/funnel.csv$q")
        return TAB_VIEW
    }

    /** 패스키 도입·사용 지표. 조회가 실패해도 탭의 나머지(성공률)는 그대로 보이고 이 영역만 오류 + 다시 시도. */
    private fun addAdoption(model: Model, tid: UUID, w: Int, csvBase: String) {
        val a = try {
            adoption.get(tid, w)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[funnel-tab] adoption load failed: {}", e.javaClass.simpleName)
            model.addAttribute("adoptError", e)
            return
        }
        model.addAttribute("adoptError", null)
        val single: Double? = if (a.totalUsers > 0) a.singlePasskeyUsers.toDouble() / a.totalUsers else null
        model.addAttribute(
            "adoptKpis",
            java.util.List.of(
                AdoptKpi("활성 사용자", num(a.activeUsers) + "명", "기간 중 인증 성공 · 사용된 패스키 " + num(a.activeCredentials) + "개", false),
                AdoptKpi("신규 패스키", num(a.newCredentials) + "개", "기간 중 새로 등록", false),
                AdoptKpi("누적 패스키", num(a.totalCredentials) + "개", "사용자 " + num(a.totalUsers) + "명", false),
                AdoptKpi(
                    "패스키 1개뿐인 사용자", FunnelHelpers.fmtPct(single),
                    if (a.totalUsers == 0L) "사용자 없음" else num(a.singlePasskeyUsers) + "명 / " + num(a.totalUsers) + "명 · 기기를 잃으면 로그인할 수 없어요",
                    single != null && single >= 0.5,
                ),
            ),
        )

        model.addAttribute("months", monthViews(a.months))
        model.addAttribute("monthColumns", MONTH_COLUMNS)
        model.addAttribute("devices", deviceViews(a.devices))
        model.addAttribute("csvMonthly", "$csvBase&kind=monthly")
        model.addAttribute("csvDevices", "$csvBase&kind=devices")
    }

    /** CSV 4종: 요약(내보내기), 일별 인증, 일별 등록, 인증 실패 원인. 코드는 그대로 두고 한글 이름 열을 함께 쓴다. */
    @GetMapping(PAGE + "{id}/funnel.csv")
    fun export(
        @PathVariable id: String,
        @RequestParam(name = "win", defaultValue = "7") win: String,
        @RequestParam(name = "kind", defaultValue = "summary") kind: String,
    ): ResponseEntity<ByteArray> {
        val v = TenantTabs.load(tenants, id)
        val w = window(win)
        val today = KstFormat.kstToday(clock.instant())
        if ("monthly" == kind || "devices" == kind) return adoptionCsv(v, w, kind, today)
        // !!: 원 Java funnel.get(v.getId()) — Kotlin non-null 파라미터의 NPE 와 같은 자리.
        val data = funnel.get(v.id!!, w)
        val csv: ByteArray
        val filename: String
        when (kind) {
            "summary" -> {
                val rows = ArrayList<List<String?>>()
                rows.add(summaryRow("registration", "등록", data.registration))
                rows.add(summaryRow("authentication", "인증", data.authentication))
                csv = CsvWriter.write(java.util.List.of("ceremony", "label", "attempts", "success", "successRate"), rows)
                filename = "auth-status-" + v.slug + "-" + w + "d-" + today + ".csv"
            }
            "auth", "reg" -> {
                val rows = ArrayList<List<String?>>()
                for (p in orEmpty(if ("auth" == kind) data.authenticationSeries else data.registrationSeries)) {
                    val r = FunnelHelpers.rate(p.success, p.attempts)
                    // java.util.List.of: 원 Java 와 같이 day 가 null 이면 NPE.
                    rows.add(java.util.List.of(p.day, p.attempts.toString(), p.success.toString(), if (r == null) "" else FunnelHelpers.toFixed(r, 4)))
                }
                csv = CsvWriter.write(java.util.List.of("day", "attempts", "success", "successRate"), rows)
                filename = (if ("auth" == kind) "auth-daily-" else "registration-daily-") + v.slug + "-" + w + "d-" + today + ".csv"
            }
            "failures" -> {
                val rows = ArrayList<List<String?>>()
                for (f in orEmpty(data.authFailures)) {
                    rows.add(java.util.List.of(f.reason ?: "", failureLabel(f.reason), f.n.toString()))
                }
                csv = CsvWriter.write(java.util.List.of("reason", "label", "n"), rows)
                filename = "auth-failures-" + v.slug + "-" + w + "d-" + today + ".csv"
            }
            else -> throw BusinessException(ErrorCode.ENTITY_NOT_FOUND, "알 수 없는 내보내기")
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace(UNSAFE_FILENAME, "_") + "\"")
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv)
    }

    /** 도입·사용 CSV 2종: 월간 사용량(최근 3개월), 기기 분포. */
    private fun adoptionCsv(v: TenantAdminDto.TenantView, w: Int, kind: String, today: String): ResponseEntity<ByteArray> {
        // !!: 원 Java adoption.get(v.getId()) — Kotlin non-null 파라미터의 NPE 와 같은 자리.
        val a = adoption.get(v.id!!, w)
        val rows = ArrayList<List<String?>>()
        val csv: ByteArray
        val filename: String
        if ("monthly" == kind) {
            for (m in orEmpty(a.months)) {
                // java.util.List.of: 원 Java 와 같이 month 가 null 이면 NPE.
                rows.add(
                    java.util.List.of(
                        m.month, if (m.current) "Y" else "N", m.authSuccess.toString(), m.activeUsers.toString(), m.newCredentials.toString(),
                    ),
                )
            }
            csv = CsvWriter.write(java.util.List.of("month", "inProgress", "authSuccess", "activeUsers", "newPasskeys"), rows)
            filename = "monthly-usage-" + v.slug + "-" + today + ".csv"
        } else {
            for (d in orEmpty(a.devices)) {
                rows.add(java.util.List.of(d.name, d.credentials.toString(), d.authSuccess.toString()))
            }
            csv = CsvWriter.write(java.util.List.of("authenticator", "passkeys", "authSuccessInWindow"), rows)
            filename = "devices-" + v.slug + "-" + w + "d-" + today + ".csv"
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace(UNSAFE_FILENAME, "_") + "\"")
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv)
    }

    companion object {
        private const val FRAG = "/admin/_/tenants/"
        private const val PAGE = "/admin/tenants/"
        private const val TAB_VIEW = "admin/tenant/funnel :: tab"

        private val WINDOWS: List<Int> = java.util.List.of(1, 7, 30)
        private val WIN_LABEL: Map<Int, String> = java.util.Map.of(1, "24h", 7, "7d", 30, "30d")
        private val PERIOD_LABEL: Map<Int, String> = java.util.Map.of(1, "최근 24시간", 7, "최근 7일", 30, "최근 30일")

        private val UNSAFE_FILENAME = Regex("[^A-Za-z0-9._-]")
        private val MONTH_RE: Pattern = Pattern.compile("\\d{4}-\\d{2}")
        private val DAY_RE: Pattern = Pattern.compile("\\d{2}-\\d{2}")

        private fun col(key: String, header: String, right: Boolean): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["key"] = key
            m["header"] = header
            m["align"] = if (right) "right" else null
            m["width"] = null
            m["srOnly"] = null
            return m
        }

        val SERIES_COLUMNS: List<Map<String, Any?>> = java.util.List.of(
            col("day", "날짜", false), col("a", "시도", true), col("s", "성공", true), col("r", "성공률", true),
        )
        private val FAILURE_COLUMNS: List<Map<String, Any?>> = java.util.List.of(
            col("reason", "원인", false), col("meaning", "의미", false), col("n", "건수", true),
        )

        /** credential_auth_event.failure_reason 코드 → [운영자용 이름, 의미]. 모르는 코드는 코드 그대로 보여준다. */
        private val FAILURE_LABELS: Map<String, Array<String>> = java.util.Map.of(
            CredentialAuthFailureReason.SIGNATURE_INVALID,
            arrayOf("서명 검증 실패", "등록된 공개키로 서명을 확인하지 못했어요. 위조 시도나 데이터 손상일 수 있어요."),
            CredentialAuthFailureReason.SIGN_COUNT_REPLAY,
            arrayOf("서명 카운터 재사용", "인증기 카운터가 이전 값보다 작거나 같아요. 패스키 복제가 의심돼요."),
        )

        val MONTH_COLUMNS: List<Map<String, Any?>> = java.util.List.of(
            col("month", "월", false), col("auth", "인증 성공", true), col("mau", "활성 사용자", true), col("new", "신규 패스키", true),
        )

        private fun failureLabel(code: String?): String {
            if (code == null) return "원인 미기록"
            val l = FAILURE_LABELS[code]
            return if (l == null) code else l[0]
        }

        private fun failureMeaning(code: String?): String {
            val l = if (code == null) null else FAILURE_LABELS[code]
            return if (l == null) "" else l[1]
        }

        /** 월간 사용량 표의 줄(플랫폼 현황도 같은 모양으로 쓴다). */
        fun monthViews(list: List<AdoptionDto.Month>?): List<MonthView> {
            val months = ArrayList<MonthView>()
            for (m in orEmpty(list)) {
                months.add(MonthView(monthLabel(m), num(m.authSuccess), num(m.activeUsers) + "명", num(m.newCredentials)))
            }
            return months
        }

        /** 기기 분포 줄: 비율은 전체 패스키 대비(플랫폼 현황도 같은 모양으로 쓴다). */
        fun deviceViews(list: List<AdoptionDto.Device>?): List<DeviceView> {
            val devices = ArrayList<DeviceView>()
            var total = 0L
            for (d in orEmpty(list)) total += d.credentials
            for (d in orEmpty(list)) {
                val share: Double? = if (total > 0) d.credentials.toDouble() / total else null
                devices.add(
                    DeviceView(
                        d.name, num(d.credentials) + "개", FunnelHelpers.fmtPct(share),
                        pct(if (share == null) 0.0 else share * 100), "기간 인증 " + num(d.authSuccess) + "건",
                    ),
                )
            }
            return devices
        }

        /** "2026-11" → "2026년 11월", 이번 달은 "(진행 중)". */
        fun monthLabel(m: AdoptionDto.Month): String {
            val s = m.month
            val label = if (s != null && MONTH_RE.matcher(s).matches()) {
                s.substring(0, 4) + "년 " + Integer.parseInt(s.substring(5)) + "월"
            } else {
                s.toString() // 원 Java String.valueOf — null 이면 "null"
            }
            return if (m.current) "$label (진행 중)" else label
        }

        private fun rateKpi(label: String, stage: FunnelDto.Stage?, delta: Double?): RateKpi {
            // !!: 원 Java s.getAttempts() 의 NPE 와 같은 자리.
            val s = stage!!
            if (s.attempts == 0L) return RateKpi(label, "—", "시도 없음")
            var hint = "성공 " + num(s.success) + " / 시도 " + num(s.attempts)
            if (delta != null) hint += " · 직전 7일 대비 " + FunnelHelpers.fmtDeltaPp(delta)
            return RateKpi(label, FunnelHelpers.fmtPct(FunnelHelpers.rate(s.success, s.attempts)), hint)
        }

        /** series: 플랫폼 현황(InsightsPageController)이 nullable DTO 값을 그대로 넘긴다. */
        fun seriesCard(key: String?, title: String?, series: List<FunnelDto.DailyPoint>?, csv: String?): SeriesCard {
            // !!: 원 Java 의 첫 순회(for (p : series)) NPE 와 같은 자리.
            val ss = series!!
            var smax = 1L
            for (p in ss) smax = Math.max(smax, Math.max(p.attempts, p.success))
            val days = ArrayList<DayView>()
            for (p in ss) {
                val rate = FunnelHelpers.fmtPct(FunnelHelpers.rate(p.success, p.attempts))
                days.add(
                    DayView(
                        p.day, p.attempts, p.success, num(p.attempts), num(p.success), rate,
                        pct(p.attempts.toDouble() / smax * 100), pct(p.success.toDouble() / smax * 100),
                        dateText(p.day), "시도 " + num(p.attempts) + " · 성공 " + num(p.success) + " · " + rate,
                    ),
                )
            }
            return SeriesCard(key, title, days, axisLabels(ss), ss.size > 14, title + " 막대 차트, " + ss.size + "일", csv)
        }

        /** "MM-DD" → "M월 D일". 모양이 다르면 그대로. */
        private fun dateText(day: String?): String? {
            if (day == null || !DAY_RE.matcher(day).matches()) return day
            return Integer.parseInt(day.substring(0, 2)).toString() + "월 " + Integer.parseInt(day.substring(3)) + "일"
        }

        private fun <T> orEmpty(l: List<T>?): List<T> = l ?: java.util.List.of()

        private fun summaryRow(code: String, label: String, stage: FunnelDto.Stage?): List<String?> {
            // !!: 원 Java s.getSuccess() 의 NPE 와 같은 자리.
            val s = stage!!
            val r = FunnelHelpers.rate(s.success, s.attempts)
            return java.util.List.of(code, label, s.attempts.toString(), s.success.toString(), if (r == null) "" else FunnelHelpers.toFixed(r, 4))
        }

        // ── 도우미 ──────────────────────────────────────────────

        private fun window(s: String): Int {
            try {
                val n = Integer.parseInt(s.javaTrim())
                return if (WINDOWS.contains(n)) n else 7
            } catch (e: RuntimeException) {
                return 7
            }
        }

        /** SeriesCard axisLabels. java.util.List.of: 원 Java 와 같이 day 가 null 이면 NPE. */
        private fun axisLabels(s: List<FunnelDto.DailyPoint>): List<String?> {
            if (s.isEmpty()) return java.util.List.of()
            if (s.size == 1) return java.util.List.of("오늘")
            if (s.size == 2) return java.util.List.of(s[0].day, "오늘")
            return java.util.List.of(s[0].day, s[(s.size - 1) / 2].day, "오늘")
        }

        /** CSS 에 쓰는 퍼센트 숫자(소수 둘째 자리, 지수 표기 없음). */
        private fun pct(v: Double): Double = Math.round(v * 100) / 100.0

        private fun denied(e: RuntimeException): Boolean =
            e is AccessDeniedException || (e is BusinessException && e.errorCode == ErrorCode.ACCESS_DENIED)

        private fun get(url: String): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = url
            return m
        }

        private fun num(n: Long): String = String.format(Locale.KOREA, "%,d", n)
    }
}
