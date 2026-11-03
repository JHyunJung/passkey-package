package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.ApiKeyExpiry;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.Josa;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant 상세의 API 키 탭(← pages/tenant/ApiKeysTab.tsx, apiKeyHelpers.ts, api/apiKeys.ts, ui/SecretBox.tsx, lib/apiKeyExpiry.ts).
 *
 * <p>접근 경계: 탭·발급은 {@link TenantTabs#load} 를 먼저 부른다. 회전·회수 주소는 키 id 로 되어 있어(REST 와 같다) 화면이 키의 tenant 를 함께 받는다
 * ({@code ?tenantId=}): 그 tenant 를 {@link TenantTabs#load} 로 확인하고, 그 tenant 의 키 목록에 이 키가 있어야만 REST 를 부른다
 * (REST 서비스도 {@code TenantBoundary.assertCanAccessTenant} 로 같은 경계를 지키지만, 화면은 거기에 기대지 않는다).
 *
 * <p>평문 키: 발급·회전의 평문은 그 응답 조각(발급 완료 다이얼로그의 SecretBox)에만 그린다. 세션·플래시·헤더(토스트 포함)·로그에 싣지 않고
 * 리다이렉트도 쓰지 않는다(ui.js 의 토스트 넘기기가 sessionStorage 를 쓰므로). 응답은 {@code Cache-Control: no-store}.
 */
@Slf4j
@Controller
public class ApiKeysTabController {

    static final String FRAG = "/admin/_/tenants/";
    static final String KEY_FRAG = "/admin/_/api-keys/";
    static final String TAB_VIEW = "admin/tenant/api-keys :: tab";
    static final String LIST_VIEW = "admin/tenant/api-keys :: listResponse";
    static final String ISSUE_ERROR_VIEW = "admin/tenant/api-keys :: issueError";
    static final String ISSUED_VIEW = "admin/tenant/api-key-issued :: issued";

    public static final int NAME_MAX = 64;
    public static final int ROTATION_DAYS = 90;
    static final long DAY_MS = 86_400_000L;
    static final List<String> SCOPES = List.of("registration", "authentication");
    static final Map<String, String> SCOPE_LABEL = Map.of("registration", "패스키 등록", "authentication", "패스키 인증");
    static final Map<String, String> SCOPE_DESC = Map.of("registration", "패스키 등록 · 목록 · 이름 변경 · 삭제", "authentication", "패스키 인증");
    /** 만료 프리셋(개월). null = 무기한. 기본 24개월. */
    static final List<Integer> EXPIRY_MONTHS = java.util.Arrays.asList(6, 12, 24, 36, null);
    static final int DEFAULT_MONTHS = 24;
    static final String ISSUE_FAIL_TITLE = "API 키를 발급하지 못했습니다.";

    private final TenantAdminController tenants;
    private final ApiKeyAdminController apiKeys;
    private final Validator validator;
    private final Clock clock;

    public ApiKeysTabController(TenantAdminController tenants, ApiKeyAdminController apiKeys, Validator validator, Clock clock) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.validator = validator;
        this.clock = clock;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** 만료 열 배지(apiKeyHelpers.expiryBadge). tone: ok | warn | bad | neutral. */
    public record ExpiryBadge(String tone, String label, String sub) {}

    public record Scope(String value, String label) {}

    /** 표의 한 줄(api/apiKeys.ts adapt). status: ACTIVE | EXPIRED | REVOKED. */
    public record Row(String id, String name, String prefix, String status, List<Scope> scopes,
                      String lastUsed, String lastUsedLong, String created, ExpiryBadge expiry,
                      Map<String, Object> rotateAttrs, Map<String, Object> revokeAttrs, List<Map<String, Object>> menuItems,
                      List<Map<String, Object>> menuDanger) {
        public boolean active() { return "ACTIVE".equals(status); }
    }

    public record Kpis(String total, String totalHint, String latest, String overdueHint, boolean overdueWarn) {}

    /** 발급 완료 다이얼로그. expiry 가 null 이면 만료 줄을 숨긴다(회전 뒤 목록에서 새 키를 못 찾았을 때). */
    public record Issued(String id, String name, String prefix, String expiry, String plaintext, String oldKeyNotice) {}

    public record ExpiryOption(String value, String label) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/apikeys")
    public String tab(@PathVariable String id, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        String tid = v.id().toString();
        fillForm(model, tid);
        try {
            fillList(model, tid, apiKeys.list(tid).data());
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            listFailed(model, tid, e);
        }
        return TAB_VIEW;
    }

    /** 권한 거부는 화면 안 안내가 아니라 WebExceptionAdvice 의 403 으로(데이터 없음). */
    private static boolean denied(RuntimeException e) {
        return e instanceof AccessDeniedException
                || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.ACCESS_DENIED);
    }

    /**
     * 발급(새 키 발급 다이얼로그의 발급). 검증 실패·REST 거절은 다이얼로그 안 인라인 경고(422, 입력 유지, 토스트 없음 — 원본과 같다).
     * 성공하면 발급 완료 다이얼로그(평문은 여기에만) + 목록·탭 배지 OOB 갱신.
     */
    @PostMapping(FRAG + "{id}/api-keys")
    public String issue(@PathVariable String id,
                        @RequestParam(name = "name", defaultValue = "") String name,
                        @RequestParam(name = "scopes", required = false) List<String> scopes,
                        @RequestParam(name = "expiresInMonths", defaultValue = "") String months,
                        Authentication auth, HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        String tid = v.id().toString();
        noStore(res);

        Integer expiresInMonths = null;
        String error = null;
        if (!months.isBlank()) {
            try {
                expiresInMonths = Integer.valueOf(months.trim());
            } catch (NumberFormatException e) {
                error = "만료 기간이 올바르지 않습니다";
            }
        }
        Set<String> scopeSet = new LinkedHashSet<>();
        if (scopes != null) for (String s : scopes) if (s != null && !s.isBlank()) scopeSet.add(s.trim());
        if (error == null && !SCOPES.containsAll(scopeSet)) error = "권한 범위가 올바르지 않습니다";
        ApiKeyAdminDto.ApiKeyCreateRequest req = new ApiKeyAdminDto.ApiKeyCreateRequest(v.id(), name, scopeSet, expiresInMonths);
        if (error == null) error = violation(req);
        if (error != null) return issueError(res, model, error, null);

        ApiKeyAdminDto.ApiKeyCreateResponse created;
        try {
            created = apiKeys.issue(req, auth).data();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[apikeys-tab] issue failed: {}", e.getClass().getSimpleName());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            return issueError(res, model, info.title(), info.detail());
        }
        model.addAttribute("issued", new Issued(String.valueOf(created.id()), name, created.prefix(),
                created.expiresAt() == null ? "무기한" : KstFormat.kstDate(created.expiresAt()), created.plainText(), null));
        refreshAfterChange(model, tid);
        return ISSUED_VIEW;
    }

    /** 회전(L2 확인 뒤). 새 키의 평문과 이전 키 만료 안내를 발급 완료 다이얼로그로 보인다. 실패는 확인 다이얼로그 안 인라인(confirmError). */
    @PostMapping(KEY_FRAG + "{keyId}/rotate")
    public String rotate(@PathVariable UUID keyId, @RequestParam(name = "tenantId") String tenantId,
                         Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        requireConfirm(req);
        Owned o = owned(keyId, tenantId);
        noStore(res);
        ApiKeyAdminDto.ApiKeyRotateResponse r = apiKeys.rotate(keyId, auth).data();
        List<ApiKeyAdminDto.ApiKeyView> fresh = refreshAfterChange(model, o.tid());
        // 회전 응답에는 새 키의 만료가 없다 — 다시 읽은 목록에서 새 키를 찾으면 그 만료, 못 찾으면 만료 줄을 숨긴다(이전 키의 만료를 보이지 않음).
        String expiry = null;
        if (fresh != null) {
            for (ApiKeyAdminDto.ApiKeyView k : fresh) {
                if (k.id() != null && k.id().equals(r.id())) expiry = k.expiresAt() == null ? "무기한" : KstFormat.kstDate(k.expiresAt());
            }
        }
        model.addAttribute("issued", new Issued(String.valueOf(r.id()), o.key().name(), r.prefix(), expiry, r.plaintextKey(),
                oldKeyNotice(r.oldKeyExpiresAt(), clock.instant())));
        return ISSUED_VIEW;
    }

    /** 회수(L2 확인 뒤). 성공하면 목록을 다시 그리고 성공 토스트. 실패는 확인 다이얼로그 안 인라인(confirmError). */
    @DeleteMapping(KEY_FRAG + "{keyId}")
    public String revoke(@PathVariable UUID keyId, @RequestParam(name = "tenantId") String tenantId,
                         Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        requireConfirm(req);
        Owned o = owned(keyId, tenantId);
        apiKeys.revoke(keyId, auth);
        String name = o.key().name() == null ? "" : o.key().name();
        Toasts.successTitled(res, "API 키 “" + name + "”" + Josa.eulReul(name) + " 회수했습니다.", o.key().keyPrefix());
        boolean ok = refreshAfterChange(model, o.tid()) != null;
        model.addAttribute("listOob", false); // 이 응답에서는 목록이 본 대상(#ak-list outerHTML)이다
        model.addAttribute("badgeOob", ok);
        return LIST_VIEW;
    }

    // ── 경계 ────────────────────────────────────────────────

    record Owned(String tid, ApiKeyAdminDto.ApiKeyView key) {}

    /** 키가 속한 tenant 를 경계 확인한 뒤(RP 다른 tenant → 403) 그 tenant 의 목록에서 키를 찾는다. 없으면 404(API_KEY_NOT_FOUND). */
    private Owned owned(UUID keyId, String tenantId) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, tenantId);
        String tid = v.id().toString();
        for (ApiKeyAdminDto.ApiKeyView k : apiKeys.list(tid).data()) {
            if (keyId.equals(k.id()) && v.id().equals(k.tenantId())) return new Owned(tid, k);
        }
        throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
    }

    /** 회전·회수는 확인 다이얼로그의 확인 버튼(X-Confirm)으로만 받는다. */
    private static void requireConfirm(HttpServletRequest req) {
        if (!Toasts.isConfirm(req)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "확인 후 다시 시도하세요.");
    }

    // ── 응답 도우미 ─────────────────────────────────────────

    private static void noStore(HttpServletResponse res) {
        res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        res.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    private String issueError(HttpServletResponse res, Model model, String title, String detail) {
        res.setStatus(422);
        model.addAttribute("issueError", issueErrorText(title, detail));
        return ISSUE_ERROR_VIEW;
    }

    /** {@code [title, detail.trim().replace(/[.。]$/, '')].filter(Boolean).join('. ') + '. 입력한 내용은 그대로 있습니다.'} */
    static String issueErrorText(String title, String detail) {
        List<String> parts = new ArrayList<>();
        if (title != null && !title.isEmpty()) parts.add(title);
        String d = detail == null ? "" : detail.trim().replaceAll("[.。]$", "");
        if (!d.isEmpty()) parts.add(d);
        return String.join(". ", parts) + ". 입력한 내용은 그대로 있습니다.";
    }

    private String violation(ApiKeyAdminDto.ApiKeyCreateRequest req) {
        Set<ConstraintViolation<ApiKeyAdminDto.ApiKeyCreateRequest>> vs = validator.validate(req);
        if (vs.isEmpty()) return null;
        String path = vs.iterator().next().getPropertyPath().toString();
        for (ConstraintViolation<ApiKeyAdminDto.ApiKeyCreateRequest> cv : vs) {
            String p = cv.getPropertyPath().toString();
            if (p.startsWith("name")) return req.name() == null || req.name().isBlank() ? "용도(이름)를 입력하세요" : "용도(이름)는 " + NAME_MAX + "자 이하로 입력하세요";
        }
        if (path.startsWith("scopes")) return "권한 범위를 하나 이상 선택하세요";
        if (path.startsWith("expiresInMonths")) return "만료 기간이 올바르지 않습니다";
        return "입력 값이 올바르지 않습니다";
    }

    /** 변경 뒤 목록을 다시 읽어 목록·탭 배지(OOB)를 채운다. 다시 읽기가 실패하면 목록 자리에 LoadError. */
    private List<ApiKeyAdminDto.ApiKeyView> refreshAfterChange(Model model, String tid) {
        fillForm(model, tid);
        model.addAttribute("listOob", true);
        model.addAttribute("badgeOob", true);
        try {
            List<ApiKeyAdminDto.ApiKeyView> keys = apiKeys.list(tid).data();
            fillList(model, tid, keys);
            return keys;
        } catch (RuntimeException e) {
            listFailed(model, tid, e);
            model.addAttribute("badgeOob", false);
            return null;
        }
    }

    private void listFailed(Model model, String tid, RuntimeException e) {
        log.warn("[apikeys-tab] list failed: {}", e.getClass().getSimpleName());
        model.addAttribute("loadError", e);
        model.addAttribute("retryAttrs", TenantTabs.requesterAttrs(FRAG + tid + "/tab/apikeys"));
        model.addAttribute("rows", List.of());
        model.addAttribute("kpis", new Kpis("—", null, "—", null, false));
    }

    private void fillForm(Model model, String tid) {
        model.addAttribute("tid", tid);
        model.addAttribute("issueUrl", FRAG + tid + "/api-keys");
        model.addAttribute("nameMax", NAME_MAX);
        model.addAttribute("rotationDays", ROTATION_DAYS);
        model.addAttribute("scopeOptions", SCOPES.stream().map(s -> new Scope(s, SCOPE_DESC.get(s))).toList());
        List<ExpiryOption> opts = new ArrayList<>();
        Map<String, String> previews = new LinkedHashMap<>();
        for (Integer m : EXPIRY_MONTHS) {
            opts.add(new ExpiryOption(m == null ? "" : String.valueOf(m), m == null ? "무기한" : m + "개월"));
            if (m != null) previews.put(String.valueOf(m), previewExpiry(m, clock.instant()));
        }
        model.addAttribute("expiryOptions", opts);
        model.addAttribute("expiryPreviews", previews);
        model.addAttribute("loadError", null);
    }

    private void fillList(Model model, String tid, List<ApiKeyAdminDto.ApiKeyView> keys) {
        Instant now = clock.instant();
        List<Row> rows = new ArrayList<>();
        int active = 0, expired = 0, revoked = 0;
        OffsetDateTime latest = null;
        for (ApiKeyAdminDto.ApiKeyView k : keys) {
            String st = status(k, now);
            switch (st) {
                case "ACTIVE" -> active++;
                case "EXPIRED" -> expired++;
                default -> revoked++;
            }
            if (k.createdAt() != null && (latest == null || k.createdAt().isAfter(latest))) latest = k.createdAt();
            rows.add(row(tid, k, st, now));
        }
        int overdue = rotationOverdueCount(keys, now);
        model.addAttribute("rows", rows);
        model.addAttribute("kpis", new Kpis(String.valueOf(keys.size()), "활성 " + active + " · 만료 " + expired + " · 회수 " + revoked,
                latest == null ? "—" : KstFormat.timeAgo(latest.toInstant(), now), "초과 키 " + overdue + "개", overdue > 0));
        int expiring = (int) keys.stream().filter(k -> ApiKeyExpiry.isExpiringSoon(k.revokedAt(), k.expiresAt(), now)).count();
        model.addAttribute("badgeText", expiring > 0 ? "확인 필요 " + expiring : null);
        model.addAttribute("badgeReason", expiring > 0 ? "30일 안에 만료되는 키 " + expiring + "개" : null);
    }

    private Row row(String tid, ApiKeyAdminDto.ApiKeyView k, String st, Instant now) {
        String name = k.name() == null ? "" : k.name();
        String prefix = k.keyPrefix() == null ? "" : k.keyPrefix();
        List<Scope> scopes = new ArrayList<>();
        if (k.scopes() != null) for (String s : k.scopes()) scopes.add(new Scope(s, SCOPE_LABEL.getOrDefault(s, s)));
        String keyId = String.valueOf(k.id());
        String q = "?tenantId=" + tid;
        Map<String, Object> rotateAttrs = new LinkedHashMap<>();
        rotateAttrs.put("hx-post", KEY_FRAG + keyId + "/rotate" + q);
        rotateAttrs.put("hx-target", "#ak-issued-slot");
        rotateAttrs.put("hx-swap", "innerHTML");
        rotateAttrs.put("hx-sync", "#ak-root:drop");
        Map<String, Object> revokeAttrs = new LinkedHashMap<>();
        revokeAttrs.put("hx-delete", KEY_FRAG + keyId + q);
        revokeAttrs.put("hx-target", "#ak-list");
        revokeAttrs.put("hx-swap", "outerHTML");
        revokeAttrs.put("hx-sync", "#ak-root:drop");
        List<Map<String, Object>> items = List.of(
                menuItem("회전", "refresh", false, Map.of("data-dialog", "ak-rotate-" + keyId, "x-on:click.capture", OPEN_DIALOG)),
                menuItem("접두사 복사", "copy", false, Map.of("data-prefix", prefix, "x-on:click.capture", COPY_PREFIX)));
        List<Map<String, Object>> danger = List.of(
                menuItem("회수", "trash", true, Map.of("data-dialog", "ak-revoke-" + keyId, "x-on:click.capture", OPEN_DIALOG)));
        String lastUsed = k.lastUsedAt() == null ? null : KstFormat.timeAgo(k.lastUsedAt().toInstant(), now);
        return new Row(keyId, name, prefix, st, scopes, lastUsed == null ? "미사용" : lastUsed, lastUsed == null ? "사용 안 함" : lastUsed,
                KstFormat.kstDate(k.createdAt()), expiryBadge(st, k.expiresAt(), now), rotateAttrs, revokeAttrs, items, danger);
    }

    /** 메뉴 항목이 여는 확인 다이얼로그(id 는 data-dialog). 템플릿 상수. */
    static final String OPEN_DIALOG = "$dispatch('dialog:open', { id: $el.dataset.dialog })";
    /** 접두사 복사 + 토스트(원본 copyPrefix). 접두사는 data-prefix 로 넘긴다. 템플릿 상수. */
    static final String COPY_PREFIX = "window.adminCopy($el.dataset.prefix).then((ok) => $dispatch('toast', ok"
            + " ? { kind: 'success', title: '접두사를 복사했습니다.', message: $el.dataset.prefix }"
            + " : { kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' }))";

    private static Map<String, Object> menuItem(String label, String icon, boolean danger, Map<String, Object> attrs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("icon", icon);
        m.put("danger", danger);
        m.put("attrs", attrs);
        return m;
    }

    // ── apiKeyHelpers.ts ────────────────────────────────────

    /** api/apiKeys.ts adapt: revokedAt → REVOKED, 만료 시각이 지났으면 EXPIRED, 그 밖 ACTIVE. */
    static String status(ApiKeyAdminDto.ApiKeyView k, Instant now) {
        if (k.revokedAt() != null) return "REVOKED";
        if (k.expiresAt() != null && !k.expiresAt().toInstant().isAfter(now)) return "EXPIRED";
        return "ACTIVE";
    }

    /** none | expired | soon | ok */
    static String expiryState(OffsetDateTime expiresAt, Instant now) {
        if (expiresAt == null) return "none";
        long exp = expiresAt.toInstant().toEpochMilli();
        long n = now.toEpochMilli();
        if (exp <= n) return "expired";
        if (exp - n <= ApiKeyExpiry.EXPIRY_WARN_DAYS * DAY_MS) return "soon";
        return "ok";
    }

    static long daysLeft(OffsetDateTime expiresAt, Instant now) {
        return (long) Math.ceil((expiresAt.toInstant().toEpochMilli() - now.toEpochMilli()) / (double) DAY_MS);
    }

    /** 만료 열 배지 — 색만이 아니라 글로 상태를 말한다(정상 · 만료 임박 N일 · 만료됨 · 무기한 · 해당 없음). */
    public static ExpiryBadge expiryBadge(String status, OffsetDateTime expiresAt, Instant now) {
        if (expiresAt == null) return new ExpiryBadge("neutral", "무기한", "만료일 없음");
        String sub = KstFormat.kstDate(expiresAt);
        if ("REVOKED".equals(status)) return new ExpiryBadge("neutral", "해당 없음", sub);
        String st = "EXPIRED".equals(status) ? "expired" : expiryState(expiresAt, now);
        if ("expired".equals(st)) return new ExpiryBadge("bad", "만료됨", sub);
        if ("soon".equals(st)) return new ExpiryBadge("warn", "만료 임박 · " + daysLeft(expiresAt, now) + "일 남음", sub);
        return new ExpiryBadge("ok", "정상", sub);
    }

    /** 권장 회전 주기(90일)를 넘긴 ACTIVE 키 수. createdAt 기준(정확히 90일은 초과 아님). */
    static int rotationOverdueCount(List<ApiKeyAdminDto.ApiKeyView> keys, Instant now) {
        int n = 0;
        for (ApiKeyAdminDto.ApiKeyView k : keys) {
            if (!"ACTIVE".equals(status(k, now)) || k.createdAt() == null) continue;
            if (now.toEpochMilli() - k.createdAt().toInstant().toEpochMilli() > ROTATION_DAYS * DAY_MS) n++;
        }
        return n;
    }

    /** now + N개월의 KST 날짜(YYYY-MM-DD). 다음 달에 같은 일자가 없으면 그 달 말일(plusMonths 의 말일 보정). */
    static String previewExpiry(int months, Instant now) {
        return now.atZone(KstFormat.KST).plusMonths(months).toLocalDate().toString();
    }

    /** 회전 뒤 이전 키 만료 안내. 시간은 반올림, 음수는 0. */
    static String oldKeyNotice(OffsetDateTime oldKeyExpiresAt, Instant now) {
        if (oldKeyExpiresAt == null) return null;
        long hours = Math.max(0, Math.round(Duration.between(now, oldKeyExpiresAt.toInstant()).toMillis() / 3_600_000d));
        return "이전 키는 " + hours + "시간 후(" + KstFormat.kstDateTimeMin(oldKeyExpiresAt) + ") 만료됩니다. 그 전에 RP 서버를 새 키로 교체하세요.";
    }
}
