package com.crosscert.passkey.admin.web.activity

import com.crosscert.passkey.admin.activity.ActivityController
import com.crosscert.passkey.admin.activity.ActivityDetailView
import com.crosscert.passkey.admin.activity.ActivityView
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.HomeRedirectController
import com.crosscert.passkey.admin.web.format.ActivityDiff
import com.crosscert.passkey.admin.web.format.ActivityLabels
import com.crosscert.passkey.admin.web.format.CsvWriter
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.shell.Crumbs
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.ExportLimit
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.admin.web.tenant.AuditHelpers
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import java.util.function.Function
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(ActivityPageController::class.java)

/**
 * 활동 화면(← pages/ActivityPage.tsx, api/activity.ts). PLATFORM 전용 — RP_ADMIN 은 [HomeRedirectController] 와 같은 규칙으로
 * 자기 홈으로 보내고(전체 페이지), 조각·CSV 는 403 이다. REST 의 `@PreAuthorize` 도 함께 막는다.
 *
 * 구조: 전체 페이지 `GET /admin/activity`, 목록 교체 `/admin/_/activity/rows`(필터 변경·다시 시도),
 * 폴링·새로고침 `/admin/_/activity/poll`, 더 보기 `/admin/_/activity/more`, 상세 `/admin/_/activity/{id}`,
 * CSV `/admin/_/activity/export`. 서버는 상태가 없다 — 화면에 보이는 목록(DOM)이 상태이고, 요청마다 현재 필터와
 * 숨은 입력(가장 새 행 id, 가장 오래된 행의 커서, 날짜 머리글 라벨)이 실린다.
 *
 * - 폴링은 원본처럼 최신 한 쪽만 받아 "아직 없는 행만" 앞에 붙인다(OOB afterbegin). 더 보기로 불러온 행·펼친 상세·스크롤은 그대로다.
 * - 필터(카테고리·tenant)가 바뀌면 목록을 통째로 교체한다. 액션 검색(q)은 원본처럼 서버가 아니라 클라이언트(Alpine)가 읽어 온 행을 거른다.
 *   날짜 그룹은 KST 날짜 키(id=day-KEY)로 이어 붙는다.
 * - 사용자 입력(행위자·tenant 이름·payload 값)은 템플릿에서 th:text 로만 그린다. 상세의 diff 도 문자열이다.
 *
 * `!!` 는 원 Java 가 nullable 값(`ActivityView.Event.id`·`TopTenant.tenantId`·`TenantView.id` 의 `toString()`, `kpi`·`top5` 역참조)에서
 * NPE 를 내던 자리에만 둔다 — 모두 try 밖(원 Java 와 같은 쪽)이다.
 */
