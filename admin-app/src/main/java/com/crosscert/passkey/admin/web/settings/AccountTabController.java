package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.config.MeController;
import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.format.KstFormat;
import com.crosscert.passkey.admin.web.format.RecoveryCodeFilename;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.QrSvg;
import com.crosscert.passkey.admin.web.support.Toasts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 설정 › 내 계정 탭(← pages/settings/AccountTab.tsx, RecoveryCodesModal.tsx, shell/QrCode.tsx, api/mfa.ts).
 *
 * <p>2단계 인증(MFA) 켜기·끄기는 {@link MfaController} 의 enroll/confirm/disable 을 Java 메서드로 부른다(ResponseEntity 의 상태·본문을 그대로 해석).
 * <ul>
 *   <li>켜기 1–2단계: {@code POST /admin/_/settings/mfa/enroll} → 응답 본문이 켜기 다이얼로그(QR = {@link QrSvg} data URI, Base32 secret).
 *       실패는 원본 errorToast 처럼 "2단계 인증을 시작하지 못했습니다" 오류 토스트 + 실제 상태 + 빈 본문.</li>
 *   <li>확인: {@code POST …/mfa/confirm} → 성공 응답이 복구 코드 다이얼로그(3/3)다. 코드는 이 응답 본문에만 있다 — 세션·플래시·헤더·토스트·리다이렉트
 *       없음, {@code Cache-Control: no-store}, 다이얼로그 루트 {@code hx-history="false"}. .txt 다운로드·복사는 브라우저(Alpine)가 화면의 코드로 한다.
 *       실패는 토스트 없이 입력칸 아래 인라인(422 + HX-Retarget): 401 invalid_code 만 "코드가 맞지 않습니다…", 그 밖은 쉬운 오류 문구.</li>
 *   <li>끄기: {@code POST …/mfa/disable} → 성공하면 계정 카드를 다시 그린다(경고 토스트 "2단계 인증이 꺼졌습니다."). 실패는 확인 다이얼로그 안 인라인.</li>
 * </ul>
 * 켜기·끄기가 성공하면 원본의 {@code onMeChange(await getMe())} 처럼 {@link MeController#me} 로 계정 정보를 다시 읽어 카드를 그린다(실패하면
 * "계정 정보를 새로고침하지 못했습니다." 경고). 코드·secret 은 어떤 로그에도 남기지 않는다(예외는 클래스 이름만).
 */
@Slf4j
@Controller
public class AccountTabController {

    static final String TAB_PATH = "/admin/_/settings/tab/account";
    static final String ENROLL_PATH = "/admin/_/settings/mfa/enroll";
    static final String CONFIRM_PATH = "/admin/_/settings/mfa/confirm";
    static final String DISABLE_PATH = "/admin/_/settings/mfa/disable";

    static final String CONTENT_VIEW = "admin/settings/account :: content";
    static final String ACCOUNT_VIEW = "admin/settings/account :: account";
    static final String ENROLL_VIEW = "admin/settings/account :: enroll";
    static final String CODES_VIEW = "admin/settings/account :: recoveryCodes";
    static final String CODE_ERROR_VIEW = "admin/settings/account :: codeError";
    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    /** AccountTab.tsx CODE_MISMATCH */
    static final String CODE_MISMATCH = "코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요.";
    /** 원본 QrCode size(AccountTab.tsx: {@code <QrCode size={152} />}). */
    static final int QR_SIZE = 152;

    static final String ENROLL_ERROR_ID = "mfa-enroll-error";
    static final String DISABLE_ERROR_ID = "mfa-disable-err";

    private final MfaController mfa;
    private final MeController meController;
    private final Clock clock;

    public AccountTabController(MfaController mfa, MeController meController, Clock clock) {
        this.mfa = mfa;
        this.meController = meController;
        this.clock = clock;
    }

    /** 계정 카드 모델. enrolledAt 은 서버(MeView)가 아직 내려주지 않아 늘 null — 원본처럼 그 줄을 그리지 않는다. */
    public record AccountView(String email, boolean mfaEnabled, String enrolledAt) {
        static AccountView of(CurrentAdmin me) {
            return new AccountView(me.email(), me.mfaEnabled(), null);
        }
    }

