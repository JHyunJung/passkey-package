package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.tenant.AllowedOriginFormat;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.tenant.WebauthnConfigDiff;
import com.crosscert.passkey.admin.tenant.WebauthnDiffRequest;
import com.crosscert.passkey.admin.web.format.ApkKeyHash;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.OriginValidation;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tenant 상세의 WebAuthn 설정 탭(← pages/tenant/WebauthnConfigTab.tsx, api/webauthn.ts).
 *
 * <p>모든 엔드포인트는 {@link TenantTabs#load} 로 접근 경계를 먼저 확인한다(RP_ADMIN 의 다른 테넌트 → 403). 화면에 없는 값(rpId·displayName·
 * acceptedFormats·{@code mdsRequired})은 요청에서 받지 않고 그때 서버에서 읽은 현재 값을 그대로 다시 보낸다 — api/webauthn.ts 의 adaptConfig·역변환과
 * 같은 결과이면서 오래된 값으로 다른 관리자의 변경을 되돌리지 않고 클라이언트가 위조할 수도 없다.
 *
 * <p>흐름: 폼 제출 = 미리보기({@code POST …/webauthn/diff}: 검증 → 서버 diff + 로컬 diff 병합 → 확인 다이얼로그 조각) → 다이얼로그 확인 =
 * 저장({@code PUT …/webauthn}: 비교한 바로 그 값, 성공하면 탭을 새 값으로 다시 그림). 검증 실패는 422 + 필드 옆 오류(OOB, 입력·변경 상태 유지),
 * 저장 단계 거절은 확인 다이얼로그 안 인라인 오류다.
 */
@Slf4j
@Controller
public class WebauthnTabController {

    static final String FRAG = "/admin/_/tenants/";
    static final String TAB_VIEW = "admin/tenant/webauthn :: tab";
    static final String RESULT_VIEW = "admin/tenant/webauthn-diff :: result";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    static final Map<String, String> UV_LABELS = Map.of("REQUIRED", "필수", "PREFERRED", "권장", "DISCOURAGED", "요구 안 함");
    static final Map<String, String> AT_LABELS = Map.of("NONE", "요청 안 함", "INDIRECT", "익명 허용", "DIRECT", "직접", "ENTERPRISE", "기업용");
    /** '요구 안 함'(DISCOURAGED)은 서버가 boolean 만 저장해 되돌아오지 않으므로 숨긴다(스펙 §8). */
    static final List<String> UV_VALUES = List.of("REQUIRED", "PREFERRED");
    static final List<String> AT_VALUES = List.of("NONE", "INDIRECT", "DIRECT", "ENTERPRISE");

    static final Map<String, String> UV_GUIDE = Map.of(
            "REQUIRED", "PIN·지문·얼굴 등 사용자 확인(UV)을 반드시 요구합니다. UV를 못 하는 인증기는 패스키 등록·인증이 실패합니다. 2FA 수준의 보안이 필요할 때 권장.",
            "PREFERRED", "가능하면 UV를 수행하되, 지원하지 않는 인증기는 UV 없이도 허용합니다. 보안과 호환성의 균형 — 대부분의 서비스 기본값.");
    static final Map<String, String> AT_GUIDE = Map.of(
            "NONE", "attestation을 요청하지 않습니다. 프라이버시가 가장 높고 등록이 단순해, 인증기 모델 검증이 불필요한 일반 서비스에 권장.",
            "INDIRECT", "attestation을 원하되 클라이언트가 익명화할 수 있게 허용합니다. 출처 정보를 받되 개별 기기 추적은 피하고 싶을 때.",
            "DIRECT", "인증기의 attestation을 그대로 받습니다. AAGUID·모델 확인이나 MDS 매칭으로 허용 기기를 통제하려면 필요. 등록 시 사용자 동의 프롬프트가 뜰 수 있습니다.",
            "ENTERPRISE", "기기를 개별 식별할 수 있는 attestation을 요구합니다. 사전 등록된 인증기만 쓰는 사내 환경 전용 — 일반 사용자 대상 서비스에는 부적합.");

