package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.HomeRedirectController;
import com.crosscert.passkey.admin.web.format.ApiKeyExpiry;
import com.crosscert.passkey.admin.web.format.CsvWriter;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.format.Labels;
import com.crosscert.passkey.admin.web.shell.Crumbs;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.Htmx;
import com.crosscert.passkey.admin.web.support.Toasts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tenant 목록과 신규 생성(← pages/TenantsListPage.tsx, api/tenants.ts).
 *
 * <p>화면 구조(이후 화면 task 의 본보기): 전체 페이지는 {@code GET /admin/tenants}(레이아웃 안), 같은 화면의 부분 갱신은
 * {@code GET /admin/_/tenants/list}(레이아웃 없는 조각, 목록 영역 + OOB 로 KPI·필터 컨트롤)다. 두 경로가 같은 조각
 * ({@code admin/tenants/list-rows})을 그려서 결과가 항상 같다. 조각 응답은 {@code HX-Push-Url} 로 주소창을 화면 주소로 바꾼다.
 *
 * <p>검색·필터·페이징은 원본이 클라이언트에서 하던 그대로 서버에서 한다: 정렬은 하지 않고 {@code TenantAdminController.list()} 가
 * 준 순서를 유지하며, 검색은 name(대소문자 무시)·slug 부분 일치다(원본 {@code t.slug.includes(q.toLowerCase())}).
 * 기존 REST 컨트롤러 빈을 Java 메서드로 호출한다(HTTP 아님).
 */
@Slf4j
@Controller
public class TenantsPageController {

    static final String PAGE_VIEW = "admin/tenants/list";
    static final String ROWS_VIEW = "admin/tenants/list-rows :: rows";
    static final String FORM_VIEW = "admin/tenants/new-dialog :: formFields";

    static final String PAGE_PATH = "/admin/tenants";
    static final String LIST_PATH = "/admin/_/tenants/list";
    static final String EXPORT_PATH = "/admin/_/tenants/export";

    /** 원본 PAGE_SIZES. 첫 값이 기본이다. */
    static final List<Integer> PAGE_SIZES = List.of(50, 100);
    static final String ALL = "ALL";
    static final String ACTIVE = "ACTIVE";
    static final String SUSPENDED = "SUSPENDED";

    /** 검색 입력의 id. 조각 요청의 {@code HX-Trigger} 요청 헤더가 이 값이면 사용자가 타이핑 중이라 입력칸을 되돌려 쓰지 않는다. */
    static final String SEARCH_INPUT_ID = "tenant-q";

    // NewTenantDialog 의 정규식 그대로.
    static final Pattern SLUG_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");
    // rpId 는 WebAuthn RP ID — registrable domain(hostname). 스킴/포트/경로 없이 점이 포함된 호스트명. placeholder(example.com) 저장 방지.
    static final Pattern RP_ID_RE = Pattern.compile("^(?!.*\\.example\\.com$)([a-z0-9-]+\\.)+[a-z]{2,}$");
    static final String SLUG_ERROR = "영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.";
    static final String RP_ID_ERROR = "스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com). example.com은 placeholder라 사용할 수 없습니다.";
    static final String NAME_ERROR = "표시 이름을 입력하세요.";
    static final String NAME_LONG_ERROR = "표시 이름은 256자 이하로 입력하세요.";
    static final String RP_ID_LONG_ERROR = "RP ID는 256자 이하로 입력하세요.";

    private static final List<Map<String, Object>> COLUMNS = List.of(
            col("tenant", "Tenant", null, null, null),
            col("slug", "슬러그", null, null, null),
            col("rpId", "RP ID", null, null, null),
            col("credentials", "Credentials", "right", null, null),
            col("apiKeys", "API 키", "right", null, null),
            col("status", "상태", null, null, null),
            col("lastEvent", "마지막 이벤트", null, null, null),
            col("createdAt", "생성일", null, null, null),
            col("open", "열기", null, "40px", true));

    private static Map<String, Object> col(String key, String header, String align, String width, Boolean srOnly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("header", header);
        m.put("align", align);
        m.put("width", width);
        m.put("srOnly", srOnly);
        return m;
    }