    /** 켜기 다이얼로그 모델: QR 이미지 data URI 와 수동 입력 키. */
    public record EnrollView(String qrSrc, String secret) {}

    /** 복구 코드 한 줄: 번호(01–10)와 코드. */
    public record Code(String no, String value) {}

    // ── 탭 ──────────────────────────────────────────────────

    /** 설정 화면(전체 페이지·일반 탭 조각)이 같은 모델로 내 계정 탭을 그린다. */
    public void populate(Model model, CurrentAdmin me) {
        model.addAttribute("account", AccountView.of(me));
        model.addAttribute("enrollAttrs", Map.of(
                "hx-post", ENROLL_PATH, "hx-target", "#mfa-enroll-slot", "hx-swap", "innerHTML",
                "hx-sync", "this:drop", "hx-disabled-elt", "this"));
    }

    @GetMapping(TAB_PATH)
    public String tab(Model model) {
        populate(model, me(model));
        return CONTENT_VIEW;
    }

    // ── 켜기 ────────────────────────────────────────────────

    @PostMapping(ENROLL_PATH)
    public String enroll(Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        me(model);
        noStore(res);
        ResponseEntity<?> r;
        try {
            r = mfa.enroll(auth, req);
        } catch (RuntimeException e) {
            log.warn("[settings] mfa enroll failed: {}", e.getClass().getSimpleName());
            ErrorMessages.Info info = ErrorMessages.describe(e);
            int status = info.httpStatus() == null ? 500 : info.httpStatus();
            return enrollFailed(res, status, info);
        }
        int status = r.getStatusCode().value();
        if (status < 200 || status >= 300) {
            String err = errorOf(r);
            return enrollFailed(res, status, ErrorMessages.describe(status, err, err));
        }
        Map<?, ?> body = r.getBody() instanceof Map<?, ?> m ? m : Map.of();
        String secret = String.valueOf(body.get("secret"));
        String uri = String.valueOf(body.get("otpauthUri"));
        model.addAttribute("enroll", new EnrollView(QrSvg.render(uri, QR_SIZE), secret));
        model.addAttribute("confirmAction", CONFIRM_PATH);
        return ENROLL_VIEW;
    }

    private String enrollFailed(HttpServletResponse res, int status, ErrorMessages.Info info) {
        Toasts.errorTitled(res, "2단계 인증을 시작하지 못했습니다", info.toastMessage(), "HTTP " + status, MDC.get("traceId"));
        res.setStatus(status);
        return EMPTY_VIEW;
    }

    // ── 확인(복구 코드) ─────────────────────────────────────

    @PostMapping(CONFIRM_PATH)
    public String confirm(@RequestParam(name = "code", required = false) String code,
                          Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        CurrentAdmin me = me(model);
        noStore(res);
        ResponseEntity<?> r;
        try {
            r = mfa.confirm(new MfaController.VerifyRequest(code), auth, req);
        } catch (RuntimeException e) {
            log.warn("[settings] mfa confirm failed: {}", e.getClass().getSimpleName());
            return codeError(res, model, ENROLL_ERROR_ID, codeErrorText(e));
        }
        int status = r.getStatusCode().value();
        if (status < 200 || status >= 300) return codeError(res, model, ENROLL_ERROR_ID, codeErrorText(status, errorOf(r)));

        List<Code> codes = new ArrayList<>();
        if (r.getBody() instanceof Map<?, ?> body && body.get("recoveryCodes") instanceof List<?> list) {
            for (Object c : list) codes.add(new Code(String.format("%02d", codes.size() + 1), String.valueOf(c)));
        }
        model.addAttribute("codes", codes);
        model.addAttribute("rcFilename", RecoveryCodeFilename.of(me.email(), KstFormat.kstTodayDate(clock.instant())));
        // 복구 코드는 계정 정보 새로고침의 성공 여부와 무관하게 먼저 보여 준다.
        CurrentAdmin fresh = reloadMe(auth, req);
        if (fresh != null) {
            populate(model, fresh);
            model.addAttribute("accountOob", true);
            Toasts.successTitled(res, "2단계 인증이 켜졌습니다.", "");
        } else {
            model.addAttribute("accountOob", false);
            meRefreshFailed(res);
        }
        return CODES_VIEW;
    }