    /** 화면 라벨. diff 다이얼로그·저장 바 요약·설정 행 이름에 쓰고, 원시 키는 title 툴팁으로 남긴다. */
    static final Map<String, String> FIELD_LABELS = Map.of(
            "rpId", "RP ID",
            "rpName", "RP 표시 이름",
            "timeoutMs", "대기 시간",
            "origins", "허용 origin",
            "userVerification", "사용자 검증",
            "requireUserVerification", "사용자 검증",
            "mdsRequired", "MDS 검증 필수",
            "attestationConveyance", "인증기 증명",
            "formats", "허용 증명 형식");
    static final List<String> FIELD_ORDER = List.of("rpName", "timeoutMs", "origins", "userVerification", "attestationConveyance", "formats");

    private final TenantAdminController tenants;
    private final Validator validator;

    public WebauthnTabController(TenantAdminController tenants, Validator validator) {
        this.tenants = tenants;
        this.validator = validator;
    }

    // ── 모델 ────────────────────────────────────────────────

    public record Guide(String tone, String text) {}

    /** 화면에 그리는 편집 값(폼 입력). timeoutMs 는 입력 원문이다. */
    public record Form(String rpId, String rpName, String timeoutMs, List<String> origins, String uv, String at) {
        static Form of(TenantAdminDto.TenantView v) {
            return new Form(v.rpId(), v.rpName(), String.valueOf(v.webauthnTimeoutMs()), List.copyOf(v.allowedOrigins()),
                    v.requireUserVerification() ? "REQUIRED" : "PREFERRED", v.attestationConveyance());
        }
    }

    /** 한 줄 변경(원본 Change). 배열 필드의 from/to 는 List<String>. */
    public record Change(String key, Object from, Object to) {}

    /** 변경 비교 한 줄(화면용). */
    public record DiffRow(String key, String label, boolean array, List<String> removed, List<String> added,
                          String from, String to, String fromTitle, String toTitle) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/webauthn")
    public String tab(@PathVariable String id, Model model) {
        TenantAdminDto.TenantView v;
        try {
            v = TenantTabs.load(tenants, id);
        } catch (BusinessException | AccessDeniedException e) {
            throw e; // 403·404 는 WebExceptionAdvice(본문 없음 + 토스트) — 데이터를 그리지 않는다
        } catch (RuntimeException e) {
            // 그 밖의 조회 실패는 인라인 LoadError(원본: 스켈레톤에 멈추지 않고 다시 시도)
            log.warn("[webauthn-tab] load failed: {}", e.toString());
            model.addAttribute("loadError", e);
            model.addAttribute("retryAttrs", TenantTabs.requesterAttrs(FRAG + id + "/tab/webauthn"));
            return TAB_VIEW;
        }
        fill(model, v, Form.of(v));
        return TAB_VIEW;
    }

