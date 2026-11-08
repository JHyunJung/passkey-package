package com.crosscert.passkey.admin.web.audit

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview
import com.crosscert.passkey.admin.audit.IncidentConflictException
import com.crosscert.passkey.admin.audit.IncidentDto
import com.crosscert.passkey.admin.audit.IncidentNotFoundException
import com.crosscert.passkey.admin.audit.IncidentNotTamperedException
import com.crosscert.passkey.admin.web.HomeRedirectController
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.Labels
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.shell.Crumbs
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.util.Locale
import java.util.UUID

private val log = LoggerFactory.getLogger(AuditChainPageController::class.java)

/**
 * 감사 체인 화면(← pages/AuditChainPage.tsx). PLATFORM 전용(App.tsx 의 RequirePlatform) — 페이지와 모든 조각·동작이 서버에서도 막는다.
 *
 * - 페이지(`GET /admin/audit-chain`)는 골격(스켈레톤)만 보내고 `#chain-root` 가 load 로 개요를 읽는다. 개요 호출은 서버가 전 tenant 체인을
 *   검증하는 긴 작업이라(원본도 같은 순서: 로딩 → 결과) 페이지 이동 자체를 막지 않는다.
 * - 개요·검증·백필·사건 변경은 모두 `#chain-root` 를 통째로 다시 그려 돌려준다(원본의 reverify). 요청은 `hx-sync="#chain-root:drop"` 라
 *   더블 클릭·동시 실행이 중복되지 않는다. 타임아웃은 원본 LONG_TIMEOUT_MS(120s).
 * - 재검증이 실패하면 원본처럼 이전 결과를 남기고(교체하지 않음) 경고만 보인다: 200/422 + `HX-Retarget #chain-warn`. 처음 불러오는 중의 실패는
 *   오류 카드(LoadError, 422)다.
 * - 백필(L3 확인, X-Confirm): 원본 runBackfill 의 가드 — 가장 최근 검증이 성공했고 위변조가 없을 때만 — 를 서버에서도 지킨다(요청마다 개요를 다시 검증해
 *   확인한 뒤에만 `backfill()`). 동시 실행 직렬화는 서비스의 DB 락이 한다.
 * - 사건 생성·해결: 컨트롤러 `@ExceptionHandler` 가 직접 호출에는 적용되지 않으므로 여기서 잡아 원본 문구로 바꾼다
 *   (409 → 경고 토스트 + 목록 새로 그림, 422/404/그 밖의 실패 → 다이얼로그 안 인라인, 토스트 없음).
 * - 사용자 영향 값(tenant 이름·해결 메모·이메일)은 템플릿에서 th:text 로만 그린다.
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 `requirePlatform(model)`(403)이 먼저다. 넘기는 자리의 `auth!!` 는 원 Java 에서
 * Kotlin 피호출자(AuditChainMonitorController)의 파라미터 검사 NPE 가 나던 자리로, 같은 try 안이라 같은 catch(RuntimeException) 로 간다.
 */
