package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.HomeRedirectController
import com.crosscert.passkey.admin.web.format.ApiKeyExpiry
import com.crosscert.passkey.admin.web.format.CsvWriter
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.Labels
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.shell.Crumbs
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.Htmx
import com.crosscert.passkey.admin.web.support.Toasts
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Locale
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(TenantsPageController::class.java)

/**
 * Tenant 목록과 신규 생성(← pages/TenantsListPage.tsx, api/tenants.ts).
 *
 * 화면 구조(이후 화면 task 의 본보기): 전체 페이지는 `GET /admin/tenants`(레이아웃 안), 같은 화면의 부분 갱신은
 * `GET /admin/_/tenants/list`(레이아웃 없는 조각, 목록 영역 + OOB 로 KPI·필터 컨트롤)다. 두 경로가 같은 조각
 * (`admin/tenants/list-rows`)을 그려서 결과가 항상 같다. 조각 응답은 `HX-Push-Url` 로 주소창을 화면 주소로 바꾼다.
 *
 * 검색·필터·페이징은 원본이 클라이언트에서 하던 그대로 서버에서 한다: 정렬은 하지 않고 `TenantAdminController.list()` 가
 * 준 순서를 유지하며, 검색은 name(대소문자 무시)·slug 부분 일치다(원본 `t.slug.includes(q.toLowerCase())`).
 * 기존 REST 컨트롤러 빈을 메서드로 호출한다(HTTP 아님).
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 `requirePlatform(model)`(403)이 먼저다. 넘기는 자리의 `!!` 는 원 Java 에서
 * Kotlin 피호출자의 파라미터 검사 NPE 가 나던 자리(try 안팎도 같다).
 */
