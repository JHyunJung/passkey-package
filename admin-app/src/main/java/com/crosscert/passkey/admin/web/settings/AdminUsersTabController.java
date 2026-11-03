package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.operator.AdminUserController;
import com.crosscert.passkey.admin.operator.AdminUserDto;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.operator.SignupRequestDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.Toasts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 설정 › Admin 사용자 탭(← pages/settings/AdminUsersTab.tsx, api/adminUsers.ts, api/signupRequests.ts). PLATFORM 전용 — 모든 엔드포인트가 PLATFORM 을 먼저 확인한다
 * (RP_ADMIN → 403, REST 미호출). 일반 탭 매핑보다 이 리터럴 경로가 우선한다.
 *
 * <p>화면: 가입 요청 카드(#au-signups) + 콘솔 운영자 카드(#au-users). 승인·거절은 {@code SignupRequestController}, 정지·활성화는 {@code AdminUserController}.
 * 원본에 없는 동작은 만들지 않는다: {@code addTenant}/{@code removeTenant} 는 원본 탭이 부르지 않아 화면에도 없다.
 * <ul>
 *   <li>정지: 행의 "정지…" → L1 확인 다이얼로그. 본인·마지막 활성 PLATFORM 은 버튼을 막고 이유를 보인다(원본 suspendBlock). 확인 때 서버가 목록을 다시 읽어
 *       ACTIVE 인지·막힘 규칙(본인·마지막 PLATFORM)을 한 번 더 본다(원본 staleReason/suspendBlock) — 어긋나면 REST 를 부르지 않고 확인창 안에 409 사유.</li>
 *   <li>활성화: 확인 없이 바로(원본 handleActivate). 행 버튼은 요청 동안 비활성(hx-disabled-elt, hx-sync=this:drop).</li>
 *   <li>승인: "승인…" → 대화상자(GET) → 제출. RP_ADMIN 인데 tenant 를 안 고르면 422 + 대화상자 안 오류(ruling G). 409(다른 관리자가 먼저)는 경고 토스트 후 목록 갱신.</li>
 *   <li>거절: L1 확인. 409 는 경고 토스트 후 목록 갱신. 승인·거절 뒤 탭 줄의 가입 요청 수 배지를 OOB 로 갱신한다.</li>
 * </ul>
 * 이메일·tenant 이름·가입 사유는 사용자가 정한 값이라 템플릿에서 th:text 로만 그린다.
 */
@Slf4j
@Controller
public class AdminUsersTabController {

    static final String TAB_PATH = "/admin/_/settings/tab/admins";
    static final String USER_BASE = "/admin/_/settings/admins/";
    static final String SIGNUP_BASE = "/admin/_/settings/signups/";

    static final String TAB_VIEW = "admin/settings/admins :: tab";
    static final String USERS_VIEW = "admin/settings/admins :: usersResponse";
    static final String SIGNUPS_VIEW = "admin/settings/admins :: signupsResponse";
    static final String CONTENT_VIEW = "admin/settings/admins :: contentResponse";
    static final String PARTIAL_VIEW = "admin/settings/admins :: partialResponse";
    static final String UNDO_EVENT = "au-undo-suspend";
    static final String DIALOG_VIEW = "admin/settings/admins :: approveDialog";
    static final String APPROVE_ERROR_VIEW = "admin/settings/admins :: approveError";

    static final String STALE_REASON = "목록을 새로 고친 뒤 다시 시도하세요";
    static final String TENANT_ERROR = "Tenant를 최소 1개 선택해야 합니다.";
    static final String PLATFORM_ROLE = "PLATFORM_OPERATOR";
    static final String RP_ROLE = "RP_ADMIN";

    private final AdminUserController users;
    private final SignupRequestController signups;
    private final TenantAdminController tenants;
    private final Clock clock;

    public AdminUsersTabController(AdminUserController users, SignupRequestController signups, TenantAdminController tenants, Clock clock) {
        this.users = users;
        this.signups = signups;
        this.tenants = tenants;
        this.clock = clock;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** Tenant 열(원본 TenantCell): ALL=전체 tenant, NONE=—, NAME=이름(+외 N개), ID=이름을 모를 때 id 꼬리. */
    public record TenantCell(String kind, String text, String title, int more) {}

    public record OpRow(String id, String email, String initial, String idTail, String roleKey, String roleLabel, boolean platform, TenantCell tenant,
                        boolean mfa, String lastLogin, String status, String statusKey, String block, String hay,
                        Map<String, Object> activateAttrs, Map<String, Object> suspendAttrs, String dialogId) {}

    public record SignupRow(String id, String email, String initial, String reason, String ago, String rejectDialogId,
                            Map<String, Object> approveAttrs, Map<String, Object> rejectAttrs) {}

    public record TenantOpt(String id, String name, String slug, String q) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(TAB_PATH)
    public String tab(Model model) {
        CurrentAdmin me = SettingsSupport.platform(model);
        loadSignups(model);
        loadUsers(model, me, loadTenantNames());
        return TAB_VIEW;
    }

    /** 승인 대화상자: 활성 tenant 목록을 지금 읽어 그린다(원본 ApproveDialog 가 열릴 때 tenantsApi.list). */
    @GetMapping(SIGNUP_BASE + "{id}/approve")
    public String approveDialog(@PathVariable UUID id, Model model) {
        SettingsSupport.platform(model);
        SignupRequestDto.View req = signups.list().stream().filter(r -> id.equals(r.id())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found"));
        List<TenantOpt> opts = new ArrayList<>();
        try {
            for (TenantAdminDto.TenantView t : tenants.list().data()) {
                if ("ACTIVE".equals(t.status())) opts.add(new TenantOpt(t.id().toString(), t.displayName(), t.slug(),
                        (t.displayName() + " " + t.slug()).toLowerCase(java.util.Locale.ROOT)));
            }
        } catch (RuntimeException e) {
            if (e instanceof AccessDeniedException) throw e;
            log.debug("[settings] tenants for approve dialog failed: {}", e.getClass().getSimpleName()); // non-critical: 빈 목록
        }
        model.addAttribute("req", signupRow(req, Instant.now(clock)));
        model.addAttribute("tenantOpts", opts);
        model.addAttribute("approveUrl", SIGNUP_BASE + id + "/approve");
        model.addAttribute("rolePlatformAttrs", roleAttrs(PLATFORM_ROLE));
        model.addAttribute("roleRpAttrs", roleAttrs(RP_ROLE));
        return DIALOG_VIEW;
    }

    // ── 정지·활성화 ─────────────────────────────────────────

    @PostMapping(USER_BASE + "{id}/suspend")
    public String suspend(@PathVariable UUID id, Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        CurrentAdmin me = SettingsSupport.platform(model);
        List<AdminUserDto.View> list = users.list();
        AdminUserDto.View row = list.stream().filter(u -> id.equals(u.id())).findFirst().orElse(null);
        // 원본 confirmSuspend: 목록이 낡았거나(상태 바뀜) 막힌 대상이면 REST 를 부르지 않고 사유를 확인창 안에 보인다.
        if (row == null || !"ACTIVE".equals(row.status())) {
            return SettingsSupport.blocked(res, "운영자 상태가 바뀌었습니다. " + STALE_REASON, Toasts.isConfirm(req));
        }
        String why = suspendBlock(row, list, me.email());
        if (why != null) return SettingsSupport.blocked(res, why, Toasts.isConfirm(req));
        users.suspend(id, auth);
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("url", USER_BASE + id + "/activate?undo=1");
        Toasts.successTitledAction(res, row.email() + " 운영자를 정지했습니다.", "다음 요청부터 콘솔에 접근할 수 없습니다.", "되돌리기", UNDO_EVENT, undo);
        return usersResult(model, res, me);
    }

    /**
     * 활성화. 행의 "활성화" 버튼은 확인 없이 바로 호출한다(원본 handleActivate — 호출 전에 목록을 읽지 않는다; 토스트의 이메일은 다시 읽은 목록에서 찾는다).
     * {@code undo=1} 은 정지 토스트의 "되돌리기"(원본 undoSuspend): 지금 정지 상태인지 확인하고(아니면 REST 미호출 + 경고), 원본의 성공·실패 문구를 쓴다.
     */
    @PostMapping(USER_BASE + "{id}/activate")
    public String activate(@PathVariable UUID id, @RequestParam(name = "undo", defaultValue = "") String undo,
                           Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        CurrentAdmin me = SettingsSupport.platform(model);
        boolean isUndo = "1".equals(undo);
        if (isUndo) {
            AdminUserDto.View row = users.list().stream().filter(u -> id.equals(u.id())).findFirst().orElse(null);
            if (row == null || !"SUSPENDED".equals(row.status())) {
                Toasts.warnTitled(res, "되돌릴 수 없습니다", "운영자 상태가 바뀌었습니다. " + STALE_REASON);
                res.setHeader("HX-Reswap", "none");
                return SettingsSupport.EMPTY_VIEW;
            }
        }
        try {
            users.activate(id, auth);
        } catch (RuntimeException e) {
            if (e instanceof AccessDeniedException) throw e;
            if (isUndo) {
                ErrorMessages.Info info = ErrorMessages.describe(e);
                int status = SettingsSupport.statusOf(e);
                Toasts.errorTitled(res, "정지를 되돌리지 못했습니다", info.title() + ". 목록에서 “활성화”를 눌러 다시 시도하세요.", "HTTP " + status, org.slf4j.MDC.get("traceId"));
                res.setStatus(status);
                return SettingsSupport.EMPTY_VIEW;
            }
            return SettingsSupport.fail(res, "활성화하지 못했습니다", e, Toasts.isConfirm(req));
        }
        boolean ok = loadUsers(model, me, loadTenantNames());
        String email = "";
        if (ok) {
            @SuppressWarnings("unchecked") List<OpRow> ops = (List<OpRow>) model.getAttribute("ops");
            email = ops.stream().filter(o -> id.toString().equals(o.id())).map(OpRow::email).findFirst().orElse("");
        }
        if (isUndo) Toasts.successTitled(res, email + " 운영자의 정지를 되돌렸습니다.", "");
        else Toasts.successTitled(res, "운영자를 다시 활성화했습니다.", email);
        return ok ? USERS_VIEW : warnUsers(model, res);
    }

    /** 변경 성공 뒤 운영자 카드 응답: 다시 읽기에 성공하면 카드를 교체, 실패하면 화면은 그대로 두고 경고 자리만 채운다(원본 StaleWarning). */
    private String usersResult(Model model, HttpServletResponse res, CurrentAdmin me) {
        return loadUsers(model, me, loadTenantNames()) ? USERS_VIEW : warnUsers(model, res);
    }

    private String warnUsers(Model model, HttpServletResponse res) {
        res.setHeader("HX-Reswap", "none");
        model.addAttribute("usersWarn", model.getAttribute("usersError"));
        model.addAttribute("oobUsers", false);
        model.addAttribute("oobSignups", false);
        model.addAttribute("signupsWarn", null);
        model.addAttribute("clearSlot", false);
        model.addAttribute("pendingCount", null);
        return PARTIAL_VIEW;
    }

    // ── 가입 요청 ───────────────────────────────────────────

    /**
     * 승인. role 은 PLATFORM_OPERATOR | RP_ADMIN, RP_ADMIN 은 tenant 1개 이상(원본 formValid). PLATFORM 은 tenant 를 보내지 않는다(원본 tenantIds: []).
     * 검증 실패는 422 + 대화상자 안 오류(HX-Retarget). 성공·409 는 가입 요청·운영자 카드와 탭 배지를 새로 그리고 대화상자 자리를 비운다.
     */
    @PostMapping(SIGNUP_BASE + "{id}/approve")
    public String approve(@PathVariable UUID id,
                          @RequestParam(name = "role", defaultValue = "") String role,
                          @RequestParam(name = "tenantId", required = false) List<String> tenantIds,
                          Authentication auth, HttpServletResponse res, Model model) {
        CurrentAdmin me = SettingsSupport.platform(model);
        if (!PLATFORM_ROLE.equals(role) && !RP_ROLE.equals(role)) {
            return approveError(res, model, "역할을 선택하세요.");
        }
        List<UUID> ids = new ArrayList<>();
        if (RP_ROLE.equals(role) && tenantIds != null) {
            for (String t : tenantIds) {
                if (t == null || t.isBlank()) continue;
                UUID u = UUID.fromString(t.trim()); // 잘못된 값은 IllegalArgumentException → 400(원문 없음)
                if (!ids.contains(u)) ids.add(u);
            }
        }
        if (RP_ROLE.equals(role) && ids.isEmpty()) return approveError(res, model, TENANT_ERROR);
        AdminUserDto.View created;
        try {
            created = signups.approve(id, new SignupRequestDto.Approve(role, ids), auth);
        } catch (RuntimeException e) {
            if (e instanceof AccessDeniedException) throw e;
            if (SettingsSupport.statusOf(e) == 409) {
                log.debug("[settings] signup approve conflict");
                Toasts.warnTitled(res, "이미 처리된 요청입니다.", "다른 관리자가 먼저 처리했습니다.");
                return refreshAll(model, res, me);
            }
            return SettingsSupport.fail(res, "승인 실패", e, false);
        }
        Toasts.successTitled(res, "가입 요청을 승인했습니다.", created.email() + " · 즉시 로그인 가능");
        return refreshAll(model, res, me);
    }

    @PostMapping(SIGNUP_BASE + "{id}/reject")
    public String reject(@PathVariable UUID id, Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        SettingsSupport.platform(model);
        String email = "";
        try {
            email = signups.list().stream().filter(r -> id.equals(r.id())).map(SignupRequestDto.View::email).findFirst().orElse("");
        } catch (RuntimeException e) {
            if (e instanceof AccessDeniedException) throw e;
            // 이메일은 토스트 문구용일 뿐이라 읽기 실패가 거절을 막지 않는다.
        }
        try {
            signups.reject(id, auth);
            Toasts.successTitled(res, "가입 요청을 거절했습니다.", email);
        } catch (RuntimeException e) {
            if (e instanceof AccessDeniedException) throw e;
            if (SettingsSupport.statusOf(e) != 409) throw e; // 그 밖의 실패는 확인창 안(confirmError)
            Toasts.warnTitled(res, "이미 처리된 요청입니다.", "다른 관리자가 먼저 처리했습니다.");
        }
        if (loadSignups(model)) return SIGNUPS_VIEW;
        res.setHeader("HX-Reswap", "none");
        model.addAttribute("signupsWarn", model.getAttribute("signupsError"));
        model.addAttribute("oobSignups", false);
        model.addAttribute("oobUsers", false);
        model.addAttribute("usersWarn", null);
        model.addAttribute("clearSlot", false);
        model.addAttribute("pendingCount", null);
        return PARTIAL_VIEW;
    }

    // ── 도우미 ──────────────────────────────────────────────

    /** 역할 카드 속성(템플릿 상수만 — JS 평가 속성에 사용자 입력 없음). */
    private static Map<String, Object> roleAttrs(String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x-on:click", "role = '" + role + "'; clearErr()");
        m.put("x-bind:class", "{ 'choice--on': role === '" + role + "' }");
        m.put("x-bind:aria-checked", "role === '" + role + "'");
        return m;
    }

    private String approveError(HttpServletResponse res, Model model, String message) {
        res.setStatus(422);
        res.setHeader("HX-Retarget", "#au-approve-err");
        res.setHeader("HX-Reswap", "innerHTML");
        model.addAttribute("approveError", message);
        return APPROVE_ERROR_VIEW;
    }

    /** 승인 뒤: 둘 다 다시 읽으면 #au-content 교체. 하나라도 실패하면 본 대상은 스왑하지 않고(HX-Reswap none) 읽은 쪽은 OOB 로 새로, 못 읽은 쪽은 경고 자리 OOB. */
    private String refreshAll(Model model, HttpServletResponse res, CurrentAdmin me) {
        boolean s = loadSignups(model);
        boolean u = loadUsers(model, me, loadTenantNames());
        if (s && u) return CONTENT_VIEW;
        res.setHeader("HX-Reswap", "none");
        model.addAttribute("oobSignups", s);
        model.addAttribute("oobUsers", u);
        model.addAttribute("signupsWarn", s ? null : model.getAttribute("signupsError"));
        model.addAttribute("usersWarn", u ? null : model.getAttribute("usersError"));
        model.addAttribute("clearSlot", true);
        return PARTIAL_VIEW;
    }

    private Map<String, String> loadTenantNames() {
        Map<String, String> names = new HashMap<>();
        try {
            for (TenantAdminDto.TenantView t : tenants.list().data()) names.put(t.id().toString(), t.displayName());
        } catch (RuntimeException e) {
            if (e instanceof AccessDeniedException) throw e;
            log.debug("[settings] tenant names failed: {}", e.getClass().getSimpleName()); // 실패해도 id 꼬리로 보인다
        }
        return names;
    }

    private boolean loadSignups(Model model) {
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH)); // 다시 시도 = 탭 전체를 다시 받는다
        model.addAttribute("signupsError", null);
        model.addAttribute("pending", null);
        model.addAttribute("pendingCount", null);
        try {
            Instant now = Instant.now(clock);
            List<SignupRow> rows = new ArrayList<>();
            for (SignupRequestDto.View r : signups.list()) rows.add(signupRow(r, now));
            model.addAttribute("pending", rows);
            model.addAttribute("pendingCount", rows.size());
            return true;
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[settings] signup requests load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("signupsError", e);
            return false;
        }
    }

    private SignupRow signupRow(SignupRequestDto.View r, Instant now) {
        String id = r.id().toString();
        Map<String, Object> approve = new LinkedHashMap<>();
        approve.put("hx-get", SIGNUP_BASE + id + "/approve");
        approve.put("hx-target", "#au-dialog-slot");
        approve.put("hx-swap", "innerHTML");
        approve.put("hx-sync", "this:drop");
        Map<String, Object> reject = new LinkedHashMap<>();
        reject.put("hx-post", SIGNUP_BASE + id + "/reject");
        reject.put("hx-target", "#au-signups");
        reject.put("hx-swap", "outerHTML");
        reject.put("hx-sync", "this:drop");
        Instant at = r.requestedAt() == null ? null : r.requestedAt().toInstant();
        return new SignupRow(id, r.email(), initial(r.email()), r.reason(), KstFormat.timeAgo(at, now), "au-reject-" + id, approve, reject);
    }

    private boolean loadUsers(Model model, CurrentAdmin me, Map<String, String> names) {
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH));
        model.addAttribute("usersError", null);
        model.addAttribute("ops", null);
        try {
            List<AdminUserDto.View> list = users.list();
            Instant now = Instant.now(clock);
            List<OpRow> rows = new ArrayList<>();
            long active = list.stream().filter(u -> "ACTIVE".equals(u.status())).count();
            for (AdminUserDto.View u : list) rows.add(opRow(u, list, me.email(), names, now));
            model.addAttribute("ops", rows);
            model.addAttribute("opsSummary", list.size() + "명 · 활성 " + active + "명");
            model.addAttribute("roleOptions", List.of(opt("all", "전체"), opt("PLATFORM", "PLATFORM"), opt("RP_ADMIN", "RP_ADMIN"), opt("SUSPENDED", "정지")));
            return true;
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[settings] admin users load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("usersError", e);
            return false;
        }
    }

    private static Map<String, Object> opt(String value, String label) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("value", value);
        o.put("label", label);
        o.put("title", null);
        o.put("attrs", null);
        return o;
    }

    private OpRow opRow(AdminUserDto.View u, List<AdminUserDto.View> all, String meEmail, Map<String, String> names, Instant now) {
        String id = u.id().toString();
        boolean platform = PLATFORM_ROLE.equals(u.role());
        String block = "ACTIVE".equals(u.status()) ? suspendBlock(u, all, meEmail) : null;
        Map<String, Object> suspend = new LinkedHashMap<>();
        suspend.put("hx-post", USER_BASE + id + "/suspend");
        suspend.put("hx-target", "#au-users");
        suspend.put("hx-swap", "outerHTML");
        suspend.put("hx-sync", "this:drop");
        Map<String, Object> activate = new LinkedHashMap<>();
        activate.put("hx-post", USER_BASE + id + "/activate");
        activate.put("hx-target", "#au-users");
        activate.put("hx-swap", "outerHTML");
        activate.put("hx-sync", "this:drop");
        activate.put("hx-disabled-elt", "this");
        Instant last = u.lastLoginAt() == null ? null : u.lastLoginAt().toInstant();
        return new OpRow(id, u.email(), initial(u.email()), tail(id, 10), u.role(), platform ? "PLATFORM" : "RP_ADMIN", platform,
                tenantCell(u, names), u.mfaEnabled(), last == null ? "미접속" : KstFormat.timeAgo(last, now), u.status(), filterStatus(u),
                block, (u.email() + " " + id).toLowerCase(java.util.Locale.ROOT), activate, suspend, "au-suspend-" + id);
    }

    private static String filterStatus(AdminUserDto.View u) {
        return "SUSPENDED".equals(u.status()) ? "SUSPENDED" : "";
    }

    /** 원본 suspendBlock: 본인, 또는 마지막 활성 PLATFORM 운영자. */
    static String suspendBlock(AdminUserDto.View u, List<AdminUserDto.View> all, String meEmail) {
        if (u.email() != null && meEmail != null && u.email().equalsIgnoreCase(meEmail)) return "본인 계정은 정지할 수 없음";
        long activePlatform = all.stream().filter(x -> PLATFORM_ROLE.equals(x.role()) && "ACTIVE".equals(x.status())).count();
        if (PLATFORM_ROLE.equals(u.role()) && "ACTIVE".equals(u.status()) && activePlatform <= 1) return "마지막 활성 PLATFORM 운영자";
        return null;
    }

    static TenantCell tenantCell(AdminUserDto.View u, Map<String, String> names) {
        if (PLATFORM_ROLE.equals(u.role())) return new TenantCell("ALL", "전체 tenant", null, 0);
        List<UUID> ids = u.tenantIds() == null ? List.of() : u.tenantIds();
        if (ids.isEmpty()) return new TenantCell("NONE", "—", null, 0);
        String first = ids.get(0).toString();
        String name = names.get(first);
        int more = ids.size() - 1;
        return name != null ? new TenantCell("NAME", name, first, more) : new TenantCell("ID", "…" + tail(first, 10), first, more);
    }

    static String initial(String email) {
        return email == null || email.isEmpty() ? "" : email.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
