package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditLogController;
import com.crosscert.passkey.admin.audit.AuditLogController.AuditLogView;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.CsvWriter;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant 상세의 감사 로그 탭(← pages/tenant/AuditTab.tsx, auditHelpers.ts, api/audit.ts, lib/csvExport.ts).
 *
 * <p>모든 엔드포인트가 {@link TenantTabs#load} 를 먼저 부른다(RP 다른 tenant → 403, CSV 포함).
 *
 * <p>원본에는 자동 갱신(폴링)이 없다(setInterval 은 체인 검증 경과 시간뿐) — 그래서 hx-trigger every 도 두지 않는다(브리프와 다름, 원본이 이긴다).
 * 필터·기간·"더 보기" 상태는 전부 요청 파라미터이고, 각 컨트롤의 다음 상태 주소를 서버가 만들어 hx-get 에 담는다
 * (카드 전체가 교체되므로 화면의 상태와 어긋나지 않는다). 상태: win(기간), to(구간 끝 — 더 보기·필터가 같은 구간을 쓴다), pages(읽은 쪽 수),
 * g(이벤트 종류, 여러 개), failures(실패만). 필터는 원본처럼 "읽어 온 행 안에서" 거른다 — REST 에 그 파라미터가 없다.
 * 더 보기는 stateless 서버라 1..N 쪽을 같은 구간으로 다시 읽는다.
 *
 * <p>감사 로그 payload·행위자·대상은 사용자가 정할 수 있는 값이다 — 템플릿은 th:text 만 쓰고 속성의 JS 평가 위치에는 넣지 않는다.
 */
@Slf4j
@Controller
public class AuditTabController {

    static final String FRAG = "/admin/_/tenants/";
    static final String PAGE = "/admin/tenants/";
    static final String TAB_VIEW = "admin/tenant/audit :: tab";
    static final String CARD_VIEW = "admin/tenant/audit-rows :: response";
    static final String CHAIN_VIEW = "admin/tenant/audit :: chain";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    static final int PAGE_SIZE = 50;
    /** 한 요청이 읽는 최대 쪽 수(50건 × 40 = 2,000건). */
    static final int MAX_PAGES = 40;
    /** 체인 검증 클라이언트 시간 제한(LONG_TIMEOUT_MS). 템플릿의 hx-request 와 같은 값. */
    static final int VERIFY_TIMEOUT_MS = 120_000;

    private final TenantAdminController tenants;
    private final AuditLogController audit;
    private final AuditChainMonitorController chain;
    private final Clock clock;
    private final ObjectMapper json;

    public AuditTabController(TenantAdminController tenants, AuditLogController audit, AuditChainMonitorController chain,
                              Clock clock, ObjectMapper json) {
        this.tenants = tenants;
        this.audit = audit;
        this.chain = chain;
        this.clock = clock;
        this.json = json;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** 목록 상태. to 가 null 이면 이번 요청의 now. */
    record St(String win, List<String> groups, boolean failures, int pages, Instant to) {
        static St of(String win, List<String> g, String failures, String pages, String to, Instant now) {
            List<String> groups = new ArrayList<>();
            if (g != null) {
                for (AuditHelpers.EventGroup eg : AuditHelpers.EVENT_GROUPS) {
                    if (g.contains(eg.id())) groups.add(eg.id()); // 알려진 그룹만, 선언 순서
                }
            }
            int p = 1;
            try {
                p = Integer.parseInt(pages.trim());
            } catch (RuntimeException ignored) {
                // 숫자가 아니면 1
            }
            Instant t = null;
            if (to != null && !to.isBlank()) {
                try {
                    t = Instant.parse(to.trim());
                    if (t.isAfter(now)) t = now; // 미래 구간은 now 로
                } catch (RuntimeException ignored) {
                    t = null;
                }
            }
            return new St(AuditHelpers.window(win).key(), groups, "true".equals(failures), Math.max(1, Math.min(MAX_PAGES, p)), t);
        }

        boolean filtering() { return !groups.isEmpty() || failures; }

        St with(String win, List<String> groups, boolean failures, int pages, Instant to) { return new St(win, groups, failures, pages, to); }

        St toggled(String id) {
            List<String> g = new ArrayList<>(groups);
            if (!g.remove(id)) g.add(id);
            return with(win, g, failures, pages, to);
        }

        String query() {
            StringBuilder b = new StringBuilder("win=" + win + "&pages=" + pages);
            if (to != null) b.append("&to=").append(enc(AuditHelpers.iso(to)));
            if (failures) b.append("&failures=true");
            for (String g : groups) b.append("&g=").append(g);
            return b.toString();
        }
    }

    /** 표의 한 줄(api/audit.ts adapt + 열 계산). */
    public record Ev(String id, String eventType, String tone, String hms, String ago, String actorType, String actorId, String actorTail,
                     String subjectType, String subjectId, String subjectTail, String detail, List<Diff> diff, String pretty,
                     String whenKst, String whenIso) {}

    public record Diff(String field, String from, String to) {}

    public record Day(String label, List<Ev> events) {}

    public record ChainResult(boolean intact, int tampered, String at) {}

    private record Item(AuditLogView view, JsonNode payload) {}

    private record Loaded(St st, List<Item> items, boolean hasMore, RuntimeException error, RuntimeException moreError) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/audit")
    public String tab(@PathVariable String id,
                      @RequestParam(name = "win", defaultValue = AuditHelpers.DEFAULT_WINDOW) String win,
                      @RequestParam(name = "g", required = false) List<String> g,
                      @RequestParam(name = "failures", defaultValue = "") String failures,
                      @RequestParam(name = "pages", defaultValue = "1") String pages,
                      @RequestParam(name = "to", defaultValue = "") String to,
                      HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        CurrentAdmin me = me(model);
        Instant now = clock.instant();
        St st = St.of(win, g, failures, pages, to, now);
        model.addAttribute("tid", v.id().toString());
        model.addAttribute("platform", me.platform());
        model.addAttribute("tenantName", v.displayName() == null ? "" : v.displayName());
        model.addAttribute("chainResult", null);
        fillChainAttrs(model, v.id());
        fillCard(model, res, v.id(), v.slug(), st, now);
        return TAB_VIEW;
    }

    /** 카드 교체 응답(필터·기간·더 보기·다시 시도). 탭 영역이 아니라 #audit-card 만 바꾼다. */
    @GetMapping(FRAG + "{id}/tab/audit/card")
    public String card(@PathVariable String id,
                       @RequestParam(name = "win", defaultValue = AuditHelpers.DEFAULT_WINDOW) String win,
                       @RequestParam(name = "g", required = false) List<String> g,
                       @RequestParam(name = "failures", defaultValue = "") String failures,
                       @RequestParam(name = "pages", defaultValue = "1") String pages,
                       @RequestParam(name = "to", defaultValue = "") String to,
                       HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Instant now = clock.instant();
        St st = St.of(win, g, failures, pages, to, now);
        model.addAttribute("tid", v.id().toString());
        fillCard(model, res, v.id(), v.slug(), st, now);
        return CARD_VIEW;
    }

    /**
     * 체인 검증(PLATFORM 전용). 성공하면 카드 전체를 결과로 교체하고(열린 다이얼로그도 함께 사라진다 — 원본 setShowVerify(false)),
     * 실패하면 실제 오류 상태 + 토스트(본문 없음)라 카드·다이얼로그가 그대로 남고 버튼이 다시 풀린다.
     */
    @GetMapping(FRAG + "{id}/audit/verify")
    public String verify(@PathVariable String id, HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        CurrentAdmin me = me(model);
        if (!me.platform()) throw new AccessDeniedException("platform operator only");
        try {
            AuditChainMonitorController.TenantVerifyResponse r = chain.verifyTenant(v.id().toString());
            int tampered = r.tamperedEntryId() == null ? 0 : 1;
            model.addAttribute("chainResult", new ChainResult(r.intact(), tampered, r.verifiedAt() == null ? "—" : KstFormat.kstDateTimeMin(r.verifiedAt())));
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-tab] chain verify failed: {}", e.getClass().getSimpleName());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            int status = info.httpStatus() == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : info.httpStatus();
            Toasts.errorTitled(res, "체인 검증에 실패했습니다", info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
            res.setStatus(status);
            return EMPTY_VIEW;
        }
        model.addAttribute("tid", v.id().toString());
        model.addAttribute("platform", true);
        model.addAttribute("tenantName", v.displayName() == null ? "" : v.displayName());
        fillChainAttrs(model, v.id());
        return CHAIN_VIEW;
    }

    /** 현재 상태에서 보이는 행의 CSV(원본 downloadCsv: audit-{slug}-{KST 오늘}.csv). */
    @GetMapping(PAGE + "{id}/audit.csv")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(name = "win", defaultValue = AuditHelpers.DEFAULT_WINDOW) String win,
                                         @RequestParam(name = "g", required = false) List<String> g,
                                         @RequestParam(name = "failures", defaultValue = "") String failures,
                                         @RequestParam(name = "pages", defaultValue = "1") String pages,
                                         @RequestParam(name = "to", defaultValue = "") String to) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Instant now = clock.instant();
        St st = St.of(win, g, failures, pages, to, now);
        Loaded l = load(v.id(), st, now);
        if (l.error() != null) throw l.error();
        if (l.moreError() != null) throw l.moreError(); // 부분 파일 금지
        List<List<String>> body = new ArrayList<>();
        for (Item it : filter(l.items(), st)) {
            AuditLogView a = it.view();
            body.add(List.of(isoOf(a.createdAt()), nz(a.action()), a.actorEmail() != null ? "ADMIN" : "SYSTEM", nz(a.actorEmail()),
                    nz(a.targetType()), nz(a.targetId()), payloadCompact(it.payload(), true)));
        }
        byte[] csv = CsvWriter.write(List.of("ts", "eventType", "actorType", "actorId", "subjectType", "subjectId", "payload"), body);
        String filename = "audit-" + v.slug() + "-" + KstFormat.kstToday(now) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // ── 조회 ────────────────────────────────────────────────

    /** 1..pages 쪽을 같은 구간으로 읽는다. 첫 쪽 실패 = error, 이후 쪽 실패 = 읽은 데까지 + moreError. 권한 거부는 그대로 던진다. */
    private Loaded load(UUID tid, St requested, Instant now) {
        AuditHelpers.Window w = AuditHelpers.window(requested.win());
        Instant to = requested.to() != null ? requested.to() : now;
        St st = requested.with(requested.win(), requested.groups(), requested.failures(), requested.pages(), to);
        AuditHelpers.Range range = AuditHelpers.windowRange(w.hours(), to);
        OffsetDateTime from = OffsetDateTime.ofInstant(Instant.parse(range.from()), ZoneOffset.UTC);
        OffsetDateTime toT = OffsetDateTime.ofInstant(Instant.parse(range.to()), ZoneOffset.UTC);
        List<Item> items = new ArrayList<>();
        boolean hasMore = false;
        int loaded = 0;
        RuntimeException error = null;
        RuntimeException moreError = null;
        for (int p = 0; p < st.pages(); p++) {
            List<AuditLogView> rows;
            try {
                rows = audit.list(null, null, tid, from, toT, p, PAGE_SIZE).data();
            } catch (RuntimeException e) {
                if (denied(e)) throw e;
                log.warn("[audit-tab] list failed (page {}): {}", p, e.getClass().getSimpleName());
                if (p == 0) error = e; else moreError = e;
                break;
            }
            for (AuditLogView a : rows) items.add(new Item(a, parsePayload(a.payload())));
            loaded++;
            hasMore = rows.size() == PAGE_SIZE;
            if (!hasMore) break;
        }
        if (moreError != null) hasMore = true;
        return new Loaded(st.with(st.win(), st.groups(), st.failures(), Math.max(1, loaded), to), items, hasMore && error == null, error, moreError);
    }

    private static List<Item> filter(List<Item> items, St st) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            String type = it.view().action() == null ? "" : it.view().action();
            if ((st.groups().isEmpty() || st.groups().contains(AuditHelpers.eventGroup(type)))
                    && (!st.failures() || AuditHelpers.isFailure(type, null))) out.add(it);
        }
        return out;
    }

    /** payload 문자열 → JSON. 비어 있으면 null, 파싱 실패는 {raw: 원문}, JSON null 은 null. */
    JsonNode parsePayload(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            JsonNode n = json.readTree(s);
            return n == null || n instanceof NullNode ? null : n;
        } catch (JsonProcessingException e) {
            ObjectNode o = json.createObjectNode();
            o.put("raw", s);
            return o;
        }
    }

    /** JSON.stringify(payload) — null 은 "null". orEmpty 면 payload ?? {} (CSV). */
    private static String payloadCompact(JsonNode p, boolean orEmpty) {
        if (p == null) return orEmpty ? "{}" : "null";
        return p.toString();
    }

    private String isoOf(OffsetDateTime t) {
        if (t == null) return "";
        try {
            return json.convertValue(t, String.class);
        } catch (RuntimeException e) {
            return t.toString();
        }
    }

    // ── 모델 채우기 ─────────────────────────────────────────

    private void fillChainAttrs(Model model, UUID tid) {
        model.addAttribute("verifyUrl", FRAG + tid + "/audit/verify");
    }

    private void fillCard(Model model, HttpServletResponse res, UUID tid, String slug, St requested, Instant now) {
        Loaded l = load(tid, requested, now);
        St st = l.st();
        String base = FRAG + tid + "/tab/audit/card";
        List<Item> visible = l.error() != null ? List.of() : filter(l.items(), st);
        boolean filtering = st.filtering();
        boolean failed = l.error() != null;

        if (l.moreError() != null) {
            ErrorMessages.Info info = ErrorMessages.describe(l.moreError());
            Toasts.errorTitled(res, "감사 로그를 더 불러오지 못했습니다", info.toastMessage(),
                    info.httpStatus() == null ? null : "HTTP " + info.httpStatus(), MDC.get("traceId"));
        }

        List<Ev> evs = visible.stream().map(it -> ev(it, now)).toList();
        List<Day> days = new ArrayList<>();
        for (KstFormat.DayGroup<Ev> g : KstFormat.groupByDay(evs, Ev::whenIso, now)) days.add(new Day(g.label(), g.items()));

        model.addAttribute("tid", tid.toString());
        model.addAttribute("win", st.win());
        model.addAttribute("days", days);
        model.addAttribute("loadError", l.error());
        model.addAttribute("filtering", filtering);
        model.addAttribute("shown", visible.size());
        model.addAttribute("countText", failed ? "—"
                : filtering ? "이 페이지 " + num(l.items().size()) + "건 중 " + num(visible.size()) + "건" : "최근 " + num(l.items().size()) + "건");
        model.addAttribute("emptyTitle", filtering ? "조건에 맞는 이벤트가 없습니다." : "이 기간에 감사 이벤트가 없습니다.");
        model.addAttribute("csvLabel", "CSV 내보내기 (현재 페이지 " + num(visible.size()) + "건)");
        model.addAttribute("csvDisabled", failed || visible.isEmpty());
        model.addAttribute("csvUrl", PAGE + tid + "/audit.csv?" + st.query());
        model.addAttribute("hasMore", l.hasMore() && !failed);
        model.addAttribute("failures", st.failures());

        // 다음 상태 주소(전부 서버 상수 + 인코딩한 값). 요소마다 따로 만든 Map 이라 ui:attrs 가 다른 속성을 덮지 않는다.
        List<Map<String, Object>> chips = new ArrayList<>();
        for (AuditHelpers.EventGroup eg : AuditHelpers.EVENT_GROUPS) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", eg.id());
            c.put("label", eg.label());
            c.put("title", eg.codes());
            c.put("active", st.groups().contains(eg.id()));
            c.put("attrs", get(base + "?" + st.toggled(eg.id()).query()));
            chips.add(c);
        }
        model.addAttribute("chips", chips);
        model.addAttribute("clearAttrs", get(base + "?" + st.with(st.win(), List.of(), false, st.pages(), st.to()).query()));
        Map<String, Object> sw = get(base + "?" + st.with(st.win(), st.groups(), !st.failures(), st.pages(), st.to()).query());
        sw.put("hx-trigger", "change");
        model.addAttribute("failuresAttrs", sw);
        List<Map<String, Object>> windows = new ArrayList<>();
        for (AuditHelpers.Window w : AuditHelpers.WINDOWS) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", w.key());
            o.put("label", w.label());
            o.put("title", null);
            // 기간을 바꾸면 구간 끝(to)을 새로 잡고 첫 쪽부터.
            o.put("attrs", get(base + "?" + st.with(w.key(), st.groups(), st.failures(), 1, null).query()));
            windows.add(o);
        }
        model.addAttribute("windows", windows);
        model.addAttribute("retryAttrs", get(base + "?" + st.with(st.win(), st.groups(), st.failures(), 1, null).query()));
        model.addAttribute("moreAttrs", get(base + "?" + st.with(st.win(), st.groups(), st.failures(), Math.min(MAX_PAGES, st.pages() + 1), st.to()).query()));
        model.addAttribute("moreCapped", l.hasMore() && !failed && st.pages() >= MAX_PAGES);
        model.addAttribute("slug", slug);
    }

    private static Map<String, Object> get(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", url);
        return m;
    }

    private Ev ev(Item it, Instant now) {
        AuditLogView a = it.view();
        String type = a.action() == null ? "" : a.action();
        JsonNode p = it.payload();
        Instant at = a.createdAt() == null ? null : a.createdAt().toInstant();
        String kst = KstFormat.kstDateTime(at);
        String actorId = a.actorEmail();
        String actorType = actorId != null ? "ADMIN" : "SYSTEM";
        List<Diff> diff = AuditHelpers.payloadDiff(p).stream()
                .map(d -> new Diff(d.field(), AuditHelpers.diffValue(d.from()), AuditHelpers.diffValue(d.to()))).toList();
        return new Ev(String.valueOf(a.id()), type, AuditHelpers.eventTone(type, null),
                kst.length() > 11 ? kst.substring(11) : kst, KstFormat.timeAgo(at, now), actorType, actorId, actorId == null ? null : "…" + tail(actorId, 10),
                a.targetType() == null ? "—" : a.targetType(), a.targetId(), a.targetId() == null ? null : "…" + tail(a.targetId(), 12),
                payloadCompact(p, false), diff, AuditHelpers.pretty(p == null ? json.createObjectNode() : p), kst,
                at == null ? "" : AuditHelpers.iso(at));
    }

    private static boolean denied(RuntimeException e) {
        return e instanceof AccessDeniedException
                || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.ACCESS_DENIED);
    }

    private static CurrentAdmin me(Model model) {
        if (!(model.getAttribute("me") instanceof CurrentAdmin me)) throw new AccessDeniedException("no authenticated admin");
        return me;
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String num(long n) {
        return String.format(Locale.KOREA, "%,d", n);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
