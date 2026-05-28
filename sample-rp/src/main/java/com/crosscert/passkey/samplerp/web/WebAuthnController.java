package com.crosscert.passkey.samplerp.web;

import com.crosscert.passkey.samplerp.common.exception.BusinessException;
import com.crosscert.passkey.samplerp.common.exception.ErrorCode;
import com.crosscert.passkey.samplerp.common.response.ApiResponse;
import com.crosscert.passkey.samplerp.config.PasskeyProperties;
import com.crosscert.passkey.samplerp.session.SessionKeys;
import com.crosscert.passkey.samplerp.user.InMemoryUserStore;
import com.crosscert.passkey.samplerp.user.SampleRpUser;
import com.crosscert.passkey.samplerp.web.dto.*;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.*;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webauthn")
public class WebAuthnController {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnController.class);

    private final PasskeyClient passkey;
    private final InMemoryUserStore users;
    private final PasskeyProperties props;

    public WebAuthnController(PasskeyClient passkey, InMemoryUserStore users, PasskeyProperties props) {
        this.passkey = passkey;
        this.users   = users;
        this.props   = props;
    }

    // ── Registration ─────────────────────────────────────────────

    @PostMapping("/register/options")
    public ApiResponse<RegisterOptionsResp> registerOptions(@Valid @RequestBody RegisterStartReq req,
                                                            HttpSession s) {
        log.info("register/options entry: usernamePresent={}", req.username() != null);
        String userHandle = users.createPending(req.username(), req.displayName());
        RegistrationStartResponse sdkResp;
        try {
            sdkResp = passkey.registrationStart(
                    new RegistrationStartRequest(userHandle, req.displayName(), req.username()));
        } catch (RuntimeException e) {
            log.warn("register/options upstream-failed: cause={}", e.toString());
            throw e;
        }
        s.setAttribute(SessionKeys.PENDING_REG_TOKEN, sdkResp.registrationToken());
        s.setAttribute(SessionKeys.PENDING_USER_HANDLE, userHandle);
        log.info("register/options ok: userHandle={}", idTail(userHandle));
        return ApiResponse.ok(new RegisterOptionsResp(sdkResp.publicKeyCredentialCreationOptions()));
    }

    @PostMapping("/register/complete")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req,
                                                                    HttpSession s) {
        log.info("register/complete entry: sessionId={}", idTail(s.getId()));
        String token  = (String) s.getAttribute(SessionKeys.PENDING_REG_TOKEN);
        String handle = (String) s.getAttribute(SessionKeys.PENDING_USER_HANDLE);
        if (token == null) {
            log.warn("register/complete failed: reason=pending-token-missing");
            throw new BusinessException(ErrorCode.PENDING_REG_MISSING);
        }

        RegistrationFinishResponse fin;
        try {
            fin = passkey.registrationFinish(new RegistrationFinishRequest(token, req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("register/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        users.confirmRegistration(handle, fin.credentialId());
        s.removeAttribute(SessionKeys.PENDING_REG_TOKEN);
        s.removeAttribute(SessionKeys.PENDING_USER_HANDLE);
        log.info("register/complete ok: userHandle={} credentialId={}",
                idTail(handle), idTail(fin.credentialId()));
        return ApiResponse.ok("Passkey registered", fin);
    }

    // ── Login ────────────────────────────────────────────────────

    @PostMapping("/login/options")
    public ApiResponse<LoginOptionsResp> loginOptions(@RequestBody LoginStartReq req, HttpSession s) {
        log.info("login/options entry: flow={}",
                req.username() == null ? "discoverable" : "typed");
        String userHandle = req.username() == null ? null
                : users.findByUsername(req.username()).map(SampleRpUser::userHandle).orElse(null);
        AuthenticationStartResponse sdkResp;
        try {
            sdkResp = passkey.authenticationStart(new AuthenticationStartRequest(userHandle));
        } catch (RuntimeException e) {
            log.warn("login/options upstream-failed: cause={}", e.toString());
            throw e;
        }
        s.setAttribute(SessionKeys.PENDING_AUTH_TOKEN, sdkResp.authenticationToken());
        log.info("login/options ok: userHandlePresent={}", userHandle != null);
        return ApiResponse.ok(new LoginOptionsResp(sdkResp.publicKeyCredentialRequestOptions()));
    }

    @PostMapping("/login/complete")
    public ApiResponse<Void> loginComplete(@Valid @RequestBody LoginCompleteReq req, HttpSession s) {
        log.info("login/complete entry: sessionId={}", idTail(s.getId()));
        String token = (String) s.getAttribute(SessionKeys.PENDING_AUTH_TOKEN);
        if (token == null) {
            log.warn("login/complete failed: reason=pending-token-missing");
            throw new BusinessException(ErrorCode.PENDING_AUTH_MISSING);
        }

        AuthenticationFinishResponse fin;
        try {
            fin = passkey.authenticationFinish(new AuthenticationFinishRequest(token, req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("login/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        IdTokenClaims claims;
        try {
            claims = passkey.verifyIdToken(fin.idToken());
        } catch (RuntimeException e) {
            log.warn("login/complete failed: reason=id-token-verify-failed cause={}", e.toString());
            throw e;
        }

        // passkey-app 의 IdTokenIssuer 는 `passkey.id-token.issuer-base` (기본
        // `https://passkey.crosscert.com`) + "/" + tenantId 를 발급한다.
        // sample-rp 의 issuerBase 프로퍼티가 그 값과 일치해야 한다.
        String expectedIss = props.issuerBase() + "/" + props.tenantId();
        if (!expectedIss.equals(claims.iss())) {
            log.warn("login/complete failed: reason=iss-mismatch expected={} got={}",
                    expectedIss, claims.iss());
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "iss mismatch");
        }
        if (!props.tenantId().equals(claims.aud())) {
            log.warn("login/complete failed: reason=aud-mismatch expected={} got={}",
                    props.tenantId(), claims.aud());
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "aud mismatch");
        }

        SampleRpUser user = users.findByUserHandle(claims.sub())
                .orElseThrow(() -> {
                    log.warn("login/complete failed: reason=unknown-sub subTail={}",
                            idTail(claims.sub()));
                    return new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub");
                });
        s.setAttribute(SessionKeys.USER, user);
        s.removeAttribute(SessionKeys.PENDING_AUTH_TOKEN);
        log.info("login/complete ok: subTail={} userHandle={}",
                idTail(claims.sub()), idTail(user.userHandle()));
        return ApiResponse.ok();
    }

    /** Last 12 chars of a base64url id for correlation — never the full id.
     *  Short ids (≤12 chars) are masked to "***" so the full value is never logged. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }
}