    /** ChipInput 원격 검증(origin): 본문 = 오류 문구(비면 통과), 통과하면 X-Normalized-Value = 정규화한 origin. */
    @GetMapping(FRAG + "{id}/webauthn/origin-check")
    public ResponseEntity<String> originCheck(@PathVariable String id, @RequestParam(name = "value", defaultValue = "") String value,
                                              @RequestParam(name = "existing", required = false) List<String> existing) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        OriginValidation.Result r = OriginValidation.validate(value, v.rpId());
        if (!r.ok()) return text(r.error(), null);
        if (existing != null && existing.contains(r.value())) return text("'" + r.value() + "' 는 이미 추가되어 있습니다.", null);
        return text("", r.value());
    }

    /** Android 앱 키(SHA-256 지문 → apk-key-hash origin). 본문 = 오류 문구(비면 통과), X-Normalized-Value = origin. */
    @GetMapping(FRAG + "{id}/webauthn/apk-key")
    public ResponseEntity<String> apkKey(@PathVariable String id, @RequestParam(name = "value", defaultValue = "") String value,
                                         @RequestParam(name = "existing", required = false) List<String> existing) {
        TenantTabs.load(tenants, id);
        ApkKeyHash.Result r = ApkKeyHash.fromFingerprint(value);
        if (!r.ok()) return text(r.error(), null);
        if (existing != null && existing.contains(r.value())) return text("이미 추가된 Android 키입니다.", null);
        return text("", r.value());
    }

    /**
     * 미리보기(저장… 버튼). 검증 실패는 422 + 필드 옆 오류(OOB). 통과하면 서버 diff({@code TenantAdminController.diff})와 로컬 diff 를 합쳐
     * 확인 다이얼로그를 돌려준다. 변경이 없으면 경고 토스트만.
     */
    @PostMapping(FRAG + "{id}/webauthn/diff")
    public String diff(@PathVariable String id,
                       @RequestParam(name = "rpName", defaultValue = "") String rpName,
                       @RequestParam(name = "timeoutMs", defaultValue = "") String timeoutMs,
                       @RequestParam(name = "origins", required = false) List<String> origins,
                       @RequestParam(name = "userVerification", defaultValue = "") String uv,
                       @RequestParam(name = "attestationConveyance", defaultValue = "") String at,
                       HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Form draft = new Form(v.rpId(), rpName, timeoutMs.trim(), clean(origins), uv, at);
        Map<String, String> errors = validate(v, draft);
        model.addAttribute("errors", errors);
        model.addAttribute("dialog", false);
        if (!errors.isEmpty()) {
            res.setStatus(422);
            return RESULT_VIEW;
        }

        List<Change> merged;
        try {
            List<Change> local = diffObjects(Form.of(v), draft);
            WebauthnConfigDiff server = tenants.diff(v.id().toString(), new WebauthnDiffRequest(
                    v.rpId(), draft.rpName(), draft.origins(), v.acceptedFormats(), "REQUIRED".equals(draft.uv()), v.mdsRequired()));
            merged = mergeChanges(adaptDiff(server), local);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[webauthn-tab] diff failed: {}", e.toString());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            int status = info.httpStatus() == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : info.httpStatus();
            Toasts.errorTitled(res, "변경 사항을 비교하지 못했습니다", info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
            res.setStatus(status);
            return EMPTY_VIEW;
        }
        if (merged.isEmpty()) {
            Toasts.warnTitled(res, "변경 사항이 없습니다.", "");
            return RESULT_VIEW;
        }
        List<DiffRow> rows = new ArrayList<>();
        for (Change c : merged) rows.add(row(c));
        model.addAttribute("dialog", true);
        model.addAttribute("rows", rows);
        model.addAttribute("title", "변경 사항 " + merged.size() + "개를 저장할까요?");
        model.addAttribute("draft", draft);
        model.addAttribute("changeCount", merged.size());
        model.addAttribute("saveAttrs", saveAttrs(v));
        return RESULT_VIEW;
    }

    /**
     * 저장(확인 다이얼로그의 확인). 비교한 값 그대로 {@code TenantAdminController.update} 로 보낸다. 화면에 없는 값은 지금 서버 값.
     * 성공하면 탭을 새 값으로 다시 그리고({@code HX-Trigger: saved}) 토스트를 띄운다. 검증·REST 거절은 다이얼로그 안 인라인 오류.
     */
    @PutMapping(FRAG + "{id}/webauthn")
    public String save(@PathVariable String id,
                       @RequestParam(name = "rpName", defaultValue = "") String rpName,
                       @RequestParam(name = "timeoutMs", defaultValue = "") String timeoutMs,
                       @RequestParam(name = "origins", required = false) List<String> origins,
                       @RequestParam(name = "userVerification", defaultValue = "") String uv,
                       @RequestParam(name = "attestationConveyance", defaultValue = "") String at,
                       @RequestParam(name = "changes", defaultValue = "0") String changes,
                       Authentication auth, HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Form draft = new Form(v.rpId(), rpName, timeoutMs.trim(), clean(origins), uv, at);
        Map<String, String> errors = validate(v, draft);
        if (!errors.isEmpty()) {
            // 비교 단계가 이미 걸렀으므로 여기서의 실패는 위조·경합이다: 다이얼로그 안 인라인 오류(스왑 없음).
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            Toasts.confirmError(res, errors.values().iterator().next(), null, null);
            return EMPTY_VIEW;
        }
        TenantAdminDto.TenantView updated = tenants.update(v.id().toString(), request(v, draft), auth).data();
        int n = parseCount(changes);
        Toasts.successSaved(res, "WebAuthn 설정을 저장했습니다", n + "개 항목이 바뀌었습니다.");
        fill(model, updated, Form.of(updated));
        return TAB_VIEW;
    }

    // ── 검증 ────────────────────────────────────────────────

    /** 필드 이름 → 오류 문구(비면 통과). 서버(REST)가 거절할 값을 미리 걸러 필드 옆에 보인다. */
    Map<String, String> validate(TenantAdminDto.TenantView v, Form f) {
        Map<String, String> e = new LinkedHashMap<>();
        if (f.rpName().isBlank()) e.put("rpName", "RP 표시 이름을 입력하세요.");
        Integer ms = parseMs(f.timeoutMs());
        if (ms == null || ms < 1000 || ms > 600000) e.put("timeoutMs", "대기 시간은 1000–600000ms 범위의 숫자여야 합니다.");
        if (f.origins().isEmpty()) {
            e.put("origins", "허용 origin 을 하나 이상 추가하세요.");
        } else {
            List<String> bad = new ArrayList<>();
            for (String o : f.origins()) if (!AllowedOriginFormat.isValid(o)) bad.add("'" + o + "' 는 올바른 origin 형식이 아닙니다.");
            if (!bad.isEmpty()) e.put("origins", String.join(" ", bad));
        }
        if (!UV_VALUES.contains(f.uv())) e.put("userVerification", "사용자 검증은 필수 또는 권장만 고를 수 있습니다.");
        if (!AT_VALUES.contains(f.at())) e.put("attestationConveyance", "인증기 증명 값이 올바르지 않습니다.");
        if (e.isEmpty()) {
            // 길이 제한 등 나머지 제약은 REST 와 같은 Bean Validation 으로.
            for (ConstraintViolation<TenantAdminDto.TenantUpdateRequest> cv : validator.validate(request(v, f))) {
                String p = cv.getPropertyPath().toString();
                if (p.startsWith("rpName")) e.putIfAbsent("rpName", "RP 표시 이름은 256자 이하로 입력하세요.");
                else if (p.startsWith("allowedOrigins")) e.putIfAbsent("origins", "허용 origin 은 512자 이하여야 합니다.");
                else if (p.startsWith("webauthnTimeoutMs")) e.putIfAbsent("timeoutMs", "대기 시간은 1000–600000ms 범위의 숫자여야 합니다.");
                else e.putIfAbsent("form", "저장할 수 없는 값이 있습니다. 입력을 확인한 뒤 다시 시도하세요.");
            }
        }
        return e;
    }

    /** api/webauthn.ts update 의 역변환. 화면에 없는 값은 읽어 온 현재 값(mdsRequired 포함)을 그대로 쓴다. */
    static TenantAdminDto.TenantUpdateRequest request(TenantAdminDto.TenantView v, Form f) {
        Integer ms = parseMs(f.timeoutMs());
        return new TenantAdminDto.TenantUpdateRequest(
                v.displayName(), v.rpId(), f.rpName(), f.origins(), v.acceptedFormats(),
                "REQUIRED".equals(f.uv()), v.mdsRequired(), f.at(), ms == null ? 0 : ms);
    }

    // ── diff ────────────────────────────────────────────────

    /** diffObjects: 화면에 보이는 필드 중 처음 값과 달라진 것(rpId·formats 는 편집하지 않는다). */
    static List<Change> diffObjects(Form a, Form b) {
        List<Change> out = new ArrayList<>();
        if (!Objects.equals(a.rpName(), b.rpName())) out.add(new Change("rpName", a.rpName(), b.rpName()));
        Integer am = parseMs(a.timeoutMs());
        Integer bm = parseMs(b.timeoutMs());
        if (!Objects.equals(am, bm)) out.add(new Change("timeoutMs", am, bm));
        if (!a.origins().equals(b.origins())) out.add(new Change("origins", a.origins(), b.origins()));
        if (!Objects.equals(a.uv(), b.uv())) out.add(new Change("userVerification", a.uv(), b.uv()));
        if (!Objects.equals(a.at(), b.at())) out.add(new Change("attestationConveyance", a.at(), b.at()));
        return out;
    }

    /** adaptDiffChanges: 배열 필드는 서버가 from/to 대신 added/removed 를 채운다(from=removed, to=added 로 옮긴다). */
    static List<Change> adaptDiff(WebauthnConfigDiff diff) {
        List<Change> out = new ArrayList<>();
        for (WebauthnConfigDiff.FieldChange c : diff.changes()) {
            if (c.added() != null || c.removed() != null) {
                out.add(new Change(c.field(), c.removed() == null ? List.<String>of() : c.removed(), c.added() == null ? List.<String>of() : c.added()));
            } else {
                out.add(new Change(c.field(), c.from(), c.to()));
            }
        }
        return out;
    }

    /** L2 확인 목록 = 서버 diff + 로컬 diff 중 서버가 비교하지 않는 필드(timeoutMs·attestationConveyance). 화면 순서로 정렬(안정). */
    static List<Change> mergeChanges(List<Change> server, List<Change> local) {
        Set<String> covered = new LinkedHashSet<>();
        for (Change c : server) covered.add(norm(c.key()));
        List<Change> merged = new ArrayList<>(server);
        for (Change c : local) if (!covered.contains(norm(c.key()))) merged.add(c);
        List<Change> sorted = new ArrayList<>(merged);
        sorted.sort((x, y) -> Integer.compare(rank(x.key()), rank(y.key())));
        return sorted;
    }

    private static String norm(String k) {
        return "requireUserVerification".equals(k) ? "userVerification" : k;
    }

    private static int rank(String k) {
        int i = FIELD_ORDER.indexOf(norm(k));
        return i < 0 ? FIELD_ORDER.size() : i;
    }

    /** 저장 바 요약용: 바뀐 필드 이름을 화면 순서로. */
    static List<String> changedFieldNames(List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String k : FIELD_ORDER) if (keys.contains(k)) out.add(k);
        for (String k : keys) if (!FIELD_ORDER.contains(k)) out.add(k);
        return out;
    }

    /** DiffRow 한 줄(원시 값 대신 사람이 읽는 값). */
    @SuppressWarnings("unchecked")
    static DiffRow row(Change c) {
        String label = FIELD_LABELS.getOrDefault(c.key(), c.key());
        if (c.from() instanceof List<?> f && c.to() instanceof List<?> t) {
            List<String> from = (List<String>) f;
            List<String> to = (List<String>) t;
            List<String> added = to.stream().filter(x -> !from.contains(x)).toList();
            List<String> removed = from.stream().filter(x -> !to.contains(x)).toList();
            return new DiffRow(c.key(), label, true, removed, added, null, null, null, null);
        }
        return new DiffRow(c.key(), label, false, List.of(), List.of(), showValue(c.key(), c.from()), showValue(c.key(), c.to()),
                String.valueOf(c.from()), String.valueOf(c.to()));
    }

    static String showValue(String key, Object v) {
        if ("timeoutMs".equals(key) && v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue() / 1000.0).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "초";
        }
        if ("userVerification".equals(key) && v instanceof String s) return UV_LABELS.getOrDefault(s, s);
        // 서버 diff 의 boolean: 탭은 true→필수, false→권장 으로 매핑한다(webauthn.ts adaptConfig).
        if ("requireUserVerification".equals(key) && v instanceof Boolean b) return UV_LABELS.get(b ? "REQUIRED" : "PREFERRED");
        if ("mdsRequired".equals(key) && v instanceof Boolean b) return b ? "필수" : "필수 아님";
        if ("attestationConveyance".equals(key) && v instanceof String s) return AT_LABELS.getOrDefault(s, s);
        return String.valueOf(v);
    }

    // ── 모델 채우기 ─────────────────────────────────────────

    private void fill(Model model, TenantAdminDto.TenantView v, Form f) {
        String base = FRAG + v.id() + "/webauthn";
        model.addAttribute("tid", v.id().toString());
        model.addAttribute("f", f);
        model.addAttribute("diffUrl", base + "/diff");
        model.addAttribute("originCheckUrl", base + "/origin-check");
        model.addAttribute("apkUrl", base + "/apk-key");
        model.addAttribute("originPlaceholder", "https://" + f.rpId() + " 입력 후 Enter");
        model.addAttribute("timeoutGuide", timeoutGuide(parseMs(f.timeoutMs())));
        model.addAttribute("uvOptions", options(UV_VALUES, UV_LABELS));
        model.addAttribute("atOptions", options(AT_VALUES, AT_LABELS));
        model.addAttribute("uvGuides", guides(UV_VALUES, UV_LABELS, UV_GUIDE));
        model.addAttribute("atGuides", guides(AT_VALUES, AT_LABELS, AT_GUIDE));
        model.addAttribute("saveLabels", saveLabelsJson());
    }

    private static Map<String, Object> saveAttrs(TenantAdminDto.TenantView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-put", FRAG + v.id() + "/webauthn");
        m.put("hx-target", "#" + TenantTabs.TARGET_ID);
        m.put("hx-swap", "innerHTML");
        m.put("hx-sync", TenantTabs.SYNC);
        return m;
    }

    /** 저장 바 요약(data-savebar-labels): 폼 필드 이름 → 라벨, 화면 순서. */
    private static String saveLabelsJson() {
        StringBuilder sb = new StringBuilder("{");
        String[][] defs = {{"rpName", "rpName"}, {"timeoutMs", "timeoutMs"}, {"origins", "origins"}, {"userVerification", "userVerification"}, {"attestationConveyance", "attestationConveyance"}};
        for (int i = 0; i < defs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(defs[i][0]).append("\":\"").append(FIELD_LABELS.get(defs[i][1])).append('"');
        }
        return sb.append('}').toString();
    }

    private static List<Map<String, Object>> options(List<String> values, Map<String, String> labels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String v : values) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", v);
            o.put("label", labels.get(v));
            o.put("title", v);
            o.put("attrs", null);
            out.add(o);
        }
        return out;
    }

    public record GuideBox(String value, String label, String text) {}

    private static List<GuideBox> guides(List<String> values, Map<String, String> labels, Map<String, String> texts) {
        List<GuideBox> out = new ArrayList<>();
        for (String v : values) out.add(new GuideBox(v, labels.get(v), texts.get(v)));
        return out;
    }

    /** timeoutGuide: 권장 범위 60000–120000ms. 벗어나면 warn 톤(ui.js timeoutHint 와 같은 문구). */
    static Guide timeoutGuide(Integer msBox) {
        int ms = msBox == null ? 0 : msBox;
        if (ms <= 0) return new Guide("warn", "0보다 큰 값을 입력하세요. 권장 60000–120000ms(60–120초).");
        double totalSec = ms / 1000.0;
        String secLabel = (totalSec == Math.floor(totalSec) ? String.valueOf((long) totalSec)
                : BigDecimal.valueOf(totalSec).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()) + "초";
        String human = "= " + secLabel;
        if (totalSec >= 60) {
            long m = (long) Math.floor(totalSec / 60);
            long s = Math.round(totalSec - m * 60);
            human = "= " + secLabel + (s == 0 ? " (" + m + "분)" : " (" + m + "분 " + s + "초)");
        }
        if (ms < 60000) return new Guide("warn", human + " — 권장(60초)보다 짧습니다. 너무 짧으면 사용자가 인증을 끝내기 전에 만료될 수 있습니다.");
        if (ms > 120000) return new Guide("warn", human + " — 권장(120초)보다 깁니다. 너무 길면 만료를 기다리는 동안 서버 자원이 오래 점유됩니다.");
        return new Guide("ok", human + " — 권장 범위(60–120초) 안입니다.");
    }

    // ── 도우미 ──────────────────────────────────────────────

    private static List<String> clean(List<String> origins) {
        List<String> out = new ArrayList<>();
        if (origins != null) for (String o : origins) if (o != null && !o.isBlank() && !out.contains(o.trim())) out.add(o.trim());
        return out;
    }

    private static Integer parseMs(String raw) {
        try {
            return Integer.valueOf(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseCount(String raw) {
        try {
            return Math.max(0, Math.min(99, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ResponseEntity<String> text(String body, String normalized) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8));
        if (normalized != null) b.header("X-Normalized-Value", normalized);
        b.header(HttpHeaders.CACHE_CONTROL, "no-store");
        return b.body(body);
    }
}
