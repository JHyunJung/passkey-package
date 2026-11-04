package com.crosscert.passkey.admin.web.activity;

import com.crosscert.passkey.admin.activity.ActivityController;
import com.crosscert.passkey.admin.activity.ActivityDetailView;
import com.crosscert.passkey.admin.activity.ActivityView;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.HomeRedirectController;
import com.crosscert.passkey.admin.web.format.ActivityDiff;
import com.crosscert.passkey.admin.web.format.ActivityLabels;
import com.crosscert.passkey.admin.web.format.CsvWriter;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.shell.Crumbs;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.ExportLimit;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.admin.web.tenant.AuditHelpers;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 활동 화면(← pages/ActivityPage.tsx, api/activity.ts). PLATFORM 전용 — RP_ADMIN 은 {@link HomeRedirectController} 와 같은 규칙으로
 * 자기 홈으로 보내고(전체 페이지), 조각·CSV 는 403 이다. REST 의 {@code @PreAuthorize} 도 함께 막는다.
 *
 * <p>구조: 전체 페이지 {@code GET /admin/activity}, 목록 교체 {@code /admin/_/activity/rows}(필터 변경·다시 시도),
 * 폴링·새로고침 {@code /admin/_/activity/poll}, 더 보기 {@code /admin/_/activity/more}, 상세 {@code /admin/_/activity/{id}},
 * CSV {@code /admin/_/activity/export}. 서버는 상태가 없다 — 화면에 보이는 목록(DOM)이 상태이고, 요청마다 현재 필터와
 * 숨은 입력(가장 새 행 id, 가장 오래된 행의 커서, 날짜 머리글 라벨)이 실린다.
 *
 * <ul>
 *   <li>폴링은 원본처럼 최신 한 쪽만 받아 "아직 없는 행만" 앞에 붙인다(OOB afterbegin). 더 보기로 불러온 행·펼친 상세·스크롤은 그대로다.</li>
 *   <li>필터(카테고리·tenant)가 바뀌면 목록을 통째로 교체한다. 액션 검색(q)은 원본처럼 서버가 아니라 클라이언트(Alpine)가 읽어 온 행을 거른다.
 *       날짜 그룹은 KST 날짜 키(id=day-KEY)로 이어 붙는다.</li>
 *   <li>사용자 입력(행위자·tenant 이름·payload 값)은 템플릿에서 th:text 로만 그린다. 상세의 diff 도 문자열이다.</li>
 * </ul>
 */
@Slf4j
@Controller
public class ActivityPageController {

    static final String PAGE_PATH = "/admin/activity";
    static final String ROWS_PATH = "/admin/_/activity/rows";
    static final String POLL_PATH = "/admin/_/activity/poll";
    static final String MORE_PATH = "/admin/_/activity/more";
    static final String EXPORT_PATH = "/admin/_/activity/export";
    static final String DETAIL_BASE = "/admin/_/activity/";

    static final String PAGE_VIEW = "admin/activity/page";
    static final String ROWS_VIEW = "admin/activity/rows :: rows";
    static final String POLL_VIEW = "admin/activity/rows :: poll";
    static final String MORE_VIEW = "admin/activity/rows :: more";
    static final String DETAIL_VIEW = "admin/activity/detail :: panel";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    /** ActivityPage.tsx 의 POLL_MS. 템플릿의 {@code hx-trigger="every Ns"} 가 이 값에서 나온다. */
    static final int POLL_MS = 5000;
    /** 서버 피드 한 쪽 크기(ActivityService.FEED_PAGE). CSV 가 불러온 행까지 이어 읽을 때 쓴다. */
    static final int FEED_PAGE = 50;

    private static final Set<String> FAILURE_TYPES = Set.of("SIGNATURE_COUNTER_REGRESSION", "ATTESTATION_TRUST_FAILED", "ADMIN_LOGIN_FAILED");
    private static final java.util.regex.Pattern FAILURE_NAME = java.util.regex.Pattern.compile("_(FAILED|FAIL|REGRESSION)$");
    private static final Map<String, String> TONE_VAR = Map.of(
            "danger", "var(--bad)", "warning", "var(--warn-dot)", "info", "var(--brand-700)", "teal", "var(--faint)");
    private static final String CAT_ALL = "all";

    private final ActivityController activity;
    private final TenantAdminController tenants;
    private final Clock clock;
    private final ObjectMapper json;