    // ── 끄기 ────────────────────────────────────────────────

    @PostMapping(DISABLE_PATH)
    public String disable(@RequestParam(name = "code", required = false) String code,
                          Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        me(model);
        noStore(res);
        ResponseEntity<?> r;
        try {
            r = mfa.disable(new MfaController.VerifyRequest(code), auth, req);
        } catch (RuntimeException e) {
            log.warn("[settings] mfa disable failed: {}", e.getClass().getSimpleName());
            return codeError(res, model, DISABLE_ERROR_ID, codeErrorText(e));
        }
        int status = r.getStatusCode().value();
        if (status < 200 || status >= 300) return codeError(res, model, DISABLE_ERROR_ID, codeErrorText(status, errorOf(r)));

        CurrentAdmin fresh = reloadMe(auth, req);
        if (fresh == null) {
            // 카드는 그대로 두고(원본도 me 가 안 바뀐다) 다이얼로그만 닫힌다(요청 성공).
            res.setHeader("HX-Reswap", "none");
            meRefreshFailed(res);
            return EMPTY_VIEW;
        }
        populate(model, fresh);
        Toasts.warnTitled(res, "2단계 인증이 꺼졌습니다.", "");
        return ACCOUNT_VIEW;
    }

    // ── 도우미 ──────────────────────────────────────────────

    /**
     * 원본 codeErrorText: 서버의 코드 불일치 응답(401 {"error":"invalid_code"})만 코드 불일치, 그 밖(403 권한·429·5xx 등)은 describeError 의
     * {@code `${title}. ${detail}`}.
     */
    static String codeErrorText(int status, String error) {
        if (status == 401 && "invalid_code".equals(error)) return CODE_MISMATCH;
        return join(ErrorMessages.describe(status, error, error));
    }

    static String codeErrorText(Throwable e) {
        return join(ErrorMessages.describe(e));
    }

    private static String join(ErrorMessages.Info info) {
        String d = info.detail();
        return d == null || d.isEmpty() ? info.title() + "." : info.title() + ". " + d;
    }

    /** 실패 문구를 입력칸 아래 자리(slotId)에 422 로 넣는다. 토스트 없음(원본: 입력칸 아래에만). */
    private static String codeError(HttpServletResponse res, Model model, String slotId, String text) {
        res.setStatus(422);
        res.setHeader("HX-Retarget", "#" + slotId);
        res.setHeader("HX-Reswap", "innerHTML");
        model.addAttribute("codeError", text);
        model.addAttribute("codeErrorId", slotId);
        return CODE_ERROR_VIEW;
    }

    /** mfaApi 의 ApiError(code): 본문의 {@code error} 값, 없으면 'error'. */
    private static String errorOf(ResponseEntity<?> r) {
        if (r.getBody() instanceof Map<?, ?> m && m.get("error") != null) return String.valueOf(m.get("error"));
        return "error";
    }

    private CurrentAdmin reloadMe(Authentication auth, HttpServletRequest req) {
        try {
            return CurrentAdmin.from(meController.me(auth, req).data());
        } catch (RuntimeException e) {
            log.warn("[settings] me reload failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 원본은 성공 토스트 뒤에 이 경고를 하나 더 띄운다. HX-Trigger 토스트는 응답마다 하나라 더 중요한 이 경고만 보낸다(성공은 화면 변화로 알 수 있다).
     */
    private static void meRefreshFailed(HttpServletResponse res) {
        Toasts.warnTitled(res, "계정 정보를 새로고침하지 못했습니다.", "페이지를 새로고침해 주세요.");
    }

    private static void noStore(HttpServletResponse res) {
        res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        res.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    static CurrentAdmin me(Model model) {
        if (!(model.getAttribute("me") instanceof CurrentAdmin me)) throw new AccessDeniedException("no authenticated admin");
        return me;
    }
}
