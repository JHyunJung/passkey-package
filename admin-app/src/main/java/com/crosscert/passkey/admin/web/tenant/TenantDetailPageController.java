package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.activity.ActivityController;
import com.crosscert.passkey.admin.activity.ActivityView;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditLogController;
import com.crosscert.passkey.admin.funnel.FunnelController;
import com.crosscert.passkey.admin.funnel.FunnelDto;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.policy.AaguidPolicyDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.ApiKeyExpiry;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.Josa;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.shell.Crumbs;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.Htmx;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant 상세 틀과 개요 탭(← pages/TenantDetailPage.tsx, pages/tenant/TenantOverview.tsx, tenantSignals.ts, recentActivityAdapter.ts).
 *
 * <p>전체 페이지 {@code GET /admin/tenants/{idOrSlug}?tab=} 는 헤더·탭 줄·개요 탭을 서버에서 그린다(R3'). 다른 탭이 골라지면 탭 컨테이너가
 * {@code hx-trigger="load"} 로 {@code GET /admin/_/tenants/{id}/tab/{tab}} 를 불러온다. 이 일반 매핑은 개요를 돌려주고 모르는 탭 키도 개요로
 * 떨어진다. 이후 탭 task 는 같은 URL 규약의 <b>리터럴 경로</b>({@code /admin/_/tenants/{id}/tab/webauthn} 등)를 각자의 컨트롤러에 매핑한다 —
 * Spring 은 {@code {tab}} 변수 패턴보다 리터럴 패턴을 우선하므로 이 매핑이 막지 않는다(테스트 {@code literalTabMappingWinsOverGeneric}).
 *
 * <p>접근 경계: 테넌트는 {@code TenantAdminController.get} 으로 읽는다(서비스가 TenantBoundary 를 적용). RP_ADMIN 이 다른 테넌트를 열면
 * 403({@code WebExceptionAdvice}: 직접 접근은 오류 화면, htmx 는 토스트 + 본문 없음)이며 어떤 데이터도 그리지 않는다.
 * 정지·정지 해제는 PLATFORM 전용이다 — REST 메서드의 {@code @PreAuthorize} 는 Spring 프록시를 거치는 호출이라 직접 호출에도 적용되지만,
 * 그 기대에 기대지 않고 여기서도 역할을 검사한다.
 */
@Slf4j
@Controller
public class TenantDetailPageController {

    static final String PAGE_VIEW = "admin/tenant/detail";
    static final String OVERVIEW_VIEW = "admin/tenant/overview :: content";
    static final String CHAIN_VIEW = "admin/tenant/overview :: chainCard";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    static final String PAGE_BASE = "/admin/tenants/";
    static final String FRAG_BASE = "/admin/_/tenants/";

    /** TenantDetailPage.tsx 의 TENANT_TABS (id, 라벨). 첫 항목이 기본이다. */
    static final List<String[]> TABS = List.of(
            new String[] {"overview", "개요"},
            new String[] {"webauthn", "WebAuthn"},
            new String[] {"aaguid", "AAGUID 정책"},
            new String[] {"apikeys", "API 키"},
            new String[] {"credentials", "Credentials"},
            new String[] {"audit", "감사 로그"},
            new String[] {"funnel", "인증 현황"});

    /** 체인 검증 응답을 이 시간(초)까지 기다린 뒤 "확인 불가"로 바꾼다(CHAIN_TIMEOUT_MS = 15_000). 템플릿의 hx-request 와 같은 값. */
    static final int CHAIN_TIMEOUT_SECONDS = 15;

    private static final Pattern FAILURE_TYPE = Pattern.compile("FAIL|REVOKED|REGRESSION|TAMPER");
    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);

    private final TenantAdminController tenants;
    private final FunnelController funnel;
    private final ActivityController activity;
    private final AuditLogController audit;
    private final AuditChainMonitorController chain;
    private final ApiKeyAdminController apiKeys;
    private final AaguidPolicyController aaguid;
    private final Clock clock;

    public TenantDetailPageController(TenantAdminController tenants, FunnelController funnel, ActivityController activity,
                                      AuditLogController audit, AuditChainMonitorController chain,
                                      ApiKeyAdminController apiKeys, AaguidPolicyController aaguid, Clock clock) {
        this.tenants = tenants;
        this.funnel = funnel;
        this.activity = activity;
        this.audit = audit;
        this.chain = chain;
        this.apiKeys = apiKeys;
        this.aaguid = aaguid;
        this.clock = clock;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** 화면에 쓰는 Tenant(원본 adaptTenant: name=displayName, status 는 ACTIVE 아니면 SUSPENDED). */
    public record TenantDetail(String id, String name, String slug, String rpId, String status,
                               long credentials, long apiKeys, OffsetDateTime createdAt,
                               List<String> origins, String userVerification, String attestation, int timeoutMs) {

        static TenantDetail from(TenantAdminDto.TenantView v) {
            String st = v.status();
            return new TenantDetail(String.valueOf(v.id()), v.displayName() == null ? "" : v.displayName(),
                    v.slug() == null ? "" : v.slug(), v.rpId(),
                    st != null && st.equalsIgnoreCase("ACTIVE") ? "ACTIVE" : "SUSPENDED",
                    v.credentials(), v.apiKeys(), v.createdAt(),
                    v.allowedOrigins(), v.requireUserVerification() ? "REQUIRED" : "PREFERRED",
                    v.attestationConveyance(), v.webauthnTimeoutMs());
        }

        /** {@code tenant.name.slice(0, 1).toUpperCase()} */
        public String initial() {
            return name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        /** 헤더 "RP 사이트 열기" 주소. rpId 가 없으면 null. */
        public String rpSiteUrl() {
            return rpId == null || rpId.isEmpty() ? null : "https://" + rpId;
        }

        /** fmtDateKst */
        public String createdKst() {
            return KstFormat.kstDate(createdAt);
        }

        /** 정지 확인 제목: {@code ${name}${eulReul(name)} 정지할까요?} */
        public String suspendTitle() {
            return name + Josa.eulReul(name) + " 정지할까요?";
        }
    }

    /** 개요 KPI 한 칸. */
    public record Kpi(String value, String hint, String tone) {}

    /** 최근 활동 한 줄. */
    public record RecentEvent(String type, String subject, String ago, String color) {}

    /** 감사 체인 카드 상태: checking | done | unavailable. */
    public record ChainView(String state, String reason, boolean intact, String ago, String tamperedTail) {
        static ChainView checking() {
            return new ChainView("checking", "", true, "", null);
        }

        static ChainView unavailable(String reason) {
            return new ChainView("unavailable", reason, true, "", null);
        }
    }

    // ── 화면 ────────────────────────────────────────────────

    /**
     * 전체 페이지. 로드 실패는 boost 이동이 4xx/5xx 를 스왑하지 않으므로(htmx 규칙 A) htmx 요청이면 200 + 안내 카드로 그린다
     * (Task 8 과 같은 규칙). 직접 접근이면 실제 상태(404)를 쓴다. 403 은 그대로 {@code WebExceptionAdvice} 가 맡는다.
     */
    @GetMapping(PAGE_BASE + "{idOrSlug}")
    public String page(@PathVariable String idOrSlug,
                       @RequestParam(name = "tab", required = false) String tab,
                       HttpServletRequest req, HttpServletResponse res, Model model) {
        CurrentAdmin me = me(model);
        String requested = tab == null || tab.isEmpty() ? "overview" : tab;
        String active = knownTab(requested) ? requested : "overview";
        model.addAttribute("platform", me.platform());
        model.addAttribute("notFound", false);
        model.addAttribute("loadError", null);
        model.addAttribute("t", null);

        TenantDetail t;
        try {
            t = load(idOrSlug);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.TENANT_NOT_FOUND) {
                model.addAttribute("breadcrumbs", Crumbs.tenant(me.platform(), idOrSlug, null, active));
                model.addAttribute("notFound", true);
                res.setStatus(Htmx.isHtmx(req) ? HttpServletResponse.SC_OK : HttpServletResponse.SC_NOT_FOUND);
                return PAGE_VIEW;
            }
            return loadFailed(e, idOrSlug, tab, active, me, req, res, model);
        } catch (RuntimeException e) {
            return loadFailed(e, idOrSlug, tab, active, me, req, res, model);
        }

        model.addAttribute("t", t);
        model.addAttribute("breadcrumbs", Crumbs.tenant(me.platform(), t.id(), t.name(), active));
        model.addAttribute("activeTab", active);
        model.addAttribute("tabUrl", FRAG_BASE + t.id() + "/tab/" + active);
        model.addAttribute("tabAttrsLoad", Map.of(
                "hx-get", FRAG_BASE + t.id() + "/tab/" + active,
                "hx-trigger", "load",
                "hx-swap", "innerHTML",
                "hx-sync", TenantTabs.SYNC));
        model.addAttribute("tabLazy", !"overview".equals(active));
        UUID tid = UUID.fromString(t.id());
        Integer expiring = expiringKeys(tid);
        Integer unverified = unverifiedAaguid(tid);
        model.addAttribute("tabs", tabItems(t.id(), unverified, expiring));
        if (me.platform()) {
            model.addAttribute("suspendAttrs", Map.of("hx-post", FRAG_BASE + t.id() + "/suspend", "hx-swap", "none"));
            model.addAttribute("activateAttrs", Map.of("hx-post", FRAG_BASE + t.id() + "/activate", "hx-swap", "none"));
            model.addAttribute("menuSuspend", List.of(menuItem("테넌트 정지", "suspend-tenant", true)));
            model.addAttribute("menuActivate", List.of(menuItem("정지 해제", "activate-tenant", false)));
        }
        if (!"overview".equals(active)) return PAGE_VIEW; // 다른 탭의 내용은 탭 조각이 읽는다(개요 데이터를 미리 읽지 않는다)
        populateOverview(model, t, me, expiring);
        return PAGE_VIEW;
    }

    /**
     * 404 가 아닌 모든 조회 실패(403·5xx 포함)는 TSX 처럼 인라인 LoadError 로 그린다(데이터 없음, 다시 시도). 문구는 {@code ErrorMessages}
     * (알 수 없는 예외의 원문은 싣지 않는다). 상태: 직접 접근의 403 은 403, 그 밖에는 200(boost 가 4xx/5xx 를 스왑하지 않으므로).
     */
    private String loadFailed(RuntimeException e, String idOrSlug, String tab, String active, CurrentAdmin me,
                              HttpServletRequest req, HttpServletResponse res, Model model) {
        log.warn("[tenant-detail] load failed: {}", e.toString());
        Integer status = ErrorMessages.describe(e).httpStatus();
        if (status != null && status == HttpServletResponse.SC_FORBIDDEN && !Htmx.isHtmx(req)) res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        model.addAttribute("breadcrumbs", Crumbs.tenant(me.platform(), idOrSlug, null, active));
        model.addAttribute("loadError", e);
        String url = PAGE_BASE + UriUtils.encodePathSegment(idOrSlug, StandardCharsets.UTF_8) + (tab == null ? "" : "?tab=" + active);
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("hx-get", url);
        retry.put("hx-target", "body");
        retry.put("hx-swap", "innerHTML");
        retry.put("hx-push-url", "false");
        model.addAttribute("retryAttrs", retry);
        return PAGE_VIEW;
    }

    /**
     * 탭 조각. 이 일반 매핑은 개요를 돌려주고 모르는 탭 키도 개요로 떨어진다. 다른 탭 task 가 같은 URL 의 리터럴 경로를 각자 매핑한다.
     * 조회 실패(403·404·5xx)는 {@code WebExceptionAdvice} 의 실제 상태 + 토스트(본문 없음) — 데이터가 노출되지 않는다.
     */
    @GetMapping(FRAG_BASE + "{id}/tab/{tab}")
    public String tab(@PathVariable String id, @PathVariable String tab, Model model) {
        CurrentAdmin me = me(model);
        TenantDetail t = load(id);
        model.addAttribute("platform", me.platform());
        populateOverview(model, t, me, expiringKeys(UUID.fromString(t.id())));
        return OVERVIEW_VIEW;
    }

    /**
     * 감사 체인 카드(PLATFORM 전용: verifyTenant 는 PLATFORM API). 자동 검증은 실패해도 200 + "확인 불가" 카드, 수동 검증({@code manual=1})은
     * 실패하면 실제 오류 상태 + 오류 토스트(본문 없음)라 이전 결과가 그대로 남는다.
     */
    @GetMapping(FRAG_BASE + "{id}/overview/chain")
    public String chainCard(@PathVariable String id, @RequestParam(name = "manual", required = false) String manual,
                            HttpServletResponse res, Model model) {
        CurrentAdmin me = me(model);
        requirePlatform(me);
        TenantDetail t = load(id);
        boolean isManual = "1".equals(manual);
        try {
            AuditChainMonitorController.TenantVerifyResponse r = chain.verifyTenant(t.id());
            Instant at = r.verifiedAt();
            ChainView v = new ChainView("done", "", r.intact(), at == null ? "—" : KstFormat.timeAgo(at, clock.instant()),
                    r.tamperedEntryId() == null ? null : tail(r.tamperedEntryId().toString(), 8));
            model.addAttribute("chainView", v);
            if (isManual) {
                if (r.intact()) Toasts.successTitled(res, "수동 검증 완료", "체인 무결");
                else Toasts.warnTitled(res, "수동 검증 완료", "위변조 감지");
            }
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[tenant-detail] chain verify failed (manual={}): {}", isManual, e.toString());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            if (isManual) {
                int status = info.httpStatus() == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : info.httpStatus();
                Toasts.errorTitled(res, "수동 검증 실패", info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
                res.setStatus(status);
                return EMPTY_VIEW;
            }
            model.addAttribute("chainView", ChainView.unavailable(info.title() + "."));
        }
        populateChainModel(model, t);
        return CHAIN_VIEW;
    }

    /** 정지(L3 확인 뒤). PLATFORM 전용 — {@code TenantAdminController.suspend}. 성공하면 같은 탭으로 다시 그린다(원본 onReload). */
    @PostMapping(FRAG_BASE + "{id}/suspend")
    public String suspend(@PathVariable String id,
                          @RequestHeader(name = "HX-Current-URL", required = false) String currentUrl,
                          Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) throws IOException {
        requirePlatform(me(model));
        TenantDetail t = load(id);
        tenants.suspend(t.id(), auth);
        Toasts.warnTitled(res, "테넌트가 정지되었습니다.", t.slug() + " · 모든 API 키 회수됨");
        Htmx.redirect(req, res, PAGE_BASE + t.id() + "?tab=" + tabFrom(currentUrl));
        return EMPTY_VIEW;
    }

    /** 정지 해제(L1 확인 뒤). PLATFORM 전용 — {@code TenantAdminController.activate}. */
    @PostMapping(FRAG_BASE + "{id}/activate")
    public String activate(@PathVariable String id,
                           @RequestHeader(name = "HX-Current-URL", required = false) String currentUrl,
                           Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) throws IOException {
        requirePlatform(me(model));
        TenantDetail t = load(id);
        tenants.activate(t.id(), auth);
        Toasts.successTitled(res, "정지 해제됨", "");
        Htmx.redirect(req, res, PAGE_BASE + t.id() + "?tab=" + tabFrom(currentUrl));
        return EMPTY_VIEW;
    }

    // ── 개요 ────────────────────────────────────────────────

    private void populateOverview(Model model, TenantDetail t, CurrentAdmin me, Integer expiring) {
        UUID tid = UUID.fromString(t.id());
        model.addAttribute("t", t);
        model.addAttribute("platform", me.platform());
        model.addAttribute("credKpi", new Kpi(fmt(t.credentials()), null, null));
        // 7일 증감(credentialsDelta7d)은 서버 TenantView 에 없어 원본도 항상 미표시다.
        model.addAttribute("keysKpi", new Kpi(String.valueOf(t.apiKeys()),
                expiring == null ? null : "만료 임박 " + expiring, expiring != null && expiring > 0 ? "warn" : "default"));

        // 퍼널: 실패는 "—" + 원인, 로딩은 없다(서버 렌더). "시도 없음"은 조회가 성공했을 때만.
        FunnelDto.View f = null;
        Exception funnelError = null;
        try {
            f = funnel.get(tid, 7);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[tenant-detail] funnel failed: {}", e.toString());
            funnelError = e;
        }
        model.addAttribute("funnelError", funnelError);
        model.addAttribute("regKpi", rateKpi(f, funnelError, true));
        model.addAttribute("authKpi", rateKpi(f, funnelError, false));
        Map<String, Object> retry = TenantTabs.requesterAttrs(FRAG_BASE + t.id() + "/tab/overview");
        model.addAttribute("funnelRetry", retry);

        model.addAttribute("events", recentEvents(tid, me));
        model.addAttribute("allEventsUrl", me.platform() ? "/admin/activity?tenantId=" + t.id() : PAGE_BASE + t.id() + "?tab=audit");
        model.addAttribute("webauthnUrl", PAGE_BASE + t.id() + "?tab=webauthn");
        model.addAttribute("auditUrl", PAGE_BASE + t.id() + "?tab=audit");
        if (me.platform()) {
            // 첫 화면은 "확인 중" 카드 + load 트리거(검증은 느릴 수 있다). 실제 검증은 chainCard 가 한다.
            model.addAttribute("chainView", ChainView.checking());
            populateChainModel(model, t);
        }
    }

    private void populateChainModel(Model model, TenantDetail t) {
        model.addAttribute("t", t);
        model.addAttribute("chainUrl", FRAG_BASE + t.id() + "/overview/chain");
        model.addAttribute("auditUrl", PAGE_BASE + t.id() + "?tab=audit");
        model.addAttribute("chainTimeoutText", CHAIN_TIMEOUT_SECONDS + "초 안에 응답이 없었습니다.");
    }

    private Kpi rateKpi(FunnelDto.View f, Exception error, boolean registration) {
        String none = registration ? "최근 7일 등록 시도 없음" : "최근 7일 인증 시도 없음";
        if (error != null) return new Kpi("—", "불러오지 못했습니다", "warn");
        FunnelDto.Stage s = registration ? f.registration() : f.authentication();
        if (s.attempts() > 0) {
            return new Kpi(String.format(Locale.ROOT, "%.1f%%", s.ratio() * 100), fmt(s.success()) + " / " + fmt(s.attempts()) + " 시도", "default");
        }
        return new Kpi("—", none, "default");
    }

    /**
     * 최근 5건. PLATFORM: 활동 피드(PLATFORM 전용) 중 이 tenant 만 — 원본은 전체 피드를 받아 클라이언트에서 걸렀지만 REST 가
     * tenantId 필터를 지원하므로 서버에서 건다(이 tenant 의 이벤트가 전체 피드의 최근 N건 밖이어도 보인다).
     * RP_ADMIN: 이 tenant 의 감사 로그. 실패는 빈 목록(원본 catch).
     */
    private List<RecentEvent> recentEvents(UUID tid, CurrentAdmin me) {
        Instant now = clock.instant();
        List<RecentEvent> out = new ArrayList<>();
        try {
            if (me.platform()) {
                ActivityView view = activity.activity(null, null, null, null, tid).data();
                for (ActivityView.Event e : view.feed()) {
                    if (!tid.equals(e.tenantId())) continue;
                    out.add(event(e.action(), e.targetId(), e.createdAt(), now));
                    if (out.size() == 5) break;
                }
            } else {
                for (AuditLogController.AuditLogView e : audit.list(null, null, tid, null, null, 0, 5).data()) {
                    out.add(event(e.action(), e.targetId(), e.createdAt(), now));
                    if (out.size() == 5) break;
                }
            }
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[tenant-detail] recent events failed: {}", e.toString());
            return List.of();
        }
        return out;
    }

    private static RecentEvent event(String type, String subjectId, OffsetDateTime at, Instant now) {
        String t = type == null ? "" : type;
        String subject = subjectId == null || subjectId.isEmpty() ? "—" : subjectId;
        return new RecentEvent(t, "…" + tail(subject, 16), at == null ? "—" : KstFormat.timeAgo(at.toInstant(), now), eventColor(t));
    }

    /** 이벤트 유형 점 색. 유형 이름 태그가 함께 표시되므로 색만으로 의미를 전달하지 않는다. */
    static String eventColor(String type) {
        if (FAILURE_TYPE.matcher(type).find()) return "var(--bad)";
        if (type.startsWith("API_KEY") || type.endsWith("_UPDATED")) return "var(--warn-dot)";
        return "var(--brand-700)";
    }

    // ── 탭 신호(tenantSignals.ts) ─────────────────────────

    /** 30일 안에 만료되는 ACTIVE 키 수. 조회 실패는 null(원본: 조용히 무시). */
    private Integer expiringKeys(UUID tid) {
        try {
            Instant now = clock.instant();
            List<ApiKeyAdminDto.ApiKeyView> keys = apiKeys.list(tid.toString()).data();
            return (int) keys.stream().filter(k -> ApiKeyExpiry.isExpiringSoon(k.revokedAt(), k.expiresAt(), now)).count();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** mode≠ANY 이고 mdsName==null 인 AAGUID 수(목록에 보이는 항목만). 조회 실패는 null. */
    private Integer unverifiedAaguid(UUID tid) {
        try {
            AaguidPolicyDto.View p = aaguid.get(tid);
            if (p == null || p.mode() == null || "ANY".equals(p.mode().name())) return 0;
            return (int) p.entries().stream().filter(e -> e.aaguid() != null && e.mdsName() == null).count();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Map<String, Object>> tabItems(String tenantId, Integer unverified, Integer expiring) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String[] def : TABS) {
            String id = def[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", def[1]);
            m.put("href", PAGE_BASE + tenantId + "?tab=" + id);
            m.put("elId", "tenant-tab-" + id);
            // 탭 클릭 = 조각 교체 + 주소 갱신. 활성 표시·마지막 브레드크럼은 ui.js(htmx:afterRequest)가 data-* 로 바꾼다.
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("hx-get", FRAG_BASE + tenantId + "/tab/" + id);
            attrs.put("hx-target", "#" + TenantTabs.TARGET_ID);
            attrs.put("hx-swap", "innerHTML");
            attrs.put("hx-push-url", PAGE_BASE + tenantId + "?tab=" + id);
            attrs.put("hx-sync", TenantTabs.SYNC);
            attrs.put("data-guard-dirty", "true"); // 저장 안 한 변경이 있으면 app.js 가 요청을 취소하고 이동 확인을 띄운다
            attrs.put("data-tab", id);
            attrs.put("data-label", def[1]);
            m.put("attrs", attrs);
            if ("aaguid".equals(id) && unverified != null && unverified > 0) {
                m.put("badgeText", "확인 필요 " + unverified);
                m.put("badgeReason", "MDS 미확인 AAGUID " + unverified + "건");
            }
            if ("apikeys".equals(id) && expiring != null && expiring > 0) {
                m.put("badgeText", "확인 필요 " + expiring);
                m.put("badgeReason", "30일 안에 만료되는 키 " + expiring + "개");
            }
            items.add(m);
        }
        return items;
    }

    /** 더보기 메뉴 항목. 클릭하면 확인 다이얼로그를 연다(메뉴 자체의 click 처리는 그대로 두려고 .capture 로 따로 붙인다). */
    private static Map<String, Object> menuItem(String label, String dialogId, boolean danger) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("danger", danger);
        m.put("attrs", Map.of("x-on:click.capture", "$dispatch('dialog:open', { id: '" + dialogId + "' })"));
        return m;
    }

    // ── 도우미 ──────────────────────────────────────────────

    private TenantDetail load(String idOrSlug) {
        return TenantDetail.from(TenantTabs.load(tenants, idOrSlug));
    }

    static boolean knownTab(String key) {
        return TABS.stream().anyMatch(t -> t[0].equals(key));
    }

    /** 다이얼로그를 연 화면의 탭(HX-Current-URL 의 tab). 모르는 값이면 개요. */
    static String tabFrom(String currentUrl) {
        if (currentUrl == null) return "overview";
        try {
            String tab = UriComponentsBuilder.fromUriString(currentUrl).build().getQueryParams().getFirst("tab");
            return tab != null && knownTab(tab) ? tab : "overview";
        } catch (RuntimeException e) {
            return "overview";
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String fmt(long n) {
        return INT.format(n);
    }

    private static void requirePlatform(CurrentAdmin me) {
        if (!me.platform()) throw new AccessDeniedException("platform operator only");
    }

    private static CurrentAdmin me(Model model) {
        if (!(model.getAttribute("me") instanceof CurrentAdmin me)) {
            throw new AccessDeniedException("no authenticated admin");
        }
        return me;
    }
}