    public ActivityPageController(ActivityController activity, TenantAdminController tenants, Clock clock, ObjectMapper json) {
        this.activity = activity;
        this.tenants = tenants;
        this.clock = clock;
        this.json = json;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** 조회 조건. category: all|ops|security, tenantId: 없으면 null, q: 입력 그대로(필터 때만 trim). */
    record Filter(String category, UUID tenantId) {
        String categoryParam() { return CAT_ALL.equals(category) ? null : category; }
        boolean hasServerApplied() { return !CAT_ALL.equals(category) || tenantId != null; }
    }

    /** 피드 한 줄(원본 adaptFeedItems 의 DisplayEvent + 화면 값). */
    public record Ev(String id, String type, String typeUpper, String typeTitle, String toneVar, String hm, String iso, String tenantId, String tenantName,
                     String tenantUrl, String sentence, Map<String, Object> detailAttrs) {}

    /** 원본 groupByDay 의 그룹. key = KST 날짜(yyyy-MM-dd) — 폴링·더 보기가 이 키로 기존 그룹에 이어 붙는다. */
    public record Day(String label, String key, List<Ev> events) {}

    public record TopRow(String tenantId, String name, String slug, String initial, String count, int pct, boolean selected, Map<String, Object> attrs) {}

    public record Option(String id, String label, boolean selected) {}

    /** 원본 {@code Loaded}: 서버가 준 한 쪽. */
    private record Page(ActivityView view, List<ActivityView.Event> feed) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(PAGE_PATH)
    public String page(@RequestParam(name = "category", defaultValue = "") String category,
                       @RequestParam(name = "tenantId", defaultValue = "") String tenantId,
                       Model model) {
        CurrentAdmin me = me(model);
        model.addAttribute("breadcrumbs", Crumbs.activity());
        if (!me.platform()) return "redirect:" + HomeRedirectController.homePath(me);
        Filter f = filter(category, tenantId);
        Instant now = clock.instant();
        ActivityView view = null;
        RuntimeException error = null;
        try {
            view = activity.activity(null, f.categoryParam(), null, null, f.tenantId()).data();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[activity] first load failed: {}", e.toString());
            error = e;
        }
        model.addAttribute("pollMs", POLL_MS);
        model.addAttribute("pollSec", POLL_MS / 1000);
        model.addAttribute("initialPage", true);
        populate(model, f, view, error, now, loadTenantOptions());
        return PAGE_VIEW;
    }

    /**
     * 목록 교체(필터 변경·다시 시도). 목록 영역({@code #activity-main})과 OOB 로 KPI·적용된 필터·활발한 tenant·숨은 상태를 돌려준다.
     * {@code reset} 은 서버가 값을 되돌려 써야 하는 컨트롤(category,tenant,q — 칩 제거·모두 지우기·활발한 tenant 클릭)이다. 사용자가 방금 친 입력과
     * 선택은 되돌려 쓰지 않는다(포커스·타이핑 보존). 조회 실패는 422 + 같은 영역의 오류 카드(원본의 LoadError, 토스트 없음).
     */
    @GetMapping(ROWS_PATH)
    public String rows(@RequestParam(name = "category", defaultValue = "") String category,
                       @RequestParam(name = "tenantId", defaultValue = "") String tenantId,
                       @RequestParam(name = "reset", defaultValue = "") String reset,
                       HttpServletResponse res, Model model) {
        requirePlatform(model);
        Filter f = filter(category, tenantId);
        Instant now = clock.instant();
        ActivityView view = null;
        RuntimeException error = null;
        try {
            view = activity.activity(null, f.categoryParam(), null, null, f.tenantId()).data();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[activity] rows failed: {}", e.toString());
            error = e;
            res.setStatus(422);
        }
        // 필터는 URL 이 단일 출처 — 새로고침·링크 공유에도 유지(원본 setSearchParams replace). 검색어는 주소에 두지 않는다.
        res.setHeader("HX-Replace-Url", canonicalUrl(f));
        List<String> resets = List.of(reset.split(","));
        model.addAttribute("resetCategory", resets.contains("category"));
        model.addAttribute("resetTenant", resets.contains("tenant"));
        populate(model, f, view, error, now, loadTenantOptions());
        return ROWS_VIEW;
    }

    /**
     * 폴링·새로고침. 최신 한 쪽을 받아 {@code top}(화면의 가장 새 행 id) 앞의 행만 앞에 붙이고 KPI·활발한 tenant 를 맞춘다.
     * 응답 본문은 OOB 조각뿐이다({@code hx-swap="none"}). 조회 실패는 503 + 빈 본문 + 토스트 없음 — 화면은 "연결 끊김"으로만 알린다(원본 disconnected).
     */
    @GetMapping(POLL_PATH)
    public String poll(@RequestParam(name = "category", defaultValue = "") String category,
                       @RequestParam(name = "tenantId", defaultValue = "") String tenantId,
                       @RequestParam(name = "top", defaultValue = "") String top,
                       @RequestParam(name = "day", defaultValue = "") String day,
                       @RequestParam(name = "topSig", defaultValue = "") String topSig,
                       @RequestParam(name = "loaded", defaultValue = "1") String loaded,
                       HttpServletResponse res, Model model) {
        requirePlatform(model);
        Filter f = filter(category, tenantId);
        Instant now = clock.instant();
        ActivityView view;
        try {
            view = activity.activity(null, f.categoryParam(), null, null, f.tenantId()).data();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[activity] poll failed: {}", e.getClass().getSimpleName());
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return EMPTY_VIEW;
        }
        if ("0".equals(loaded)) {
            // 첫 조회(또는 필터를 바꾼 조회)가 실패한 상태에서 폴링이 성공했다 — 원본은 이때 목록을 통째로 보인다(replace). 목록 영역을 교체한다.
            res.setHeader("HX-Retarget", "#activity-main");
            res.setHeader("HX-Reswap", "innerHTML");
            model.addAttribute("resetCategory", false);
            model.addAttribute("resetTenant", false);
            populate(model, f, view, null, now, loadTenantOptions());
            return ROWS_VIEW;
        }
        List<ActivityView.Event> feed = feedOf(view);
        int k = top.isBlank() ? -1 : indexOfId(feed, top);
        List<ActivityView.Event> fresh = k < 0 ? feed : feed.subList(0, k);
        boolean wasEmpty = top.isBlank();
        // 새 행은 KST 날짜 그룹으로 묶는다. 화면 맨 위 그룹과 같은 날짜면 그 그룹의 행 목록 앞에, 아니면 새 그룹을 목록 맨 위에 붙인다.
        List<Day> days = days(fresh, now);
        splitDays(model, days, day, "fresh");
        model.addAttribute("hasFresh", !fresh.isEmpty());
        model.addAttribute("clearEmpty", wasEmpty && !fresh.isEmpty());
        model.addAttribute("topId", fresh.isEmpty() ? null : feed.get(0).id().toString());
        model.addAttribute("topDay", fresh.isEmpty() ? null : dayKey(feed.get(0)));
        // 비어 있던 목록이 처음 채워지면 더 보기·CSV 가 쓸 아래쪽 커서도 정한다.
        model.addAttribute("bottomState", wasEmpty && !feed.isEmpty());
        fillBottomState(model, feed);
        fillKpis(model, view, true);
        List<TopRow> topRows = topRows(view, f);
        String sig = topSig(topRows, f);
        model.addAttribute("topChanged", !sig.equals(topSig));
        model.addAttribute("top", topRows);
        model.addAttribute("topSig", sig);
        model.addAttribute("topOob", true);
        return POLL_VIEW;
    }

    /** 더 보기: 가장 오래된 행(커서 {@code before}/{@code beforeId}, 튜플 커서 F07) 이전 한 쪽을 아래에 붙인다. */
    @GetMapping(MORE_PATH)
    public String more(@RequestParam(name = "category", defaultValue = "") String category,
                       @RequestParam(name = "tenantId", defaultValue = "") String tenantId,
                       @RequestParam(name = "before", defaultValue = "") String before,
                       @RequestParam(name = "beforeId", defaultValue = "") String beforeId,
                       @RequestParam(name = "lastDay", defaultValue = "") String lastDay,
                       HttpServletResponse res, Model model) {
        requirePlatform(model);
        Filter f = filter(category, tenantId);
        OffsetDateTime cursor = parseCursor(before);
        if (cursor == null) return EMPTY_VIEW; // 읽어 온 행이 없다 — 원본도 아무 것도 하지 않는다
        Instant now = clock.instant();
        ActivityView view;
        try {
            view = activity.activity(null, f.categoryParam(), cursor, parseUuid(beforeId), f.tenantId()).data();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[activity] more failed: {}", e.toString());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            Toasts.errorTitled(res, "이전 기록을 불러오지 못했습니다", info.toastMessage(),
                    info.httpStatus() == null ? null : "HTTP " + info.httpStatus(), MDC.get("traceId"));
            res.setStatus(info.httpStatus() == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : info.httpStatus());
            return EMPTY_VIEW;
        }
        List<ActivityView.Event> feed = feedOf(view);
        splitDays(model, days(feed, now), lastDay, "more");
        model.addAttribute("hasRaw", !feed.isEmpty());
        fillBottomState(model, feed);
        return MORE_VIEW;
    }

