package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.policy.AaguidPolicyDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.Labels;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenant 상세의 AAGUID 정책 탭(← pages/tenant/AaguidPolicyTab.tsx, api/aaguidPolicy.ts, tenantSignals.ts unverifiedAaguidCount).
 *
 * <p>모든 엔드포인트는 {@link TenantTabs#load} 로 접근 경계를 먼저 확인한다. 흐름은 WebAuthn 탭과 같다: 폼 제출 = 미리보기
 * ({@code POST …/aaguid/preview}: 검증 → 저장 확인 등급 L3/L2 결정 → 확인 다이얼로그) → 확인 = 저장({@code PUT …/aaguid} →
 * {@code AaguidPolicyController.update}). 항목 메모는 이 화면이 편집하지 않으므로 저장할 때 서버의 현재 메모를 항목별로 되돌려 보낸다
 * (원본은 불러온 항목의 note 를 그대로 보냄). 저장 성공은 탭을 새 정책으로 다시 그리고 탭 줄의 "확인 필요" 배지를 OOB 로 갱신한다(원본 onSaved).
 */
@Slf4j
@Controller
public class AaguidTabController {

    static final String FRAG = "/admin/_/tenants/";
    static final String TAB_VIEW = "admin/tenant/aaguid :: tab";
    static final String RESULT_VIEW = "admin/tenant/aaguid :: result";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    static final Pattern UUID_RE = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);
    static final String MDS_STRICT_IMPACT = "MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다.";
    static final List<String> MODES = List.of("ANY", "ALLOWLIST", "DENYLIST");
    static final Map<String, String> MODE_DESC = Map.of("ANY", "모든 인증기 허용", "ALLOWLIST", "목록의 모델만 허용", "DENYLIST", "목록의 모델만 차단");

    private final TenantAdminController tenants;
    private final AaguidPolicyController aaguid;

    public AaguidTabController(TenantAdminController tenants, AaguidPolicyController aaguid) {
        this.tenants = tenants;
        this.aaguid = aaguid;
    }

    // ── 모델 ────────────────────────────────────────────────

    public record Entry(String aaguid, String note, String mdsName) {}

    /** 정책(저장된 값 또는 편집 중인 값). mode 는 enum 이름. */
    public record Policy(String mode, boolean mdsStrict, List<Entry> entries) {
        static Policy of(AaguidPolicyDto.View v) {
            List<Entry> es = new ArrayList<>();
            for (AaguidPolicyDto.Entry e : v.getEntries()) es.add(new Entry(e.getAaguid() == null ? "" : e.getAaguid().toString(), e.getNote(), e.getMdsName()));
            return new Policy(v.getMode().name(), v.getMdsStrict(), es);
        }
    }

    public sealed interface Change permits ModeChange, EntryChange, NoteChange, MdsChange {}
    public record ModeChange(String from, String to) implements Change {}
    public record EntryChange(boolean added, String aaguid) implements Change {}
    public record NoteChange(String aaguid, String from, String to) implements Change {}
    public record MdsChange(boolean from, boolean to) implements Change {}

    /** 확인 다이얼로그 목록의 한 줄. */
    public record Line(String text, boolean mono) {}

    public enum ConfirmKind { BLOCK_ALL, MDS_STRICT, DIFF }

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/aaguid")
    public String tab(@PathVariable String id, Model model) {
        TenantAdminDto.TenantView v;
        AaguidPolicyDto.View policy;
        try {
            v = TenantTabs.load(tenants, id);
            policy = aaguid.get(v.id());
        } catch (BusinessException | AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[aaguid-tab] load failed: {}", e.toString());
            model.addAttribute("loadError", e);
            model.addAttribute("retryAttrs", TenantTabs.requesterAttrs(FRAG + id + "/tab/aaguid"));
            return TAB_VIEW;
        }
        fill(model, v.id().toString(), Policy.of(policy));
        return TAB_VIEW;
    }

    /** ChipInput 원격 검증(AAGUID): 입력을 8-4-4-4-12 소문자로 맞춘다. 본문 = 오류 문구(비면 통과), X-Normalized-Value = 맞춘 값. */
    @GetMapping(FRAG + "{id}/aaguid/entry-check")
    public ResponseEntity<String> entryCheck(@PathVariable String id, @RequestParam(name = "value", defaultValue = "") String value) {
        TenantTabs.load(tenants, id);
        String f = formatAaguid(value);
        if (!UUID_RE.matcher(f).matches()) return text("AAGUID 형식이 올바르지 않습니다. 8-4-4-4-12 형식의 16진수 32자를 입력하세요.", null);
        return text("", f);
    }

    /** 미리보기(저장… 버튼): 검증 실패는 422 + 필드 옆 오류(OOB). 통과하면 저장 확인 등급에 맞는 다이얼로그. 변경이 없으면 경고 토스트만. */
    @PostMapping(FRAG + "{id}/aaguid/preview")
    public String preview(@PathVariable String id,
                          @RequestParam(name = "mode", defaultValue = "") String mode,
                          @RequestParam(name = "aaguid", required = false) List<String> aaguids,
                          @RequestParam(name = "mdsStrict", defaultValue = "false") String mdsStrict,
                          HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Map<String, String> errors = validate(mode, aaguids);
        model.addAttribute("errors", errors);
        model.addAttribute("dialog", false);
        if (!errors.isEmpty()) {
            res.setStatus(422);
            return RESULT_VIEW;
        }
        Policy saved = Policy.of(aaguid.get(v.id()));
        Policy draft = draft(saved, mode, aaguids, mdsStrict);
        List<Change> changes = policyChanges(saved, draft);
        if (changes.isEmpty()) {
            Toasts.warnTitled(res, "변경 사항이 없습니다.", "");
            return RESULT_VIEW;
        }
        ConfirmKind kind = saveConfirmKind(saved, draft);
        model.addAttribute("dialog", true);
        model.addAttribute("kind", kind.name());
        model.addAttribute("changes", lines(changes));
        model.addAttribute("changeCount", changes.size());
        model.addAttribute("draft", draft);
        model.addAttribute("draftAaguids", draft.entries().stream().map(Entry::aaguid).toList());
        model.addAttribute("strictNote", allowlistUnknownNote(draft));
        model.addAttribute("impact", MDS_STRICT_IMPACT);
        model.addAttribute("saveAttrs", saveAttrs(v));
        return RESULT_VIEW;
    }

    /**
     * 저장(확인 다이얼로그의 확인). {@code AaguidPolicyController.update} 로 보낸다(항목 메모는 서버의 현재 값). 성공이면 탭을 새 정책으로 다시 그리고
     * 탭 줄의 AAGUID 배지를 갱신한다. 검증·REST 거절은 다이얼로그 안 인라인 오류.
     */
    @PutMapping(FRAG + "{id}/aaguid")
    public String save(@PathVariable String id,
                       @RequestParam(name = "mode", defaultValue = "") String mode,
                       @RequestParam(name = "aaguid", required = false) List<String> aaguids,
                       @RequestParam(name = "mdsStrict", defaultValue = "false") String mdsStrict,
                       Authentication auth, HttpServletResponse res, Model model) {
        TenantAdminDto.TenantView v = TenantTabs.load(tenants, id);
        Map<String, String> errors = validate(mode, aaguids);
        if (!errors.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            Toasts.confirmError(res, errors.values().iterator().next(), null, null);
            return EMPTY_VIEW;
        }
        Policy saved = Policy.of(aaguid.get(v.id()));
        Policy draft = draft(saved, mode, aaguids, mdsStrict);
        List<AaguidPolicyDto.EntryInput> entries = new ArrayList<>();
        for (Entry e : draft.entries()) entries.add(new AaguidPolicyDto.EntryInput(UUID.fromString(e.aaguid()), e.note()));
        AaguidPolicyDto.View updated = aaguid.update(v.id(),
                new AaguidPolicyDto.UpdateRequest(TenantAaguidPolicy.Mode.valueOf(draft.mode()), draft.mdsStrict(), entries), auth);
        Policy p = Policy.of(updated);
        Toasts.successSaved(res, "AAGUID 정책 저장 완료", "");
        fill(model, v.id().toString(), p);
        int unverified = unverifiedCount(p);
        model.addAttribute("badgeOob", true);
        model.addAttribute("badgeText", unverified > 0 ? "확인 필요 " + unverified : null);
        model.addAttribute("badgeReason", unverified > 0 ? "MDS 미확인 AAGUID " + unverified + "건" : null);
        return TAB_VIEW;
    }

    // ── 검증·계산 ───────────────────────────────────────────

    static Map<String, String> validate(String mode, List<String> aaguids) {
        Map<String, String> e = new LinkedHashMap<>();
        if (!MODES.contains(mode)) e.put("mode", "정책 방식이 올바르지 않습니다.");
        List<String> bad = new ArrayList<>();
        if (aaguids != null) for (String a : aaguids) if (a != null && !a.isBlank() && !UUID_RE.matcher(a.trim()).matches()) bad.add("'" + a + "' 는 올바른 AAGUID 형식이 아닙니다.");
        if (!bad.isEmpty()) e.put("aaguid", String.join(" ", bad));
        return e;
    }

    /** 편집 중 정책: 목록 순서를 유지하고, 저장된 항목은 메모·MDS 이름을 그대로(원본 draft 와 같다), 새 항목은 둘 다 null. */
    static Policy draft(Policy saved, String mode, List<String> aaguids, String mdsStrict) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry e : saved.entries()) byId.put(e.aaguid(), e);
        List<Entry> entries = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (aaguids != null) {
            for (String raw : aaguids) {
                if (raw == null || raw.isBlank()) continue;
                String a = raw.trim().toLowerCase(Locale.ROOT);
                if (seen.contains(a)) continue;
                seen.add(a);
                entries.add(byId.getOrDefault(a, new Entry(a, null, null)));
            }
        }
        return new Policy(mode, "true".equalsIgnoreCase(mdsStrict), entries);
    }

    /** 입력에서 16진수만 남기고(최대 32자) 8-4-4-4-12 위치에 하이픈을 넣는다(formatAaguid). */
    public static String formatAaguid(String raw) {
        String hex = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
        if (hex.length() > 32) hex = hex.substring(0, 32);
        int[] seg = {8, 4, 4, 4, 12};
        List<String> out = new ArrayList<>();
        int i = 0;
        for (int len : seg) {
            if (i >= hex.length()) break;
            out.add(hex.substring(i, Math.min(i + len, hex.length())));
            i += len;
        }
        return String.join("-", out);
    }

    /**
     * 저장된 정책과 편집 중인 정책의 실제 차이. 백엔드는 entries 를 집합(AAGUID)으로 쓰므로 순서와 서버가 채우는 mdsName 은 변경이 아니다.
     */
    public static List<Change> policyChanges(Policy saved, Policy draft) {
        List<Change> out = new ArrayList<>();
        if (!saved.mode().equals(draft.mode())) out.add(new ModeChange(saved.mode(), draft.mode()));
        Map<String, Entry> savedMap = byId(saved);
        Map<String, Entry> draftMap = byId(draft);
        for (Map.Entry<String, Entry> d : draftMap.entrySet()) {
            Entry before = savedMap.get(d.getKey());
            if (before == null) out.add(new EntryChange(true, d.getKey()));
            else if (!Objects.equals(before.note(), d.getValue().note())) out.add(new NoteChange(d.getKey(), before.note(), d.getValue().note()));
        }
        for (String a : savedMap.keySet()) if (!draftMap.containsKey(a)) out.add(new EntryChange(false, a));
        if (saved.mdsStrict() != draft.mdsStrict()) out.add(new MdsChange(saved.mdsStrict(), draft.mdsStrict()));
        return out;
    }

    private static Map<String, Entry> byId(Policy p) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Entry e : p.entries()) if (e.aaguid() != null && !e.aaguid().isEmpty()) m.put(e.aaguid(), e);
        return m;
    }

    /** 저장 확인 등급. 빈 허용 목록 = L3 체크, MDS 엄격 켜기 = L2 영향 요약, 그 밖 = L2 변경 비교. */
    public static ConfirmKind saveConfirmKind(Policy saved, Policy draft) {
        if ("ALLOWLIST".equals(draft.mode()) && byId(draft).isEmpty()) return ConfirmKind.BLOCK_ALL;
        if (!saved.mdsStrict() && draft.mdsStrict()) return ConfirmKind.MDS_STRICT;
        return ConfirmKind.DIFF;
    }

    /** SaveBar 요약용 변경 필드 이름(저장 바의 필드 이름과 같은 키: mode / aaguid / mdsStrict). */
    static List<String> changedFields(Policy saved, Policy draft) {
        List<Change> cs = policyChanges(saved, draft);
        List<String> out = new ArrayList<>();
        if (cs.stream().anyMatch(c -> c instanceof ModeChange)) out.add("mode");
        if (cs.stream().anyMatch(c -> c instanceof EntryChange || c instanceof NoteChange)) out.add("aaguid");
        if (cs.stream().anyMatch(c -> c instanceof MdsChange)) out.add("mdsStrict");
        return out;
    }

    /** mode≠ANY 이고 mdsName==null 인 AAGUID 수(목록에 보이는 항목만). */
    static int unverifiedCount(Policy p) {
        if ("ANY".equals(p.mode())) return 0;
        return (int) p.entries().stream().filter(e -> e.aaguid() != null && !e.aaguid().isEmpty() && e.mdsName() == null).count();
    }

    /** 허용 목록 모드에서는 목록 안의 MDS 미확인 모델도 같이 막힌다. */
    static String allowlistUnknownNote(Policy p) {
        int n = "ALLOWLIST".equals(p.mode()) ? unverifiedCount(p) : 0;
        return n > 0 ? " 허용 목록 중 MDS 미확인 " + n + "개도 차단됩니다." : "";
    }

    private static String onOff(boolean b) {
        return b ? "켜짐" : "꺼짐";
    }

    static List<Line> lines(List<Change> changes) {
        List<Line> out = new ArrayList<>();
        for (Change c : changes) {
            if (c instanceof ModeChange m) out.add(new Line("방식: " + Labels.aaguidMode(m.from()) + " → " + Labels.aaguidMode(m.to()), false));
            else if (c instanceof EntryChange e) out.add(new Line((e.added() ? "+ " : "- ") + e.aaguid(), true));
            else if (c instanceof NoteChange n) out.add(new Line(n.aaguid() + " 메모: " + (n.from() == null ? "없음" : n.from()) + " → " + (n.to() == null ? "없음" : n.to()), true));
            else if (c instanceof MdsChange d) out.add(new Line("MDS 엄격: " + onOff(d.from()) + " → " + onOff(d.to()), false));
        }
        return out;
    }

    // ── 모델 채우기 ─────────────────────────────────────────

    public record ModeOption(String value, String label, String desc) {}

    private void fill(Model model, String tid, Policy p) {
        String base = FRAG + tid + "/aaguid";
        List<ModeOption> modes = new ArrayList<>();
        for (String m : MODES) modes.add(new ModeOption(m, Labels.aaguidMode(m), MODE_DESC.get(m)));
        List<String> values = p.entries().stream().map(Entry::aaguid).filter(a -> a != null && !a.isEmpty()).toList();
        Map<String, String> meta = new LinkedHashMap<>();
        for (Entry e : p.entries()) if (e.mdsName() != null) meta.put(e.aaguid(), e.mdsName());
        int unknown = unverifiedCount(p);
        model.addAttribute("tid", tid);
        model.addAttribute("p", p);
        model.addAttribute("modes", modes);
        model.addAttribute("modeLabel", Labels.aaguidMode(p.mode()));
        model.addAttribute("values", values);
        model.addAttribute("meta", meta);
        model.addAttribute("known", new ArrayList<>(meta.keySet()));
        model.addAttribute("unknownCount", unknown);
        model.addAttribute("previewUrl", base + "/preview");
        model.addAttribute("entryCheckUrl", base + "/entry-check");
        model.addAttribute("listLabel", "ALLOWLIST".equals(p.mode()) ? "허용된 AAGUID" : "차단된 AAGUID");
        model.addAttribute("emptyAllow", "ALLOWLIST".equals(p.mode()) && values.isEmpty());
        model.addAttribute("emptyDeny", "DENYLIST".equals(p.mode()) && values.isEmpty());
        model.addAttribute("strictHint", (p.mdsStrict() ? "" : "켜면 ") + MDS_STRICT_IMPACT + allowlistUnknownNote(p));
        model.addAttribute("impact", MDS_STRICT_IMPACT);
        model.addAttribute("saveLabels", "{\"mode\":\"mode\",\"aaguid\":\"AAGUID 목록\",\"mdsStrict\":\"MDS strict\"}");
    }

    private static Map<String, Object> saveAttrs(TenantAdminDto.TenantView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-put", FRAG + v.id() + "/aaguid");
        m.put("hx-target", "#" + TenantTabs.TARGET_ID);
        m.put("hx-swap", "innerHTML");
        m.put("hx-sync", TenantTabs.SYNC);
        return m;
    }

    private static ResponseEntity<String> text(String body, String normalized) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8));
        if (normalized != null) b.header("X-Normalized-Value", normalized);
        b.header(HttpHeaders.CACHE_CONTROL, "no-store");
        return b.body(body);
    }
}
