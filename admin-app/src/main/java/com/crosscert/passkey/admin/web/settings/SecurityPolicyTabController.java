package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.policy.SecurityPolicyController;
import com.crosscert.passkey.admin.policy.SecurityPolicyDto;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.PolicyValidation;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설정 › 보안 정책 탭(← pages/settings/SecurityPolicyTab.tsx, api/securityPolicy.ts, lib/policyValidation.ts). PLATFORM 전용 — 모든 엔드포인트가 PLATFORM 을 먼저 확인한다.
 *
 * <p>흐름(원본 handleSave): 저장 바의 저장 = 폼 제출 = {@code PUT /admin/_/settings/security}.
 * <ol>
 *   <li>검증({@link PolicyValidation}, 서버에 이미 저장된 idle timeout 을 손대지 않았으면 오류로 막지 않는다): 실패는 422 + 필드 옆 오류(OOB, 입력·변경 상태 유지, 토스트 없음).</li>
 *   <li>지금 쓰는 콘솔 주소(요청의 origin)가 저장된 CORS 허용 목록에 있는데 새 목록에서 빠지면 L2 확인 다이얼로그를 돌려준다. 저장이 아니므로
 *       <b>422</b> 로 응답한다 — 저장 바가 2xx 를 "저장됨"으로 세어 변경 표시를 지우지 않게 하기 위함이다(본문은 인라인 조각이라 스왑된다).
 *       다이얼로그의 확인 버튼이 같은 PUT 을 {@code confirmLockout=1} 로 다시 보낸다.</li>
 *   <li>저장: {@code SecurityPolicyController.update}. 성공하면 탭을 새 값으로 다시 그리고 {@code saved} + 성공 토스트("보안 정책 저장됨").
 *       REST 거절·실패는 저장 바 경로면 오류 토스트("보안 정책을 저장하지 못했습니다" + "…입력한 내용은 그대로 있습니다."), 확인 다이얼로그 경로면 다이얼로그 안 인라인.</li>
 * </ol>
 * 허용 목록의 origin 검증은 칩 추가 때 {@code GET …/security/origin-check} 가 {@link PolicyValidation#validateOrigin} 으로 한다.
 */
@Slf4j
@Controller
public class SecurityPolicyTabController {

    static final String TAB_PATH = "/admin/_/settings/tab/security";
    static final String SAVE_PATH = "/admin/_/settings/security";
    static final String ORIGIN_CHECK_PATH = "/admin/_/settings/security/origin-check";

    static final String TAB_VIEW = "admin/settings/security :: tab";
    static final String RESULT_VIEW = "admin/settings/security :: result";

    static final String SAVE_FAILED_TITLE = "보안 정책을 저장하지 못했습니다";

    private final SecurityPolicyController policy;
    private final Validator validator;

    public SecurityPolicyTabController(SecurityPolicyController policy, Validator validator) {
        this.policy = policy;
        this.validator = validator;
    }

    /** 화면 값. idle 은 입력 원문, consoleSaved 는 저장된 목록에서 콘솔 주소와 같은(대소문자 무시) 항목(없으면 null). */
    public record Form(String idle, boolean mfa, List<String> cors, String consoleOrigin, String consoleSaved) {}

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(TAB_PATH)
    public String tab(HttpServletRequest req, Model model) {
        SettingsSupport.platform(model);
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH));
        SecurityPolicyDto.View v;
        try {
            v = policy.get();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            // 로드 실패를 기본값(30분·MFA 켜짐)처럼 보이지 않게: 입력칸·저장 없이 LoadError.
            log.warn("[settings] security policy load failed: {}", e.getClass().getSimpleName());
            model.addAttribute("loadError", e);
            model.addAttribute("sp", null);
            return TAB_VIEW;
        }
        fill(model, v, consoleOrigin(req));
        return TAB_VIEW;
    }

    /** ChipInput 원격 검증: 본문 = 오류 문구(비면 통과), 통과하면 X-Normalized-Value = 정규화한 origin. */
    @GetMapping(ORIGIN_CHECK_PATH)
    public ResponseEntity<String> originCheck(@RequestParam(name = "value", defaultValue = "") String value,
                                              @RequestParam(name = "existing", required = false) List<String> existing, Model model) {
        SettingsSupport.platform(model);
        String err = PolicyValidation.validateOrigin(value, existing == null ? List.of() : existing);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (err != null) return b.body(err);
        return b.header("X-Normalized-Value", PolicyValidation.normalizeOrigin(value)).body("");
    }

    // ── 저장 ────────────────────────────────────────────────

    @PutMapping(SAVE_PATH)
    public String save(@RequestParam(name = "sessionIdleTimeoutMinutes", defaultValue = "") String idle,
                       @RequestParam(name = "mfaRequired", defaultValue = "") String mfaRaw,
                       @RequestParam(name = "corsAllowlist", required = false) List<String> corsRaw,
                       @RequestParam(name = "confirmLockout", defaultValue = "") String confirmLockout,
                       Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        SettingsSupport.platform(model);
        boolean confirm = Toasts.isConfirm(req);
        List<String> cors = clean(corsRaw);
        String idleIn = idle.trim();

        SecurityPolicyDto.View saved;
        try {
            saved = policy.get(); // 저장된 값 기준(손대지 않은 idle·콘솔 주소 판정)
        } catch (RuntimeException e) {
            return SettingsSupport.fail(res, SAVE_FAILED_TITLE, e, confirm);
        }

        Map<String, String> errors = new LinkedHashMap<>();
        boolean untouched = idleIn.equals(String.valueOf(saved.sessionIdleTimeoutMinutes()));
        Integer idleMin = untouched ? Integer.valueOf(saved.sessionIdleTimeoutMinutes()) : null;
        if (!untouched) {
            String e = PolicyValidation.timeoutError(idleIn);
            if (e != null) errors.put("sessionIdleTimeoutMinutes", e);
            else idleMin = Integer.parseInt(idleIn); // 1–1440 검증 통과
        }
        Boolean mfa = "true".equals(mfaRaw) ? Boolean.TRUE : "false".equals(mfaRaw) ? Boolean.FALSE : null;
        if (mfa == null) errors.put("form", "저장할 수 없는 값이 있습니다. 입력을 확인한 뒤 다시 시도하세요.");
        // 허용 목록은 PolicyValidation 과 같은 규칙(개수·길이·형식·중복)으로. idle 은 위에서 따로 걸렀으므로 자리값(1)으로 채운다.
        String listErr = PolicyValidation.validate(new SecurityPolicyDto.UpdateRequest(1, Boolean.TRUE, cors)).get("corsAllowlist");
        if (listErr != null) errors.put("corsAllowlist", listErr);

        SecurityPolicyDto.UpdateRequest body = errors.isEmpty() ? new SecurityPolicyDto.UpdateRequest(idleMin, mfa, cors) : null;
        if (body != null) {
            for (ConstraintViolation<SecurityPolicyDto.UpdateRequest> cv : validator.validate(body)) {
                // 위에서 걸러지지 않은 제약 위반(예: 저장된 idle 이 1 미만) → 일반 오류. REST 는 부르지 않는다.
                log.debug("[settings] security policy violation: {}", cv.getPropertyPath());
                errors.putIfAbsent("form", "저장할 수 없는 값이 있습니다. 입력을 확인한 뒤 다시 시도하세요.");
            }
        }
        if (!errors.isEmpty()) {
            if (confirm) {
                // 확인 단계에서의 검증 실패는 위조·경합: 다이얼로그 안 인라인 오류.
                Toasts.confirmError(res, errors.values().iterator().next(), null, null);
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return SettingsSupport.EMPTY_VIEW;
            }
            res.setStatus(422);
            model.addAttribute("errors", errors);
            model.addAttribute("dialog", false);
            return RESULT_VIEW;
        }

        String console = consoleOrigin(req);
        if (removesConsole(saved.corsAllowlist(), cors, console) && !"1".equals(confirmLockout)) {
            res.setStatus(422); // 저장이 아님 — 저장 바가 변경 표시를 지우지 않도록 2xx 를 쓰지 않는다.
            model.addAttribute("errors", Map.<String, String>of());
            model.addAttribute("dialog", true);
            model.addAttribute("draftIdle", String.valueOf(idleMin));
            model.addAttribute("draftMfa", mfa);
            model.addAttribute("draftCors", cors);
            model.addAttribute("consoleOrigin", console);
            model.addAttribute("saveAttrs", saveAttrs());
            return RESULT_VIEW;
        }

        SecurityPolicyDto.View updated;
        try {
            updated = policy.update(body, auth);
        } catch (RuntimeException e) {
            if (e instanceof AccessDeniedException) throw e;
            log.warn("[settings] security policy save failed: {}", e.getClass().getSimpleName());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            int status = SettingsSupport.statusOf(e);
            if (confirm) {
                Toasts.confirmError(res, SAVE_FAILED_TITLE + ". " + info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
            } else {
                Toasts.errorTitled(res, SAVE_FAILED_TITLE, info.title() + ". 입력한 내용은 그대로 있습니다.", "HTTP " + status, MDC.get("traceId"));
            }
            res.setStatus(status);
            return SettingsSupport.EMPTY_VIEW;
        }
        Toasts.successSaved(res, "보안 정책 저장됨", "");
        res.setHeader("HX-Retarget", "#" + SettingsPageController.TARGET_ID);
        res.setHeader("HX-Reswap", "innerHTML");
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH));
        fill(model, updated, console);
        return TAB_VIEW;
    }

    // ── 도우미 ──────────────────────────────────────────────

    private void fill(Model model, SecurityPolicyDto.View v, String console) {
        String saved = null;
        for (String o : v.corsAllowlist()) if (o.equalsIgnoreCase(console)) { saved = o; break; }
        model.addAttribute("loadError", null);
        model.addAttribute("sp", new Form(String.valueOf(v.sessionIdleTimeoutMinutes()), v.mfaRequired(), v.corsAllowlist(), console, saved));
        model.addAttribute("savePath", SAVE_PATH);
        model.addAttribute("originCheckUrl", ORIGIN_CHECK_PATH);
    }

    /** 이 콘솔의 origin(scheme://host[:port]) — 원본 window.location.origin. */
    static String consoleOrigin(HttpServletRequest req) {
        return ServletUriComponentsBuilder.fromRequest(req).replacePath(null).replaceQuery(null).build().toUriString();
    }

    static boolean hasConsole(List<String> list, String console) {
        return list.stream().anyMatch(o -> o.equalsIgnoreCase(console));
    }

    /** 원본 removesConsole: 저장된 목록엔 콘솔 주소가 있고 새 목록엔 없다. */
    static boolean removesConsole(List<String> saved, List<String> next, String console) {
        return hasConsole(saved, console) && !hasConsole(next, console);
    }

    static Map<String, Object> saveAttrs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hx-put", SAVE_PATH);
        m.put("hx-target", "#" + SettingsPageController.TARGET_ID);
        m.put("hx-swap", "innerHTML");
        m.put("hx-sync", SettingsPageController.SYNC);
        return m;
    }

    private static List<String> clean(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) for (String o : raw) if (o != null && !o.isBlank() && !out.contains(o.trim())) out.add(o.trim());
        return out;
    }
}
