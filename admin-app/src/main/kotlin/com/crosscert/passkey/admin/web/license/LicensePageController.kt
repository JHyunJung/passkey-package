package com.crosscert.passkey.admin.web.license

import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.web.HomeRedirectController
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.shell.Crumbs
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Clock
import java.time.Instant

private val log = LoggerFactory.getLogger(LicensePageController::class.java)

private const val PAGE_VIEW = "admin/license/page"
private const val BODY_VIEW = "admin/license/page :: licenseBody"
private const val ALERT_VIEW = "admin/license/page :: recheckAlert"

private const val RECHECK_STEP = "이 화면에서 \"지금 다시 확인\"을 누릅니다. 상태가 \"정상\"으로 바뀌면 끝입니다."
private const val REQUEST_STEP = "영업 담당자에게 갱신을 요청합니다. 아래 상세의 라이선스 ID와 고객사 이름을 함께 보내 주세요."
private const val APPLY_STEP = "받은 라이선스 파일을 서버에 적용합니다."

/**
 * 라이선스 화면(← pages/LicensePage.tsx, api/license.ts). PLATFORM 전용(App.tsx RequirePlatform): RP_ADMIN 은 홈(자기 tenant)으로 보낸다.
 * 데이터는 `LicenseController.get()` 하나. 설치 방식은 응답의 `deploymentMode` 를 따른다 — onprem 이 아니면(SaaS) 온프레미스 전용 안내만.
 *
 * "지금 다시 확인"은 `GET /admin/_/license/recheck` 로 상세 영역(#license-body)을 새 값으로 교체한다. 실패하면 원본처럼 이전 정보를 그대로 두고
 * 경고만 보인다(200 + HX-Retarget #license-recheck-alert). 처음 불러오기 실패는 LoadError(다시 시도 = 화면 다시 받기). 알 수 없는 예외의 원문은 싣지 않는다.
 */