    /** 상세(행의 "상세 열기"). 조회 실패는 422 + 패널 안 오류(다시 시도), 권한 거부는 403. */
    @GetMapping(DETAIL_BASE + "{id}")
    public String detail(@PathVariable String id, HttpServletResponse res, Model model) {
        requirePlatform(model);
        model.addAttribute("detailUrl", DETAIL_BASE + enc(id));
        UUID uuid = parseUuid(id);
        ActivityDetailView d = null;
        RuntimeException error = null;
        if (uuid == null) {
            error = new IllegalArgumentException("invalid id");
        } else {
            try {
                d = activity.detail(uuid).data();
            } catch (RuntimeException e) {
                if (denied(e)) throw e;
                log.warn("[activity] detail failed: {}", e.toString());
                error = e;
            }
        }
        if (d == null) {
            res.setStatus(422);
            model.addAttribute("loadError", error == null ? new IllegalStateException("empty") : error);
            model.addAttribute("d", null);
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("hx-get", DETAIL_BASE + enc(id));
            retry.put("hx-target", "#activity-detail");
            retry.put("hx-swap", "innerHTML");
            retry.put("hx-sync", "#activity-detail:replace");
            retry.put("data-quiet", true);
            model.addAttribute("detailRetryAttrs", retry);
            return DETAIL_VIEW;
        }
        model.addAttribute("loadError", null);
        model.addAttribute("d", detailModel(d));
        return DETAIL_VIEW;
    }

