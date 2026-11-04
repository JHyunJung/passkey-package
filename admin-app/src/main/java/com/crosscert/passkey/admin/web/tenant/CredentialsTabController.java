package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.credential.CredentialAdminController;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.AuthEventView;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.CsvWriter;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.api.PageView;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant 상세의 자격 증명 탭(← pages/tenant/CredentialsTab.tsx, CredentialDetailDialog.tsx, credentialHelpers.ts, api/credentials.ts).
 *
 * <p>접근 경계: 모든 엔드포인트가 {@link TenantTabs#load} 를 먼저 부른다(RP 다른 tenant → 403). 상세·회수 주소는 credentialId 로 되어 있어,
 * 화면은 그 tenant 의 목록(현재 화면의 페이지 창, 없으면 id 검색)에서 그 credential 을 찾은 뒤에만 REST 를 부른다 — 없으면 404, 변경 호출 없음.
 * (REST 서비스도 tenantId 일치를 검사하지만 화면은 거기에 기대지 않는다.)
 *
 * <p>목록 상태(page·size·q·aaguid·status)는 모두 요청 파라미터다. 서버는 상태를 갖지 않고 매 요청의 입력값(hx-include)을 읽는다.
 * aaguid·status 는 원본처럼 "현재 페이지 안에서" 거른다(REST 에 그 파라미터가 없다).
 * credential 데이터(사용자 핸들·별칭·인증기 이름 등)는 사용자가 정할 수 있는 값이라 속성 중 JS 로 평가되는 곳에는 쓰지 않는다
 * (URL 은 인코딩해 hx-* 주소에만, 확인 입력값은 base64url 로 검증한 credentialId 꼬리).
 */
@Slf4j
@Controller
public class CredentialsTabController {

    static final String FRAG = "/admin/_/tenants/";
    static final String TAB_VIEW = "admin/tenant/credentials :: tab";
    static final String LIST_VIEW = "admin/tenant/credential-rows :: response";
    static final String DETAIL_VIEW = "admin/tenant/credential-detail :: detail";
    static final String REVOKE_VIEW = "admin/tenant/credential-detail :: revoke";

    static final List<Integer> PAGE_SIZES = List.of(50, 100, 200);
    static final String ALL = "ALL";
    static final String ACTIVE = "ACTIVE";
    static final String REVOKED = "REVOKED";
    static final Map<String, String> STATUS_LABELS = Map.of(ALL, "전체", ACTIVE, "활성", REVOKED, "회수됨");
    static final Map<String, String> TRANSPORT_LABELS = Map.of(
            "internal", "내장", "usb", "USB", "nfc", "NFC", "ble", "블루투스", "hybrid", "휴대폰 연동", "smart-card", "스마트카드");
    static final int EVENTS_SIZE = 20;
    private static final Pattern B64URL = Pattern.compile("[A-Za-z0-9_-]{1,512}");

    private static final List<Map<String, Object>> COLUMNS = List.of(
            col("id", "Credential ID", null, null, null), col("user", "사용자", null, null, null), col("nick", "별칭", null, null, null),
            col("authn", "인증기", null, null, null), col("tr", "전송 방식", null, null, null), col("cnt", "서명 카운터", "right", null, null),
            col("st", "상태", null, null, null), col("used", "마지막 사용", null, null, null), col("revoke", "회수", null, "48px", true));

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
    private final CredentialAdminController credentials;
    private final Clock clock;
    private final ObjectMapper json;

    public CredentialsTabController(TenantAdminController tenants, CredentialAdminController credentials, Clock clock, ObjectMapper json) {
        this.json = json;
        this.tenants = tenants;
        this.credentials = credentials;
        this.clock = clock;
    }

    // ── 모델 ────────────────────────────────────────────────

    /** 목록 상태. page 는 1부터. */
    record St(int page, int size, String q, String aaguid, String status) {
        static St of(String page, String size, String q, String aaguid, String status) {
            int sz = parseInt(size, PAGE_SIZES.get(0));
            return new St(Math.max(1, parseInt(page, 1)), PAGE_SIZES.contains(sz) ? sz : PAGE_SIZES.get(0),
                    q == null ? "" : q.trim(), aaguid == null ? "" : aaguid.trim(), normStatus(status));
        }

        St withPage(int p) { return new St(Math.max(1, p), size, q, aaguid, status); }

        boolean clientFiltered() { return !aaguid.isEmpty() || !ALL.equals(status); }

        /** 쿼리 문자열(인코딩 완료). 기본값도 명시한다. */
        String query() {
            StringBuilder b = new StringBuilder("page=" + page + "&size=" + size);
            if (!q.isEmpty()) b.append("&q=").append(enc(q));
            if (!aaguid.isEmpty()) b.append("&aaguid=").append(enc(aaguid));
            if (!ALL.equals(status)) b.append("&status=").append(status);
            return b.toString();
        }
    }

    /** 표의 한 줄(api/credentials.ts adapt + 열 계산). counter: value | na | zero. */
    public record Row(String id, String tail8, String userHandle, String nickname, String aaguid, String authenticator, boolean authenticatorNamed,
                      List<Transport> transports, String counterKind, long signCount, String lastUsed,
                      Map<String, Object> detailAttrs, Map<String, Object> revokeAttrs, String revokeLabel) {}

    public record Transport(String value, String label) {}

    /** 상세 다이얼로그에서 이전/다음으로 갈 주소(없으면 null). */
    public record Nav(String position, Map<String, Object> prev, Map<String, Object> next) {}

    public record Event(String when, String result, String reason, long signCount) {}

    // ── 화면 ────────────────────────────────────────────────

    /** 탭 조각. 첫 요청이라 상태는 기본값이지만 같은 파라미터도 받는다(주소로 직접 연 경우 대비). */
    @GetMapping(FRAG + "{id}/tab/credentials")
    public String tab(@PathVariable String id,
                      @RequestParam(name = "page", defaultValue = "1") String page,
                      @RequestParam(name = "size", defaultValue = "50") String size,
                      @RequestParam(name = "q", defaultValue = "") String q,
                      @RequestParam(name = "aaguid", defaultValue = "") String aaguid,
                      @RequestParam(name = "status", defaultValue = ALL) String status,
                      Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        St st = St.of(page, size, q, aaguid, status);
        model.addAttribute("tid", v.id().toString());
        fillList(model, v.id(), st, false, null);
        return TAB_VIEW;
    }

    /**
     * 목록 조각(검색·필터·페이징·회수 뒤 새로고침). 교체 대상은 탭 영역이 아니라 탭 안의 {@code #cred-results} 다.
     * 조회 실패는 200 으로 그 자리에 LoadError(원본: 다른 화면 요청이 실패하면 이전 행을 남기지 않는다).
     * {@code reset} 은 aaguid | status | all — 해당 입력을 비운 값으로 OOB 되돌려 쓴다(그 밖의 요청은 현재 입력값을 싣고 오므로 되돌려 쓰지 않는다).
     */
    @GetMapping(FRAG + "{id}/tab/credentials/list")
    public String list(@PathVariable String id,
                       @RequestParam(name = "page", defaultValue = "1") String page,
                       @RequestParam(name = "size", defaultValue = "50") String size,
                       @RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "aaguid", defaultValue = "") String aaguid,
                       @RequestParam(name = "status", defaultValue = ALL) String status,
                       @RequestParam(name = "reset", required = false) String reset,
                       Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        St st = St.of(page, size, q, aaguid, status);
        if ("aaguid".equals(reset) || "all".equals(reset)) st = new St(st.page(), st.size(), st.q(), "", st.status());
        if ("status".equals(reset) || "all".equals(reset)) st = new St(st.page(), st.size(), st.q(), st.aaguid(), ALL);
        if ("all".equals(reset)) st = new St(1, st.size(), "", st.aaguid(), st.status());
        model.addAttribute("tid", v.id().toString());
        fillList(model, v.id(), st, false, reset);
        return LIST_VIEW;
    }

    /**
     * 상세 다이얼로그(← CredentialDetailDialog). credential 은 이 tenant 의 목록에서 찾는다(page·size·q 의 창, 없으면 id 검색) — 없으면 404.
     * 이전/다음은 필터를 건 뒤 목록 안에서 바로, 경계에서는 필터가 없을 때만 이웃 페이지의 첫/마지막 항목으로 잇는다.
     * {@code list=1} 이면(이웃 페이지로 넘어간 경우) 그 페이지의 목록도 OOB 로 돌려준다.
     */
    @GetMapping(FRAG + "{id}/credentials/{cid}")
    public String detail(@PathVariable String id, @PathVariable String cid,
                         @RequestParam(name = "page", defaultValue = "1") String page,
                         @RequestParam(name = "size", defaultValue = "50") String size,
                         @RequestParam(name = "q", defaultValue = "") String q,
                         @RequestParam(name = "aaguid", defaultValue = "") String aaguid,
                         @RequestParam(name = "status", defaultValue = ALL) String status,
                         @RequestParam(name = "list", required = false) String listToo,
                         Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        UUID tid = v.id();
        St st = St.of(page, size, q, aaguid, status);
        Found f = find(tid, cid, st);
        CredentialView c = f.view();
        model.addAttribute("tid", tid.toString());
        model.addAttribute("c", row(tid, c, st));
        model.addAttribute("createdAt", KstFormat.kstDateTimeMin(c.createdAt()));
        model.addAttribute("lastUsedAt", c.lastUsedAt() == null ? "—" : KstFormat.timeAgo(c.lastUsedAt().toInstant(), clock.instant()));
        model.addAttribute("transportsRaw", transports(c.transports()).stream().map(Transport::value).toList());
        model.addAttribute("attestation", c.attestationFormat());
        model.addAttribute("nav", f.window() == null ? null : nav(tid, cid, st, f));
        model.addAttribute("revokeGetAttrs", revokeConfirmAttrs(tid, cid, st));
        model.addAttribute("detailRetryAttrs", detailAttrs(tid, cid, st, false, false));
        model.addAttribute("authName", c.authenticatorName());
        try {
            PageView<AuthEventView> ev = credentials.authEvents(tid, cid, 0, EVENTS_SIZE).data();
            List<Event> events = new ArrayList<>();
            for (AuthEventView e : ev.content()) {
                events.add(new Event(KstFormat.kstDateTimeMin(e.createdAt()), "SUCCESS".equals(e.result()) ? "SUCCESS" : "FAILED",
                        e.failureReason(), e.signCount()));
            }
            model.addAttribute("events", events);
            model.addAttribute("eventsError", null);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[credentials-tab] auth events failed: {}", e.getClass().getSimpleName());
            model.addAttribute("events", List.of());
            model.addAttribute("eventsError", e);
        }
        boolean oob = "1".equals(listToo);
        if (oob && f.window() != null) {
            // 이웃 페이지로 넘어갔다 — 그 페이지의 목록(방금 읽은 창)을 함께 갱신한다.
            fillRows(model, tid, st, f.window(), null, true, null);
        } else {
            model.addAttribute("listOob", false);
        }
        return DETAIL_VIEW;
    }

    /** 회수 확인 다이얼로그(L3, 마지막 8자 입력). 열어 둔 채 응답한다 — 확인 버튼이 DELETE 를 보낸다. */
    @GetMapping(FRAG + "{id}/credentials/{cid}/revoke-confirm")
    public String revokeConfirm(@PathVariable String id, @PathVariable String cid,
                                @RequestParam(name = "page", defaultValue = "1") String page,
                                @RequestParam(name = "size", defaultValue = "50") String size,
                                @RequestParam(name = "q", defaultValue = "") String q,
                                @RequestParam(name = "aaguid", defaultValue = "") String aaguid,
                                @RequestParam(name = "status", defaultValue = ALL) String status,
                                Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        St st = St.of(page, size, q, aaguid, status);
        CredentialView c = find(v.id(), cid, st).view();
        Row r = row(v.id(), c, st);
        model.addAttribute("c", r);
        model.addAttribute("typeLabel", "확인을 위해 마지막 8자 " + r.tail8() + " 를 입력하세요");
        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("hx-delete", FRAG + v.id() + "/credentials/" + cid + "?" + st.query());
        confirm.put("hx-target", "#cred-results");
        confirm.put("hx-swap", "outerHTML");
        confirm.put("hx-sync", "this:drop");
        model.addAttribute("confirmAttrs", confirm);
        return REVOKE_VIEW;
    }

    /**
     * 회수(L3 확인 뒤). 화면이 먼저 이 tenant 의 목록에서 credential 을 확인한다(없으면 404, REST 호출 없음).
     * 성공이면 목록을 다시 읽어 그리고 성공 토스트. 실패는 WebExceptionAdvice 가 확인 다이얼로그 안 인라인(confirmError)으로 알린다.
     */
    @DeleteMapping(FRAG + "{id}/credentials/{cid}")
    public String revoke(@PathVariable String id, @PathVariable String cid,
                         @RequestParam(name = "page", defaultValue = "1") String page,
                         @RequestParam(name = "size", defaultValue = "50") String size,
                         @RequestParam(name = "q", defaultValue = "") String q,
                         @RequestParam(name = "aaguid", defaultValue = "") String aaguid,
                         @RequestParam(name = "status", defaultValue = ALL) String status,
                         Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        if (!Toasts.isConfirm(req)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "확인 후 다시 시도하세요.");
        St st = St.of(page, size, q, aaguid, status);
        CredentialView c = find(v.id(), cid, st).view();
        credentials.revoke(v.id(), cid, auth);
        Toasts.successTitled(res, "Credential을 회수했습니다.", tail(c.credentialId(), 12) + " · " + c.userHandle());
        model.addAttribute("tid", v.id().toString());
        fillList(model, v.id(), st, false, null); // 회수는 이미 성공했다 — 목록 새로고침 실패는 목록 자리의 LoadError 로
        return LIST_VIEW;
    }

    /** 현재 페이지에 보이는 행의 CSV(원본 downloadCsv: credentials-{slug}-{KST 오늘}.csv). */
    @GetMapping(FRAG + "{id}/credentials.csv")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(name = "page", defaultValue = "1") String page,
                                         @RequestParam(name = "size", defaultValue = "50") String size,
                                         @RequestParam(name = "q", defaultValue = "") String q,
                                         @RequestParam(name = "aaguid", defaultValue = "") String aaguid,
                                         @RequestParam(name = "status", defaultValue = ALL) String status) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        St st = St.of(page, size, q, aaguid, status);
        Loaded l = load(v.id(), st);
        List<List<String>> body = new ArrayList<>();
        for (CredentialView c : applyClientFilter(l.pv().content(), l.st())) {
            body.add(List.of(c.credentialId(), c.userHandle(), nz(c.label()), nz(c.authenticatorName()), ACTIVE, nz(c.aaguidHex()),
                    String.join("|", transports(c.transports()).stream().map(Transport::value).toList()), String.valueOf(c.signCount()),
                    iso(c.lastUsedAt()), iso(c.createdAt())));
        }
        byte[] csv = CsvWriter.write(List.of("credentialId", "externalUserId", "label", "authenticatorName", "status", "aaguid", "transports",
                "signatureCounter", "lastUsedAt", "createdAt"), body);
        String filename = "credentials-" + v.slug() + "-" + KstFormat.kstToday(clock.instant()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    // ── 조회·경계 ───────────────────────────────────────────

    record Loaded(St st, PageView<CredentialView> pv) {}

    record Found(CredentialView view, Loaded window) {}

    private PageView<CredentialView> fetch(UUID tid, int page, int size, String q) {
        return credentials.list(tid, page - 1, size, q == null || q.isEmpty() ? null : q).data();
    }

    /** 현재 상태의 페이지를 읽는다. 지운 뒤 마지막 페이지가 비어 버렸으면 새 마지막 페이지로 한 번 보정한다. */
    private Loaded load(UUID tid, St st) {
        PageView<CredentialView> pv = fetch(tid, st.page(), st.size(), st.q());
        long total = pv.totalElements();
        int last = (int) Math.max(1, (total + st.size() - 1) / st.size());
        if (st.page() > last) {
            st = st.withPage(last);
            pv = fetch(tid, st.page(), st.size(), st.q());
        }
        return new Loaded(st, pv);
    }

    /**
     * 이 tenant 의 목록에서 credential 을 찾는다. 먼저 화면이 보던 페이지 창, 없으면 id 로 검색(정확히 같은 id 만). 못 찾으면 404.
     * 다른 tenant 의 credential 은 이 tenant 의 목록에 없으므로 여기서 막힌다.
     */
    private Found find(UUID tid, String cid, St st) {
        if (!B64URL.matcher(cid).matches()) throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음");
        Loaded w = load(tid, st);
        for (CredentialView c : w.pv().content()) {
            if (cid.equals(c.credentialId())) return new Found(c, w);
        }
        for (CredentialView c : fetch(tid, 1, PAGE_SIZES.get(0), cid).content()) {
            if (cid.equals(c.credentialId())) return new Found(c, null);
        }
        throw new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음");
    }

    private static boolean denied(RuntimeException e) {
        return e instanceof AccessDeniedException
                || (e instanceof BusinessException be && be.getErrorCode() == ErrorCode.ACCESS_DENIED);
    }

    // ── 목록 모델 ───────────────────────────────────────────

    /** 목록을 읽어 모델을 채운다. 실패하면 loadError(권한 거부는 그대로 던진다). */
    private void fillList(Model model, UUID tid, St st, boolean listOob, String reset) {
        Loaded l = null;
        RuntimeException error = null;
        try {
            l = load(tid, st);
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[credentials-tab] list failed: {}", e.getClass().getSimpleName());
            error = e;
        }
        fillRows(model, tid, st, l, error, listOob, reset);
    }

    private void fillRows(Model model, UUID tid, St requested, Loaded l, RuntimeException error, boolean listOob, String reset) {
        St st = l == null ? requested : l.st();
        String base = FRAG + tid + "/tab/credentials/list";
        List<CredentialView> items = l == null ? List.of() : l.pv().content();
        List<CredentialView> visible = applyClientFilter(items, st);
        long total = l == null ? 0 : l.pv().totalElements();
        boolean cf = st.clientFiltered();

        model.addAttribute("tid", tid.toString());
        model.addAttribute("q", st.q());
        model.addAttribute("aaguidFilter", st.aaguid());
        model.addAttribute("status", st.status());
        model.addAttribute("size", st.size());
        model.addAttribute("page", st.page());
        model.addAttribute("pageSizes", PAGE_SIZES);
        model.addAttribute("columns", COLUMNS);
        model.addAttribute("total", total);
        model.addAttribute("rows", visible.stream().map(c -> row(tid, c, st)).toList());
        model.addAttribute("loadError", error);
        model.addAttribute("clientFiltered", cf);
        model.addAttribute("noData", total == 0 && st.q().isEmpty() && !cf);
        model.addAttribute("countText", error != null ? "—" : cf ? "이 페이지 " + num(items.size()) + "건 중 " + num(visible.size()) + "건" : num(total) + "건");
        model.addAttribute("csvLabel", "CSV 내보내기 (현재 페이지 " + num(visible.size()) + "건)");
        model.addAttribute("csvDisabled", error != null || visible.isEmpty());
        model.addAttribute("csvUrl", FRAG + tid + "/credentials.csv?" + st.query());
        model.addAttribute("statusLabel", STATUS_LABELS.get(st.status()));
        model.addAttribute("listOob", listOob);
        model.addAttribute("oobQ", "all".equals(reset));
        model.addAttribute("oobPanel", reset != null);

        // 요청 속성: 검색·필터가 이어 받는 입력(현재 입력값)을 hx-include 로 싣는다. 값은 모두 서버가 정한 주소·셀렉터다.
        String inclSize = "#cred-size";
        model.addAttribute("pageBase", base);
        model.addAttribute("includeAll", "#cred-q, #cred-aaguid, #cred-status, #cred-size");
        model.addAttribute("includeForQ", "#cred-aaguid, #cred-status, " + inclSize);
        model.addAttribute("includeForAaguid", "#cred-q, #cred-status, #cred-page, " + inclSize);
        model.addAttribute("includeForSize", "#cred-q, #cred-aaguid, #cred-status");
        model.addAttribute("includeForStatus", "#cred-q, #cred-aaguid, " + inclSize);
        model.addAttribute("includeForAaguidChip", "#cred-q, #cred-status, " + inclSize);
        model.addAttribute("includeForStatusChip", "#cred-q, #cred-aaguid, " + inclSize);

        List<Map<String, Object>> options = new ArrayList<>();
        for (String s : List.of(ALL, ACTIVE, REVOKED)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", s);
            o.put("label", STATUS_LABELS.get(s));
            o.put("title", ALL.equals(s) ? null : s);
            o.put("attrs", swap(base + "?status=" + s, "#cred-q, #cred-aaguid, #cred-page, " + inclSize));
            options.add(o);
        }
        model.addAttribute("statusOptions", options);
        model.addAttribute("chipAaguidAttrs", swap(base + "?reset=aaguid", "#cred-q, #cred-status, #cred-page, " + inclSize));
        model.addAttribute("chipStatusAttrs", swap(base + "?status=ALL&reset=status", "#cred-q, #cred-aaguid, #cred-page, " + inclSize));
        model.addAttribute("clearAttrs", swap(base + "?reset=all", inclSize));
        Map<String, Object> retry = swap(base + "?" + st.query(), null);
        model.addAttribute("retryAttrs", retry);
        // 이전/다음 페이지: 쪽 번호만 주소에, 나머지는 현재 입력값(hx-include)
        model.addAttribute("pageUrlBase", base + "?page=");
    }

    private static Map<String, Object> swap(String url, String include) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", url);
        if (include != null) m.put("hx-include", include);
        m.put("hx-target", "#cred-results");
        m.put("hx-swap", "outerHTML");
        return m;
    }

    private Row row(UUID tid, CredentialView c, St st) {
        String cid = c.credentialId();
        String tail8 = tail(cid, 8);
        boolean named = c.authenticatorName() != null && !c.authenticatorName().isEmpty();
        long cnt = c.signCount();
        String kind = cnt > 0 ? "value" : c.lastUsedAt() != null ? "na" : "zero";
        String lastUsed = c.lastUsedAt() == null ? "—" : KstFormat.timeAgo(c.lastUsedAt().toInstant(), clock.instant());
        return new Row(cid, tail8, c.userHandle(), c.label(), c.aaguidHex(), authenticatorLabel(c), named, transports(c.transports()), kind, cnt, lastUsed,
                detailAttrs(tid, cid, st, false, true), revokeConfirmAttrs(tid, cid, st), "회수 · …" + tail8);
    }

    /** 행 클릭(cred-open 이벤트)이 상세 다이얼로그를 불러온다. */
    private static Map<String, Object> detailAttrs(UUID tid, String cid, St st, boolean listToo, boolean rowClick) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", FRAG + tid + "/credentials/" + cid + "?" + st.query() + (listToo ? "&list=1" : ""));
        if (rowClick) m.put("hx-trigger", "cred-open"); // 행만 이벤트로 연다. 버튼(이전/다음/다시 시도)은 기본 click.
        m.put("hx-target", "#cred-detail-slot");
        m.put("hx-swap", "innerHTML");
        m.put("hx-sync", "#cred-detail-slot:replace");
        return m;
    }

    private static Map<String, Object> revokeConfirmAttrs(UUID tid, String cid, St st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-get", FRAG + tid + "/credentials/" + cid + "/revoke-confirm?" + st.query());
        m.put("hx-target", "#cred-confirm-slot");
        m.put("hx-swap", "innerHTML");
        m.put("hx-sync", "#cred-confirm-slot:replace");
        return m;
    }

    private Nav nav(UUID tid, String cid, St st, Found f) {
        List<CredentialView> visible = applyClientFilter(f.window().pv().content(), f.window().st());
        St cur = f.window().st();
        int idx = -1;
        for (int i = 0; i < visible.size(); i++) if (cid.equals(visible.get(i).credentialId())) idx = i;
        if (idx < 0) return null;
        long total = f.window().pv().totalElements();
        int lastPage = (int) Math.max(1, (total + cur.size() - 1) / cur.size());
        boolean cf = cur.clientFiltered();
        String pos = cf ? (idx + 1) + " / " + visible.size() : ((long) (cur.page() - 1) * cur.size() + idx + 1) + " / " + total;

        Map<String, Object> prev = null;
        Map<String, Object> next = null;
        if (idx > 0) {
            prev = detailAttrs(tid, visible.get(idx - 1).credentialId(), cur, false, false);
        } else if (!cf && cur.page() > 1) {
            St p = cur.withPage(cur.page() - 1);
            List<CredentialView> items = fetchQuiet(tid, p);
            if (!items.isEmpty()) prev = detailAttrs(tid, items.get(items.size() - 1).credentialId(), p, true, false);
        }
        if (idx < visible.size() - 1) {
            next = detailAttrs(tid, visible.get(idx + 1).credentialId(), cur, false, false);
        } else if (!cf && cur.page() < lastPage) {
            St p = cur.withPage(cur.page() + 1);
            List<CredentialView> items = fetchQuiet(tid, p);
            if (!items.isEmpty()) next = detailAttrs(tid, items.get(0).credentialId(), p, true, false);
        }
        return new Nav(pos, prev, next);
    }

    /** 이웃 페이지 읽기. 실패하면(이동 버튼만 비활성) 조용히 빈 목록. */
    private List<CredentialView> fetchQuiet(UUID tid, St st) {
        try {
            return fetch(tid, st.page(), st.size(), st.q()).content();
        } catch (RuntimeException e) {
            if (denied(e)) throw e;
            log.warn("[credentials-tab] neighbour page failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ── credentialHelpers.ts ────────────────────────────────

    static String norm(String s) {
        return s.replace("-", "").toLowerCase();
    }

    /** 서버에 aaguid·status 파라미터가 없어 현재 페이지 안에서만 거른다. status 는 서버 뷰에 없어 모두 ACTIVE(원본 adapt). */
    static List<CredentialView> applyClientFilter(List<CredentialView> items, St f) {
        String a = norm(f.aaguid().trim());
        List<CredentialView> out = new ArrayList<>();
        for (CredentialView c : items) {
            boolean statusOk = ALL.equals(f.status()) || ACTIVE.equals(f.status());
            boolean aaguidOk = a.isEmpty() || (c.aaguidHex() != null && norm(c.aaguidHex()).contains(a));
            if (statusOk && aaguidOk) out.add(c);
        }
        return out;
    }

    /** MDS 이름 → aaguid 끝 8자 → unknown. */
    static String authenticatorLabel(CredentialView c) {
        if (c.authenticatorName() != null && !c.authenticatorName().isEmpty()) return c.authenticatorName();
        if (c.aaguidHex() != null && !c.aaguidHex().isEmpty()) {
            String n = norm(c.aaguidHex());
            return n.length() <= 8 ? n : n.substring(n.length() - 8);
        }
        return "unknown";
    }

    static List<Transport> transports(String csv) {
        List<Transport> out = new ArrayList<>();
        if (csv == null) return out;
        for (String t : csv.split(",")) {
            String v = t.trim();
            if (!v.isEmpty()) out.add(new Transport(v, TRANSPORT_LABELS.getOrDefault(v, v)));
        }
        return out;
    }

    // ── 도우미 ──────────────────────────────────────────────

    static String normStatus(String s) {
        return ACTIVE.equals(s) || REVOKED.equals(s) ? s : ALL;
    }

    /** 숫자가 아닌 page/size 는 400 이 아니라 기본값. */
    static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** 원본 CSV 는 서버 JSON 의 문자열을 그대로 쓴다 — 같은 ObjectMapper 직렬화 형태. */
    private String iso(OffsetDateTime t) {
        if (t == null) return "";
        try {
            return json.convertValue(t, String.class);
        } catch (RuntimeException e) {
            return t.toString();
        }
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String num(long n) {
        return String.format(java.util.Locale.KOREA, "%,d", n);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