@Controller
class TenantsPageController(
    private val tenants: TenantAdminController,
    private val apiKeys: ApiKeyAdminController,
    private val validator: Validator,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** 화면에 쓰는 Tenant 한 줄(원본 adaptTenant: name=displayName, status 는 ACTIVE 아니면 SUSPENDED). */
    data class TenantRow(
        val id: String,
        val name: String,
        val slug: String,
        val rpId: String?,
        val status: String,
        val credentials: Long,
        val apiKeys: Long,
        val lastEventAt: OffsetDateTime?,
        val createdAt: OffsetDateTime?,
    ) {
        /** `t.name.slice(0, 1).toUpperCase()` */
        fun initial(): String = if (name.isEmpty()) "" else name.substring(0, 1).uppercase(Locale.ROOT)

        /** `…${tail(id, 8)}` */
        fun idTail(): String = "…" + (if (id.length > 8) id.substring(id.length - 8) else id)

        fun detailUrl(): String = "$PAGE_PATH/$id?tab=overview"

        companion object {
            internal fun from(v: TenantAdminDto.TenantView): TenantRow {
                val st = v.status
                return TenantRow(
                    v.id.toString(), // 원 Java String.valueOf — null 이면 "null"
                    v.displayName ?: "", v.slug ?: "", v.rpId,
                    if (st != null && st.equals(ACTIVE, ignoreCase = true)) ACTIVE else SUSPENDED,
                    v.credentials, v.apiKeys, v.lastEventAt, v.createdAt,
                )
            }
        }
    }

    /** 신규 생성 폼의 값·필드 오류·실패 안내. 다이얼로그 조각이 이 값으로 그린다. */
    data class NewTenantForm(
        val name: String?,
        val slug: String?,
        val rpId: String?,
        val nameError: String?,
        val slugError: String?,
        val rpIdError: String?,
        val failure: Failure?,
    ) {
        fun hasErrors(): Boolean = nameError != null || slugError != null || rpIdError != null

        companion object {
            internal fun empty(): NewTenantForm = NewTenantForm("", "", "", null, null, null, null)
        }
    }

    /** 생성 요청이 서버에서 실패했을 때 다이얼로그 안 경고(Alert tone=bad)의 문구. */
    data class Failure(val text: String?, val meta: String?)

    // ── 화면 ────────────────────────────────────────────────

    /**
     * 전체 페이지. PLATFORM 전용이다(RequirePlatform): RP_ADMIN 은 [HomeRedirectController] 와 같은 규칙으로 자기 테넌트로
     * 보내고, 테넌트가 없는 RP_ADMIN(데이터 이상)은 "계정 구성 오류"를 본다. 목록 조회가 실패하면 200 으로 오류 카드를 그린다
     * (hx-boost 이동은 4xx/5xx 를 스왑하지 않아 오류 화면이 비어 보이므로).
     */
    @GetMapping(PAGE_PATH)
    fun page(
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        @RequestParam(name = "page", defaultValue = "1") pageParam: String,
        @RequestParam(name = "size", defaultValue = "50") sizeParam: String,
        @RequestParam(name = "new", required = false) openNew: String?,
        model: Model,
    ): String {
        val me = me(model)
        model.addAttribute("breadcrumbs", Crumbs.tenants())
        if (!me.platform) {
            val home = HomeRedirectController.homePath(me)
            if (PAGE_PATH != home) return "redirect:$home"
            model.addAttribute("configError", true)
            return PAGE_VIEW
        }
        model.addAttribute("configError", false)
        val st = normStatus(status)
        val sz = normSize(parseInt(sizeParam, PAGE_SIZES[0]))
        val page = parseInt(pageParam, 1)

        var all: List<TenantRow>? = null
        var loadError: Exception? = null
        try {
            all = loadTenants()
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[tenants] list failed: {}", e.toString())
            loadError = e
        }
        model.addAttribute("loaded", all != null)
        model.addAttribute("loadError", loadError)
        model.addAttribute("kpiDash", "—")
        // 목록 조회 실패 카드의 "다시 시도": 같은 전체 페이지를 다시 받아 본문을 교체한다(주소는 그대로).
        val retry = LinkedHashMap<String, Any?>()
        retry["hx-get"] = canonicalUrl(q, st, page, sz)
        retry["hx-target"] = "body"
        retry["hx-swap"] = "innerHTML"
        retry["hx-push-url"] = "false"
        model.addAttribute("retryAttrs", retry)
        // 새로 고침·뒤로 가기에서 다시 열리지 않게 new 를 주소에서 지운다(원본 setParams replace). 다이얼로그를 처음부터 열어 둔다.
        val open = "1" == openNew
        model.addAttribute("cleanUrl", canonicalUrl(q, st, page, sz))
        model.addAttribute("openNew", open)
        model.addAttribute("ntOpts", if (open) java.util.Map.of("open", true) else null)
        model.addAttribute("nt", NewTenantForm.empty())
        if (all != null) {
            populate(model, all, q, st, page, sz)
            populateKpis(model, all)
            if (all.isEmpty()) model.addAttribute("kpiActiveAttrs", null) // 빈 목록: 가리킬 영역이 없어 클릭형이 아니다
        }
        return PAGE_VIEW
    }

    /**
     * 목록 조각(검색·필터·페이징·KPI 클릭). 목록 영역(`#tenants-results`)과 OOB 로 필터 컨트롤·활성 KPI·검색 입력을 돌려준다.
     * 주소창은 `HX-Push-Url` 로 화면 주소(`/admin/tenants?…`)가 된다. 조회 실패는 WebExceptionAdvice 의 오류 토스트
     * (본문 없음)라 화면은 그대로 남는다.
     */
    @GetMapping(LIST_PATH)
    fun list(
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        @RequestParam(name = "page", defaultValue = "1") pageParam: String,
        @RequestParam(name = "size", defaultValue = "50") sizeParam: String,
        @RequestParam(name = "reset", required = false) reset: String?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        val st = normStatus(status)
        val sz = normSize(parseInt(sizeParam, PAGE_SIZES[0]))
        val page = parseInt(pageParam, 1)
        val all = loadTenants()
        if (all.isEmpty()) {
            // 그 사이 tenant 가 모두 사라졌다 — 빈 상태 화면은 전체 페이지가 그린다.
            Htmx.redirect(req, res, PAGE_PATH)
            return "admin/fragments/shell/empty :: none"
        }
        populate(model, all, q, st, page, sz)
        val url = canonicalUrl(q, st, model.getAttribute("page") as Int, sz)
        val typing = SEARCH_INPUT_ID == req.getHeader("HX-Trigger")
        // 디바운스된 검색 요청은 멈출 때마다 이력을 쌓지 않고 현재 항목을 바꾼다.
        res.setHeader(if (typing) "HX-Replace-Url" else "HX-Push-Url", url)
        // 검색 입력은 검색어를 지우는 요청(reset=q: 칩 제거·모두 지우기)에서만 OOB 로 되돌려 쓴다.
        // 그 밖의 요청은 현재 입력값(hx-include)을 싣고 오므로 되돌려 쓰면 응답 사이에 친 글자만 지운다.
        model.addAttribute("oobQ", "q" == reset && !typing)
        return ROWS_VIEW
    }

    /** 필터를 적용한 목록의 CSV(원본 downloadCsv: tenants-{KST 오늘}.csv, 필터 적용 행 전체 — 페이지 무시). */
    @GetMapping(EXPORT_PATH)
    fun export(
        @RequestParam(name = "q", defaultValue = "") q: String,
        @RequestParam(name = "status", defaultValue = ALL) status: String,
        model: Model,
    ): ResponseEntity<ByteArray> {
        requirePlatform(model)
        val rows = filter(loadTenants(), q, normStatus(status))
        val body = ArrayList<List<String>>(rows.size)
        for (t in rows) {
            body.add(
                java.util.List.of(
                    t.name, t.slug, t.rpId.toString(), t.status, // rpId: 원 Java String.valueOf — null 이면 "null"
                    t.credentials.toString(), t.apiKeys.toString(),
                    if (t.createdAt == null) "" else t.createdAt.toInstant().toString(),
                ),
            )
        }
        val csv = CsvWriter.write(java.util.List.of("name", "slug", "rpId", "status", "credentials", "apiKeys", "createdAt"), body)
        val filename = "tenants-" + KstFormat.kstToday(clock.instant()) + ".csv"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv)
    }

    // ── 생성 ────────────────────────────────────────────────

    /**
     * 신규 Tenant(`TenantAdminController.create`). PLATFORM 전용. 성공이면 새 tenant 의 WebAuthn 설정 탭으로 이동하고
     * (원본 navigate(`/tenants/${id}?tab=webauthn`)) 토스트를 띄운다(HX-Redirect 로 페이지가 바뀌므로 ui.js 가 토스트를 다음 화면에 넘긴다).
     * 입력 검증 오류와 서버 거절은 모두 422 + 다이얼로그 폼 조각이다 — 열린 채 입력값을 유지하고(원본: 오류 토스트 없이 인라인 경고),
     * 서버 거절은 경고 안에 HTTP 상태·trace 를 보인다.
     */
    @PostMapping("/admin/_/tenants")
    fun create(
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "slug", defaultValue = "") slug: String,
        @RequestParam(name = "rpId", defaultValue = "") rpId: String,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String? {
        requirePlatform(model)
        // 입력 핸들러와 같은 정규화: slug 는 소문자, rpId 는 소문자·trim.
        val slugV = slug.lowercase(Locale.ROOT)
        val rpIdV = rpId.lowercase(Locale.ROOT).javaTrim()

        var nameError: String? = if (name.javaIsBlank()) NAME_ERROR else null
        var slugError: String? = if (SLUG_RE.matcher(slugV).matches()) null else SLUG_ERROR
        var rpIdError: String? = if (RP_ID_RE.matcher(rpIdV).matches()) null else RP_ID_ERROR
        val body = toRequest(name, slugV, rpIdV)
        if (nameError == null && slugError == null && rpIdError == null) {
            // 크기 제한 등 나머지 제약은 REST 와 같은 Bean Validation 으로.
            for (v in validator.validate(body)) {
                when (v.propertyPath.toString()) {
                    "displayName", "rpName" -> nameError = NAME_LONG_ERROR
                    "slug" -> slugError = SLUG_ERROR
                    "rpId" -> rpIdError = RP_ID_LONG_ERROR
                    else -> { /* 다른 필드는 이 화면이 고정값으로 채운다 */ }
                }
            }
        }
        if (nameError != null || slugError != null || rpIdError != null) {
            return invalid(res, model, NewTenantForm(name, slugV, rpIdV, nameError, slugError, rpIdError, null))
        }

        val created = try {
            tenants.create(body, auth!!).data()
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            val info = ErrorMessages.describe(e)
            log.warn("[tenants] create failed: status={} cause={}", info.httpStatus, e.toString())
            return invalid(res, model, NewTenantForm(name, slugV, rpIdV, null, null, null, failure(info)))
        }
        Toasts.successTitled(res, "Tenant 생성 완료", created.displayName + " · WebAuthn 설정으로 이동합니다")
        Htmx.redirect(req, res, PAGE_PATH + "/" + created.id + "?tab=webauthn")
        return null
    }

    // ── 검색·필터·페이징(원본 TenantsListPage 와 같은 결과) ──────────

    private fun populate(model: Model, all: List<TenantRow>, q: String, status: String, page: Int, size: Int) {
        val filtered = filter(all, q, status)
        val total = filtered.size
        val last = Math.max(1, (total + size - 1) / size)
        val p = Math.min(Math.max(1, page), last)
        val pageRows = filtered.subList(Math.min((p - 1) * size, total), Math.min(p * size, total))
        val active = all.count { ACTIVE == it.status }.toLong()
        val suspended = all.count { SUSPENDED == it.status }.toLong()

        model.addAttribute("q", q)
        model.addAttribute("status", status)
        model.addAttribute("page", p)
        model.addAttribute("size", size)
        model.addAttribute("pageSizes", PAGE_SIZES)
        model.addAttribute("rows", pageRows)
        model.addAttribute("total", total)
        model.addAttribute("countAll", all.size)
        model.addAttribute("columns", COLUMNS)
        model.addAttribute("hasFilter", ALL != status || q.isNotEmpty())
        model.addAttribute("statusLabel", Labels.statusLabel(status))
        model.addAttribute("oobQ", false)
        model.addAttribute("csvUrl", EXPORT_PATH + "?q=" + enc(q) + "&status=" + status)
        model.addAttribute("listBase", "$LIST_PATH?status=$status&size=$size")

        // 상태 세그먼트 · 활성 KPI · 필터 칩의 htmx 속성. 값은 서버가 정한 주소뿐이다(사용자 입력은 인코딩해 주소 안에만).
        val options = ArrayList<Map<String, Any?>>()
        options.add(option(ALL, "전체 " + all.size, listUrl(ALL, 1, size)))
        options.add(option(ACTIVE, "활성 $active", listUrl(ACTIVE, 1, size)))
        options.add(option(SUSPENDED, "정지 $suspended", listUrl(SUSPENDED, 1, size)))
        model.addAttribute("statusOptions", options)

        // 활성 KPI 클릭 = ACTIVE 토글(applyStatus(statusFilter === 'ACTIVE' ? 'ALL' : 'ACTIVE')).
        val kpiUrl = listUrl(if (ACTIVE == status) ALL else ACTIVE, 1, size)
        val kpi = swap(kpiUrl, true)
        kpi["id"] = "tenants-kpi-active"
        kpi["hx-sync"] = "#tenants-card:replace"
        model.addAttribute("kpiActiveAttrs", kpi)
        val kpiOob = LinkedHashMap<String, Any?>(kpi)
        kpiOob["hx-swap-oob"] = "true"
        model.addAttribute("kpiActiveAttrsOob", kpiOob)
        model.addAttribute("kpiActiveValue", fmt(active))
        model.addAttribute("kpiActiveHint", "전체 " + fmt(all.size.toLong()) + "건 · 클릭하면 활성만 보기")

        model.addAttribute("chipStatusAttrs", swap(listUrl(ALL, 1, size), true))
        model.addAttribute("chipQAttrs", swap(listUrl(status, 1, size) + "&reset=q", false))
        model.addAttribute("clearAttrs", swap(listUrl(ALL, 1, size) + "&reset=q", false))
    }

    private fun populateKpis(model: Model, all: List<TenantRow>) {
        val creds = all.sumOf { it.credentials }
        val keys = all.sumOf { it.apiKeys }
        model.addAttribute("kpiCredValue", fmt(creds))
        // 원본의 credentialsDelta7d 는 서버 TenantView 에 없어 항상 미표시("모든 tenant 합산").
        model.addAttribute("kpiCredHint", "모든 tenant 합산")
        model.addAttribute("kpiKeysValue", fmt(keys))
        model.addAttribute("expiring", expiringKeys())
    }

    /** ACTIVE 키 중 30일 이내 만료 수. 키 목록을 못 읽으면 null(보조 줄만 숨김 — 목록 화면은 그대로). */
    private fun expiringKeys(): Int? {
        try {
            val keys = apiKeys.list(null).data()
            val now = clock.instant()
            return keys.count { k -> ApiKeyExpiry.isExpiringSoon(k.revokedAt, k.expiresAt, now) }
        } catch (e: RuntimeException) {
            log.warn("[tenants] api key list for expiry KPI failed: {}", e.toString())
            return null
        }
    }

    private fun loadTenants(): List<TenantRow> = tenants.list().data().map { TenantRow.from(it) }

    companion object {
        private const val PAGE_VIEW = "admin/tenants/list"
        private const val ROWS_VIEW = "admin/tenants/list-rows :: rows"
        private const val FORM_VIEW = "admin/tenants/new-dialog :: formFields"

        internal const val PAGE_PATH = "/admin/tenants"
        private const val LIST_PATH = "/admin/_/tenants/list"
        private const val EXPORT_PATH = "/admin/_/tenants/export"

        /** 원본 PAGE_SIZES. 첫 값이 기본이다. */
        private val PAGE_SIZES: List<Int> = java.util.List.of(50, 100)
        private const val ALL = "ALL"
        private const val ACTIVE = "ACTIVE"
        private const val SUSPENDED = "SUSPENDED"

        /** 검색 입력의 id. 조각 요청의 `HX-Trigger` 요청 헤더가 이 값이면 사용자가 타이핑 중이라 입력칸을 되돌려 쓰지 않는다. */
        private const val SEARCH_INPUT_ID = "tenant-q"

        // NewTenantDialog 의 정규식 그대로.
        private val SLUG_RE: Pattern = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$")

        // rpId 는 WebAuthn RP ID — registrable domain(hostname). 스킴/포트/경로 없이 점이 포함된 호스트명. placeholder(example.com) 저장 방지.
        private val RP_ID_RE: Pattern = Pattern.compile("^(?!.*\\.example\\.com$)([a-z0-9-]+\\.)+[a-z]{2,}$")
        private const val SLUG_ERROR = "영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요."
        private const val RP_ID_ERROR = "스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com). example.com은 placeholder라 사용할 수 없습니다."
        private const val NAME_ERROR = "표시 이름을 입력하세요."
        private const val NAME_LONG_ERROR = "표시 이름은 256자 이하로 입력하세요."
        private const val RP_ID_LONG_ERROR = "RP ID는 256자 이하로 입력하세요."

        private val COLUMNS: List<Map<String, Any?>> = java.util.List.of(
            col("tenant", "Tenant", null, null, null),
            col("slug", "슬러그", null, null, null),
            col("rpId", "RP ID", null, null, null),
            col("credentials", "Credentials", "right", null, null),
            col("apiKeys", "API 키", "right", null, null),
            col("status", "상태", null, null, null),
            col("lastEvent", "마지막 이벤트", null, null, null),
            col("createdAt", "생성일", null, null, null),
            col("open", "열기", null, "40px", true),
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

        /** NewTenantDialog 의 onCreate → tenantsApi.create 본문과 같은 값. */
        private fun toRequest(name: String, slug: String, rpId: String): TenantAdminDto.TenantCreateRequest =
            TenantAdminDto.TenantCreateRequest(
                slug, name, rpId, name,
                java.util.List.of("https://$rpId"),
                LinkedHashSet(java.util.List.of("none", "packed")),
                true, false, "NONE", 60000,
            )

        private fun invalid(res: HttpServletResponse, model: Model, form: NewTenantForm): String {
            res.status = 422
            model.addAttribute("nt", form)
            return FORM_VIEW
        }

        private fun failure(info: ErrorMessages.Info): Failure {
            val detail = info.detail ?: ""
            val text = info.title + ". " + detail + " 입력한 내용은 그대로 있습니다."
            val meta = ArrayList<String>()
            if (info.httpStatus != null) meta.add("HTTP " + info.httpStatus)
            val trace: String? = MDC.get("traceId")
            if (trace != null && trace.isNotEmpty()) meta.add("trace $trace")
            return Failure(text, meta.joinToString(" · "))
        }

        /**
         * 원본 filtered: `!q || name.toLowerCase().includes(q.toLowerCase()) || slug.includes(q.toLowerCase())` 와
         * `status === 'ALL' || t.status === status`. q 는 trim 하지 않는다. 순서는 입력 그대로.
         * internal: 원 Java 는 package-private static — 같은 모듈 테스트(TenantsPageControllerTest)가 부른다.
         */
        internal fun filter(tenants: List<TenantRow>, q: String, status: String?): List<TenantRow> {
            val lower = q.lowercase(Locale.ROOT)
            val out = ArrayList<TenantRow>()
            for (t in tenants) {
                val matchesQ = q.isEmpty() ||
                    t.name.lowercase(Locale.ROOT).contains(lower) ||
                    t.slug.contains(lower)
                val matchesStatus = ALL == status || t.status == status
                if (matchesQ && matchesStatus) out.add(t)
            }
            return out
        }

        /** internal: 같은 모듈 테스트(TenantsPageControllerTest)가 부른다. */
        internal fun normStatus(s: String?): String = if (ACTIVE == s) ACTIVE else if (SUSPENDED == s) SUSPENDED else ALL

        /** 숫자가 아닌 page/size 는 400 이 아니라 기본값(원본은 select·버튼으로만 바뀌어 잘못된 값이 없다). */
        private fun parseInt(v: String, def: Int): Int =
            try {
                Integer.parseInt(v.javaTrim())
            } catch (e: RuntimeException) {
                def
            }

        /** internal: 같은 모듈 테스트(TenantsPageControllerTest)가 부른다. */
        internal fun normSize(size: Int): Int = if (PAGE_SIZES.contains(size)) size else PAGE_SIZES[0]

        // ── 주소·속성 ────────────────────────────────────────────

        private fun option(value: String, label: String, url: String): Map<String, Any?> {
            val o = LinkedHashMap<String, Any?>()
            o["value"] = value
            o["label"] = label
            o["title"] = null
            o["attrs"] = swap(url, true)
            return o
        }

        /** 목록 영역을 바꾸는 요청의 공통 속성. 대상 `#tenants-results` 를 통째로 교체하고 응답의 OOB 가 컨트롤을 맞춘다. */
        private fun swap(url: String, withQuery: Boolean): MutableMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            // 검색어는 주소에 굽지 않고 요청 시점의 입력값(#tenant-q)을 싣는다 — 입력 중 눌러도 옛 검색어로 되돌아가지 않는다.
            if (withQuery) m["hx-include"] = "#$SEARCH_INPUT_ID"
            m["hx-get"] = url
            m["hx-target"] = "#tenants-results"
            m["hx-swap"] = "outerHTML"
            return m
        }

        private fun listUrl(status: String, page: Int, size: Int): String =
            "$LIST_PATH?status=$status&size=$size" + (if (page > 1) "&page=$page" else "")

        /** 주소창에 남길 화면 주소: 기본값(전체·1쪽·50건·빈 검색어)은 생략한다. */
        private fun canonicalUrl(q: String, status: String, page: Int, size: Int): String {
            val params = ArrayList<String>()
            if (q.isNotEmpty()) params.add("q=" + enc(q))
            if (ALL != status) params.add("status=$status")
            if (page > 1) params.add("page=$page")
            if (size != PAGE_SIZES[0]) params.add("size=$size")
            return if (params.isEmpty()) PAGE_PATH else PAGE_PATH + "?" + params.joinToString("&")
        }

        private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

        private fun fmt(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

        private fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")

        /** 조각·변경 엔드포인트는 PLATFORM 전용(원본은 RequirePlatform 이 화면째 막았다). 보안 경계는 REST 의 @PreAuthorize 도 함께. */
        private fun requirePlatform(model: Model) {
            if (!me(model).platform) throw AccessDeniedException("PLATFORM_OPERATOR only")
        }
    }
}
