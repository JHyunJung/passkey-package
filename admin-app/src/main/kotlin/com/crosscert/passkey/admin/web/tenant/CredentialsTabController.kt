package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.credential.CredentialAdminController
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.web.format.CsvWriter
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.api.PageView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(CredentialsTabController::class.java)

/**
 * Tenant 상세의 자격 증명 탭(← pages/tenant/CredentialsTab.tsx, CredentialDetailDialog.tsx, credentialHelpers.ts, api/credentials.ts).
 *
 * 접근 경계: 모든 엔드포인트가 [TenantTabs.load] 를 먼저 부른다(RP 다른 tenant → 403). 상세·회수 주소는 credentialId 로 되어 있어,
 * 화면은 그 tenant 의 목록(현재 화면의 페이지 창, 없으면 id 검색)에서 그 credential 을 찾은 뒤에만 REST 를 부른다 — 없으면 404, 변경 호출 없음.
 * (REST 서비스도 tenantId 일치를 검사하지만 화면은 거기에 기대지 않는다.)
 *
 * 목록 상태(page·size·q·aaguid·status)는 모두 요청 파라미터다. 서버는 상태를 갖지 않고 매 요청의 입력값(hx-include)을 읽는다.
 * aaguid·status 는 원본처럼 "현재 페이지 안에서" 거른다(REST 에 그 파라미터가 없다).
 * credential 데이터(사용자 핸들·별칭·인증기 이름 등)는 사용자가 정할 수 있는 값이라 속성 중 JS 로 평가되는 곳에는 쓰지 않는다
 * (URL 은 인코딩해 hx-* 주소에만, 확인 입력값은 base64url 로 검증한 credentialId 꼬리).
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 테넌트 조회·확인 검사·credential 확인이 먼저다. 넘기는 자리의 `!!` 는
 * 원 Java 에서 Kotlin 피호출자(`CredentialAdminController.revoke`)의 파라미터 검사 NPE 가 나던 자리(try 밖).
 * `v.id!!` 는 원 Java 의 첫 역참조(`toString()` 또는 Kotlin non-null 파라미터로 넘김) 자리다.
 */
