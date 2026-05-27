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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webauthn")
public class WebAuthnController {

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
        String userHandle = users.createPending(req.username(), req.displayName());
        var sdkResp = passkey.registrationStart(
                new RegistrationStartRequest(userHandle, req.displayName(), req.username()));
        s.setAttribute(SessionKeys.PENDING_REG_TOKEN, sdkResp.registrationToken());
        s.setAttribute(SessionKeys.PENDING_USER_HANDLE, userHandle);
        return ApiResponse.ok(new RegisterOptionsResp(sdkResp.publicKeyCredentialCreationOptions()));
    }

    @PostMapping("/register/complete")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req,
                                                                    HttpSession s) {
        String token  = (String) s.getAttribute(SessionKeys.PENDING_REG_TOKEN);
        String handle = (String) s.getAttribute(SessionKeys.PENDING_USER_HANDLE);
        if (token == null) throw new BusinessException(ErrorCode.PENDING_REG_MISSING);

        var fin = passkey.registrationFinish(new RegistrationFinishRequest(token, req.publicKeyCredential()));
        users.confirmRegistration(handle, fin.credentialId());
        s.removeAttribute(SessionKeys.PENDING_REG_TOKEN);
        s.removeAttribute(SessionKeys.PENDING_USER_HANDLE);
        return ApiResponse.ok("Passkey registered", fin);
    }

    // ── Login ────────────────────────────────────────────────────

    @PostMapping("/login/options")
    public ApiResponse<LoginOptionsResp> loginOptions(@RequestBody LoginStartReq req, HttpSession s) {
        String userHandle = req.username() == null ? null
                : users.findByUsername(req.username()).map(SampleRpUser::userHandle).orElse(null);
        var sdkResp = passkey.authenticationStart(new AuthenticationStartRequest(userHandle));
        s.setAttribute(SessionKeys.PENDING_AUTH_TOKEN, sdkResp.authenticationToken());
        return ApiResponse.ok(new LoginOptionsResp(sdkResp.publicKeyCredentialRequestOptions()));
    }

    @PostMapping("/login/complete")
    public ApiResponse<Void> loginComplete(@Valid @RequestBody LoginCompleteReq req, HttpSession s) {
        String token = (String) s.getAttribute(SessionKeys.PENDING_AUTH_TOKEN);
        if (token == null) throw new BusinessException(ErrorCode.PENDING_AUTH_MISSING);

        var fin = passkey.authenticationFinish(new AuthenticationFinishRequest(token, req.publicKeyCredential()));
        IdTokenClaims claims = passkey.verifyIdToken(fin.idToken());

        // passkey-app 의 IdTokenIssuer 는 `passkey.id-token.issuer-base` (기본
        // `https://passkey.crosscert.com`) + "/" + tenantId 를 발급한다.
        // sample-rp 의 issuerBase 프로퍼티가 그 값과 일치해야 한다.
        String expectedIss = props.issuerBase() + "/" + props.tenantId();
        if (!expectedIss.equals(claims.iss()))
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "iss mismatch");
        if (!props.tenantId().equals(claims.aud()))
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "aud mismatch");

        SampleRpUser user = users.findByUserHandle(claims.sub())
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub"));
        s.setAttribute(SessionKeys.USER, user);
        s.removeAttribute(SessionKeys.PENDING_AUTH_TOKEN);
        return ApiResponse.ok();
    }
}