@Controller
class LicensePageController(
    private val license: LicenseController,
    private val clock: Clock,
) {

    data class Badge(val tone: String?, val label: String?)

    data class Row(val k: String?, val v: String?, val mono: Boolean, val copy: String?, val tags: List<String>?)

    /** 다음 할 일 카드(VALID 는 없음). */
    data class Action(val title: String?, val lead: String?, val steps: List<String>?)

    data class View(
        val onprem: Boolean,
        val state: String?,
        val badge: Badge?,
        val heartbeat: String?,
        val days: String?,
        val daysColor: String?,
        val expiresLine: String?,
        val action: Action?,
        val rows: List<Row>?,
    )

    @GetMapping(PAGE_PATH)
    fun page(model: Model): String {
        val me = me(model)
        model.addAttribute("breadcrumbs", Crumbs.license())
        if (!me.platform) return "redirect:" + HomeRedirectController.homePath(me)
        model.addAttribute("retryAttrs", java.util.Map.of("hx-get", PAGE_PATH, "hx-target", "body", "hx-swap", "innerHTML", "hx-push-url", "false"))
        model.addAttribute("recheckAttrs", recheckAttrs())
        try {
            model.addAttribute("view", toView(license.get().data(), clock.instant()))
            model.addAttribute("loadError", null)
        } catch (e: RuntimeException) {
            log.warn("[license] load failed: {}", e.javaClass.simpleName)
            model.addAttribute("view", null)
            model.addAttribute("loadError", e)
        }
        return PAGE_VIEW
    }

    @GetMapping(RECHECK_PATH)
    fun recheck(res: HttpServletResponse, model: Model): String {
        if (!me(model).platform) throw AccessDeniedException("platform only")
        model.addAttribute("recheckAttrs", recheckAttrs())
        // nullable: 원 Java 는 null data 를 try 밖 toView 에서 NPE(500) — non-null 선언이면 try 안 검사로 바뀐다.
        val v: LicenseView?
        try {
            v = license.get().data()
        } catch (e: RuntimeException) {
            log.warn("[license] recheck failed: {}", e.javaClass.simpleName)
            res.setHeader("HX-Retarget", "#license-recheck-alert")
            res.setHeader("HX-Reswap", "innerHTML")
            model.addAttribute("recheckError", ErrorMessages.describe(e).title + ". 아래는 마지막으로 확인한 정보입니다.")
            return ALERT_VIEW
        }
        model.addAttribute("view", toView(v, clock.instant()))
        return BODY_VIEW
    }

    companion object {
        internal const val PAGE_PATH = "/admin/license"

        /** 같은 패키지 Java 테스트(LicensePageControllerTest)가 읽는다 — const 라 정적 필드로 남는다. */
        const val RECHECK_PATH = "/admin/_/license/recheck"

        private fun recheckAttrs(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = RECHECK_PATH
            m["hx-target"] = "#license-body"
            m["hx-swap"] = "outerHTML"
            m["hx-sync"] = "this:drop"
            return m
        }

        /** 원 Java 는 v 를 그대로 역참조 — null 이면 첫 줄에서 NPE(page 의 catch 로 LoadError). */
        private fun toView(v: LicenseView?, now: Instant): View {
            val onprem = "onprem" == v!!.deploymentMode
            if (!onprem) return View(false, null, null, null, null, null, null, null, java.util.List.of())
            val state = v.state
            val badge = badge(state)
            val hb = ArrayList<String>()
            if (v.lastVerifiedAt != null) hb.add("마지막 확인 " + KstFormat.relative(v.lastVerifiedAt, now))
            if (v.nextHeartbeatAt != null) hb.add("다음 확인 " + KstFormat.relative(v.nextHeartbeatAt, now))
            val daysColor = if ("WARNING" == state) "var(--warn)" else if ("DEAD" == state) "var(--bad)" else null
            val expires = if (v.expiresAt == null) null else "만료일 " + KstFormat.formatDateTime(v.expiresAt)

            val rows = ArrayList<Row>()
            rows.add(Row("고객사", v.sub ?: "—", false, null, null))
            rows.add(Row("라이선스 ID", v.jti ?: "—", v.jti != null, v.jti, null))
            rows.add(Row("만료일", if (v.expiresAt == null) "—" else KstFormat.formatDateTime(v.expiresAt), false, null, null))
            rows.add(Row("남은 일수", v.daysUntilExpiry.toString(), false, null, null))
            val features: List<String> = v.features ?: java.util.List.of()
            rows.add(Row("사용 가능한 기능", if (features.isEmpty()) "—" else null, false, null, if (features.isEmpty()) null else features))
            rows.add(Row("마지막 확인", if (v.lastVerifiedAt == null) "—" else KstFormat.formatDateTime(v.lastVerifiedAt), false, null, null))
            rows.add(Row("다음 확인 예정", if (v.nextHeartbeatAt == null) "—" else KstFormat.formatDateTime(v.nextHeartbeatAt), false, null, null))
            if (v.graceRemainingHours != null) rows.add(Row("유예 기간 남은 시간", v.graceRemainingHours.toString() + "시간", false, null, null))

            return View(true, state, badge, hb.joinToString(" · "), v.daysUntilExpiry.toString() + "일", daysColor, expires, action(v), rows)
        }

        private fun badge(state: String?): Badge? = when (state) {
            null -> null
            "VALID" -> Badge("ok", "정상")
            "WARNING" -> Badge("warn", "만료 임박")
            "NETWORK_GRACE" -> Badge("warn", "서버 연결 불가")
            "DEAD" -> Badge("bad", "만료")
            else -> null
        }

        /** 상태만 알리지 않고 다음 행동을 함께 보인다. VALID 는 할 일이 없으므로 카드 없음. */
        private fun action(v: LicenseView): Action? {
            val s = v.state
            if ("WARNING" == s) {
                return Action(
                    "지금 해야 할 일 — 라이선스 갱신",
                    "라이선스가 " + v.daysUntilExpiry + "일 후 만료됩니다. 만료되면 패스키 등록·인증이 중단됩니다.",
                    java.util.List.of(REQUEST_STEP, APPLY_STEP, RECHECK_STEP),
                )
            }
            if ("NETWORK_GRACE" == s) {
                val left = if (v.graceRemainingHours != null) v.graceRemainingHours.toString() + "시간 안에" else "곧"
                return Action(
                    "지금 해야 할 일 — 라이선스 서버 연결 복구",
                    "라이선스 서버에 연결할 수 없습니다. $left 연결되지 않으면 서비스가 중단됩니다.",
                    java.util.List.of(
                        "방화벽에서 라이선스 서버(443 포트)로 나가는 연결을 허용했는지 확인합니다.",
                        "프록시를 쓰는 경우 프록시 설정을 확인합니다.",
                        RECHECK_STEP,
                    ),
                )
            }
            if ("DEAD" == s) {
                return Action(
                    "지금 해야 할 일 — 새 라이선스 적용",
                    "라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다. 새 라이선스 파일을 적용하면 바로 복구됩니다.",
                    java.util.List.of(REQUEST_STEP, APPLY_STEP, RECHECK_STEP),
                )
            }
            return null
        }

        private fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")
    }
}
