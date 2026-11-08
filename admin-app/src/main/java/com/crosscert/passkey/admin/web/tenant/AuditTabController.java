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
import com.crosscert.passkey.admin.web.support.ExportLimit;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
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
 * 기간 전환·다시 시도는 카드를 새로 그린다(구간 끝 to 를 새로 잡고 첫 쪽). "더 보기"는 원본 loadMore 처럼 같은 구간(win·to)의 다음 한 쪽만 읽어
 * 표 끝에 붙인다(OOB beforeend) — 다음 쪽 번호·마지막 날짜 키는 카드 안 숨은 입력(#audit-state)이 들고 다니고 응답이 갱신한다. 쪽 수 상한은 없다.
 * 이벤트 종류·실패만 필터는 원본처럼 "읽어 온 행 안에서" 클라이언트(Alpine auditFilter)가 거른다 — REST 에 그 파라미터가 없고,
 * 더 보기로 붙인 행에도 같은 필터가 적용된다. 필터 상태는 카드 밖(#audit-root)에 있어 기간 전환으로 카드가 바뀌어도 유지된다(원본과 같다).
 * CSV 는 화면이 불러온 쪽 수(pages)만큼 같은 구간을 다시 읽어 같은 필터로 거른다({@link ExportLimit#MAX_PAGES} 초과는 422 — 일부만 담은 파일 금지).
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
    static final String MORE_VIEW = "admin/tenant/audit-rows :: more";
    static final String CHAIN_VIEW = "admin/tenant/audit :: chain";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    static final int PAGE_SIZE = 50;
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

    /** 조회 구간: 기간 키와 구간 끝(to). 같은 구간으로 더 보기·CSV 가 이어 읽는다. */
    record Span(String win, Instant to) {
        /** to 가 비었거나 형식이 틀리면 now, 미래면 now. */
        static Span of(String win, String to, Instant now) {
            Instant t = now;
            if (to != null && !to.isBlank()) {
                try {
                    t = Instant.parse(to.trim());
                    if (t.isAfter(now)) t = now;
                } catch (RuntimeException ignored) {
                    t = now;
                }
            }
            return new Span(AuditHelpers.window(win).key(), t);
        }

        OffsetDateTime[] range() {
            AuditHelpers.Range r = AuditHelpers.windowRange(AuditHelpers.window(win).hours(), to);
            return new OffsetDateTime[] {
                    OffsetDateTime.ofInstant(Instant.parse(r.from()), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(Instant.parse(r.to()), ZoneOffset.UTC)};
        }

        String toIso() { return AuditHelpers.iso(to); }
    }

    /** 표의 한 줄(api/audit.ts adapt + 열 계산). group·fail·dayKey 는 클라이언트 필터·날짜 머리글이 읽는 data-* 값(서버 상수). */
    public record Ev(String id, String eventType, String tone, String hms, String ago, String actorType, String actorId, String actorTail,
                     String subjectType, String subjectId, String subjectTail, String detail, List<Diff> diff, String pretty,
                     String whenKst, String whenIso, String group, boolean fail, String dayKey) {}

    public record Diff(String field, String from, String to) {}

    /** 날짜 그룹. head=false 면 머리글 행을 그리지 않는다(더 보기의 첫 그룹이 화면 마지막 그룹과 같은 날짜일 때). */
    public record Day(String label, String key, boolean head, List<Ev> events) {}

    public record Chip(String id, String label, String title) {}

    public record ChainResult(boolean intact, int tampered, String at) {}

    private record Item(AuditLogView view, JsonNode payload) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/audit")
    public String tab(@PathVariable String id,
                      @RequestParam(name = "win", defaultValue = AuditHelpers.DEFAULT_WINDOW) String win,
                      Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        CurrentAdmin me = me(model);
        Instant now = clock.instant();
        model.addAttribute("tid", v.getId().toString());
        model.addAttribute("platform", me.getPlatform());
        model.addAttribute("tenantName", v.getDisplayName() == null ? "" : v.getDisplayName());
        model.addAttribute("chainResult", null);
        fillChainAttrs(model, v.getId());
        fillCard(model, v.getId(), new Span(AuditHelpers.window(win).key(), now), now);
        return TAB_VIEW;
    }

    /** 카드 교체 응답(기간 전환·다시 시도). 구간 끝을 새로 잡고 첫 쪽을 읽는다. 탭 영역이 아니라 #audit-card 만 바꾼다. */
    @GetMapping(FRAG + "{id}/tab/audit/card")
    public String card(@PathVariable String id,
                       @RequestParam(name = "win", defaultValue = AuditHelpers.DEFAULT_WINDOW) String win,
                       Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Instant now = clock.instant();
        model.addAttribute("tid", v.getId().toString());
        fillCard(model, v.getId(), new Span(AuditHelpers.window(win).key(), now), now);
        return CARD_VIEW;
    }

    /**
     * 더 보기(원본 loadMore): 같은 구간(win·to)의 {@code page} 쪽 하나만 읽어 표 끝에 붙인다(OOB). 다음 쪽 번호·마지막 날짜 키·버튼을 OOB 로 갱신한다.
     * 실패는 실제 오류 상태 + 토스트(본문 없음) — 붙인 행과 숨은 상태는 그대로라 같은 쪽을 다시 누를 수 있다.
     */
    @GetMapping(FRAG + "{id}/tab/audit/more")
    public String more(@PathVariable String id,
                       @RequestParam(name = "win", defaultValue = AuditHelpers.DEFAULT_WINDOW) String win,
                       @RequestParam(name = "to", defaultValue = "") String to,
                       @RequestParam(name = "page", defaultValue = "1") String page,
                       @RequestParam(name = "lastDay", defaultValue = "") String lastDay,
                       HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Instant now = clock.instant();
        Span span = Span.of(win, to, now);
        int p = Math.max(1, parseInt(page, 1));
        List<Item> items;
        try {
            items = readPage(v.getId(), span, p);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-tab] more failed (page {}): {}", p, e.getClass().getSimpleName());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            int status = info.getHttpStatus() == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : info.getHttpStatus();
            Toasts.errorTitled(res, "감사 로그를 더 불러오지 못했습니다", info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
            res.setStatus(status);
            return EMPTY_VIEW;
        }
        List<Ev> evs = items.stream().map(it -> ev(it, now)).toList();
        model.addAttribute("days", days(evs, lastDay, now));
        model.addAttribute("nextPage", p + 1);
        model.addAttribute("lastDay", evs.isEmpty() ? lastDay : evs.get(evs.size() - 1).dayKey());
        model.addAttribute("hasMore", items.size() == PAGE_SIZE);
        model.addAttribute("moreUrl", FRAG + v.getId() + "/tab/audit/more");
        return MORE_VIEW;
    }

    /**
     * 체인 검증(PLATFORM 전용). 성공하면 카드 전체를 결과로 교체하고(열린 다이얼로그도 함께 사라진다 — 원본 setShowVerify(false)),
     * 실패하면 실제 오류 상태 + 토스트(본문 없음)라 카드·다이얼로그가 그대로 남고 버튼이 다시 풀린다.
     */
    @GetMapping(FRAG + "{id}/audit/verify")
    public String verify(@PathVariable String id, HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        CurrentAdmin me = me(model);
        if (!me.getPlatform()) throw new AccessDeniedException("platform operator only");
        try {
            AuditChainMonitorController.TenantVerifyResponse r = chain.verifyTenant(v.getId().toString());
            int tampered = r.getTamperedEntryId() == null ? 0 : 1;
            model.addAttribute("chainResult", new ChainResult(r.getIntact(), tampered, r.getVerifiedAt() == null ? "—" : KstFormat.kstDateTimeMin(r.getVerifiedAt())));
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-tab] chain verify failed: {}", e.getClass().getSimpleName());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            int status = info.getHttpStatus() == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : info.getHttpStatus();
            Toasts.errorTitled(res, "체인 검증에 실패했습니다", info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
            res.setStatus(status);
            return EMPTY_VIEW;
        }
        model.addAttribute("tid", v.getId().toString());
        model.addAttribute("platform", true);
        model.addAttribute("tenantName", v.getDisplayName() == null ? "" : v.getDisplayName());
        fillChainAttrs(model, v.getId());
        return CHAIN_VIEW;
    }

    /**
     * 화면이 불러온 행의 CSV(원본 downloadCsv: audit-{slug}-{KST 오늘}.csv). 같은 구간(win·to)의 0..pages-1 쪽을 다시 읽어 화면과 같은 필터(g·failures)로 거른다.
     * pages 가 {@link ExportLimit#MAX_PAGES} 를 넘으면 422(이유 문구). 어느 쪽이든 읽기에 실패하면 부분 파일 없이 오류.
     */
    @GetMapping(PAGE + "{id}/audit.csv")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(name = "win", defaultValue = AuditHelpers.DEFAULT_WINDOW) String win,
                                         @RequestParam(name = "g", required = false) List<String> g,
                                         @RequestParam(name = "failures", defaultValue = "") String failures,
                                         @RequestParam(name = "pages", defaultValue = "1") String pages,
                                         @RequestParam(name = "to", defaultValue = "") String to) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Instant now = clock.instant();
        Span span = Span.of(win, to, now);
        int n = Math.max(1, parseInt(pages, 1));
        if (n > ExportLimit.MAX_PAGES) return ExportLimit.tooLarge();
        List<String> groups = new ArrayList<>();
        if (g != null) {
            for (AuditHelpers.EventGroup eg : AuditHelpers.EVENT_GROUPS) if (g.contains(eg.id())) groups.add(eg.id()); // 알려진 그룹만
        }
        boolean onlyFailures = "true".equals(failures);
        List<Item> items = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            List<Item> page = readPage(v.getId(), span, p); // 실패는 그대로 던진다(부분 파일 금지)
            items.addAll(page);
            if (page.size() < PAGE_SIZE) break;
        }
        List<List<String>> body = new ArrayList<>();
        for (Item it : items) {
            AuditLogView a = it.view();
            String type = a.getAction() == null ? "" : a.getAction();
            if (!groups.isEmpty() && !groups.contains(AuditHelpers.eventGroup(type))) continue;
            if (onlyFailures && !AuditHelpers.isFailure(type, null)) continue;
            body.add(List.of(isoOf(a.getCreatedAt()), nz(a.getAction()), a.getActorEmail() != null ? "ADMIN" : "SYSTEM", nz(a.getActorEmail()),
                    nz(a.getTargetType()), nz(a.getTargetId()), payloadCompact(it.payload(), true)));
        }
        byte[] csv = CsvWriter.write(List.of("ts", "eventType", "actorType", "actorId", "subjectType", "subjectId", "payload"), body);
        String filename = "audit-" + v.getSlug() + "-" + KstFormat.kstToday(now) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // ── 조회 ────────────────────────────────────────────────

    /** 구간의 한 쪽. 실패는 그대로 던진다(권한 거부 포함). */
    private List<Item> readPage(UUID tid, Span span, int page) {
        OffsetDateTime[] r = span.range();
        List<AuditLogView> rows = audit.list(null, null, tid, r[0], r[1], page, PAGE_SIZE).data();
        List<Item> items = new ArrayList<>();
        if (rows != null) for (AuditLogView a : rows) items.add(new Item(a, parsePayload(a.getPayload())));
        return items;
    }

    /** payload 문자열 → JSON. 비어 있으면 null, 파싱 실패는 {raw: 원문}, JSON null 은 null. */
    JsonNode parsePayload(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            JsonNode n = json.readTree(s);
            return n == null || n instanceof NullNode ? null : n;
        } catch (JacksonException e) {
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

    private void fillCard(Model model, UUID tid, Span span, Instant now) {
        String base = FRAG + tid + "/tab/audit/card";
        List<Item> items = List.of();
        RuntimeException error = null;
        try {
            items = readPage(tid, span, 0);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-tab] list failed: {}", e.getClass().getSimpleName());
            error = e;
        }
        boolean failed = error != null;
        List<Ev> evs = items.stream().map(it -> ev(it, now)).toList();

        model.addAttribute("tid", tid.toString());
        model.addAttribute("win", span.win());
        model.addAttribute("days", days(evs, "", now));
        model.addAttribute("loadError", error);
        model.addAttribute("total", evs.size());
        // 처음 값(필터 없음). 이후 건수·CSV 라벨은 Alpine(auditFilter)이 읽어 온 행으로 다시 센다.
        model.addAttribute("countText", failed ? "—" : "최근 " + num(evs.size()) + "건");
        model.addAttribute("csvLabel", "CSV 내보내기 (현재 페이지 " + num(evs.size()) + "건)");
        String csvBase = PAGE + tid + "/audit.csv?win=" + span.win() + "&to=" + enc(span.toIso());
        model.addAttribute("csvBase", csvBase);
        model.addAttribute("csvUrl", csvBase + "&pages=1");
        model.addAttribute("hasMore", !failed && items.size() == PAGE_SIZE);
        model.addAttribute("moreUrl", FRAG + tid + "/tab/audit/more");
        // 숨은 상태: 더 보기·CSV 가 이어 받는 구간과 다음 쪽 번호(= 불러온 쪽 수)·마지막 날짜 키
        model.addAttribute("toIso", span.toIso());
        model.addAttribute("nextPage", 1);
        model.addAttribute("lastDay", evs.isEmpty() ? "" : evs.get(evs.size() - 1).dayKey());

        List<Chip> chips = new ArrayList<>();
        for (AuditHelpers.EventGroup eg : AuditHelpers.EVENT_GROUPS) chips.add(new Chip(eg.id(), eg.label(), eg.codes()));
        model.addAttribute("chips", chips);
        List<Map<String, Object>> windows = new ArrayList<>();
        for (AuditHelpers.Window w : AuditHelpers.WINDOWS) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", w.key());
            o.put("label", w.label());
            o.put("title", null);
            // 기간을 바꾸면 구간 끝(to)을 새로 잡고 첫 쪽부터(필터는 카드 밖 Alpine 상태라 그대로).
            o.put("attrs", get(base + "?win=" + w.key()));
            windows.add(o);
        }
        model.addAttribute("windows", windows);
        model.addAttribute("retryAttrs", get(base + "?win=" + span.win()));
    }

    /** KST 날짜(키)별 연속 구간으로 묶는다(원본 DataTable groupBy dayLabel). 첫 그룹의 키가 prevKey 와 같으면 머리글을 그리지 않는다. */
    private static List<Day> days(List<Ev> evs, String prevKey, Instant now) {
        List<Day> out = new ArrayList<>();
        List<Ev> cur = null;
        String key = null;
        for (Ev e : evs) {
            if (cur == null || !e.dayKey().equals(key)) {
                key = e.dayKey();
                cur = new ArrayList<>();
                boolean head = !(out.isEmpty() && key.equals(prevKey));
                out.add(new Day(KstFormat.dayLabel(e.whenIso(), now), key, head, cur));
            }
            cur.add(e);
        }
        return out;
    }

    private static Map<String, Object> get(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", url);
        return m;
    }

    private Ev ev(Item it, Instant now) {
        AuditLogView a = it.view();
        String type = a.getAction() == null ? "" : a.getAction();
        JsonNode p = it.payload();
        Instant at = a.getCreatedAt() == null ? null : a.getCreatedAt().toInstant();
        String kst = KstFormat.kstDateTime(at);
        String actorId = a.getActorEmail();
        String actorType = actorId != null ? "ADMIN" : "SYSTEM";
        List<Diff> diff = AuditHelpers.payloadDiff(p).stream()
                .map(d -> new Diff(d.field(), AuditHelpers.diffValue(d.from()), AuditHelpers.diffValue(d.to()))).toList();
        return new Ev(String.valueOf(a.getId()), type, AuditHelpers.eventTone(type, null),
                kst.length() > 11 ? kst.substring(11) : kst, KstFormat.timeAgo(at, now), actorType, actorId, actorId == null ? null : "…" + tail(actorId, 10),
                a.getTargetType() == null ? "—" : a.getTargetType(), a.getTargetId(), a.getTargetId() == null ? null : "…" + tail(a.getTargetId(), 12),
                payloadCompact(p, false), diff, AuditHelpers.pretty(p == null ? json.createObjectNode() : p), kst,
                at == null ? "" : AuditHelpers.iso(at), AuditHelpers.eventGroup(type), AuditHelpers.isFailure(type, null),
                at == null ? "" : KstFormat.kstDate(at));
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

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return dflt;
        }
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
