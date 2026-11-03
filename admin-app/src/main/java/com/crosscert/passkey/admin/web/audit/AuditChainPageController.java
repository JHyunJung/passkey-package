package com.crosscert.passkey.admin.web.audit;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditChainOverview;
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview;
import com.crosscert.passkey.admin.audit.IncidentConflictException;
import com.crosscert.passkey.admin.audit.IncidentDto;
import com.crosscert.passkey.admin.audit.IncidentNotFoundException;
import com.crosscert.passkey.admin.audit.IncidentNotTamperedException;
import com.crosscert.passkey.admin.web.HomeRedirectController;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.format.Labels;
import com.crosscert.passkey.admin.web.shell.Crumbs;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 감사 체인 화면(← pages/AuditChainPage.tsx). PLATFORM 전용(App.tsx 의 RequirePlatform) — 페이지와 모든 조각·동작이 서버에서도 막는다.
 *
 * <ul>
 *   <li>페이지({@code GET /admin/audit-chain})는 골격(스켈레톤)만 보내고 {@code #chain-root} 가 load 로 개요를 읽는다. 개요 호출은 서버가 전 tenant 체인을
 *       검증하는 긴 작업이라(원본도 같은 순서: 로딩 → 결과) 페이지 이동 자체를 막지 않는다.</li>
 *   <li>개요·검증·백필·사건 변경은 모두 {@code #chain-root} 를 통째로 다시 그려 돌려준다(원본의 reverify). 요청은 {@code hx-sync="#chain-root:drop"} 라
 *       더블 클릭·동시 실행이 중복되지 않는다. 타임아웃은 원본 LONG_TIMEOUT_MS(120s).</li>
 *   <li>재검증이 실패하면 원본처럼 이전 결과를 남기고(교체하지 않음) 경고만 보인다: 200/422 + {@code HX-Retarget #chain-warn}. 처음 불러오는 중의 실패는
 *       오류 카드(LoadError, 422)다.</li>
 *   <li>백필(L3 확인, X-Confirm): 원본 runBackfill 의 가드 — 가장 최근 검증이 성공했고 위변조가 없을 때만 — 를 서버에서도 지킨다(요청마다 개요를 다시 검증해
 *       확인한 뒤에만 {@code backfill()}). 동시 실행 직렬화는 서비스의 DB 락이 한다.</li>
 *   <li>사건 생성·해결: 컨트롤러 {@code @ExceptionHandler} 가 직접 호출에는 적용되지 않으므로 여기서 잡아 원본 문구로 바꾼다
 *       (409 → 경고 토스트 + 목록 새로 그림, 422/404/그 밖의 실패 → 다이얼로그 안 인라인, 토스트 없음).</li>
 *   <li>사용자 영향 값(tenant 이름·해결 메모·이메일)은 템플릿에서 th:text 로만 그린다.</li>
 * </ul>
 */
@Slf4j
@Controller
public class AuditChainPageController {

    static final String PAGE_PATH = "/admin/audit-chain";
    static final String BASE = "/admin/_/audit-chain";
    static final String OVERVIEW_PATH = BASE + "/overview";
    static final String VERIFY_PATH = BASE + "/verify";
    static final String BACKFILL_PATH = BASE + "/backfill";
    static final String INCIDENTS_PATH = BASE + "/incidents";
    static final String RESOLVE_PATH = BASE + "/incidents/resolve";
    static final String REPORT_API = "/admin/api/audit/chain/monthly-report";

    static final String PAGE_VIEW = "admin/audit-chain/page";
    static final String ROOT_VIEW = "admin/audit-chain/overview :: root";
    static final String FAILED_VIEW = "admin/audit-chain/overview :: loadFailed";
    static final String WARN_VIEW = "admin/audit-chain/overview :: warn";
    static final String ISSUE_VIEW = "admin/audit-chain/incidents :: issue";
    static final String REFRESH_VIEW = "admin/audit-chain/incidents :: refresh";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    /** AuditChainPage 의 {@code overview(24)}. */
    static final int WINDOW_HOURS = 24;
    /** 원본 LONG_TIMEOUT_MS. 템플릿의 hx-request timeout 이 이 값에서 나온다. */
    static final int LONG_TIMEOUT_MS = 120_000;

    static final String BLOCK_TAMPERED = "위변조 의심 tenant가 있으면 쓸 수 없습니다. 먼저 조사·해결하세요.";
    static final String BLOCK_FAILED = "최근 검증에 실패했습니다. 다시 검증해 정상임을 확인한 뒤 쓸 수 있습니다.";

    private final AuditChainMonitorController chain;
    private final Clock clock;
    private final jakarta.validation.Validator validator;

    public AuditChainPageController(AuditChainMonitorController chain, Clock clock, jakarta.validation.Validator validator) {
        this.chain = chain;
        this.clock = clock;
        this.validator = validator;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** Tenant 별 체인 표의 한 줄. attrs 는 "검증" 버튼의 요청 속성(플랫폼 체인은 null — 열기·검증 비활성). */
    public record Row(String tenantId, String name, String initial, boolean intact, String rows, String openHref, Map<String, Object> verifyAttrs) {}

    /** 위변조 배너의 한 줄. */
    public record Tampered(String tenantId, String name, String entry, String auditHref, boolean hasOpen, boolean canCreate, String createId, String createTitle,
                           Map<String, Object> createAttrs) {}

    public record Incident(String id, boolean open, String statusLabel, String tenant, String type, String created, String createdBy, String label) {}

    public record ReportTenant(String name, String rows, boolean intact) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(PAGE_PATH)
    public String page(Model model) {
        CurrentAdmin me = me(model);
        model.addAttribute("breadcrumbs", Crumbs.auditChain());
        if (!me.platform()) return "redirect:" + HomeRedirectController.homePath(me);
        model.addAttribute("loadAttrs", rootReq("hx-get", OVERVIEW_PATH + "?first=1", true));
        return PAGE_VIEW;
    }

    /** 개요 + 사건 목록. first=1 이면 처음 불러오는 중(실패 = 오류 카드), 아니면 다시 검증(실패 = 이전 결과 유지 + 경고). view 는 표 필터 보존. */
    @GetMapping(OVERVIEW_PATH)
    public String overview(@RequestParam(name = "first", defaultValue = "") String first,
                           @RequestParam(name = "view", defaultValue = "all") String view,
                           HttpServletResponse res, Model model) {
        requirePlatform(model);
        return renderRoot(model, res, view, "1".equals(first), false);
    }

    /** 한 tenant 검증(원본 handleVerifyTenant): 결과 토스트 + 재검증(reverify). */
    @GetMapping(VERIFY_PATH)
    public String verify(@RequestParam(name = "tenantId", defaultValue = "") String tenantId,
                         @RequestParam(name = "view", defaultValue = "all") String view,
                         HttpServletResponse res, Model model) {
        requirePlatform(model);
        UUID tid = parseUuid(tenantId);
        if (tid == null) throw new IllegalArgumentException("invalid tenantId");
        try {
            AuditChainMonitorController.TenantVerifyResponse r = chain.verifyTenant(tid.toString());
            if (r.intact()) {
                Toasts.successTitled(res, "검증 완료", "chain 이 정상입니다.");
            } else {
                Toasts.errorTitled(res, "위변조 의심", r.tamperedEntryId() != null ? "불일치 entry " + r.tamperedEntryId() : "hash 불일치", null, null);
            }
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-chain] verify failed: {}", e.getClass().getSimpleName());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            int status = status(info);
            Toasts.errorTitled(res, "검증하지 못했습니다", info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
            res.setStatus(status);
            return EMPTY_VIEW;
        }
        return renderRoot(model, res, view, false, true);
    }

    /**
     * 체인 백필(L3 확인 — X-Confirm 필수). 가드: 가장 최근 검증이 성공했고 위변조가 없을 때만 실행한다(원본 backfillAllowed). 서버는 요청마다 전 tenant 를
     * 다시 검증해 그것을 확인한다 — 위변조·검증 실패면 {@code backfill()} 을 부르지 않고 409 + confirmError(다이얼로그 안 인라인).
     */
    @PostMapping(BACKFILL_PATH)
    public String backfill(@RequestParam(name = "view", defaultValue = "all") String view,
                           HttpServletRequest req, HttpServletResponse res, Model model) {
        requirePlatform(model);
        if (!Toasts.isConfirm(req)) throw new IllegalArgumentException("confirmation required");
        AuditChainOverview ov;
        try {
            ov = chain.overview(WINDOW_HOURS);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-chain] backfill guard verify failed: {}", e.getClass().getSimpleName());
            return blocked(res, BLOCK_FAILED);
        }
        if (hasTampering(ov)) return blocked(res, BLOCK_TAMPERED);
        AuditChainMonitorController.BackfillResponse r = chain.backfill(); // 실패는 WebExceptionAdvice 가 confirmError 로
        Toasts.successTitled(res, "백필 완료", "tenant " + fmt(r.tenantsProcessed()) + "개 처리 · 행 " + fmt(r.rowsUpdated()) + "개 갱신");
        return renderRoot(model, res, view, false, true);
    }

    private String blocked(HttpServletResponse res, String message) {
        Toasts.confirmError(res, message, null, MDC.get("traceId"));
        res.setStatus(HttpServletResponse.SC_CONFLICT);
        return EMPTY_VIEW;
    }

    /** 사건 목록만 다시 읽기(원본 refreshIncidents) — 카드·배너 배지만 OOB 로 갱신한다. 개요(전 tenant 검증)는 다시 돌리지 않는다. */
    @GetMapping(INCIDENTS_PATH)
    public String incidents(HttpServletResponse res, Model model) {
        requirePlatform(model);
        return refresh(model, res);
    }

    /** 사건 생성(원본 handleCreateIncident). 서버가 위변조 재검증 결과에서 entry 를 도출한다 — 요청에는 tenantId 만 싣는다. */
    @PostMapping(INCIDENTS_PATH)
    public String createIncident(@RequestParam(name = "tenantId", defaultValue = "") String tenantId,
                                 @RequestParam(name = "view", defaultValue = "all") String view,
                                 Authentication auth, HttpServletResponse res, Model model) {
        requirePlatform(model);
        UUID tid = parseUuid(tenantId);
        if (tid == null) throw new IllegalArgumentException("invalid tenantId");
        String slot = "chain-ic-issue-" + tid;
        try {
            chain.createIncident(new IncidentDto.CreateRequest(tid.toString()), auth);
            Toasts.successTitled(res, "Incident 생성됨", "보안팀 대응 incident 가 등록되었습니다.");
            return renderRoot(model, res, view, false, true);
        } catch (IncidentConflictException e) {
            // 다른 운영자가 이미 OPEN incident 를 만든 stale 상태일 수 있다 — 닫고(루트 교체) 목록 동기화.
            Toasts.warnTitled(res, "이미 진행 중", "해당 tenant 에 OPEN 상태 incident 가 이미 있습니다.");
            return refresh(model, res);
        } catch (IncidentNotTamperedException e) {
            return issue(model, res, slot, "warn", "위변조가 확인되지 않았습니다", "재검증 결과 위변조가 확인되지 않아 incident 를 생성하지 않았습니다.", false);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-chain] create incident failed: {}", e.toString());
            return issue(model, res, slot, "bad", "Incident를 생성하지 못했습니다", ErrorMessages.describe(e).toastMessage(), false);
        }
    }

    /** 사건 해결(원본 handleResolveIncident). 다이얼로그는 하나를 공유하므로 대상 id 는 폼 값이다. */
    @PostMapping(RESOLVE_PATH)
    public String resolveIncident(@RequestParam(name = "id", defaultValue = "") String id,
                                  @RequestParam(name = "note", defaultValue = "") String note,
                                  @RequestParam(name = "view", defaultValue = "all") String view,
                                  Authentication auth, HttpServletResponse res, Model model) {
        requirePlatform(model);
        UUID iid = parseUuid(id);
        if (iid == null) throw new IllegalArgumentException("invalid incident id");
        String slot = "chain-rs-issue";
        String trimmed = note.trim();
        IncidentDto.ResolveRequest body = new IncidentDto.ResolveRequest(trimmed);
        if (!validator.validate(body).isEmpty()) {
            return issue(model, res, slot, "warn", "해결 메모를 확인하세요", "필수 · 감사 로그에 함께 기록됩니다. 최대 1024자입니다.", false);
        }
        try {
            chain.resolveIncident(iid.toString(), body, auth);
            Toasts.successTitled(res, "해결됨", "incident 가 RESOLVED 로 표시되었습니다.");
            return renderRoot(model, res, view, false, true);
        } catch (IncidentConflictException e) {
            // 이미 RESOLVED — stale 다이얼로그를 닫고(루트 교체) 목록 동기화.
            Toasts.warnTitled(res, "이미 해결됨", "해당 incident 는 이미 RESOLVED 상태입니다.");
            return refresh(model, res);
        } catch (IncidentNotFoundException e) {
            // 메모는 남기고 다이얼로그 안에서 알린다. 목록은 OOB 로 새로 읽는다.
            return issue(model, res, slot, "warn", "이미 처리되었거나 없는 incident입니다", "목록을 새로 고쳤습니다. 다이얼로그를 닫고 목록에서 확인하세요.", true);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-chain] resolve incident failed: {}", e.toString());
            return issue(model, res, slot, "bad", "해결로 표시하지 못했습니다", ErrorMessages.describe(e).toastMessage(), false);
        }
    }

    // ── 렌더 ────────────────────────────────────────────────

    /** 인라인 알림 조각(다이얼로그 안 슬롯으로 HX-Retarget). 원본은 어느 실패 경로든 사건 목록을 다시 읽는다 — 목록 카드를 OOB 로 함께 보낸다. */
    private String issue(Model model, HttpServletResponse res, String slotId, String tone, String title, String text, boolean gone) {
        res.setHeader("HX-Retarget", "#" + slotId);
        res.setHeader("HX-Reswap", "innerHTML");
        res.setStatus(422);
        model.addAttribute("issueTone", tone);
        model.addAttribute("issueTitle", title);
        model.addAttribute("issueText", text);
        model.addAttribute("issueGone", gone);
        fillIncidentsOob(model);
        return ISSUE_VIEW;
    }

    /**
     * 목록만 갱신하는 응답: 본문은 OOB 조각뿐이라 {@code HX-Reswap: none}(요청 대상 #chain-root 를 비우지 않는다). 다이얼로그는 닫는 쪽(요청 요소의
     * after-request 성공)이 닫는다. 목록 조회가 실패하면 카드가 오류 상태(다시 시도)가 된다.
     */
    private String refresh(Model model, HttpServletResponse res) {
        res.setHeader("HX-Reswap", "none");
        fillIncidentsOob(model);
        return REFRESH_VIEW;
    }

    /** 사건 목록을 독립적으로 읽어 카드·배너 배지 OOB 모델을 채운다(개요와 무관). 실패하면 목록 없음(오류 카드). */
    private void fillIncidentsOob(Model model) {
        List<IncidentDto.IncidentView> list = null;
        try {
            list = chain.listIncidents();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-chain] incidents refresh failed: {}", e.getClass().getSimpleName());
        }
        model.addAttribute("incidentsOob", true);
        fillIncidents(model, list, null);
        // 배너의 행 동작(생성 버튼 ↔ "Incident 처리 중"): 사건이 있는 tenant 마다. 배너에 없는 tenant 의 요소는 htmx 가 무시한다.
        List<Tampered> acts = new ArrayList<>();
        if (list != null) {
            Map<String, Boolean> byTenant = new LinkedHashMap<>();
            for (IncidentDto.IncidentView i : list) byTenant.merge(i.tenantId(), "OPEN".equals(i.status()), (a, b) -> a || b);
            byTenant.forEach((tid, open) -> acts.add(new Tampered(tid, null, null, null, open, true, "chain-ic-" + tid, null, null)));
        }
        model.addAttribute("actionsOob", acts);
    }

    /**
     * 루트 교체. 개요(전 tenant 검증)와 사건 목록은 서로 독립이다 — 목록 실패는 카드 안 오류로, 개요 실패는 first 면 오류 카드, 아니면
     * 이전 결과를 남기고 경고만(mutated 면 앞선 변경은 성공했으므로 200).
     */
    private String renderRoot(Model model, HttpServletResponse res, String viewParam, boolean first, boolean mutated) {
        AuditChainOverview ov;
        try {
            ov = chain.overview(WINDOW_HOURS);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-chain] overview failed: {}", e.toString());
            if (first) {
                res.setStatus(422);
                model.addAttribute("loadError", e);
                model.addAttribute("retryAttrs", disabledSelf(rootReq("hx-get", OVERVIEW_PATH + "?first=1", false)));
                return FAILED_VIEW;
            }
            ErrorMessages.Info info = ErrorMessages.describe(e);
            res.setHeader("HX-Retarget", "#chain-warn");
            res.setHeader("HX-Reswap", "innerHTML");
            if (!mutated) res.setStatus(422);
            // 앞선 변경은 성공했다 — 목록은 개요와 독립으로 다시 읽어 낡은 목록·열린 상태를 남기지 않는다(원본 refreshIncidents).
            model.addAttribute("incidentsOob", mutated);
            if (mutated) fillIncidentsOob(model);
            model.addAttribute("warnTitle", info.title());
            Map<String, Object> retry = disabledSelf(rootReq("hx-get", OVERVIEW_PATH, false));
            retry.put("data-chain-verify", true);
            retry.put("data-chain-reverify", true);
            model.addAttribute("warnAttrs", retry);
            return WARN_VIEW;
        }
        List<IncidentDto.IncidentView> list = null;
        try {
            list = chain.listIncidents();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[audit-chain] incidents failed: {}", e.getClass().getSimpleName());
        }
        String view = "tampered".equals(viewParam) ? "tampered" : "all";
        populate(model, ov, list, view);
        return ROOT_VIEW;
    }

    private void populate(Model model, AuditChainOverview ov, List<IncidentDto.IncidentView> incidents, String view) {
        Instant now = clock.instant();
        AuditChainOverview.Totals t = ov.totals();
        List<AuditChainTenantOverview> tenants = ov.tenants();
        boolean tampered = hasTampering(ov);
        Set<String> openTenants = new java.util.HashSet<>();
        if (incidents != null) for (IncidentDto.IncidentView i : incidents) if ("OPEN".equals(i.status())) openTenants.add(i.tenantId());

        List<Row> rows = new ArrayList<>();
        List<Tampered> tamperedRows = new ArrayList<>();
        for (AuditChainTenantOverview c : tenants) {
            String tid = c.tenantId() == null ? null : c.tenantId().toString();
            String name = c.tenantName() == null ? "" : c.tenantName();
            Map<String, Object> vAttrs = null;
            if (tid != null) {
                vAttrs = rootReq("hx-get", VERIFY_PATH + "?tenantId=" + tid, false);
                vAttrs.put("data-chain-verify", true);
                vAttrs.put("data-vid", tid);
            }
            rows.add(new Row(tid, name, initial(name), c.intact(), fmt(c.verifiedRows()), tid == null ? null : "/admin/tenants/" + tid, vAttrs));
            if (!c.intact()) {
                boolean hasOpen = tid != null && openTenants.contains(tid);
                boolean known = incidents != null;
                String title = tid == null ? "플랫폼 체인은 incident 대상이 아닙니다" : !known ? "Incident 목록을 확인한 뒤 생성할 수 있습니다" : null;
                String entry = c.tamperedEntryId() == null ? null : tail8(c.tamperedEntryId().toString());
                tamperedRows.add(new Tampered(tid, name, entry, tid == null ? null : "/admin/tenants/" + tid + "?tab=audit", hasOpen,
                        tid != null && known, tid == null ? null : "chain-ic-" + tid, title,
                        tid == null ? null : rootReq("hx-post", INCIDENTS_PATH + "?tenantId=" + tid, false)));
            }
        }
        model.addAttribute("view", view);
        model.addAttribute("rows", rows);
        model.addAttribute("tamperedRows", tamperedRows);
        model.addAttribute("tampered", tampered);
        model.addAttribute("verifiedAtIso", ov.verifiedAt() == null ? "" : ov.verifiedAt().toString());
        model.addAttribute("headline", tampered
                ? t.tenantsTampered() + "개 tenant 위변조 의심 · " + t.tenantsIntact() + " / " + t.tenantsTotal() + " 정상"
                : "모든 tenant 정상 · " + t.tenantsIntact() + " / " + t.tenantsTotal());
        String ago = KstFormat.timeAgo(ov.verifiedAt(), now);
        model.addAttribute("ago", ago);
        model.addAttribute("lastLine", "마지막 검증 " + ago);
        model.addAttribute("agoCap", ago + " 검증 기준");
        model.addAttribute("kpiRatio", t.tenantsIntact() + " / " + t.tenantsTotal());
        model.addAttribute("kpiRows", fmt(t.verifiedRows()));
        model.addAttribute("kpiMs", fmt(t.verificationMs()) + "ms");
        model.addAttribute("tenantCount", tenants.size());
        model.addAttribute("tamperedCount", tamperedRows.size());
        model.addAttribute("tamperedFlag", tampered ? "1" : "0");
        model.addAttribute("incidentsKnownFlag", incidents != null ? "1" : "0");
        model.addAttribute("chainColumns", List.of(col("tenant", "Tenant", null), col("status", "상태", null), col("rows", "검증 행 수", "right"),
                col("verifiedAt", "마지막 검증", null), col("actions", "액션", null)));
        model.addAttribute("segOptions", List.of(opt("all", "전체"), opt("tampered", "위변조만")));

        // 백필: 지금 렌더가 곧 가장 최근 검증이 성공한 상태다. 위변조가 있으면 비활성 + 사유.
        model.addAttribute("backfillAllowed", !tampered);
        model.addAttribute("backfillReason", BLOCK_TAMPERED);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", "체인 백필…");
        item.put("danger", true);
        item.put("disabled", tampered);
        item.put("disabledReason", BLOCK_TAMPERED);
        // 검증 중·재검증 실패에는 Alpine 이 항목을 비활성(aria-disabled + 사유 title)으로 바꾼다 — 메뉴가 클릭을 막고 다이얼로그도 없다.
        Map<String, Object> ia = new LinkedHashMap<>();
        ia.put("x-on:click.capture", "if (!blocked) $dispatch('dialog:open', { id: 'chain-backfill' })");
        ia.put("x-bind:aria-disabled", "blocked ? 'true' : null");
        ia.put("x-bind:title", "blocked ? blockedReason : null");
        item.put("attrs", ia);
        model.addAttribute("menuItems", List.of(item));
        model.addAttribute("resolveAttrs", rootReq("hx-post", RESOLVE_PATH, false));
        model.addAttribute("backfillAttrs", rootReq("hx-post", BACKFILL_PATH, false));

        // 월간 보고서
        KstFormat.DateRange range = KstFormat.kstPrevMonthRange(now);
        model.addAttribute("reportFrom", range.from());
        model.addAttribute("reportTo", range.to());
        model.addAttribute("reportHref", REPORT_API);
        model.addAttribute("reportOpts", Map.of("wide", true));
        List<ReportTenant> rt = new ArrayList<>();
        for (AuditChainTenantOverview c : tenants) rt.add(new ReportTenant(c.tenantName() == null ? "" : c.tenantName(), fmt(c.verifiedRows()) + " rows", c.intact()));
        model.addAttribute("reportTenants", rt);

        // 새로고침 요청 속성
        Map<String, Object> reverify = disabledSelf(rootReq("hx-get", OVERVIEW_PATH, false));
        reverify.put("data-chain-verify", true);
        reverify.put("data-chain-reverify", true);
        model.addAttribute("reverifyAttrs", reverify);

        fillIncidents(model, incidents, tampered);
    }

    private void fillIncidents(Model model, List<IncidentDto.IncidentView> list, Boolean tampered) {
        boolean known = list != null;
        List<Incident> out = new ArrayList<>();
        int open = 0;
        if (known) {
            for (IncidentDto.IncidentView i : list) {
                boolean isOpen = "OPEN".equals(i.status());
                if (isOpen) open++;
                String created = i.createdAt() == null ? "—" : KstFormat.kstDateTime(i.createdAt());
                if (created.length() > 16) created = created.substring(0, 16);
                out.add(new Incident(i.id(), isOpen, Labels.statusLabel(i.status()), i.tenantName(), i.type(), created, i.createdByEmail(),
                        i.tenantName() + " · " + i.type()));
            }
        }
        model.addAttribute("incidentColumns", List.of(col("status", "상태", null), col("tenant", "Tenant", null), col("type", "유형", null),
                col("createdAt", "생성시각", null), col("createdBy", "생성자", null), col("actions", "액션", null)));
        model.addAttribute("incidentsKnown", known);
        model.addAttribute("incidentList", out);
        model.addAttribute("openCount", open);
        model.addAttribute("openLabel", Labels.statusLabel("OPEN") + " " + open);
        model.addAttribute("emptyDesc", tampered == null ? null
                : tampered ? "위변조 의심 tenant 는 위 배너에서 Incident 를 생성하세요." : "모든 chain 이 정상입니다.");
        // 다시 시도 = 목록만 다시 읽기(원본 refreshIncidents). 응답은 OOB 뿐이라 swap none.
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("hx-get", INCIDENTS_PATH);
        retry.put("hx-swap", "none");
        retry.put("hx-sync", "#chain-root:drop");
        retry.put("hx-disabled-elt", "this");
        model.addAttribute("incidentRetryAttrs", retry);
    }

    // ── 요청 속성 ───────────────────────────────────────────

    /** 루트 교체 요청. 같은 동기화 그룹(#chain-root:drop)이라 더블 클릭·동시 실행은 뒤의 것을 버린다. view 는 표 필터를 보존한다. */
    private static Map<String, Object> rootReq(String method, String url, boolean self) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(method, url);
        m.put("hx-target", self ? "this" : "#chain-root");
        m.put("hx-swap", "outerHTML");
        m.put("hx-sync", "#chain-root:drop");
        m.put("hx-include", "#chain-root [name=view]");
        m.put("hx-request", "{\"timeout\": " + LONG_TIMEOUT_MS + "}");
        if (self) m.put("hx-trigger", "load");
        return m;
    }

    private static Map<String, Object> disabledSelf(Map<String, Object> m) {
        m.put("hx-disabled-elt", "this");
        return m;
    }

    private static Map<String, Object> col(String key, String header, String align) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("key", key);
        c.put("header", header);
        c.put("align", align);
        c.put("width", null);
        c.put("srOnly", null);
        return c;
    }

    private static Map<String, Object> opt(String value, String label) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("label", label);
        o.put("title", null);
        o.put("attrs", null);
        return o;
    }

    // ── 도우미 ──────────────────────────────────────────────

    static boolean hasTampering(AuditChainOverview ov) {
        return ov.totals().tenantsTampered() > 0 || ov.tenants().stream().anyMatch(c -> !c.intact());
    }

    /** 아바타 글자 — 첫 문자/숫자(예: "[platform]" → "P"). */
    static String initial(String name) {
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
            i += Character.charCount(cp);
        }
        return name.isEmpty() ? "" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    private static String tail8(String s) {
        return s.length() <= 8 ? s : s.substring(s.length() - 8);
    }

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static int status(ErrorMessages.Info info) {
        return info.httpStatus() == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : info.httpStatus();
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
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