@Controller
class ActivityPageController(
    private val activity: ActivityController,
    private val tenants: TenantAdminController,
    private val clock: Clock,
    private val json: ObjectMapper,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** 조회 조건. category: all|ops|security, tenantId: 없으면 null, q: 입력 그대로(필터 때만 trim). */
    internal data class Filter(val category: String, val tenantId: UUID?) {
        fun categoryParam(): String? = if (CAT_ALL == category) null else category

        fun hasServerApplied(): Boolean = CAT_ALL != category || tenantId != null
    }

    /** 피드 한 줄(원본 adaptFeedItems 의 DisplayEvent + 화면 값). */
    data class Ev(
        val id: String,
        val type: String,
        val typeUpper: String,
        val typeTitle: String?,
        val toneVar: String?,
        val hm: String,
        val iso: String,
        val tenantId: String?,
        val tenantName: String,
        val tenantUrl: String?,
        val sentence: String,
        val detailAttrs: Map<String, Any?>,
    )

    /** 원본 groupByDay 의 그룹. key = KST 날짜(yyyy-MM-dd) — 폴링·더 보기가 이 키로 기존 그룹에 이어 붙는다. */
    data class Day(val label: String, val key: String?, val events: List<Ev>)

    data class TopRow(
        val tenantId: String,
        val name: String,
        val slug: String,
        val initial: String,
        val count: String,
        val pct: Int,
        val selected: Boolean,
        val attrs: Map<String, Any?>,
    )

    data class Option(val id: String, val label: String, val selected: Boolean)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(PAGE_PATH)
    fun page(
        @RequestParam(name = "category", defaultValue = "") category: String,
        @RequestParam(name = "tenantId", defaultValue = "") tenantId: String,
        model: Model,
    ): String {
        val me = me(model)
        model.addAttribute("breadcrumbs", Crumbs.activity())
        if (!me.platform) return "redirect:" + HomeRedirectController.homePath(me)
        val f = filter(category, tenantId)
        val now = clock.instant()
        var view: ActivityView? = null
        var error: RuntimeException? = null
        try {
            view = activity.activity(null, f.categoryParam(), null, null, f.tenantId).data()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[activity] first load failed: {}", e.toString())
            error = e
        }
        model.addAttribute("pollMs", POLL_MS)
        model.addAttribute("pollSec", POLL_MS / 1000)
        model.addAttribute("initialPage", true)
        populate(model, f, view, error, now, loadTenantOptions())
        return PAGE_VIEW
    }

    /**
     * 목록 교체(필터 변경·다시 시도). 목록 영역(`#activity-main`)과 OOB 로 KPI·적용된 필터·활발한 tenant·숨은 상태를 돌려준다.
     * `reset` 은 서버가 값을 되돌려 써야 하는 컨트롤(category,tenant,q — 칩 제거·모두 지우기·활발한 tenant 클릭)이다. 사용자가 방금 친 입력과
     * 선택은 되돌려 쓰지 않는다(포커스·타이핑 보존). 조회 실패는 422 + 같은 영역의 오류 카드(원본의 LoadError, 토스트 없음).
     */
    @GetMapping(ROWS_PATH)
    fun rows(
        @RequestParam(name = "category", defaultValue = "") category: String,
        @RequestParam(name = "tenantId", defaultValue = "") tenantId: String,
        @RequestParam(name = "reset", defaultValue = "") reset: String,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        val f = filter(category, tenantId)
        val now = clock.instant()
        var view: ActivityView? = null
        var error: RuntimeException? = null
        try {
            view = activity.activity(null, f.categoryParam(), null, null, f.tenantId).data()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[activity] rows failed: {}", e.toString())
            error = e
            res.status = 422
        }
        // 필터는 URL 이 단일 출처 — 새로고침·링크 공유에도 유지(원본 setSearchParams replace). 검색어는 주소에 두지 않는다.
        res.setHeader("HX-Replace-Url", canonicalUrl(f))
        // Kotlin split 은 끝의 빈 토큰을 남기지만(Java split 은 제거) 비어 있지 않은 이름의 contains 판정은 같다.
        val resets = reset.split(",")
        model.addAttribute("resetCategory", resets.contains("category"))
        model.addAttribute("resetTenant", resets.contains("tenant"))
        populate(model, f, view, error, now, loadTenantOptions())
        return ROWS_VIEW
    }

    /**
     * 폴링·새로고침. 최신 한 쪽을 받아 `top`(화면의 가장 새 행 id) 앞의 행만 앞에 붙이고 KPI·활발한 tenant 를 맞춘다.
     * 응답 본문은 OOB 조각뿐이다(`hx-swap="none"`). 조회 실패는 503 + 빈 본문 + 토스트 없음 — 화면은 "연결 끊김"으로만 알린다(원본 disconnected).
     */
    @GetMapping(POLL_PATH)
    fun poll(
        @RequestParam(name = "category", defaultValue = "") category: String,
        @RequestParam(name = "tenantId", defaultValue = "") tenantId: String,
        @RequestParam(name = "top", defaultValue = "") top: String,
        @RequestParam(name = "day", defaultValue = "") day: String,
        @RequestParam(name = "topSig", defaultValue = "") topSig: String,
        @RequestParam(name = "loaded", defaultValue = "1") loaded: String,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        val f = filter(category, tenantId)
        val now = clock.instant()
        val view: ActivityView? // data() 는 플랫폼 타입 — non-null 선언이면 null 검사가 try 안에서 일어나 503 으로 바뀐다
        try {
            view = activity.activity(null, f.categoryParam(), null, null, f.tenantId).data()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[activity] poll failed: {}", e.javaClass.simpleName)
            res.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return EMPTY_VIEW
        }
        if ("0" == loaded) {
            // 첫 조회(또는 필터를 바꾼 조회)가 실패한 상태에서 폴링이 성공했다 — 원본은 이때 목록을 통째로 보인다(replace). 목록 영역을 교체한다.
            res.setHeader("HX-Retarget", "#activity-main")
            res.setHeader("HX-Reswap", "innerHTML")
            model.addAttribute("resetCategory", false)
            model.addAttribute("resetTenant", false)
            populate(model, f, view, null, now, loadTenantOptions())
            return ROWS_VIEW
        }
        val feed = feedOf(view)
        val k = if (top.javaIsBlank()) -1 else indexOfId(feed, top)
        val fresh = if (k < 0) feed else feed.subList(0, k)
        val wasEmpty = top.javaIsBlank()
        // 새 행은 KST 날짜 그룹으로 묶는다. 화면 맨 위 그룹과 같은 날짜면 그 그룹의 행 목록 앞에, 아니면 새 그룹을 목록 맨 위에 붙인다.
        val days = days(fresh, now)
        splitDays(model, days, day, "fresh")
        model.addAttribute("hasFresh", !fresh.isEmpty())
        model.addAttribute("clearEmpty", wasEmpty && !fresh.isEmpty())
        // !! : 원 Java feed.get(0).getId().toString() NPE 자리.
        model.addAttribute("topId", if (fresh.isEmpty()) null else feed[0].id!!.toString())
        model.addAttribute("topDay", if (fresh.isEmpty()) null else dayKey(feed[0]))
        // 비어 있던 목록이 처음 채워지면 더 보기·CSV 가 쓸 아래쪽 커서도 정한다.
        model.addAttribute("bottomState", wasEmpty && !feed.isEmpty())
        fillBottomState(model, feed)
        fillKpis(model, view, true)
        val topRows = topRows(view!!, f) // !! : 원 Java topRows 안 view.getTop5() NPE 자리(그 전 응답 헤더 쓰기 없음)
        val sig = topSig(topRows, f)
        model.addAttribute("topChanged", sig != topSig)
        model.addAttribute("top", topRows)
        model.addAttribute("topSig", sig)
        model.addAttribute("topOob", true)
        return POLL_VIEW
    }

    /** 더 보기: 가장 오래된 행(커서 `before`/`beforeId`, 튜플 커서 F07) 이전 한 쪽을 아래에 붙인다. */
    @GetMapping(MORE_PATH)
    fun more(
        @RequestParam(name = "category", defaultValue = "") category: String,
        @RequestParam(name = "tenantId", defaultValue = "") tenantId: String,
        @RequestParam(name = "before", defaultValue = "") before: String,
        @RequestParam(name = "beforeId", defaultValue = "") beforeId: String,
        @RequestParam(name = "lastDay", defaultValue = "") lastDay: String,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requirePlatform(model)
        val f = filter(category, tenantId)
        val cursor = parseCursor(before) ?: return EMPTY_VIEW // 읽어 온 행이 없다 — 원본도 아무 것도 하지 않는다
        val now = clock.instant()
        val view: ActivityView? // 위 poll 과 같은 이유로 nullable(feedOf 가 null 을 받는다)
        try {
            view = activity.activity(null, f.categoryParam(), cursor, parseUuid(beforeId), f.tenantId).data()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[activity] more failed: {}", e.toString())
            val info = ErrorMessages.describe(e)
            Toasts.errorTitled(
                res, "이전 기록을 불러오지 못했습니다", info.toastMessage(),
                if (info.httpStatus == null) null else "HTTP " + info.httpStatus, MDC.get("traceId"),
            )
            res.status = info.httpStatus ?: HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            return EMPTY_VIEW
        }
        val feed = feedOf(view)
        splitDays(model, days(feed, now), lastDay, "more")
        model.addAttribute("hasRaw", !feed.isEmpty())
        fillBottomState(model, feed)
        return MORE_VIEW
    }

    /** 상세(행의 "상세 열기"). 조회 실패는 422 + 패널 안 오류(다시 시도), 권한 거부는 403. */
    @GetMapping(DETAIL_BASE + "{id}")
    fun detail(@PathVariable id: String, res: HttpServletResponse, model: Model): String {
        requirePlatform(model)
        model.addAttribute("detailUrl", DETAIL_BASE + enc(id))
        val uuid = parseUuid(id)
        var d: ActivityDetailView? = null
        var error: RuntimeException? = null
        if (uuid == null) {
            error = IllegalArgumentException("invalid id")
        } else {
            try {
                d = activity.detail(uuid).data()
            } catch (e: RuntimeException) {
                if (denied(e)) throw e
                log.warn("[activity] detail failed: {}", e.toString())
                error = e
            }
        }
        if (d == null) {
            res.status = 422
            model.addAttribute("loadError", error ?: IllegalStateException("empty"))
            model.addAttribute("d", null)
            val retry = LinkedHashMap<String, Any?>()
            retry["hx-get"] = DETAIL_BASE + enc(id)
            retry["hx-target"] = "#activity-detail"
            retry["hx-swap"] = "innerHTML"
            retry["hx-sync"] = "#activity-detail:replace"
            retry["data-quiet"] = true
            model.addAttribute("detailRetryAttrs", retry)
            return DETAIL_VIEW
        }
        model.addAttribute("loadError", null)
        model.addAttribute("d", detailModel(d))
        return DETAIL_VIEW
    }

    /**
     * CSV(원본 downloadCsv: activity-{KST 오늘}.csv, 열 timestamp·tenant·type·category·actor·subject). 원본은 화면에 읽어 온 행 전부(액션 검색은
     * 거르지 않음)를 내보낸다 — 서버는 최신 쪽부터 경계 행(`beforeId` = 화면의 가장 오래된 행)이 나올 때까지 이어 읽는다(커서가 없으면 최신 한 쪽).
     * 화면의 더 보기는 쪽 수 상한이 없으므로 내보내기도 경계까지 읽되 [ExportLimit.MAX_PAGES] 쪽 안에 닿지 못하면 422(이유 문구).
     * 그 사이 위에 새 행이 생겨도 경계는 id 로 찾으므로 영향이 없다(새 행은 폴링이 화면 맨 위에도 붙인다).
     * 경계 행이 사라졌거나(끝까지 읽음) 읽기에 실패하면 일부만 내보내지 않고 오류.
     */
    @GetMapping(EXPORT_PATH)
    fun export(
        @RequestParam(name = "category", defaultValue = "") category: String,
        @RequestParam(name = "tenantId", defaultValue = "") tenantId: String,
        @RequestParam(name = "beforeId", defaultValue = "") beforeId: String,
        model: Model,
    ): ResponseEntity<ByteArray> {
        requirePlatform(model)
        val f = filter(category, tenantId)
        val until = parseUuid(beforeId)
        val all = ArrayList<ActivityView.Event>()
        var cursor: OffsetDateTime? = null
        var cursorId: UUID? = null
        var p = 0
        while (true) {
            if (p >= ExportLimit.MAX_PAGES) return ExportLimit.tooLarge() // 경계에 닿기 전에 상한 — 조용히 일부만 내보내지 않는다
            val page = feedOf(activity.activity(null, f.categoryParam(), cursor, cursorId, f.tenantId).data())
            val k = if (until == null) -1 else indexOfId(page, until.toString())
            if (k >= 0) {
                all.addAll(page.subList(0, k + 1))
                break
            }
            all.addAll(page)
            if (until == null) break // 커서가 없으면 최신 한 쪽
            // 경계 행을 찾지 못한 채 끝에 닿았다(행이 사라짐) — 실패
            if (page.size < FEED_PAGE) throw BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)
            val last = page[page.size - 1]
            cursor = last.createdAt
            cursorId = last.id
            p++
        }
        val body = ArrayList<List<String>>()
        for (e in all) {
            val tenant = if (e.tenantSlug != null) e.tenantSlug else if (e.tenantId != null) e.tenantId.toString() else ""
            body.add(java.util.List.of(rawTs(e), tenant, nz(e.action), nz(e.category), nz(e.actorEmail), e.targetId ?: "—"))
        }
        val csv = CsvWriter.write(java.util.List.of("timestamp", "tenant", "type", "category", "actor", "subject"), body)
        val filename = "activity-" + KstFormat.kstToday(clock.instant()) + ".csv"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(csv)
    }

    // ── 모델 채우기 ─────────────────────────────────────────

    private fun populate(
        model: Model,
        f: Filter,
        view: ActivityView?,
        error: RuntimeException?,
        now: Instant,
        tenantList: List<TenantAdminDto.TenantView>,
    ) {
        val loaded = view != null
        val feed: List<ActivityView.Event> = if (view != null) feedOf(view) else java.util.List.of()
        val days = days(feed, now)
        model.addAttribute("loaded", loaded)
        model.addAttribute("loadError", error)
        model.addAttribute("days", days)
        model.addAttribute("category", f.category)
        model.addAttribute("tenantIdValue", if (f.tenantId == null) "" else f.tenantId.toString())
        fillKpis(model, view, false)
        val topRows: List<TopRow> = if (view != null) topRows(view, f) else java.util.List.of()
        model.addAttribute("top", topRows)
        model.addAttribute("topSig", topSig(topRows, f))
        model.addAttribute("topOob", true)
        model.addAttribute("topChanged", true)

        // 숨은 상태: 가장 새 행·아래쪽 커서·날짜 머리글(이후 폴링·더 보기가 이어 받는다)
        // !! : 원 Java feed.get(0).getId().toString() NPE 자리.
        model.addAttribute("topId", if (feed.isEmpty()) "" else feed[0].id!!.toString())
        model.addAttribute("topDay", if (feed.isEmpty()) "" else dayKey(feed[0]))
        model.addAttribute("bottomState", true)
        fillBottomState(model, feed)

        // 컨트롤: 카테고리 세그먼트, tenant 선택(이름 (slug)), 적용된 필터 칩
        val segs = ArrayList<Map<String, Any?>>()
        for (c in arrayOf(arrayOf(CAT_ALL, "전체"), arrayOf("ops", "운영"), arrayOf("security", "보안"))) {
            val o = LinkedHashMap<String, Any?>()
            o["value"] = c[0]
            o["label"] = c[1]
            o["title"] = null
            o["attrs"] = rowsReq(ROWS_PATH + "?category=" + c[0], "#activity-tenant")
            segs.add(o)
        }
        model.addAttribute("segOptions", segs)
        val tenantOpts = ArrayList<Option>()
        for (t in tenantList) {
            val name = t.displayName ?: ""
            // !! : 원 Java t.getId().toString() NPE 자리.
            val tid = t.id!!
            tenantOpts.add(Option(tid.toString(), name + " (" + t.slug + ")", tid == f.tenantId))
        }
        model.addAttribute("tenantOptions", tenantOpts)
        val tenantAttrs = rowsReq(ROWS_PATH, "#activity-cat")
        tenantAttrs["hx-trigger"] = "change"
        model.addAttribute("tenantAttrs", tenantAttrs)
        model.addAttribute("hasApplied", f.hasServerApplied())
        model.addAttribute(
            "categoryLabel",
            when (f.category) {
                "ops" -> "운영"
                "security" -> "보안"
                else -> "전체"
            },
        )
        var slug: String? = null
        if (f.tenantId != null) {
            // 원 Java t.getId().equals(...) 의 NPE 는 위 tenantOpts 루프에서 이미 났다 — 여기선 null id 가 올 수 없다.
            for (t in tenantList) if (t.id == f.tenantId) slug = t.slug
            // !! : 원 Java for (t : view.getTop5()) 는 top5 가 null 이면 NPE.
            if (slug == null && view != null) for (t in view.top5!!) if (f.tenantId == t.tenantId) slug = t.slug
            if (slug == null) slug = f.tenantId.toString()
        }
        model.addAttribute("tenantSlug", slug)
        model.addAttribute("chipCategoryAttrs", rowsReq("$ROWS_PATH?category=all&reset=category", "#activity-tenant"))
        model.addAttribute("chipTenantAttrs", rowsReq("$ROWS_PATH?tenantId=&reset=tenant", "#activity-cat"))
        // 액션 검색은 원본처럼 이미 읽어 온 행을 클라이언트에서 거른다 — 검색어 칩 제거·모두 지우기의 검색어 부분은 Alpine(clearQ)이 한다.
        // 모두 지우기는 서버 필터(카테고리·tenant)가 있을 때만 목록을 다시 읽는다(원본 clearFilters 는 바뀌는 서버 필터가 없으면 다시 조회하지 않는다).
        model.addAttribute(
            "clearAttrs",
            if (f.hasServerApplied()) rowsReq("$ROWS_PATH?category=all&tenantId=&reset=category,tenant", null) else null,
        )
        model.addAttribute("retryAttrs", rowsReq(ROWS_PATH, "#activity-cat, #activity-tenant"))
        model.addAttribute("pollAttrs", pollReq())
        model.addAttribute("refreshAttrs", refreshReq())
        model.addAttribute("moreAttrs", moreReq())
        model.addAttribute("exportHref", EXPORT_PATH + "?category=" + f.category + (if (f.tenantId == null) "" else "&tenantId=" + f.tenantId))
        model.addAttribute("emptyTitle", "조건에 맞는 이벤트가 없습니다")
    }

    private fun fillBottomState(model: Model, rawFeed: List<ActivityView.Event>) {
        val last = if (rawFeed.isEmpty()) null else rawFeed[rawFeed.size - 1]
        model.addAttribute("beforeIso", if (last == null) "" else iso(last))
        // !! : 원 Java last.getId().toString() NPE 자리.
        model.addAttribute("beforeId", if (last == null) "" else last.id!!.toString())
        model.addAttribute("lastDay", if (last == null) "" else dayKey(last))
    }

    private fun fillKpis(model: Model, view: ActivityView?, oob: Boolean) {
        if (view != null) {
            // !! : 원 Java k.getEvents24h() 는 kpi 가 null 이면 NPE(첫 역참조 자리, 그 전 모델 쓰기 없음).
            val k = view.kpi!!
            model.addAttribute("kpiEvents", fmt(k.events24h))
            model.addAttribute("kpiOps", fmt(k.ops24h))
            model.addAttribute("kpiSecurity", fmt(k.security24h))
            model.addAttribute("kpiSecurityBad", k.security24h > 0)
        } else {
            model.addAttribute("kpiEvents", "—")
            model.addAttribute("kpiOps", "—")
            model.addAttribute("kpiSecurity", "—")
            model.addAttribute("kpiSecurityBad", false)
        }
        // §6: 서버가 p95Ms 를 주지 않으면(현재 항상 null) KPI 를 그리지 않는다.
        val p95: Long? = view?.kpi?.p95Ms
        model.addAttribute("kpiP95", if (p95 == null) null else "${p95}ms")
        model.addAttribute("kpiP95Hint", if (p95 == null) null else "p95 ${p95}ms")
        model.addAttribute("kpiOob", oob)
    }

    // ── 피드 → 화면 값 ───────────────────────────────────────

    /** KST 날짜(yyyy-MM-dd)별로 묶는다(원본 groupByDay). 입력은 최신순. */
    private fun days(feed: List<ActivityView.Event>, now: Instant): List<Day> {
        val evs = ArrayList<Ev>()
        for (e in feed) evs.add(ev(e))
        val out = ArrayList<Day>()
        for (g in KstFormat.groupByDay(evs, Function<Ev, String?> { it.iso }, now)) {
            out.add(Day(g.label, KstFormat.kstDate(g.items[0].iso), g.items))
        }
        return out
    }

    private fun ev(e: ActivityView.Event): Ev {
        val type = nz(e.action)
        val tenantName = if (e.tenantSlug != null) e.tenantSlug else if (e.tenantId != null) e.tenantId.toString() else "—" // adaptFeedItems
        val actor = e.actorEmail
        val subject = e.targetId ?: "—"
        val sentence = ActivityLabels.eventSentence(ActivityLabels.EventSentenceInput(type, actor ?: "", e.tenantSlug, null, subject))
        val attrs = LinkedHashMap<String, Any?>()
        attrs["hx-get"] = DETAIL_BASE + e.id
        attrs["hx-target"] = "#activity-detail"
        attrs["hx-swap"] = "innerHTML"
        // 마지막으로 연 상세만 반영(A→B 순서로 열고 A 가 늦게 와도 무시). 닫기·다시 열기가 같은 요소로 중단한다.
        attrs["hx-sync"] = "#activity-detail:replace"
        attrs["data-quiet"] = true // 네트워크 실패는 패널 안에서 알린다(전역 토스트 없음)
        return Ev(
            // !! : 원 Java e.getId().toString() NPE 자리.
            e.id!!.toString(), type, type.uppercase(Locale.ROOT), ActivityLabels.actionLabel(type), TONE_VAR[eventTone(type, nz(e.category))],
            KstFormat.kstTime(e.createdAt), iso(e), if (e.tenantId == null) null else e.tenantId.toString(), tenantName,
            if (e.tenantId == null) null else "/admin/tenants/" + e.tenantId, sentence, attrs,
        )
    }

    private fun topRows(view: ActivityView, f: Filter): List<TopRow> {
        val top: List<ActivityView.TopTenant> = view.top5 ?: java.util.List.of()
        var max = 1L
        for (t in top) max = Math.max(max, t.count)
        val rows = ArrayList<TopRow>()
        for (t in top) {
            val slug = t.slug ?: ""
            val attrs = rowsReq(ROWS_PATH + "?tenantId=" + t.tenantId + "&reset=tenant", "#activity-cat")
            rows.add(
                TopRow(
                    // !! : 원 Java t.getTenantId().toString() NPE 자리.
                    t.tenantId!!.toString(), slug, slug, if (slug.isEmpty()) "" else slug.substring(0, 1).uppercase(Locale.ROOT),
                    fmt(t.count), Math.round(t.count * 100.0 / max).toInt(), t.tenantId == f.tenantId, attrs,
                ),
            )
        }
        return rows
    }

    // ── 상세 ────────────────────────────────────────────────

    data class DiffLine(
        val field: String?,
        val array: Boolean,
        val removed: List<String>,
        val added: List<String>,
        val from: String,
        val to: String,
        val sr: String,
    )

    data class DetailVm(
        val action: String,
        val actionLabel: String?,
        val kst: String?,
        val utc: String,
        val actor: String,
        val avatar: String,
        val tenantId: String?,
        val tenantLabel: String?,
        val tenantUrl: String?,
        val targetType: String,
        val targetTail: String?,
        val targetFull: String?,
        val diff: List<DiffLine>,
        val json: String,
    )

    private fun detailModel(d: ActivityDetailView): DetailVm {
        val iso = if (d.createdAt == null) "" else d.createdAt.toInstant().toString()
        val actor = if (d.actorEmail == null || d.actorEmail.isEmpty()) "system" else d.actorEmail
        val lines = ArrayList<DiffLine>()
        for (r in ActivityDiff.buildDiffRows(null, d.payload)) {
            val ac = ActivityDiff.arrayChange(r.from, r.to)
            val from = ActivityDiff.fmtVal(r.from)
            val to = ActivityDiff.fmtVal(r.to)
            lines.add(
                DiffLine(
                    r.field, ac != null, if (ac == null) java.util.List.of() else ac.removed, if (ac == null) java.util.List.of() else ac.added,
                    from, to, r.field + ": " + from + " 에서 " + to + " 로 변경",
                ),
            )
        }
        val target = d.targetId
        return DetailVm(
            nz(d.action), ActivityLabels.actionLabel(nz(d.action)), KstFormat.kstDateTime(iso), KstFormat.utcIso(iso) + " UTC",
            actor, actor.substring(0, 1).uppercase(Locale.ROOT), if (d.tenantId == null) null else d.tenantId.toString(),
            if (d.tenantSlug != null) d.tenantSlug else if (d.tenantId == null) null else d.tenantId.toString(),
            if (d.tenantId == null) null else "/admin/tenants/" + d.tenantId,
            d.targetType ?: "—",
            if (target == null || target.isEmpty()) null else "…" + (if (target.length <= 8) target else target.substring(target.length - 8)),
            target, lines, prettyPayload(d.payload),
        )
    }

    /** parsePayload(raw) ? JSON.stringify(payload, null, 2) : raw. JS 의 falsy(null·0·false·"")는 원문 그대로. */
    private fun prettyPayload(raw: String?): String {
        if (raw == null) return ""
        try {
            val n: JsonNode? = json.readTree(raw)
            if (n == null || n.isMissingNode || n.isNull) return raw
            if ((n.isNumber && n.decimalValue().signum() == 0) || (n.isBoolean && !n.asBoolean()) || (n.isString && n.asString().isEmpty())) return raw
            return AuditHelpers.pretty(n)
        } catch (e: Exception) {
            return raw
        }
    }

    /** 원본 CSV 의 timestamp: 서버가 직렬화한 createdAt 문자열 그대로(앱 ObjectMapper). */
    private fun rawTs(e: ActivityView.Event): String {
        val createdAt = e.createdAt ?: return ""
        try {
            val v: String? = json.convertValue(createdAt, String::class.java)
            return v ?: createdAt.toString()
        } catch (ex: RuntimeException) {
            return createdAt.toString()
        }
    }

    private fun loadTenantOptions(): List<TenantAdminDto.TenantView> {
        val list: List<TenantAdminDto.TenantView>? = try {
            tenants.list().data()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[activity] tenant list for filter failed: {}", e.toString()) // 원본: catch → []
            return java.util.List.of()
        }
        // !! : data() 는 플랫폼 타입 — 원 Java 는 null 을 그대로 돌려 populate 의 for 루프(같은 try 밖)에서 NPE. 그 사이 응답 쓰기는 없다.
        return list!!
    }

    companion object {
        internal const val PAGE_PATH = "/admin/activity"
        internal const val ROWS_PATH = "/admin/_/activity/rows"
        internal const val POLL_PATH = "/admin/_/activity/poll"
        internal const val MORE_PATH = "/admin/_/activity/more"
        internal const val EXPORT_PATH = "/admin/_/activity/export"
        internal const val DETAIL_BASE = "/admin/_/activity/"

        internal const val PAGE_VIEW = "admin/activity/page"
        internal const val ROWS_VIEW = "admin/activity/rows :: rows"
        internal const val POLL_VIEW = "admin/activity/rows :: poll"
        internal const val MORE_VIEW = "admin/activity/rows :: more"
        internal const val DETAIL_VIEW = "admin/activity/detail :: panel"
        internal const val EMPTY_VIEW = "admin/fragments/shell/empty :: none"

        /** ActivityPage.tsx 의 POLL_MS. 템플릿의 `hx-trigger="every Ns"` 가 이 값에서 나온다. */
        internal const val POLL_MS = 5000

        /** 서버 피드 한 쪽 크기(ActivityService.FEED_PAGE). CSV 가 불러온 행까지 이어 읽을 때 쓴다. */
        internal const val FEED_PAGE = 50

        private val FAILURE_TYPES: Set<String> = java.util.Set.of("SIGNATURE_COUNTER_REGRESSION", "ATTESTATION_TRUST_FAILED", "ADMIN_LOGIN_FAILED")
        private val FAILURE_NAME: Pattern = Pattern.compile("_(FAILED|FAIL|REGRESSION)$")
        private val TONE_VAR: Map<String, String> = java.util.Map.of(
            "danger", "var(--bad)", "warning", "var(--warn-dot)", "info", "var(--brand-700)", "teal", "var(--faint)",
        )
        private const val CAT_ALL = "all"

        private fun feedOf(v: ActivityView?): List<ActivityView.Event> = if (v == null || v.feed == null) java.util.List.of() else v.feed

        private fun indexOfId(feed: List<ActivityView.Event>, id: String): Int {
            // !! : 원 Java feed.get(i).getId().toString() NPE 자리.
            for (i in feed.indices) if (feed[i].id!!.toString() == id) return i
            return -1
        }

        private fun dayKey(e: ActivityView.Event): String? = KstFormat.kstDate(iso(e))

        /**
         * 이어 붙일 그룹을 둘로 나눈다: `existing` 키(화면의 맨 위/맨 아래 그룹의 KST 날짜)와 같은 날짜 그룹은 그 그룹의 행 목록에
         * 행만 붙이고(`<prefix>Same`), 나머지는 새 그룹으로(`<prefix>New`).
         */
        private fun splitDays(model: Model, days: List<Day>, existing: String, prefix: String) {
            var same: Day? = null
            val fresh = ArrayList<Day>()
            for (d in days) {
                if (existing.isNotEmpty() && d.key == existing) same = d else fresh.add(d)
            }
            model.addAttribute(prefix + "Same", same)
            model.addAttribute(prefix + "New", fresh)
        }

        /** eventTone(type, category). */
        internal fun eventTone(type: String, category: String): String {
            if (FAILURE_TYPES.contains(type) || FAILURE_NAME.matcher(type).find() || "security" == category) return "danger"
            if ("ADMIN_LOGIN" == type) return "info"
            if ("ops" == category) return "warning"
            return "teal"
        }

        /** 활발한 tenant 목록의 서명. 같으면 폴링이 이 영역을 다시 그리지 않는다(포커스·클릭 중인 버튼 보존). */
        private fun topSig(rows: List<TopRow>, f: Filter): String {
            val b = StringBuilder(if (f.tenantId == null) "-" else f.tenantId.toString())
            for (r in rows) b.append('|').append(r.tenantId).append(':').append(r.count).append(if (r.selected) "*" else "")
            return Integer.toHexString(b.toString().hashCode())
        }

        // ── htmx 속성(요소마다 새 Map — ui:attrs 가 다른 속성을 덮지 않는다) ─────

        /** 목록 영역을 바꾸는 요청. 최신 요청이 이긴다(진행 중이던 폴링도 중단). */
        private fun rowsReq(url: String, include: String?): MutableMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = url
            m["hx-target"] = "#activity-main"
            m["hx-swap"] = "innerHTML"
            m["hx-indicator"] = "#activity-main"
            m["hx-sync"] = "#activity-root:replace"
            if (include != null) m["hx-include"] = include
            return m
        }

        /** 폴링: 같은 시각에 진행 중인 요청이 있으면 이번 것을 버린다(쌓이지 않음 · 필터 교체를 중단시키지 않음). 주변 UI 라 실패를 토스트하지 않는다. */
        private fun pollReq(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = POLL_PATH
            m["hx-trigger"] = "every " + (POLL_MS / 1000) + "s"
            m["hx-swap"] = "none"
            m["hx-sync"] = "#activity-root:drop"
            m["hx-include"] = "#activity-cat, #activity-tenant, #activity-loaded, #activity-newest, #activity-newest-day, #activity-tenants-sig"
            m["data-quiet"] = true
            return m
        }

        /** 새로고침·지금 다시 연결: 진행 중인 요청 뒤에 이어서 한 번(폴링에 추월당하지 않고, 진행 중인 필터 교체를 끊지도 않는다). */
        private fun refreshReq(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = POLL_PATH
            m["hx-trigger"] = "click"
            m["hx-swap"] = "none"
            m["hx-sync"] = "#activity-root:queue last"
            m["hx-include"] = "#activity-cat, #activity-tenant, #activity-loaded, #activity-newest, #activity-newest-day, #activity-tenants-sig"
            m["hx-disabled-elt"] = "this"
            m["data-quiet"] = true
            return m
        }

        private fun moreReq(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-get"] = MORE_PATH
            m["hx-trigger"] = "click"
            m["hx-swap"] = "none"
            m["hx-sync"] = "this:drop"
            m["hx-include"] = "#activity-cat, #activity-tenant, #activity-before, #activity-before-id, #activity-last-day"
            return m
        }

        // ── 파싱·형식 ───────────────────────────────────────────

        internal fun filter(category: String, tenantId: String): Filter {
            val cat = if ("ops" == category || "security" == category) category else CAT_ALL
            return Filter(cat, parseUuid(tenantId))
        }

        internal fun canonicalUrl(f: Filter): String {
            val p = ArrayList<String>()
            if (CAT_ALL != f.category) p.add("category=" + f.category)
            if (f.tenantId != null) p.add("tenantId=" + f.tenantId)
            return if (p.isEmpty()) PAGE_PATH else PAGE_PATH + "?" + p.joinToString("&")
        }

        private fun parseUuid(s: String?): UUID? {
            if (s == null || s.javaIsBlank()) return null
            return try {
                UUID.fromString(s.javaTrim())
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun parseCursor(s: String?): OffsetDateTime? {
            if (s == null || s.javaIsBlank()) return null
            return try {
                OffsetDateTime.parse(s.javaTrim())
            } catch (e: RuntimeException) {
                null
            }
        }

        private fun iso(e: ActivityView.Event): String = if (e.createdAt == null) "" else e.createdAt.toInstant().toString()

        private fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)

        private fun nz(s: String?): String = s ?: ""

        private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

        private fun denied(e: RuntimeException): Boolean =
            e is AccessDeniedException || (e is BusinessException && e.errorCode == ErrorCode.ACCESS_DENIED)

        private fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")

        private fun requirePlatform(model: Model) {
            if (!me(model).platform) throw AccessDeniedException("PLATFORM_OPERATOR only")
        }
    }
}