@Controller
class CredentialsTabController(
    private val tenants: TenantAdminController,
    private val credentials: CredentialAdminController,
    private val clock: Clock,
    private val json: ObjectMapper,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** 목록 상태. page 는 1부터. */
    private data class St(val page: Int, val size: Int, val q: String, val aaguid: String, val status: String) {
        fun withPage(p: Int): St = St(Math.max(1, p), size, q, aaguid, status)

        fun clientFiltered(): Boolean = aaguid.isNotEmpty() || ALL != status

        /** 쿼리 문자열(인코딩 완료). 기본값도 명시한다. */
        fun query(): String {
            val b = StringBuilder("page=$page&size=$size")
            if (q.isNotEmpty()) b.append("&q=").append(enc(q))
            if (aaguid.isNotEmpty()) b.append("&aaguid=").append(enc(aaguid))
            if (ALL != status) b.append("&status=").append(status)
            return b.toString()
        }

        companion object {
            fun of(page: String, size: String, q: String, aaguid: String, status: String): St {
                val sz = parseInt(size, PAGE_SIZES[0])
                return St(
                    Math.max(1, parseInt(page, 1)), if (PAGE_SIZES.contains(sz)) sz else PAGE_SIZES[0],
                    q.javaTrim(), aaguid.javaTrim(), normStatus(status),
                )
            }
        }
    }

    /** 표의 한 줄(api/credentials.ts adapt + 열 계산). counter: value | na | zero. */
    data class Row(
        val id: String?,
        val tail8: String?,
        val userHandle: String?,
        val nickname: String?,
        val aaguid: String?,
        val authenticator: String?,
        val authenticatorNamed: Boolean,
        val transports: List<Transport>?,
        val counterKind: String?,
        val signCount: Long,
        val lastUsed: String?,
        val detailAttrs: Map<String, Any?>?,
        val revokeAttrs: Map<String, Any?>?,
        val revokeLabel: String?,
    )

    data class Transport(val value: String?, val label: String?)

    /** 상세 다이얼로그에서 이전/다음으로 갈 주소(없으면 null). */
    data class Nav(val position: String?, val prev: Map<String, Any?>?, val next: Map<String, Any?>?)

    data class Event(val `when`: String?, val result: String?, val reason: String?, val signCount: Long)

    // ── 화면 ────────────────────────────────────────────────

    /** 탭 조각. 첫 요청이라 상태는 기본값이지만 같은 파라미터도 받는다(주소로 직접 연 경우 대비). */
    @GetMapping(FRAG + "{id}/tab/credentials")
    fun tab(
        @PathVariable id: String,
        @RequestParam(name = "page", defaultValue = "1") page: String,
        @RequestParam(name = "size", defaultValue = "50") size: String,
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "aaguid", defaultValue = "") aaguid: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        val st = St.of(page, size, q, aaguid, status)
        // !!: 원 Java v.getId().toString() 의 NPE 와 같은 자리.
        val tid = v.id!!
        model.addAttribute("tid", tid.toString())
        fillList(model, tid, st, false, null)
        return TAB_VIEW
    }

    /**
     * 목록 조각(검색·필터·페이징·회수 뒤 새로고침). 교체 대상은 탭 영역이 아니라 탭 안의 `#cred-results` 다.
     * 조회 실패는 200 으로 그 자리에 LoadError(원본: 다른 화면 요청이 실패하면 이전 행을 남기지 않는다).
     * `reset` 은 aaguid | status | all — 해당 입력을 비운 값으로 OOB 되돌려 쓴다(그 밖의 요청은 현재 입력값을 싣고 오므로 되돌려 쓰지 않는다).
     */
    @GetMapping(FRAG + "{id}/tab/credentials/list")
    fun list(
        @PathVariable id: String,
        @RequestParam(name = "page", defaultValue = "1") page: String,
        @RequestParam(name = "size", defaultValue = "50") size: String,
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "aaguid", defaultValue = "") aaguid: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        @RequestParam(name = "reset", required = false) reset: String?,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        var st = St.of(page, size, q, aaguid, status)
        if ("aaguid" == reset || "all" == reset) st = St(st.page, st.size, st.q, "", st.status)
        if ("status" == reset || "all" == reset) st = St(st.page, st.size, st.q, st.aaguid, ALL)
        if ("all" == reset) st = St(1, st.size, "", st.aaguid, st.status)
        // !!: 원 Java v.getId().toString() 의 NPE 와 같은 자리.
        val tid = v.id!!
        model.addAttribute("tid", tid.toString())
        fillList(model, tid, st, false, reset)
        return LIST_VIEW
    }

    /**
     * 상세 다이얼로그(← CredentialDetailDialog). credential 은 이 tenant 의 목록에서 찾는다(page·size·q 의 창, 없으면 id 검색) — 없으면 404.
     * 이전/다음은 필터를 건 뒤 목록 안에서 바로, 경계에서는 필터가 없을 때만 이웃 페이지의 첫/마지막 항목으로 잇는다.
     * `list=1` 이면(이웃 페이지로 넘어간 경우) 그 페이지의 목록도 OOB 로 돌려준다.
     */
    @GetMapping(FRAG + "{id}/credentials/{cid}")
    fun detail(
        @PathVariable id: String,
        @PathVariable cid: String,
        @RequestParam(name = "page", defaultValue = "1") page: String,
        @RequestParam(name = "size", defaultValue = "50") size: String,
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "aaguid", defaultValue = "") aaguid: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        @RequestParam(name = "list", required = false) listToo: String?,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        val st = St.of(page, size, q, aaguid, status)
        val f = find(v.id, cid, st)
        // !!: find 가 이미 역참조했다(원 Java 도 find → credentials.list 에서 NPE) — 여기서는 null 일 수 없다.
        val tid = v.id!!
        val c = f.view
        model.addAttribute("tid", tid.toString())
        model.addAttribute("c", row(tid, c, st))
        model.addAttribute("createdAt", KstFormat.kstDateTimeMin(c.createdAt))
        val lastUsedAt = c.lastUsedAt
        model.addAttribute("lastUsedAt", if (lastUsedAt == null) "—" else KstFormat.timeAgo(lastUsedAt.toInstant(), clock.instant()))
        model.addAttribute("transportsRaw", transports(c.transports).map { t -> t.value })
        model.addAttribute("attestation", c.attestationFormat)
        val window = f.window
        model.addAttribute("nav", if (window == null) null else nav(tid, cid, window))
        model.addAttribute("revokeGetAttrs", revokeConfirmAttrs(tid, cid, st))
        model.addAttribute("detailRetryAttrs", detailAttrs(tid, cid, st, false, false))
        model.addAttribute("authName", c.authenticatorName)
        try {
            val ev = credentials.authEvents(tid, cid, 0, EVENTS_SIZE).data()
            val events = ArrayList<Event>()
            for (e in ev.content()) {
                events.add(
                    Event(
                        KstFormat.kstDateTimeMin(e.createdAt), if ("SUCCESS" == e.result) "SUCCESS" else "FAILED",
                        e.failureReason, e.signCount,
                    ),
                )
            }
            model.addAttribute("events", events)
            model.addAttribute("eventsError", null)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[credentials-tab] auth events failed: {}", e.javaClass.simpleName)
            model.addAttribute("events", java.util.List.of<Event>())
            model.addAttribute("eventsError", e)
        }
        val oob = "1" == listToo
        if (oob && window != null) {
            // 이웃 페이지로 넘어갔다 — 그 페이지의 목록(방금 읽은 창)을 함께 갱신한다.
            fillRows(model, tid, st, window, null, true, null)
        } else {
            model.addAttribute("listOob", false)
        }
        return DETAIL_VIEW
    }

    /** 회수 확인 다이얼로그(L3, 마지막 8자 입력). 열어 둔 채 응답한다 — 확인 버튼이 DELETE 를 보낸다. */
    @GetMapping(FRAG + "{id}/credentials/{cid}/revoke-confirm")
    fun revokeConfirm(
        @PathVariable id: String,
        @PathVariable cid: String,
        @RequestParam(name = "page", defaultValue = "1") page: String,
        @RequestParam(name = "size", defaultValue = "50") size: String,
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "aaguid", defaultValue = "") aaguid: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        val st = St.of(page, size, q, aaguid, status)
        val c = find(v.id, cid, st).view
        // !!: find 가 이미 역참조했다(원 Java 도 find → credentials.list 에서 NPE) — 여기서는 null 일 수 없다.
        val tid = v.id!!
        val r = row(tid, c, st)
        model.addAttribute("c", r)
        model.addAttribute("typeLabel", "확인을 위해 마지막 8자 " + r.tail8 + " 를 입력하세요")
        val confirm = LinkedHashMap<String, Any?>()
        confirm["hx-delete"] = FRAG + tid + "/credentials/" + cid + "?" + st.query()
        confirm["hx-target"] = "#cred-results"
        confirm["hx-swap"] = "outerHTML"
        confirm["hx-sync"] = "this:drop"
        model.addAttribute("confirmAttrs", confirm)
        return REVOKE_VIEW
    }

    /**
     * 회수(L3 확인 뒤). 화면이 먼저 이 tenant 의 목록에서 credential 을 확인한다(없으면 404, REST 호출 없음).
     * 성공이면 목록을 다시 읽어 그리고 성공 토스트. 실패는 WebExceptionAdvice 가 확인 다이얼로그 안 인라인(confirmError)으로 알린다.
     */
    @DeleteMapping(FRAG + "{id}/credentials/{cid}")
    fun revoke(
        @PathVariable id: String,
        @PathVariable cid: String,
        @RequestParam(name = "page", defaultValue = "1") page: String,
        @RequestParam(name = "size", defaultValue = "50") size: String,
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "aaguid", defaultValue = "") aaguid: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        if (!Toasts.isConfirm(req)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "확인 후 다시 시도하세요.")
        val st = St.of(page, size, q, aaguid, status)
        val c = find(v.id, cid, st).view
        // !!: find 가 이미 역참조했다(원 Java 도 find → credentials.list 에서 NPE) — 여기서는 null 일 수 없다.
        val tid = v.id!!
        credentials.revoke(tid, cid, auth!!)
        Toasts.successTitled(res, "Credential을 회수했습니다.", tail(c.credentialId, 12) + " · " + c.userHandle)
        model.addAttribute("tid", tid.toString())
        fillList(model, tid, st, false, null) // 회수는 이미 성공했다 — 목록 새로고침 실패는 목록 자리의 LoadError 로
        return LIST_VIEW
    }

    /** 현재 페이지에 보이는 행의 CSV(원본 downloadCsv: credentials-{slug}-{KST 오늘}.csv). */
    @GetMapping(FRAG + "{id}/credentials.csv")
    fun export(
        @PathVariable id: String,
        @RequestParam(name = "page", defaultValue = "1") page: String,
        @RequestParam(name = "size", defaultValue = "50") size: String,
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "aaguid", defaultValue = "") aaguid: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
    ): ResponseEntity<ByteArray> {
        val v = TenantTabs.load(tenants, id)
        val st = St.of(page, size, q, aaguid, status)
        // !!: 원 Java load(v.getId()) → credentials.list(Kotlin non-null 파라미터) 의 NPE 와 같은 자리.
        val l = load(v.id!!, st)
        val body = ArrayList<List<String?>>()
        for (c in applyClientFilter(l.pv.content(), l.st)) {
            // java.util.List.of: 원 Java 와 같이 null 원소(credentialId·userHandle)면 NPE.
            body.add(
                java.util.List.of(
                    c.credentialId, c.userHandle, nz(c.label), nz(c.authenticatorName), ACTIVE, nz(c.aaguidHex),
                    transports(c.transports).map { t -> t.value }.joinToString("|"), c.signCount.toString(),
                    iso(c.lastUsedAt), iso(c.createdAt),
                ),
            )
        }
        val csv = CsvWriter.write(
            java.util.List.of(
                "credentialId", "externalUserId", "label", "authenticatorName", "status", "aaguid", "transports",
                "signatureCounter", "lastUsedAt", "createdAt",
            ),
            body,
        )
        val filename = "credentials-" + v.slug + "-" + KstFormat.kstToday(clock.instant()) + ".csv"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace(UNSAFE_FILENAME, "_") + "\"")
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv)
    }

    // ── 조회·경계 ───────────────────────────────────────────

    private data class Loaded(val st: St, val pv: PageView<CredentialView>)

    private data class Found(val view: CredentialView, val window: Loaded?)

    private fun fetch(tid: UUID, page: Int, size: Int, q: String?): PageView<CredentialView> =
        credentials.list(tid, page - 1, size, if (q == null || q.isEmpty()) null else q).data()

    /** 현재 상태의 페이지를 읽는다. 지운 뒤 마지막 페이지가 비어 버렸으면 새 마지막 페이지로 한 번 보정한다. */
    private fun load(tid: UUID, requested: St): Loaded {
        var st = requested
        var pv = fetch(tid, st.page, st.size, st.q)
        val total = pv.totalElements()
        val last = Math.max(1L, (total + st.size - 1) / st.size).toInt()
        if (st.page > last) {
            st = st.withPage(last)
            pv = fetch(tid, st.page, st.size, st.q)
        }
        return Loaded(st, pv)
    }

    /**
     * 이 tenant 의 목록에서 credential 을 찾는다. 먼저 화면이 보던 페이지 창, 없으면 id 로 검색(정확히 같은 id 만). 못 찾으면 404.
     * 다른 tenant 의 credential 은 이 tenant 의 목록에 없으므로 여기서 막힌다.
     */
    private fun find(tenantId: UUID?, cid: String, st: St): Found {
        if (!B64URL.matcher(cid).matches()) throw BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음")
        // !!: 원 Java load(tid) → credentials.list(Kotlin non-null 파라미터) 의 NPE 와 같은 자리(형식 검사 뒤).
        val tid = tenantId!!
        val w = load(tid, st)
        for (c in w.pv.content()) {
            if (cid == c.credentialId) return Found(c, w)
        }
        for (c in fetch(tid, 1, PAGE_SIZES[0], cid).content()) {
            if (cid == c.credentialId) return Found(c, null)
        }
        throw BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음")
    }

    // ── 목록 모델 ───────────────────────────────────────────

    /** 목록을 읽어 모델을 채운다. 실패하면 loadError(권한 거부는 그대로 던진다). */
    private fun fillList(model: Model, tid: UUID, st: St, listOob: Boolean, reset: String?) {
        var l: Loaded? = null
        var error: RuntimeException? = null
        try {
            l = load(tid, st)
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[credentials-tab] list failed: {}", e.javaClass.simpleName)
            error = e
        }
        fillRows(model, tid, st, l, error, listOob, reset)
    }

    private fun fillRows(model: Model, tid: UUID, requested: St, l: Loaded?, error: RuntimeException?, listOob: Boolean, reset: String?) {
        val st = if (l == null) requested else l.st
        val base = "$FRAG$tid/tab/credentials/list"
        val items: List<CredentialView> = if (l == null) java.util.List.of() else l.pv.content()
        val visible = applyClientFilter(items, st)
        val total = if (l == null) 0L else l.pv.totalElements()
        val cf = st.clientFiltered()

        model.addAttribute("tid", tid.toString())
        model.addAttribute("q", st.q)
        model.addAttribute("aaguidFilter", st.aaguid)
        model.addAttribute("status", st.status)
        model.addAttribute("size", st.size)
        model.addAttribute("page", st.page)
        model.addAttribute("pageSizes", PAGE_SIZES)
        model.addAttribute("columns", COLUMNS)
        model.addAttribute("total", total)
        model.addAttribute("rows", visible.map { c -> row(tid, c, st) })
        model.addAttribute("loadError", error)
        model.addAttribute("clientFiltered", cf)
        model.addAttribute("noData", total == 0L && st.q.isEmpty() && !cf)
        model.addAttribute(
            "countText",
            if (error != null) {
                "—"
            } else if (cf) {
                "이 페이지 " + num(items.size.toLong()) + "건 중 " + num(visible.size.toLong()) + "건"
            } else {
                num(total) + "건"
            },
        )
        model.addAttribute("csvLabel", "CSV 내보내기 (현재 페이지 " + num(visible.size.toLong()) + "건)")
        model.addAttribute("csvDisabled", error != null || visible.isEmpty())
        model.addAttribute("csvUrl", FRAG + tid + "/credentials.csv?" + st.query())
        model.addAttribute("statusLabel", STATUS_LABELS[st.status])
        model.addAttribute("listOob", listOob)
        model.addAttribute("oobQ", "all" == reset)
        model.addAttribute("oobPanel", reset != null)

        // 요청 속성: 검색·필터가 이어 받는 입력(현재 입력값)을 hx-include 로 싣는다. 값은 모두 서버가 정한 주소·셀렉터다.
        val inclSize = "#cred-size"
        model.addAttribute("pageBase", base)
        model.addAttribute("includeAll", "#cred-q, #cred-aaguid, #cred-status, #cred-size")
        model.addAttribute("includeForQ", "#cred-aaguid, #cred-status, $inclSize")
        model.addAttribute("includeForAaguid", "#cred-q, #cred-status, #cred-page, $inclSize")
        model.addAttribute("includeForSize", "#cred-q, #cred-aaguid, #cred-status")
        model.addAttribute("includeForStatus", "#cred-q, #cred-aaguid, $inclSize")
        model.addAttribute("includeForAaguidChip", "#cred-q, #cred-status, $inclSize")
        model.addAttribute("includeForStatusChip", "#cred-q, #cred-aaguid, $inclSize")

        val options = ArrayList<Map<String, Any?>>()
        for (s in java.util.List.of(ALL, ACTIVE, REVOKED)) {
            val o = LinkedHashMap<String, Any?>()
            o["value"] = s
            o["label"] = STATUS_LABELS[s]
            o["title"] = if (ALL == s) null else s
            o["attrs"] = swap("$base?status=$s", "#cred-q, #cred-aaguid, #cred-page, $inclSize")
            options.add(o)
        }
        model.addAttribute("statusOptions", options)
        model.addAttribute("chipAaguidAttrs", swap("$base?reset=aaguid", "#cred-q, #cred-status, #cred-page, $inclSize"))
        model.addAttribute("chipStatusAttrs", swap("$base?status=ALL&reset=status", "#cred-q, #cred-aaguid, #cred-page, $inclSize"))
        model.addAttribute("clearAttrs", swap("$base?reset=all", inclSize))
        val retry = swap(base + "?" + st.query(), null)
        model.addAttribute("retryAttrs", retry)
        // 이전/다음 페이지: 쪽 번호만 주소에, 나머지는 현재 입력값(hx-include)
        model.addAttribute("pageUrlBase", "$base?page=")
    }

    private fun row(tid: UUID, c: CredentialView, st: St): Row {
        val cid = c.credentialId
        val tail8 = tail(cid, 8)
        val an = c.authenticatorName
        val named = an != null && an.isNotEmpty()
        val cnt = c.signCount
        val lastUsedAt = c.lastUsedAt
        val kind = if (cnt > 0) "value" else if (lastUsedAt != null) "na" else "zero"
        val lastUsed = if (lastUsedAt == null) "—" else KstFormat.timeAgo(lastUsedAt.toInstant(), clock.instant())
        return Row(
            cid, tail8, c.userHandle, c.label, c.aaguidHex, authenticatorLabel(c), named, transports(c.transports), kind, cnt, lastUsed,
            detailAttrs(tid, cid, st, false, true), revokeConfirmAttrs(tid, cid, st), "회수 · …$tail8",
        )
    }

    private fun nav(tid: UUID, cid: String, window: Loaded): Nav? {
        val visible = applyClientFilter(window.pv.content(), window.st)
        val cur = window.st
        var idx = -1
        for (i in visible.indices) if (cid == visible[i].credentialId) idx = i
        if (idx < 0) return null
        val total = window.pv.totalElements()
        val lastPage = Math.max(1L, (total + cur.size - 1) / cur.size).toInt()
        val cf = cur.clientFiltered()
        val pos = if (cf) (idx + 1).toString() + " / " + visible.size else ((cur.page - 1).toLong() * cur.size + idx + 1).toString() + " / " + total

        var prev: Map<String, Any?>? = null
        var next: Map<String, Any?>? = null
        if (idx > 0) {
            prev = detailAttrs(tid, visible[idx - 1].credentialId, cur, false, false)
        } else if (!cf && cur.page > 1) {
            val p = cur.withPage(cur.page - 1)
            val items = fetchQuiet(tid, p)
            if (items.isNotEmpty()) prev = detailAttrs(tid, items[items.size - 1].credentialId, p, true, false)
        }
        if (idx < visible.size - 1) {
            next = detailAttrs(tid, visible[idx + 1].credentialId, cur, false, false)
        } else if (!cf && cur.page < lastPage) {
            val p = cur.withPage(cur.page + 1)
            val items = fetchQuiet(tid, p)
            if (items.isNotEmpty()) next = detailAttrs(tid, items[0].credentialId, p, true, false)
        }
        return Nav(pos, prev, next)
    }

    /** 이웃 페이지 읽기. 실패하면(이동 버튼만 비활성) 조용히 빈 목록. */
    private fun fetchQuiet(tid: UUID, st: St): List<CredentialView> {
        try {
            return fetch(tid, st.page, st.size, st.q).content()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[credentials-tab] neighbour page failed: {}", e.javaClass.simpleName)
            return java.util.List.of()
        }
    }

    /** 원본 CSV 는 서버 JSON 의 문자열을 그대로 쓴다 — 같은 ObjectMapper 직렬화 형태. */
    private fun iso(t: OffsetDateTime?): String? {
        if (t == null) return ""
        try {
            return json.convertValue(t, String::class.java)
        } catch (e: RuntimeException) {
            return t.toString()
        }
    }

    companion object {
        private const val FRAG = "/admin/_/tenants/"
        private const val TAB_VIEW = "admin/tenant/credentials :: tab"
        private const val LIST_VIEW = "admin/tenant/credential-rows :: response"
        private const val DETAIL_VIEW = "admin/tenant/credential-detail :: detail"
        private const val REVOKE_VIEW = "admin/tenant/credential-detail :: revoke"

        private val PAGE_SIZES: List<Int> = java.util.List.of(50, 100, 200)
        private const val ALL = "ALL"
        private const val ACTIVE = "ACTIVE"
        private const val REVOKED = "REVOKED"
        private val STATUS_LABELS: Map<String, String> = java.util.Map.of(ALL, "전체", ACTIVE, "활성", REVOKED, "회수됨")
        private val TRANSPORT_LABELS: Map<String, String> = java.util.Map.of(
            "internal", "내장", "usb", "USB", "nfc", "NFC", "ble", "블루투스", "hybrid", "휴대폰 연동", "smart-card", "스마트카드",
        )
        private const val EVENTS_SIZE = 20
        private val B64URL: Pattern = Pattern.compile("[A-Za-z0-9_-]{1,512}")
        private val COMMA: Pattern = Pattern.compile(",")
        private val UNSAFE_FILENAME = Regex("[^A-Za-z0-9._-]")

        private val COLUMNS: List<Map<String, Any?>> = java.util.List.of(
            col("id", "Credential ID", null, null, null), col("user", "사용자", null, null, null), col("nick", "별칭", null, null, null),
            col("authn", "인증기", null, null, null), col("tr", "전송 방식", null, null, null), col("cnt", "서명 카운터", "right", null, null),
            col("st", "상태", null, null, null), col("used", "마지막 사용", null, null, null), col("revoke", "회수", null, "48px", true),
        )

        private fun col(key: String, header: String, align: String?, width: String?, srOnly: Boolean?): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["key"] = key
            m["header"] = header
            m["align"] = align
            m["width"] = width
            m["srOnly"] = srOnly
            return m
        }

        private fun denied(e: RuntimeException): Boolean =
            e is AccessDeniedException || (e is BusinessException && e.errorCode == ErrorCode.ACCESS_DENIED)

        private fun swap(url: String, include: String?): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = url
            if (include != null) m["hx-include"] = include
            m["hx-target"] = "#cred-results"
            m["hx-swap"] = "outerHTML"
            return m
        }

        /** 행 클릭(cred-open 이벤트)이 상세 다이얼로그를 불러온다. cid: 원 Java 문자열 연결 — null 이면 "null". */
        private fun detailAttrs(tid: UUID, cid: String?, st: St, listToo: Boolean, rowClick: Boolean): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = FRAG + tid + "/credentials/" + cid + "?" + st.query() + (if (listToo) "&list=1" else "")
            if (rowClick) m["hx-trigger"] = "cred-open" // 행만 이벤트로 연다. 버튼(이전/다음/다시 시도)은 기본 click.
            m["hx-target"] = "#cred-detail-slot"
            m["hx-swap"] = "innerHTML"
            m["hx-sync"] = "#cred-detail-slot:replace"
            return m
        }

        private fun revokeConfirmAttrs(tid: UUID, cid: String?, st: St): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = FRAG + tid + "/credentials/" + cid + "/revoke-confirm?" + st.query()
            m["hx-target"] = "#cred-confirm-slot"
            m["hx-swap"] = "innerHTML"
            m["hx-sync"] = "#cred-confirm-slot:replace"
            return m
        }

        // ── credentialHelpers.ts ────────────────────────────────

        private fun norm(s: String): String = s.replace("-", "").lowercase(Locale.ROOT)

        /** 서버에 aaguid·status 파라미터가 없어 현재 페이지 안에서만 거른다. status 는 서버 뷰에 없어 모두 ACTIVE(원본 adapt). */
        private fun applyClientFilter(items: List<CredentialView>, f: St): List<CredentialView> {
            val a = norm(f.aaguid.javaTrim())
            val out = ArrayList<CredentialView>()
            for (c in items) {
                val statusOk = ALL == f.status || ACTIVE == f.status
                val hex = c.aaguidHex
                val aaguidOk = a.isEmpty() || (hex != null && norm(hex).contains(a))
                if (statusOk && aaguidOk) out.add(c)
            }
            return out
        }

        /** MDS 이름 → aaguid 끝 8자 → unknown. */
        private fun authenticatorLabel(c: CredentialView): String {
            val name = c.authenticatorName
            if (name != null && name.isNotEmpty()) return name
            val hex = c.aaguidHex
            if (hex != null && hex.isNotEmpty()) {
                val n = norm(hex)
                return if (n.length <= 8) n else n.substring(n.length - 8)
            }
            return "unknown"
        }

        private fun transports(csv: String?): List<Transport> {
            val out = ArrayList<Transport>()
            if (csv == null) return out
            // Pattern.split: Java String.split 과 같은 토큰(끝의 빈 토큰 제거).
            for (t in COMMA.split(csv)) {
                val v = t.javaTrim()
                if (v.isNotEmpty()) out.add(Transport(v, TRANSPORT_LABELS.getOrDefault(v, v)))
            }
            return out
        }

        // ── 도우미 ──────────────────────────────────────────────

        private fun normStatus(s: String): String = if (ACTIVE == s || REVOKED == s) s else ALL

        /** 숫자가 아닌 page/size 는 400 이 아니라 기본값. */
        private fun parseInt(v: String, def: Int): Int {
            try {
                return Integer.parseInt(v.javaTrim())
            } catch (e: RuntimeException) {
                return def
            }
        }

        private fun tail(s: String?, n: Int): String {
            // !!: 원 Java s.length() 의 NPE 와 같은 자리(REST 뷰의 credentialId 가 null 인 경우).
            val t = s!!
            return if (t.length <= n) t else t.substring(t.length - n)
        }

        private fun nz(s: String?): String = s ?: ""

        private fun num(n: Long): String = String.format(Locale.KOREA, "%,d", n)

        private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
    }
}