    /**
     * CSV(원본 downloadCsv: activity-{KST 오늘}.csv, 열 timestamp·tenant·type·category·actor·subject). 원본은 화면에 읽어 온 행 전부(액션 검색은
     * 거르지 않음)를 내보낸다 — 서버는 최신 쪽부터 경계 행({@code beforeId} = 화면의 가장 오래된 행)이 나올 때까지 이어 읽는다(커서가 없으면 최신 한 쪽).
     * 화면의 더 보기는 쪽 수 상한이 없으므로 내보내기도 경계까지 읽되 {@link ExportLimit#MAX_PAGES} 쪽 안에 닿지 못하면 422(이유 문구).
     * 그 사이 위에 새 행이 생겨도 경계는 id 로 찾으므로 영향이 없다(새 행은 폴링이 화면 맨 위에도 붙인다).
     * 경계 행이 사라졌거나(끝까지 읽음) 읽기에 실패하면 일부만 내보내지 않고 오류.
     */
    @GetMapping(EXPORT_PATH)
    public ResponseEntity<byte[]> export(@RequestParam(name = "category", defaultValue = "") String category,
                                         @RequestParam(name = "tenantId", defaultValue = "") String tenantId,
                                         @RequestParam(name = "beforeId", defaultValue = "") String beforeId,
                                         Model model) {
        requirePlatform(model);
        Filter f = filter(category, tenantId);
        UUID until = parseUuid(beforeId);
        List<ActivityView.Event> all = new ArrayList<>();
        OffsetDateTime cursor = null;
        UUID cursorId = null;
        for (int p = 0; ; p++) {
            if (p >= ExportLimit.MAX_PAGES) return ExportLimit.tooLarge(); // 경계에 닿기 전에 상한 — 조용히 일부만 내보내지 않는다
            List<ActivityView.Event> page = feedOf(activity.activity(null, f.categoryParam(), cursor, cursorId, f.tenantId()).data());
            int k = until == null ? -1 : indexOfId(page, until.toString());
            if (k >= 0) {
                all.addAll(page.subList(0, k + 1));
                break;
            }
            all.addAll(page);
            if (until == null) break; // 커서가 없으면 최신 한 쪽
            // 경계 행을 찾지 못한 채 끝에 닿았다(행이 사라짐) — 실패
            if (page.size() < FEED_PAGE) throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
            ActivityView.Event last = page.get(page.size() - 1);
            cursor = last.createdAt();
            cursorId = last.id();
        }
        List<List<String>> body = new ArrayList<>();
        for (ActivityView.Event e : all) {
            String tenant = e.tenantSlug() != null ? e.tenantSlug() : (e.tenantId() != null ? e.tenantId().toString() : "");
            body.add(List.of(rawTs(e), tenant, nz(e.action()), nz(e.category()), nz(e.actorEmail()), e.targetId() != null ? e.targetId() : "—"));
        }
        byte[] csv = CsvWriter.write(List.of("timestamp", "tenant", "type", "category", "actor", "subject"), body);
        String filename = "activity-" + KstFormat.kstToday(clock.instant()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // ── 모델 채우기 ─────────────────────────────────────────

    private void populate(Model model, Filter f, ActivityView view, RuntimeException error, Instant now, List<TenantAdminDto.TenantView> tenantList) {
        boolean loaded = view != null;
        List<ActivityView.Event> feed = loaded ? feedOf(view) : List.of();
        List<Day> days = days(feed, now);
        model.addAttribute("loaded", loaded);
        model.addAttribute("loadError", error);
        model.addAttribute("days", days);
        model.addAttribute("category", f.category());
        model.addAttribute("tenantIdValue", f.tenantId() == null ? "" : f.tenantId().toString());
        fillKpis(model, view, false);
        List<TopRow> topRows = loaded ? topRows(view, f) : List.of();
        model.addAttribute("top", topRows);
        model.addAttribute("topSig", topSig(topRows, f));
        model.addAttribute("topOob", true);
        model.addAttribute("topChanged", true);

        // 숨은 상태: 가장 새 행·아래쪽 커서·날짜 머리글(이후 폴링·더 보기가 이어 받는다)
        model.addAttribute("topId", feed.isEmpty() ? "" : feed.get(0).id().toString());
        model.addAttribute("topDay", feed.isEmpty() ? "" : dayKey(feed.get(0)));
        model.addAttribute("bottomState", true);
        fillBottomState(model, feed);

        // 컨트롤: 카테고리 세그먼트, tenant 선택(이름 (slug)), 적용된 필터 칩
        List<Map<String, Object>> segs = new ArrayList<>();
        for (String[] c : new String[][] {{CAT_ALL, "전체"}, {"ops", "운영"}, {"security", "보안"}}) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", c[0]);
            o.put("label", c[1]);
            o.put("title", null);
            o.put("attrs", rowsReq(ROWS_PATH + "?category=" + c[0], "#activity-tenant"));
            segs.add(o);
        }
        model.addAttribute("segOptions", segs);
        List<Option> tenantOpts = new ArrayList<>();
        for (TenantAdminDto.TenantView t : tenantList) {
            String name = t.displayName() == null ? "" : t.displayName();
            tenantOpts.add(new Option(t.id().toString(), name + " (" + t.slug() + ")", t.id().equals(f.tenantId())));
        }
        model.addAttribute("tenantOptions", tenantOpts);
        Map<String, Object> tenantAttrs = rowsReq(ROWS_PATH, "#activity-cat");
        tenantAttrs.put("hx-trigger", "change");
        model.addAttribute("tenantAttrs", tenantAttrs);
        model.addAttribute("hasApplied", f.hasServerApplied());
        model.addAttribute("categoryLabel", switch (f.category()) { case "ops" -> "운영"; case "security" -> "보안"; default -> "전체"; });
        String slug = null;
        if (f.tenantId() != null) {
            for (TenantAdminDto.TenantView t : tenantList) if (t.id().equals(f.tenantId())) slug = t.slug();
            if (slug == null && loaded) for (ActivityView.TopTenant t : view.top5()) if (f.tenantId().equals(t.tenantId())) slug = t.slug();
            if (slug == null) slug = f.tenantId().toString();
        }
        model.addAttribute("tenantSlug", slug);
        model.addAttribute("chipCategoryAttrs", rowsReq(ROWS_PATH + "?category=all&reset=category", "#activity-tenant"));
        model.addAttribute("chipTenantAttrs", rowsReq(ROWS_PATH + "?tenantId=&reset=tenant", "#activity-cat"));
        // 액션 검색은 원본처럼 이미 읽어 온 행을 클라이언트에서 거른다 — 검색어 칩 제거·모두 지우기의 검색어 부분은 Alpine(clearQ)이 한다.
        // 모두 지우기는 서버 필터(카테고리·tenant)가 있을 때만 목록을 다시 읽는다(원본 clearFilters 는 바뀌는 서버 필터가 없으면 다시 조회하지 않는다).
        model.addAttribute("clearAttrs", f.hasServerApplied() ? rowsReq(ROWS_PATH + "?category=all&tenantId=&reset=category,tenant", null) : null);
        model.addAttribute("retryAttrs", rowsReq(ROWS_PATH, "#activity-cat, #activity-tenant"));
        model.addAttribute("pollAttrs", pollReq());
        model.addAttribute("refreshAttrs", refreshReq());
        model.addAttribute("moreAttrs", moreReq());
        model.addAttribute("exportHref", EXPORT_PATH + "?category=" + f.category() + (f.tenantId() == null ? "" : "&tenantId=" + f.tenantId()));
        model.addAttribute("emptyTitle", "조건에 맞는 이벤트가 없습니다");
    }

    private void fillBottomState(Model model, List<ActivityView.Event> rawFeed) {
        ActivityView.Event last = rawFeed.isEmpty() ? null : rawFeed.get(rawFeed.size() - 1);
        model.addAttribute("beforeIso", last == null ? "" : iso(last));
        model.addAttribute("beforeId", last == null ? "" : last.id().toString());
        model.addAttribute("lastDay", last == null ? "" : dayKey(last));
    }

    private void fillKpis(Model model, ActivityView view, boolean oob) {
        boolean loaded = view != null;
        ActivityView.Kpi k = loaded ? view.kpi() : null;
        model.addAttribute("kpiEvents", loaded ? fmt(k.events24h()) : "—");
        model.addAttribute("kpiOps", loaded ? fmt(k.ops24h()) : "—");
        model.addAttribute("kpiSecurity", loaded ? fmt(k.security24h()) : "—");
        model.addAttribute("kpiSecurityBad", loaded && k.security24h() > 0);
        // §6: 서버가 p95Ms 를 주지 않으면(현재 항상 null) KPI 를 그리지 않는다.
        Long p95 = loaded ? k.p95Ms() : null;
        model.addAttribute("kpiP95", p95 == null ? null : p95 + "ms");
        model.addAttribute("kpiP95Hint", p95 == null ? null : "p95 " + p95 + "ms");
        model.addAttribute("kpiOob", oob);
    }

    // ── 피드 → 화면 값 ───────────────────────────────────────

    private static List<ActivityView.Event> feedOf(ActivityView v) {
        return v == null || v.feed() == null ? List.of() : v.feed();
    }

    private static int indexOfId(List<ActivityView.Event> feed, String id) {
        for (int i = 0; i < feed.size(); i++) if (feed.get(i).id().toString().equals(id)) return i;
        return -1;
    }

    /** KST 날짜(yyyy-MM-dd)별로 묶는다(원본 groupByDay). 입력은 최신순. */
    private List<Day> days(List<ActivityView.Event> feed, Instant now) {
        List<Ev> evs = new ArrayList<>();
        for (ActivityView.Event e : feed) evs.add(ev(e));
        List<Day> out = new ArrayList<>();
        for (KstFormat.DayGroup<Ev> g : KstFormat.groupByDay(evs, Ev::iso, now)) {
            out.add(new Day(g.label(), KstFormat.kstDate(g.items().get(0).iso()), g.items()));
        }
        return out;
    }

    private static String dayKey(ActivityView.Event e) {
        return KstFormat.kstDate(iso(e));
    }

    /**
     * 이어 붙일 그룹을 둘로 나눈다: {@code existing} 키(화면의 맨 위/맨 아래 그룹의 KST 날짜)와 같은 날짜 그룹은 그 그룹의 행 목록에
     * 행만 붙이고({@code <prefix>Same}), 나머지는 새 그룹으로({@code <prefix>New}).
     */
    private static void splitDays(Model model, List<Day> days, String existing, String prefix) {
        Day same = null;
        List<Day> fresh = new ArrayList<>();
        for (Day d : days) {
            if (!existing.isEmpty() && d.key().equals(existing)) same = d; else fresh.add(d);
        }
        model.addAttribute(prefix + "Same", same);
        model.addAttribute(prefix + "New", fresh);
    }

    private Ev ev(ActivityView.Event e) {
        String type = nz(e.action());
        String tenantName = e.tenantSlug() != null ? e.tenantSlug() : (e.tenantId() != null ? e.tenantId().toString() : "—"); // adaptFeedItems
        String actor = e.actorEmail();
        String subject = e.targetId() != null ? e.targetId() : "—";
        String sentence = ActivityLabels.eventSentence(new ActivityLabels.EventSentenceInput(type, actor == null ? "" : actor, e.tenantSlug(), null, subject));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("hx-get", DETAIL_BASE + e.id());
        attrs.put("hx-target", "#activity-detail");
        attrs.put("hx-swap", "innerHTML");
        // 마지막으로 연 상세만 반영(A→B 순서로 열고 A 가 늦게 와도 무시). 닫기·다시 열기가 같은 요소로 중단한다.
        attrs.put("hx-sync", "#activity-detail:replace");
        attrs.put("data-quiet", true); // 네트워크 실패는 패널 안에서 알린다(전역 토스트 없음)
        return new Ev(e.id().toString(), type, type.toUpperCase(Locale.ROOT), ActivityLabels.actionLabel(type), TONE_VAR.get(eventTone(type, nz(e.category()))),
                KstFormat.kstTime(e.createdAt()), iso(e), e.tenantId() == null ? null : e.tenantId().toString(), tenantName,
                e.tenantId() == null ? null : "/admin/tenants/" + e.tenantId(), sentence, attrs);
    }

    /** eventTone(type, category). */
    static String eventTone(String type, String category) {
        if (FAILURE_TYPES.contains(type) || FAILURE_NAME.matcher(type).find() || "security".equals(category)) return "danger";
        if ("ADMIN_LOGIN".equals(type)) return "info";
        if ("ops".equals(category)) return "warning";
        return "teal";
    }

    private List<TopRow> topRows(ActivityView view, Filter f) {
        List<ActivityView.TopTenant> top = view.top5() == null ? List.of() : view.top5();
        long max = 1;
        for (ActivityView.TopTenant t : top) max = Math.max(max, t.count());
        List<TopRow> rows = new ArrayList<>();
        for (ActivityView.TopTenant t : top) {
            String slug = t.slug() == null ? "" : t.slug();
            Map<String, Object> attrs = rowsReq(ROWS_PATH + "?tenantId=" + t.tenantId() + "&reset=tenant", "#activity-cat");
            rows.add(new TopRow(t.tenantId().toString(), slug, slug, slug.isEmpty() ? "" : slug.substring(0, 1).toUpperCase(Locale.ROOT),
                    fmt(t.count()), (int) Math.round(t.count() * 100.0 / max), t.tenantId().equals(f.tenantId()), attrs));
        }
        return rows;
    }

    /** 활발한 tenant 목록의 서명. 같으면 폴링이 이 영역을 다시 그리지 않는다(포커스·클릭 중인 버튼 보존). */
    private static String topSig(List<TopRow> rows, Filter f) {
        StringBuilder b = new StringBuilder(f.tenantId() == null ? "-" : f.tenantId().toString());
        for (TopRow r : rows) b.append('|').append(r.tenantId()).append(':').append(r.count()).append((r.selected() ? "*" : ""));
        return Integer.toHexString(b.toString().hashCode());
    }

    // ── 상세 ────────────────────────────────────────────────

    public record DiffLine(String field, boolean array, List<String> removed, List<String> added, String from, String to, String sr) {}

    public record DetailVm(String action, String actionLabel, String kst, String utc, String actor, String avatar, String tenantId,
                           String tenantLabel, String tenantUrl, String targetType, String targetTail, String targetFull,
                           List<DiffLine> diff, String json) {}

    private DetailVm detailModel(ActivityDetailView d) {
        String iso = d.createdAt() == null ? "" : d.createdAt().toInstant().toString();
        String actor = d.actorEmail() == null || d.actorEmail().isEmpty() ? "system" : d.actorEmail();
        List<DiffLine> lines = new ArrayList<>();
        for (ActivityDiff.DiffRow r : ActivityDiff.buildDiffRows(null, d.payload())) {
            ActivityDiff.ArrayChange ac = ActivityDiff.arrayChange(r.from(), r.to());
            String from = ActivityDiff.fmtVal(r.from());
            String to = ActivityDiff.fmtVal(r.to());
            lines.add(new DiffLine(r.field(), ac != null, ac == null ? List.of() : ac.removed(), ac == null ? List.of() : ac.added(),
                    from, to, r.field() + ": " + from + " 에서 " + to + " 로 변경"));
        }
        String target = d.targetId();
        return new DetailVm(nz(d.action()), ActivityLabels.actionLabel(nz(d.action())), KstFormat.kstDateTime(iso), KstFormat.utcIso(iso) + " UTC",
                actor, actor.substring(0, 1).toUpperCase(Locale.ROOT), d.tenantId() == null ? null : d.tenantId().toString(),
                d.tenantSlug() != null ? d.tenantSlug() : (d.tenantId() == null ? null : d.tenantId().toString()),
                d.tenantId() == null ? null : "/admin/tenants/" + d.tenantId(),
                d.targetType() == null ? "—" : d.targetType(),
                target == null || target.isEmpty() ? null : "…" + (target.length() <= 8 ? target : target.substring(target.length() - 8)),
                target, lines, prettyPayload(d.payload()));
    }

    /** parsePayload(raw) ? JSON.stringify(payload, null, 2) : raw. JS 의 falsy(null·0·false·"")는 원문 그대로. */
    private String prettyPayload(String raw) {
        if (raw == null) return "";
        try {
            JsonNode n = json.readTree(raw);
            if (n == null || n.isMissingNode() || n.isNull()) return raw;
            if ((n.isNumber() && n.decimalValue().signum() == 0) || (n.isBoolean() && !n.asBoolean()) || (n.isTextual() && n.asText().isEmpty())) return raw;
            return AuditHelpers.pretty(n);
        } catch (Exception e) {
            return raw;
        }
    }

    // ── htmx 속성(요소마다 새 Map — ui:attrs 가 다른 속성을 덮지 않는다) ─────

    /** 목록 영역을 바꾸는 요청. 최신 요청이 이긴다(진행 중이던 폴링도 중단). */
    private static Map<String, Object> rowsReq(String url, String include) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", url);
        m.put("hx-target", "#activity-main");
        m.put("hx-swap", "innerHTML");
        m.put("hx-indicator", "#activity-main");
        m.put("hx-sync", "#activity-root:replace");
        if (include != null) m.put("hx-include", include);
        return m;
    }