    private final TenantAdminController tenants;
    private final ApiKeyAdminController apiKeys;
    private final Validator validator;
    private final Clock clock;

    public TenantsPageController(TenantAdminController tenants, ApiKeyAdminController apiKeys,
                                 Validator validator, Clock clock) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.validator = validator;
        this.clock = clock;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** 화면에 쓰는 Tenant 한 줄(원본 adaptTenant: name=displayName, status 는 ACTIVE 아니면 SUSPENDED). */
    public record TenantRow(String id, String name, String slug, String rpId, String status,
                            long credentials, long apiKeys, OffsetDateTime lastEventAt, OffsetDateTime createdAt) {

        static TenantRow from(TenantAdminDto.TenantView v) {
            String st = v.getStatus();
            return new TenantRow(String.valueOf(v.getId()), v.getDisplayName() == null ? "" : v.getDisplayName(),
                    v.getSlug() == null ? "" : v.getSlug(), v.getRpId(),
                    st != null && st.equalsIgnoreCase(ACTIVE) ? ACTIVE : SUSPENDED,
                    v.getCredentials(), v.getApiKeys(), v.getLastEventAt(), v.getCreatedAt());
        }

        /** {@code t.name.slice(0, 1).toUpperCase()} */
        public String initial() {
            return name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        }

        /** {@code …${tail(id, 8)}} */
        public String idTail() {
            return "…" + (id.length() > 8 ? id.substring(id.length() - 8) : id);
        }

        public String detailUrl() {
            return PAGE_PATH + "/" + id + "?tab=overview";
        }
    }

    /** 신규 생성 폼의 값·필드 오류·실패 안내. 다이얼로그 조각이 이 값으로 그린다. */
    public record NewTenantForm(String name, String slug, String rpId,
                                String nameError, String slugError, String rpIdError, Failure failure) {
        static NewTenantForm empty() {
            return new NewTenantForm("", "", "", null, null, null, null);
        }

        public boolean hasErrors() {
            return nameError != null || slugError != null || rpIdError != null;
        }
    }

    /** 생성 요청이 서버에서 실패했을 때 다이얼로그 안 경고(Alert tone=bad)의 문구. */
    public record Failure(String text, String meta) {}

    // ── 화면 ────────────────────────────────────────────────