@Controller
class AuditChainPageController(
    private val chain: AuditChainMonitorController,
    private val clock: Clock,
    private val validator: Validator,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** Tenant 별 체인 표의 한 줄. attrs 는 "검증" 버튼의 요청 속성(플랫폼 체인은 null — 열기·검증 비활성). */
    data class Row(
        val tenantId: String?,
        val name: String,
        val initial: String,
        val intact: Boolean,
        val rows: String,
        val openHref: String?,
        val verifyAttrs: Map<String, Any?>?,
    )

    /** 위변조 배너의 한 줄. */
    data class Tampered(
        val tenantId: String?,
        val name: String?,
        val entry: String?,
        val auditHref: String?,
        val hasOpen: Boolean,
        val canCreate: Boolean,
        val createId: String?,
        val createTitle: String?,
        val createAttrs: Map<String, Any?>?,
    )

    data class Incident(
        val id: String?,
        val open: Boolean,
        val statusLabel: String?,
        val tenant: String?,
        val type: String?,
        val created: String,
        val createdBy: String?,
        val label: String,
    )

    data class ReportTenant(val name: String, val rows: String, val intact: Boolean)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(PAGE_PATH)
    fun page(model: Model): String {
        val me = me(model)
        model.addAttribute("breadcrumbs", Crumbs.auditChain())
        if (!me.platform) return "redirect:" + HomeRedirectController.homePath(me)
        model.addAttribute("loadAttrs", rootReq("hx-get", "$OVERVIEW_PATH?first=1", true, false))
        return PAGE_VIEW
    }

    /** 개요 + 사건 목록. first=1 이면 처음 불러오는 중(실패 = 오류 카드), 아니면 다시 검증(실패 = 이전 결과 유지 + 경고). view 는 표 필터 보존. */
    @GetMapping(OVERVIEW_PATH)
    fun overview(
        @RequestParam(name = "first", defaultValue = "") first: String,
        @RequestParam(name = "view", defaultValue = "all") view: String,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        return renderRoot(model, res, view, "1" == first, false)
    }

    /** 한 tenant 검증(원본 handleVerifyTenant): 결과 토스트 + 재검증(reverify). */
    @GetMapping(VERIFY_PATH)
    fun verify(
        @RequestParam(name = "tenantId", defaultValue = "") tenantId: String,
        @RequestParam(name = "view", defaultValue = "all") view: String,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        val tid = parseUuid(tenantId) ?: throw IllegalArgumentException("invalid tenantId")
        try {
            val r = chain.verifyTenant(tid.toString())
            if (r.intact) {
                Toasts.successTitled(res, "검증 완료", "chain 이 정상입니다.")
            } else {
                Toasts.errorTitled(
                    res, "위변조 의심",
                    if (r.tamperedEntryId != null) "불일치 entry " + r.tamperedEntryId else "hash 불일치", null, null,
                )
            }
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[audit-chain] verify failed: {}", e.javaClass.simpleName)
            val info = ErrorMessages.describe(e)
            val status = status(info)
            Toasts.errorTitled(res, "검증하지 못했습니다", info.toastMessage(), "HTTP $status", MDC.get("traceId"))
            res.status = status
            return EMPTY_VIEW
        }
        return renderRoot(model, res, view, false, true)
    }

    /**
     * 체인 백필(L3 확인 — X-Confirm 필수). 가드: 가장 최근 검증이 성공했고 위변조가 없을 때만 실행한다(원본 backfillAllowed). 서버는 요청마다 전 tenant 를
     * 다시 검증해 그것을 확인한다 — 위변조·검증 실패면 `backfill()` 을 부르지 않고 409 + confirmError(다이얼로그 안 인라인).
     */
    @PostMapping(BACKFILL_PATH)
    fun backfill(
        @RequestParam(name = "view", defaultValue = "all") view: String,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        if (!Toasts.isConfirm(req)) throw IllegalArgumentException("confirmation required")
        val ov: AuditChainOverview
        try {
            ov = chain.overview(WINDOW_HOURS)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[audit-chain] backfill guard verify failed: {}", e.javaClass.simpleName)
            return blocked(res, BLOCK_FAILED)
        }
        if (hasTampering(ov)) return blocked(res, BLOCK_TAMPERED)
        val r = chain.backfill() // 실패는 WebExceptionAdvice 가 confirmError 로
        Toasts.successTitled(
            res, "백필 완료",
            "tenant " + fmt(r.tenantsProcessed.toLong()) + "개 처리 · 행 " + fmt(r.rowsUpdated.toLong()) + "개 갱신",
        )
        return renderRoot(model, res, view, false, true)
    }

    private fun blocked(res: HttpServletResponse, message: String): String {
        Toasts.confirmError(res, message, null, MDC.get("traceId"))
        res.status = HttpServletResponse.SC_CONFLICT
        return EMPTY_VIEW
    }

    /** 사건 목록만 다시 읽기(원본 refreshIncidents) — 카드·배너 배지만 OOB 로 갱신한다. 개요(전 tenant 검증)는 다시 돌리지 않는다. */
    @GetMapping(INCIDENTS_PATH)
    fun incidents(res: HttpServletResponse, model: Model): String {
        requirePlatform(model)
        return refresh(model, res)
    }

    /** 사건 생성(원본 handleCreateIncident). 서버가 위변조 재검증 결과에서 entry 를 도출한다 — 요청에는 tenantId 만 싣는다. */
    @PostMapping(INCIDENTS_PATH)
    fun createIncident(
        @RequestParam(name = "tenantId", defaultValue = "") tenantId: String,
        @RequestParam(name = "view", defaultValue = "all") view: String,
        auth: Authentication?,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        val tid = parseUuid(tenantId) ?: throw IllegalArgumentException("invalid tenantId")
        val slot = "chain-ic-issue-$tid"
        try {
            // !! : 원 Java 는 null auth 를 Kotlin 피호출자에 넘겨 파라미터 검사 NPE — 같은 try 안이라 아래 RuntimeException catch 로 간다.
            chain.createIncident(IncidentDto.CreateRequest(tid.toString()), auth!!)
            Toasts.successTitled(res, "Incident 생성됨", "보안팀 대응 incident 가 등록되었습니다.")
            return renderRoot(model, res, view, false, true)
        } catch (e: IncidentConflictException) {
            // 다른 운영자가 이미 OPEN incident 를 만든 stale 상태일 수 있다 — 닫고(루트 교체) 목록 동기화.
            Toasts.warnTitled(res, "이미 진행 중", "해당 tenant 에 OPEN 상태 incident 가 이미 있습니다.")
            return refresh(model, res)
        } catch (e: IncidentNotTamperedException) {
            return issue(model, res, slot, "warn", "위변조가 확인되지 않았습니다", "재검증 결과 위변조가 확인되지 않아 incident 를 생성하지 않았습니다.", false)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[audit-chain] create incident failed: {}", e.toString())
            return issue(model, res, slot, "bad", "Incident를 생성하지 못했습니다", ErrorMessages.describe(e).toastMessage(), false)
        }
    }

    /** 사건 해결(원본 handleResolveIncident). 다이얼로그는 하나를 공유하므로 대상 id 는 폼 값이다. */
    @PostMapping(RESOLVE_PATH)
    fun resolveIncident(
        @RequestParam(name = "id", defaultValue = "") id: String,
        @RequestParam(name = "note", defaultValue = "") note: String,
        @RequestParam(name = "view", defaultValue = "all") view: String,
        auth: Authentication?,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        val iid = parseUuid(id) ?: throw IllegalArgumentException("invalid incident id")
        val slot = "chain-rs-issue"
        val trimmed = note.javaTrim()
        val body = IncidentDto.ResolveRequest(trimmed)
        if (!validator.validate(body).isEmpty()) {
            return issue(model, res, slot, "warn", "해결 메모를 확인하세요", "필수 · 감사 로그에 함께 기록됩니다. 최대 1024자입니다.", false)
        }
        try {
            // !! : 원 Java 는 null auth 를 Kotlin 피호출자에 넘겨 파라미터 검사 NPE — 같은 try 안이라 아래 RuntimeException catch 로 간다.
            chain.resolveIncident(iid.toString(), body, auth!!)
            Toasts.successTitled(res, "해결됨", "incident 가 RESOLVED 로 표시되었습니다.")
            return renderRoot(model, res, view, false, true)
        } catch (e: IncidentConflictException) {
            // 이미 RESOLVED — stale 다이얼로그를 닫고(루트 교체) 목록 동기화.
            Toasts.warnTitled(res, "이미 해결됨", "해당 incident 는 이미 RESOLVED 상태입니다.")
            return refresh(model, res)
        } catch (e: IncidentNotFoundException) {
            // 메모는 남기고 다이얼로그 안에서 알린다. 목록은 OOB 로 새로 읽는다.
            return issue(model, res, slot, "warn", "이미 처리되었거나 없는 incident입니다", "목록을 새로 고쳤습니다. 다이얼로그를 닫고 목록에서 확인하세요.", true)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[audit-chain] resolve incident failed: {}", e.toString())
            return issue(model, res, slot, "bad", "해결로 표시하지 못했습니다", ErrorMessages.describe(e).toastMessage(), false)
        }
    }

    // ── 렌더 ────────────────────────────────────────────────

    /** 인라인 알림 조각(다이얼로그 안 슬롯으로 HX-Retarget). 원본은 어느 실패 경로든 사건 목록을 다시 읽는다 — 목록 카드를 OOB 로 함께 보낸다. */
    private fun issue(model: Model, res: HttpServletResponse, slotId: String, tone: String, title: String, text: String?, gone: Boolean): String {
        res.setHeader("HX-Retarget", "#$slotId")
        res.setHeader("HX-Reswap", "innerHTML")
        res.status = 422
        model.addAttribute("issueTone", tone)
        model.addAttribute("issueTitle", title)
        model.addAttribute("issueText", text)
        model.addAttribute("issueGone", gone)
        fillIncidentsOob(model)
        return ISSUE_VIEW
    }

    /**
     * 목록만 갱신하는 응답: 본문은 OOB 조각뿐이라 `HX-Reswap: none`(요청 대상 #chain-root 를 비우지 않는다). 다이얼로그는 닫는 쪽(요청 요소의
     * after-request 성공)이 닫는다. 목록 조회가 실패하면 카드가 오류 상태(다시 시도)가 된다.
     */
    private fun refresh(model: Model, res: HttpServletResponse): String {
        res.setHeader("HX-Reswap", "none")
        fillIncidentsOob(model)
        return REFRESH_VIEW
    }

    /** 사건 목록을 독립적으로 읽어 카드·배너 배지 OOB 모델을 채운다(개요와 무관). 실패하면 목록 없음(오류 카드). */
    private fun fillIncidentsOob(model: Model) {
        var list: List<IncidentDto.IncidentView>? = null
        try {
            list = chain.listIncidents()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[audit-chain] incidents refresh failed: {}", e.javaClass.simpleName)
        }
        model.addAttribute("incidentsOob", true)
        fillIncidents(model, list, null)
        // 배너의 행 동작(생성 버튼 ↔ "Incident 처리 중"): 사건이 있는 tenant 마다. 배너에 없는 tenant 의 요소는 htmx 가 무시한다.
        val acts = ArrayList<Tampered>()
        if (list != null) {
            val byTenant = LinkedHashMap<String?, Boolean>()
            for (i in list) byTenant.merge(i.tenantId, "OPEN" == i.status) { a, b -> a || b }
            byTenant.forEach { (tid, open) -> acts.add(Tampered(tid, null, null, null, open, true, "chain-ic-$tid", null, null)) }
        }
        model.addAttribute("actionsOob", acts)
    }

    /**
     * 루트 교체. 개요(전 tenant 검증)와 사건 목록은 서로 독립이다 — 목록 실패는 카드 안 오류로, 개요 실패는 first 면 오류 카드, 아니면
     * 이전 결과를 남기고 경고만(mutated 면 앞선 변경은 성공했으므로 200).
     */
    private fun renderRoot(model: Model, res: HttpServletResponse, viewParam: String, first: Boolean, mutated: Boolean): String {
        val ov: AuditChainOverview
        try {
            ov = chain.overview(WINDOW_HOURS)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[audit-chain] overview failed: {}", e.toString())
            if (first) {
                res.status = 422
                model.addAttribute("loadError", e)
                model.addAttribute("retryAttrs", disabledSelf(rootReq("hx-get", "$OVERVIEW_PATH?first=1", false, false)))
                return FAILED_VIEW
            }
            val info = ErrorMessages.describe(e)
            res.setHeader("HX-Retarget", "#chain-warn")
            res.setHeader("HX-Reswap", "innerHTML")
            if (!mutated) res.status = 422
            // 앞선 변경은 성공했다 — 목록은 개요와 독립으로 다시 읽어 낡은 목록·열린 상태를 남기지 않는다(원본 refreshIncidents).
            model.addAttribute("incidentsOob", mutated)
            if (mutated) fillIncidentsOob(model)
            model.addAttribute("warnTitle", info.title)
            val retry = disabledSelf(rootReq("hx-get", OVERVIEW_PATH, false))
            retry["data-chain-verify"] = true
            retry["data-chain-reverify"] = true
            model.addAttribute("warnAttrs", retry)
            return WARN_VIEW
        }
        var list: List<IncidentDto.IncidentView>? = null
        try {
            list = chain.listIncidents()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[audit-chain] incidents failed: {}", e.javaClass.simpleName)
        }
        val view = if ("tampered" == viewParam) "tampered" else "all"
        populate(model, ov, list, view)
        return ROOT_VIEW
    }

    private fun populate(model: Model, ov: AuditChainOverview, incidents: List<IncidentDto.IncidentView>?, view: String) {
        val now = clock.instant()
        val tampered = hasTampering(ov)
        // !! : 원 Java 는 totals/tenants 가 null 이면 바로 위 hasTampering(또는 아래 for 루프)에서 NPE — 그 사이 모델 쓰기는 없다.
        val t = ov.totals!!
        val tenants = ov.tenants!!
        val openTenants = HashSet<String?>()
        if (incidents != null) for (i in incidents) if ("OPEN" == i.status) openTenants.add(i.tenantId)

        val rows = ArrayList<Row>()
        val tamperedRows = ArrayList<Tampered>()
        for (c in tenants) {
            val tid = c.tenantId?.toString()
            val name = c.tenantName ?: ""
            var vAttrs: MutableMap<String, Any?>? = null
            if (tid != null) {
                vAttrs = rootReq("hx-get", "$VERIFY_PATH?tenantId=$tid", false)
                vAttrs["data-chain-verify"] = true
                vAttrs["data-vid"] = tid
            }
            rows.add(Row(tid, name, initial(name), c.intact, fmt(c.verifiedRows), if (tid == null) null else "/admin/tenants/$tid", vAttrs))
            if (!c.intact) {
                val hasOpen = tid != null && openTenants.contains(tid)
                val known = incidents != null
                val title = if (tid == null) "플랫폼 체인은 incident 대상이 아닙니다" else if (!known) "Incident 목록을 확인한 뒤 생성할 수 있습니다" else null
                val entry = if (c.tamperedEntryId == null) null else tail8(c.tamperedEntryId.toString())
                tamperedRows.add(
                    Tampered(
                        tid, name, entry, if (tid == null) null else "/admin/tenants/$tid?tab=audit", hasOpen,
                        tid != null && known, if (tid == null) null else "chain-ic-$tid", title,
                        if (tid == null) null else rootReq("hx-post", "$INCIDENTS_PATH?tenantId=$tid", false),
                    ),
                )
            }
        }
        model.addAttribute("view", view)
        model.addAttribute("rows", rows)
        model.addAttribute("tamperedRows", tamperedRows)
        model.addAttribute("tampered", tampered)
        model.addAttribute("verifiedAtIso", if (ov.verifiedAt == null) "" else ov.verifiedAt.toString())
        model.addAttribute(
            "headline",
            if (tampered) {
                t.tenantsTampered.toString() + "개 tenant 위변조 의심 · " + t.tenantsIntact + " / " + t.tenantsTotal + " 정상"
            } else {
                "모든 tenant 정상 · " + t.tenantsIntact + " / " + t.tenantsTotal
            },
        )
        val ago = KstFormat.timeAgo(ov.verifiedAt, now)
        model.addAttribute("ago", ago)
        model.addAttribute("lastLine", "마지막 검증 $ago")
        model.addAttribute("agoCap", "$ago 검증 기준")
        model.addAttribute("kpiRatio", t.tenantsIntact.toString() + " / " + t.tenantsTotal)
        model.addAttribute("kpiRows", fmt(t.verifiedRows))
        model.addAttribute("kpiMs", fmt(t.verificationMs) + "ms")
        model.addAttribute("tenantCount", tenants.size)
        model.addAttribute("tamperedCount", tamperedRows.size)
        model.addAttribute("tamperedFlag", if (tampered) "1" else "0")
        model.addAttribute("incidentsKnownFlag", if (incidents != null) "1" else "0")
        model.addAttribute(
            "chainColumns",
            java.util.List.of(
                col("tenant", "Tenant", null), col("status", "상태", null), col("rows", "검증 행 수", "right"),
                col("verifiedAt", "마지막 검증", null), col("actions", "액션", null),
            ),
        )
        model.addAttribute("segOptions", java.util.List.of(opt("all", "전체"), opt("tampered", "위변조만")))

        // 백필: 지금 렌더가 곧 가장 최근 검증이 성공한 상태다. 위변조가 있으면 비활성 + 사유.
        model.addAttribute("backfillAllowed", !tampered)
        model.addAttribute("backfillReason", BLOCK_TAMPERED)
        val item = LinkedHashMap<String, Any?>()
        item["label"] = "체인 백필…"
        item["danger"] = true
        item["disabled"] = tampered
        item["disabledReason"] = BLOCK_TAMPERED
        // 검증 중·재검증 실패에는 Alpine 이 항목을 비활성(aria-disabled + 사유 title)으로 바꾼다 — 메뉴가 클릭을 막고 다이얼로그도 없다.
        val ia = LinkedHashMap<String, Any?>()
        ia["x-on:click.capture"] = "if (!blocked) \$dispatch('dialog:open', { id: 'chain-backfill' })"
        ia["x-bind:aria-disabled"] = "blocked ? 'true' : null"
        ia["x-bind:title"] = "blocked ? blockedReason : null"
        item["attrs"] = ia
        model.addAttribute("menuItems", java.util.List.of(item))
        model.addAttribute("resolveAttrs", rootReq("hx-post", RESOLVE_PATH, false))
        model.addAttribute("backfillAttrs", rootReq("hx-post", BACKFILL_PATH, false))

        // 월간 보고서
        val range = KstFormat.kstPrevMonthRange(now)
        model.addAttribute("reportFrom", range.from)
        model.addAttribute("reportTo", range.to)
        model.addAttribute("reportHref", REPORT_API)
        model.addAttribute("reportOpts", java.util.Map.of("wide", true))
        val rt = ArrayList<ReportTenant>()
        for (c in tenants) rt.add(ReportTenant(c.tenantName ?: "", fmt(c.verifiedRows) + " rows", c.intact))
        model.addAttribute("reportTenants", rt)

        // 새로고침 요청 속성
        val reverify = disabledSelf(rootReq("hx-get", OVERVIEW_PATH, false))
        reverify["data-chain-verify"] = true
        reverify["data-chain-reverify"] = true
        model.addAttribute("reverifyAttrs", reverify)

        fillIncidents(model, incidents, tampered)
    }

    private fun fillIncidents(model: Model, list: List<IncidentDto.IncidentView>?, tampered: Boolean?) {
        val known = list != null
        val out = ArrayList<Incident>()
        var open = 0
        if (list != null) {
            for (i in list) {
                val isOpen = "OPEN" == i.status
                if (isOpen) open++
                val createdAt = i.createdAt
                // !! : kstDateTime(String) 은 null 입력에만 null 을 돌려준다(파싱 실패는 원문) — 원 Java 의 created.length() NPE 는 도달 불가.
                var created: String = if (createdAt == null) "—" else KstFormat.kstDateTime(createdAt)!!
                if (created.length > 16) created = created.substring(0, 16)
                out.add(
                    Incident(
                        i.id, isOpen, Labels.statusLabel(i.status), i.tenantName, i.type, created, i.createdByEmail,
                        i.tenantName + " · " + i.type,
                    ),
                )
            }
        }
        model.addAttribute(
            "incidentColumns",
            java.util.List.of(
                col("status", "상태", null), col("tenant", "Tenant", null), col("type", "유형", null),
                col("createdAt", "생성시각", null), col("createdBy", "생성자", null), col("actions", "액션", null),
            ),
        )
        model.addAttribute("incidentsKnown", known)
        model.addAttribute("incidentList", out)
        model.addAttribute("openCount", open)
        model.addAttribute("openLabel", Labels.statusLabel("OPEN") + " " + open)
        model.addAttribute(
            "emptyDesc",
            when (tampered) {
                null -> null
                true -> "위변조 의심 tenant 는 위 배너에서 Incident 를 생성하세요."
                false -> "모든 chain 이 정상입니다."
            },
        )
        // 다시 시도 = 목록만 다시 읽기(원본 refreshIncidents). 응답은 OOB 뿐이라 swap none.
        val retry = LinkedHashMap<String, Any?>()
        retry["hx-get"] = INCIDENTS_PATH
        retry["hx-swap"] = "none"
        retry["hx-sync"] = "#chain-root:drop"
        retry["hx-disabled-elt"] = "this"
        model.addAttribute("incidentRetryAttrs", retry)
    }

    companion object {
        internal const val PAGE_PATH = "/admin/audit-chain"
        internal const val BASE = "/admin/_/audit-chain"
        internal const val OVERVIEW_PATH = "$BASE/overview"
        internal const val VERIFY_PATH = "$BASE/verify"
        internal const val BACKFILL_PATH = "$BASE/backfill"
        internal const val INCIDENTS_PATH = "$BASE/incidents"
        internal const val RESOLVE_PATH = "$BASE/incidents/resolve"
        internal const val REPORT_API = "/admin/api/audit/chain/monthly-report"

        internal const val PAGE_VIEW = "admin/audit-chain/page"
        internal const val ROOT_VIEW = "admin/audit-chain/overview :: root"
        internal const val FAILED_VIEW = "admin/audit-chain/overview :: loadFailed"
        internal const val WARN_VIEW = "admin/audit-chain/overview :: warn"
        internal const val ISSUE_VIEW = "admin/audit-chain/incidents :: issue"
        internal const val REFRESH_VIEW = "admin/audit-chain/incidents :: refresh"
        internal const val EMPTY_VIEW = "admin/fragments/shell/empty :: none"

        /** AuditChainPage 의 `overview(24)`. */
        internal const val WINDOW_HOURS = 24

        /** 원본 LONG_TIMEOUT_MS. 템플릿의 hx-request timeout 이 이 값에서 나온다. */
        internal const val LONG_TIMEOUT_MS = 120_000

        internal const val BLOCK_TAMPERED = "위변조 의심 tenant가 있으면 쓸 수 없습니다. 먼저 조사·해결하세요."
        internal const val BLOCK_FAILED = "최근 검증에 실패했습니다. 다시 검증해 정상임을 확인한 뒤 쓸 수 있습니다."

        // ── 요청 속성 ───────────────────────────────────────────

        /**
         * 루트 교체 요청. 같은 동기화 그룹(#chain-root:drop)이라 더블 클릭·동시 실행은 뒤의 것을 버린다. view 는 표 필터를 보존한다.
         * withView=false: 표(view 필터)가 아직 없는 골격·불러오기 실패 화면의 요청 — 가리킬 [name=view] 가 없어 hx-include 를 붙이지 않는다(htmx 경고 방지).
         */
        private fun rootReq(method: String, url: String, self: Boolean, withView: Boolean = true): MutableMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m[method] = url
            m["hx-target"] = if (self) "this" else "#chain-root"
            m["hx-swap"] = "outerHTML"
            m["hx-sync"] = "#chain-root:drop"
            if (withView) m["hx-include"] = "#chain-root [name=view]"
            m["hx-request"] = "{\"timeout\": $LONG_TIMEOUT_MS}"
            if (self) m["hx-trigger"] = "load"
            return m
        }

        private fun disabledSelf(m: MutableMap<String, Any?>): MutableMap<String, Any?> {
            m["hx-disabled-elt"] = "this"
            return m
        }

        private fun col(key: String, header: String, align: String?): Map<String, Any?> {
            val c = LinkedHashMap<String, Any?>()
            c["key"] = key
            c["header"] = header
            c["align"] = align
            c["width"] = null
            c["srOnly"] = null
            return c
        }

        private fun opt(value: String, label: String): Map<String, Any?> {
            val o = LinkedHashMap<String, Any?>()
            o["value"] = value
            o["label"] = label
            o["title"] = null
            o["attrs"] = null
            return o
        }

        // ── 도우미 ──────────────────────────────────────────────

        /** !! : 원 Java 는 totals/tenants 가 null 이면 여기서 NPE(백필 가드·populate 모두 try 밖). `||` 단락도 같다. */
        internal fun hasTampering(ov: AuditChainOverview): Boolean =
            ov.totals!!.tenantsTampered > 0 || ov.tenants!!.any { c: AuditChainTenantOverview -> !c.intact }

        /** 아바타 글자 — 첫 문자/숫자(예: "[platform]" → "P"). */
        internal fun initial(name: String): String {
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp)).uppercase(Locale.ROOT)
                i += Character.charCount(cp)
            }
            return if (name.isEmpty()) "" else name.substring(0, name.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
        }

        private fun tail8(s: String): String = if (s.length <= 8) s else s.substring(s.length - 8)

        private fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)

        private fun status(info: ErrorMessages.Info): Int = info.httpStatus ?: HttpServletResponse.SC_INTERNAL_SERVER_ERROR

        private fun parseUuid(s: String?): UUID? =
            try {
                if (s == null || s.javaIsBlank()) null else UUID.fromString(s)
            } catch (e: IllegalArgumentException) {
                null
            }

        private fun denied(e: RuntimeException): Boolean =
            e is AccessDeniedException || (e is BusinessException && e.errorCode == ErrorCode.ACCESS_DENIED)

        private fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")

        private fun requirePlatform(model: Model) {
            if (!me(model).platform) throw AccessDeniedException("PLATFORM_OPERATOR only")
        }
    }
}