    /** 폴링: 같은 시각에 진행 중인 요청이 있으면 이번 것을 버린다(쌓이지 않음 · 필터 교체를 중단시키지 않음). 주변 UI 라 실패를 토스트하지 않는다. */
    private static Map<String, Object> pollReq() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", POLL_PATH);
        m.put("hx-trigger", "every " + (POLL_MS / 1000) + "s");
        m.put("hx-swap", "none");
        m.put("hx-sync", "#activity-root:drop");
        m.put("hx-include", "#activity-cat, #activity-tenant, #activity-loaded, #activity-newest, #activity-newest-day, #activity-tenants-sig");
        m.put("data-quiet", true);
        return m;
    }

    /** 새로고침·지금 다시 연결: 진행 중인 요청 뒤에 이어서 한 번(폴링에 추월당하지 않고, 진행 중인 필터 교체를 끊지도 않는다). */
    private static Map<String, Object> refreshReq() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", POLL_PATH);
        m.put("hx-trigger", "click");
        m.put("hx-swap", "none");
        m.put("hx-sync", "#activity-root:queue last");
        m.put("hx-include", "#activity-cat, #activity-tenant, #activity-loaded, #activity-newest, #activity-newest-day, #activity-tenants-sig");
        m.put("hx-disabled-elt", "this");
        m.put("data-quiet", true);
        return m;
    }

    private static Map<String, Object> moreReq() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", MORE_PATH);
        m.put("hx-trigger", "click");
        m.put("hx-swap", "none");
        m.put("hx-sync", "this:drop");
        m.put("hx-include", "#activity-cat, #activity-tenant, #activity-before, #activity-before-id, #activity-last-day");
        return m;
    }

    // ── 파싱·형식 ───────────────────────────────────────────

    static Filter filter(String category, String tenantId) {
        String cat = "ops".equals(category) || "security".equals(category) ? category : CAT_ALL;
        return new Filter(cat, parseUuid(tenantId));
    }

    static String canonicalUrl(Filter f) {
        List<String> p = new ArrayList<>();
        if (!CAT_ALL.equals(f.category())) p.add("category=" + f.category());
        if (f.tenantId() != null) p.add("tenantId=" + f.tenantId());
        return p.isEmpty() ? PAGE_PATH : PAGE_PATH + "?" + String.join("&", p);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static OffsetDateTime parseCursor(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 원본 CSV 의 timestamp: 서버가 직렬화한 createdAt 문자열 그대로(앱 ObjectMapper). */
    private String rawTs(ActivityView.Event e) {
        if (e.createdAt() == null) return "";
        try {
            String v = json.convertValue(e.createdAt(), String.class);
            return v == null ? e.createdAt().toString() : v;
        } catch (RuntimeException ex) {
            return e.createdAt().toString();
        }
    }

    private static String iso(ActivityView.Event e) {
        return e.createdAt() == null ? "" : e.createdAt().toInstant().toString();
    }

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private List<TenantAdminDto.TenantView> loadTenantOptions() {
        try {
            return tenants.list().data();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[activity] tenant list for filter failed: {}", e.toString()); // 원본: catch → []
            return List.of();
        }
    }

    private static boolean denied(RuntimeException e) {
        return e instanceof AccessDeniedException
                || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.ACCESS_DENIED);
    }

    private static CurrentAdmin me(Model model) {
        if (!(model.getAttribute("me") instanceof CurrentAdmin me)) throw new AccessDeniedException("no authenticated admin");
        return me;
    }

    private static void requirePlatform(Model model) {
        if (!me(model).platform()) throw new AccessDeniedException("PLATFORM_OPERATOR only");
    }
}