    /**
     * 전체 페이지. PLATFORM 전용이다(RequirePlatform): RP_ADMIN 은 {@link HomeRedirectController} 와 같은 규칙으로 자기 테넌트로
     * 보내고, 테넌트가 없는 RP_ADMIN(데이터 이상)은 "계정 구성 오류"를 본다. 목록 조회가 실패하면 200 으로 오류 카드를 그린다
     * (hx-boost 이동은 4xx/5xx 를 스왑하지 않아 오류 화면이 비어 보이므로).
     */
    @GetMapping(PAGE_PATH)
    public String page(@RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "status", defaultValue = ALL) String status,
                       @RequestParam(name = "page", defaultValue = "1") String pageParam,
                       @RequestParam(name = "size", defaultValue = "50") String sizeParam,
                       @RequestParam(name = "new", required = false) String openNew,
                       Model model) {
        CurrentAdmin me = me(model);
        model.addAttribute("breadcrumbs", Crumbs.tenants());
        if (!me.getPlatform()) {
            String home = HomeRedirectController.homePath(me);
            if (!PAGE_PATH.equals(home)) return "redirect:" + home;
            model.addAttribute("configError", true);
            return PAGE_VIEW;
        }
        model.addAttribute("configError", false);
        String st = normStatus(status);
        int sz = normSize(parseInt(sizeParam, PAGE_SIZES.get(0)));
        int page = parseInt(pageParam, 1);

        List<TenantRow> all = null;
        Exception loadError = null;
        try {
            all = loadTenants();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[tenants] list failed: {}", e.toString());
            loadError = e;
        }
        model.addAttribute("loaded", all != null);
        model.addAttribute("loadError", loadError);
        model.addAttribute("kpiDash", "—");
        // 목록 조회 실패 카드의 "다시 시도": 같은 전체 페이지를 다시 받아 본문을 교체한다(주소는 그대로).
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("hx-get", canonicalUrl(q, st, page, sz));
        retry.put("hx-target", "body");
        retry.put("hx-swap", "innerHTML");
        retry.put("hx-push-url", "false");
        model.addAttribute("retryAttrs", retry);
        // 새로 고침·뒤로 가기에서 다시 열리지 않게 new 를 주소에서 지운다(원본 setParams replace). 다이얼로그를 처음부터 열어 둔다.
        boolean open = "1".equals(openNew);
        model.addAttribute("cleanUrl", canonicalUrl(q, st, page, sz));
        model.addAttribute("openNew", open);
        model.addAttribute("ntOpts", open ? Map.of("open", true) : null);
        model.addAttribute("nt", NewTenantForm.empty());
        if (all != null) {
            populate(model, all, q, st, page, sz);
            populateKpis(model, all);
            if (all.isEmpty()) model.addAttribute("kpiActiveAttrs", null); // 빈 목록: 가리킬 영역이 없어 클릭형이 아니다
        }
        return PAGE_VIEW;
    }

    /**
     * 목록 조각(검색·필터·페이징·KPI 클릭). 목록 영역({@code #tenants-results})과 OOB 로 필터 컨트롤·활성 KPI·검색 입력을 돌려준다.
     * 주소창은 {@code HX-Push-Url} 로 화면 주소({@code /admin/tenants?…})가 된다. 조회 실패는 WebExceptionAdvice 의 오류 토스트
     * (본문 없음)라 화면은 그대로 남는다.
     */
    @GetMapping(LIST_PATH)
    public String list(@RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "status", defaultValue = ALL) String status,
                       @RequestParam(name = "page", defaultValue = "1") String pageParam,
                       @RequestParam(name = "size", defaultValue = "50") String sizeParam,
                       @RequestParam(name = "reset", required = false) String reset,
                       HttpServletRequest req, HttpServletResponse res, Model model) throws IOException {
        requirePlatform(model);
        String st = normStatus(status);
        int sz = normSize(parseInt(sizeParam, PAGE_SIZES.get(0)));
        int page = parseInt(pageParam, 1);
        List<TenantRow> all = loadTenants();
        if (all.isEmpty()) {
            // 그 사이 tenant 가 모두 사라졌다 — 빈 상태 화면은 전체 페이지가 그린다.
            Htmx.redirect(req, res, PAGE_PATH);
            return "admin/fragments/shell/empty :: none";
        }
        populate(model, all, q, st, page, sz);
        String url = canonicalUrl(q, st, (Integer) model.getAttribute("page"), sz);
        boolean typing = SEARCH_INPUT_ID.equals(req.getHeader("HX-Trigger"));
        // 디바운스된 검색 요청은 멈출 때마다 이력을 쌓지 않고 현재 항목을 바꾼다.
        res.setHeader(typing ? "HX-Replace-Url" : "HX-Push-Url", url);
        // 검색 입력은 검색어를 지우는 요청(reset=q: 칩 제거·모두 지우기)에서만 OOB 로 되돌려 쓴다.
        // 그 밖의 요청은 현재 입력값(hx-include)을 싣고 오므로 되돌려 쓰면 응답 사이에 친 글자만 지운다.
        model.addAttribute("oobQ", "q".equals(reset) && !typing);
        return ROWS_VIEW;
    }

    /** 필터를 적용한 목록의 CSV(원본 downloadCsv: tenants-{KST 오늘}.csv, 필터 적용 행 전체 — 페이지 무시). */
    @GetMapping(EXPORT_PATH)
    public ResponseEntity<byte[]> export(@RequestParam(name = "q", defaultValue = "") String q,
                                         @RequestParam(name = "status", defaultValue = ALL) String status,
                                         Model model) {
        requirePlatform(model);
        List<TenantRow> rows = filter(loadTenants(), q, normStatus(status));
        List<List<String>> body = new ArrayList<>(rows.size());
        for (TenantRow t : rows) {
            body.add(List.of(t.name(), t.slug(), String.valueOf(t.rpId()), t.status(),
                    String.valueOf(t.credentials()), String.valueOf(t.apiKeys()),
                    t.createdAt() == null ? "" : t.createdAt().toInstant().toString()));
        }
        byte[] csv = CsvWriter.write(List.of("name", "slug", "rpId", "status", "credentials", "apiKeys", "createdAt"), body);
        String filename = "tenants-" + KstFormat.kstToday(clock.instant()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // ── 생성 ────────────────────────────────────────────────

    /**
     * 신규 Tenant({@code TenantAdminController.create}). PLATFORM 전용. 성공이면 새 tenant 의 WebAuthn 설정 탭으로 이동하고
     * (원본 navigate(`/tenants/${id}?tab=webauthn`)) 토스트를 띄운다(HX-Redirect 로 페이지가 바뀌므로 ui.js 가 토스트를 다음 화면에 넘긴다).
     * 입력 검증 오류와 서버 거절은 모두 422 + 다이얼로그 폼 조각이다 — 열린 채 입력값을 유지하고(원본: 오류 토스트 없이 인라인 경고),
     * 서버 거절은 경고 안에 HTTP 상태·trace 를 보인다.
     */
    @PostMapping("/admin/_/tenants")
    public String create(@RequestParam(name = "name", defaultValue = "") String name,
                         @RequestParam(name = "slug", defaultValue = "") String slug,
                         @RequestParam(name = "rpId", defaultValue = "") String rpId,
                         Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) throws IOException {
        requirePlatform(model);
        // 입력 핸들러와 같은 정규화: slug 는 소문자, rpId 는 소문자·trim.
        String slugV = slug.toLowerCase(Locale.ROOT);
        String rpIdV = rpId.toLowerCase(Locale.ROOT).trim();

        String nameError = name.isBlank() ? NAME_ERROR : null;
        String slugError = SLUG_RE.matcher(slugV).matches() ? null : SLUG_ERROR;
        String rpIdError = RP_ID_RE.matcher(rpIdV).matches() ? null : RP_ID_ERROR;
        TenantAdminDto.TenantCreateRequest body = toRequest(name, slugV, rpIdV);
        if (nameError == null && slugError == null && rpIdError == null) {
            // 크기 제한 등 나머지 제약은 REST 와 같은 Bean Validation 으로.
            for (ConstraintViolation<TenantAdminDto.TenantCreateRequest> v : validator.validate(body)) {
                switch (v.getPropertyPath().toString()) {
                    case "displayName", "rpName" -> nameError = NAME_LONG_ERROR;
                    case "slug" -> slugError = SLUG_ERROR;
                    case "rpId" -> rpIdError = RP_ID_LONG_ERROR;
                    default -> { /* 다른 필드는 이 화면이 고정값으로 채운다 */ }
                }
            }
        }
        if (nameError != null || slugError != null || rpIdError != null) {
            return invalid(res, model, new NewTenantForm(name, slugV, rpIdV, nameError, slugError, rpIdError, null));
        }

        TenantAdminDto.TenantView created;
        try {
            created = tenants.create(body, auth).data();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            ErrorMessages.Info info = ErrorMessages.describe(e);
            log.warn("[tenants] create failed: status={} cause={}", info.getHttpStatus(), e.toString());
            return invalid(res, model, new NewTenantForm(name, slugV, rpIdV, null, null, null, failure(e, info)));
        }
        Toasts.successTitled(res, "Tenant 생성 완료", created.getDisplayName() + " · WebAuthn 설정으로 이동합니다");
        Htmx.redirect(req, res, PAGE_PATH + "/" + created.getId() + "?tab=webauthn");
        return null;
    }

    /** NewTenantDialog 의 onCreate → tenantsApi.create 본문과 같은 값. */
    static TenantAdminDto.TenantCreateRequest toRequest(String name, String slug, String rpId) {
        return new TenantAdminDto.TenantCreateRequest(
                slug, name, rpId, name,
                List.of("https://" + rpId),
                new LinkedHashSet<>(List.of("none", "packed")),
                true, false, "NONE", 60000);
    }

    private static String invalid(HttpServletResponse res, Model model, NewTenantForm form) {
        res.setStatus(422);
        model.addAttribute("nt", form);
        return FORM_VIEW;
    }

    private static Failure failure(RuntimeException e, ErrorMessages.Info info) {
        String detail = info.getDetail() == null ? "" : info.getDetail();
        String text = info.getTitle() + ". " + detail + " 입력한 내용은 그대로 있습니다.";
        List<String> meta = new ArrayList<>();
        if (info.getHttpStatus() != null) meta.add("HTTP " + info.getHttpStatus());
        String trace = MDC.get("traceId");
        if (trace != null && !trace.isEmpty()) meta.add("trace " + trace);
        return new Failure(text, String.join(" · ", meta));
    }

    // ── 검색·필터·페이징(원본 TenantsListPage 와 같은 결과) ──────────

    /**
     * 원본 filtered: {@code !q || name.toLowerCase().includes(q.toLowerCase()) || slug.includes(q.toLowerCase())} 와
     * {@code status === 'ALL' || t.status === status}. q 는 trim 하지 않는다. 순서는 입력 그대로.
     */
    static List<TenantRow> filter(List<TenantRow> tenants, String q, String status) {
        String lower = q.toLowerCase(Locale.ROOT);
        List<TenantRow> out = new ArrayList<>();
        for (TenantRow t : tenants) {
            boolean matchesQ = q.isEmpty()
                    || t.name().toLowerCase(Locale.ROOT).contains(lower)
                    || t.slug().contains(lower);
            boolean matchesStatus = ALL.equals(status) || t.status().equals(status);
            if (matchesQ && matchesStatus) out.add(t);
        }
        return out;
    }

    static String normStatus(String s) {
        return ACTIVE.equals(s) || SUSPENDED.equals(s) ? s : ALL;
    }

    /** 숫자가 아닌 page/size 는 400 이 아니라 기본값(원본은 select·버튼으로만 바뀌어 잘못된 값이 없다). */
    static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    static int normSize(int size) {
        return PAGE_SIZES.contains(size) ? size : PAGE_SIZES.get(0);
    }

    private void populate(Model model, List<TenantRow> all, String q, String status, int page, int size) {
        List<TenantRow> filtered = filter(all, q, status);
        int total = filtered.size();
        int last = Math.max(1, (total + size - 1) / size);
        int p = Math.min(Math.max(1, page), last);
        List<TenantRow> pageRows = filtered.subList(Math.min((p - 1) * size, total), Math.min(p * size, total));
        long active = all.stream().filter(t -> ACTIVE.equals(t.status())).count();
        long suspended = all.stream().filter(t -> SUSPENDED.equals(t.status())).count();

        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("page", p);
        model.addAttribute("size", size);
        model.addAttribute("pageSizes", PAGE_SIZES);
        model.addAttribute("rows", pageRows);
        model.addAttribute("total", total);
        model.addAttribute("countAll", all.size());
        model.addAttribute("columns", COLUMNS);
        model.addAttribute("hasFilter", !ALL.equals(status) || !q.isEmpty());
        model.addAttribute("statusLabel", Labels.statusLabel(status));
        model.addAttribute("oobQ", false);
        model.addAttribute("csvUrl", EXPORT_PATH + "?q=" + enc(q) + "&status=" + status);
        model.addAttribute("listBase", LIST_PATH + "?status=" + status + "&size=" + size);

        // 상태 세그먼트 · 활성 KPI · 필터 칩의 htmx 속성. 값은 서버가 정한 주소뿐이다(사용자 입력은 인코딩해 주소 안에만).
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option(ALL, "전체 " + all.size(), listUrl(ALL, 1, size)));
        options.add(option(ACTIVE, "활성 " + active, listUrl(ACTIVE, 1, size)));
        options.add(option(SUSPENDED, "정지 " + suspended, listUrl(SUSPENDED, 1, size)));
        model.addAttribute("statusOptions", options);

        // 활성 KPI 클릭 = ACTIVE 토글(applyStatus(statusFilter === 'ACTIVE' ? 'ALL' : 'ACTIVE')).
        String kpiUrl = listUrl(ACTIVE.equals(status) ? ALL : ACTIVE, 1, size);
        Map<String, Object> kpi = swap(kpiUrl, true);
        kpi.put("id", "tenants-kpi-active");
        kpi.put("hx-sync", "#tenants-card:replace");
        model.addAttribute("kpiActiveAttrs", kpi);
        Map<String, Object> kpiOob = new LinkedHashMap<>(kpi);
        kpiOob.put("hx-swap-oob", "true");
        model.addAttribute("kpiActiveAttrsOob", kpiOob);
        model.addAttribute("kpiActiveValue", fmt(active));
        model.addAttribute("kpiActiveHint", "전체 " + fmt(all.size()) + "건 · 클릭하면 활성만 보기");

        model.addAttribute("chipStatusAttrs", swap(listUrl(ALL, 1, size), true));
        model.addAttribute("chipQAttrs", swap(listUrl(status, 1, size) + "&reset=q", false));
        model.addAttribute("clearAttrs", swap(listUrl(ALL, 1, size) + "&reset=q", false));
    }

    private void populateKpis(Model model, List<TenantRow> all) {
        long creds = all.stream().mapToLong(TenantRow::credentials).sum();
        long keys = all.stream().mapToLong(TenantRow::apiKeys).sum();
        model.addAttribute("kpiCredValue", fmt(creds));
        // 원본의 credentialsDelta7d 는 서버 TenantView 에 없어 항상 미표시("모든 tenant 합산").
        model.addAttribute("kpiCredHint", "모든 tenant 합산");
        model.addAttribute("kpiKeysValue", fmt(keys));
        model.addAttribute("expiring", expiringKeys());
    }

    /** ACTIVE 키 중 30일 이내 만료 수. 키 목록을 못 읽으면 null(보조 줄만 숨김 — 목록 화면은 그대로). */
    private Integer expiringKeys() {
        try {
            List<ApiKeyAdminDto.ApiKeyView> keys = apiKeys.list(null).data();
            java.time.Instant now = clock.instant();
            return (int) keys.stream().filter(k -> ApiKeyExpiry.isExpiringSoon(k.getRevokedAt(), k.getExpiresAt(), now)).count();
        } catch (RuntimeException e) {
            log.warn("[tenants] api key list for expiry KPI failed: {}", e.toString());
            return null;
        }
    }

    private List<TenantRow> loadTenants() {
        return tenants.list().data().stream().map(TenantRow::from).toList();
    }

    // ── 주소·속성 ────────────────────────────────────────────

    private static Map<String, Object> option(String value, String label, String url) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("label", label);
        o.put("title", null);
        o.put("attrs", swap(url, true));
        return o;
    }

    /** 목록 영역을 바꾸는 요청의 공통 속성. 대상 {@code #tenants-results} 를 통째로 교체하고 응답의 OOB 가 컨트롤을 맞춘다. */
    private static Map<String, Object> swap(String url, boolean withQuery) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 검색어는 주소에 굽지 않고 요청 시점의 입력값(#tenant-q)을 싣는다 — 입력 중 눌러도 옛 검색어로 되돌아가지 않는다.
        if (withQuery) m.put("hx-include", "#" + SEARCH_INPUT_ID);
        m.put("hx-get", url);
        m.put("hx-target", "#tenants-results");
        m.put("hx-swap", "outerHTML");
        return m;
    }

    private static String listUrl(String status, int page, int size) {
        return LIST_PATH + "?status=" + status + "&size=" + size + (page > 1 ? "&page=" + page : "");
    }

    /** 주소창에 남길 화면 주소: 기본값(전체·1쪽·50건·빈 검색어)은 생략한다. */
    static String canonicalUrl(String q, String status, int page, int size) {
        List<String> params = new ArrayList<>();
        if (!q.isEmpty()) params.add("q=" + enc(q));
        if (!ALL.equals(status)) params.add("status=" + status);
        if (page > 1) params.add("page=" + page);
        if (size != PAGE_SIZES.get(0)) params.add("size=" + size);
        return params.isEmpty() ? PAGE_PATH : PAGE_PATH + "?" + String.join("&", params);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    private static CurrentAdmin me(Model model) {
        if (!(model.getAttribute("me") instanceof CurrentAdmin me)) {
            throw new AccessDeniedException("no authenticated admin");
        }
        return me;
    }

    /** 조각·변경 엔드포인트는 PLATFORM 전용(원본은 RequirePlatform 이 화면째 막았다). 보안 경계는 REST 의 @PreAuthorize 도 함께. */
    private static void requirePlatform(Model model) {
        if (!me(model).getPlatform()) throw new AccessDeniedException("PLATFORM_OPERATOR only");
    }
}
