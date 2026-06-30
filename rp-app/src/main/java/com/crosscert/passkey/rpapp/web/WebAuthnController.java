package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.crosscert.passkey.rpapp.common.response.ApiResponse;
import com.crosscert.passkey.rpapp.config.PasskeyProperties;
import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.rpapp.user.RpAppUser;
import com.crosscert.passkey.rpapp.web.dto.LoginCompleteReq;
import com.crosscert.passkey.rpapp.web.dto.LoginOptionsResp;
import com.crosscert.passkey.rpapp.web.dto.LoginResultResp;
import com.crosscert.passkey.rpapp.web.dto.LoginStartReq;
import com.crosscert.passkey.rpapp.web.dto.RegisterCompleteReq;
import com.crosscert.passkey.rpapp.web.dto.RegisterOptionsResp;
import com.crosscert.passkey.rpapp.web.dto.RegisterStartReq;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.sdk.dto.AuthenticationStartRequest;
import com.crosscert.passkey.sdk.dto.AuthenticationStartResponse;
import com.crosscert.passkey.sdk.dto.RegistrationFinishRequest;
import com.crosscert.passkey.sdk.dto.RegistrationFinishResponse;
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 패스키 등록·인증의 RP 엔드포인트. 브라우저와 passkey-app 사이의 중계자다.
 *
 * <p>등록·인증 두 플로우가 각각 begin → finish 2단계로 동작한다(총 4개 엔드포인트):
 * <ul>
 *   <li>{@code POST /passkey/register/begin} → {@code /register/finish}</li>
 *   <li>{@code POST /passkey/authenticate/begin} → {@code /authenticate/finish}</li>
 * </ul>
 *
 * <p>서버에 세션을 두지 않는다. begin 응답으로 받은 서명 토큰(regRelayToken / authenticationToken)을
 * finish 요청에 다시 실어 두 단계를 잇는다. 실제 WebAuthn 동작(ceremony)과 ID Token 발급은
 * passkey-app 이 맡고, rp-app 은 SDK 호출·사용자 매핑·sub 조회·ID Token 검증 오케스트레이션을
 * 담당한다(서명·iss·aud 검증은 SDK 의 verifyIdToken 에 위임, 실패는 P004 로 변환).
 */
@RestController
@RequestMapping("/passkey")
public class WebAuthnController {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnController.class);

    private final PasskeyClient passkey;
    private final InMemoryUserStore users;
    private final PasskeyProperties props;
    private final RegistrationRelayCodec relay;

    public WebAuthnController(PasskeyClient passkey, InMemoryUserStore users,
                             PasskeyProperties props, RegistrationRelayCodec relay) {
        this.passkey = passkey;
        this.users = users;
        this.props = props;
        this.relay = relay;
    }

    /** Last 12 chars of a base64url id for correlation — never the full id.
     *  Short ids (≤12 chars) are masked to "***" so the full value is never logged. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }

    // ── Registration ─────────────────────────────────────────────

    @PostMapping("/register/begin")
    public ApiResponse<RegisterOptionsResp> registerOptions(@Valid @RequestBody RegisterStartReq req) {
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
        log.info("register/options ok: userHandle={}", idTail(userHandle));
        String regRelayToken = relay.encode(
                sdkResp.registrationToken(), userHandle, req.username(), req.displayName());
        return ApiResponse.ok(
                new RegisterOptionsResp(sdkResp.publicKeyCredentialCreationOptions(), regRelayToken));
    }

    @PostMapping("/register/finish")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req) {
        RegistrationRelayCodec.RegistrationRelay r;
        try {
            r = relay.decode(req.regRelayToken());
        } catch (IllegalArgumentException e) {
            log.warn("register/complete failed: reason=relay-invalid cause={}", e.getMessage());
            throw new BusinessException(ErrorCode.PENDING_REG_MISSING, e.getMessage());
        }
        log.info("register/complete entry: userHandle={}", idTail(r.userHandle()));
        // finish 전에 username 선점 여부를 검사한다. 유효한 begin(HMAC 으로 증명된) 에서 온 username 이라도
        // finish 시점에 다른 사용자에게 확정돼 있으면 typed-login 탈취/충돌을 막기 위해 거부한다.
        // 최종 권위는 confirmRegistration 의 putIfAbsent(원자적 점유)이고, 이 선검사는 upstream 호출 전 조기 차단이다.
        if (users.isUsernameTakenByOther(r.username(), r.userHandle())) {
            log.warn("register/complete failed: reason=username-taken userHandle={}", idTail(r.userHandle()));
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        RegistrationFinishResponse fin;
        try {
            // @Valid @NotNull 로 검증된 필드 — null 이면 컨트롤러 진입 전 400. SDK 는 non-null 요구.
            fin = passkey.registrationFinish(
                    new RegistrationFinishRequest(r.registrationToken(), req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("register/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        users.confirmRegistration(r.userHandle(), r.username(), r.displayName(), fin.credentialId());
        log.info("register/complete ok: userHandle={} credentialId={}",
                idTail(r.userHandle()), idTail(fin.credentialId()));
        return ApiResponse.ok("Passkey registered", fin);
    }

    // ── Login ────────────────────────────────────────────────────

    @PostMapping("/authenticate/begin")
    public ApiResponse<LoginOptionsResp> loginOptions(@RequestBody LoginStartReq req) {
        log.info("login/options entry: flow={}", req.username() == null ? "discoverable" : "typed");
        String userHandle = (req.username() == null)
                ? null
                : users.findByUsername(req.username()).map(RpAppUser::userHandle).orElse(null);
        AuthenticationStartResponse sdkResp;
        try {
            sdkResp = passkey.authenticationStart(new AuthenticationStartRequest(userHandle));
        } catch (RuntimeException e) {
            log.warn("login/options upstream-failed: cause={}", e.toString());
            throw e;
        }
        log.info("login/options ok: userHandlePresent={}", userHandle != null);
        return ApiResponse.ok(new LoginOptionsResp(
                sdkResp.publicKeyCredentialRequestOptions(),
                sdkResp.authenticationToken()));
    }

    @PostMapping("/authenticate/finish")
    public ApiResponse<LoginResultResp> loginComplete(@Valid @RequestBody LoginCompleteReq req) {
        log.info("login/complete entry");
        AuthenticationFinishResponse fin;
        try {
            // @Valid @NotBlank/@NotNull 로 검증된 필드 — null 이면 컨트롤러 진입 전 400. SDK 는 non-null 요구.
            fin = passkey.authenticationFinish(
                    new AuthenticationFinishRequest(req.authenticationToken(), req.publicKeyCredential()));
        } catch (RuntimeException e) {
            log.warn("login/complete upstream-failed: cause={}", e.toString());
            throw e;
        }
        IdTokenClaims claims;
        try {
            // issuerBase 가 설정돼 있어야 expectedIssuer 를 조립할 수 있으므로 누락 시 즉시 실패.
            if (props.issuerBase() == null) {
                throw new NullPointerException("passkey.issuer-base 미설정");
            }
            String expectedIssuer = props.issuerBase() + "/" + props.tenantId();
            // 서명·exp + iss(prefix+tenant 정규화)·aud(정규화) 검증을 SDK 가 한 번에 수행.
            claims = passkey.verifyIdToken(fin.idToken(), expectedIssuer, props.tenantId());
        } catch (com.crosscert.passkey.sdk.exception.PasskeyIdTokenException e) {
            // SDK 의 검증 실패(서명/exp/iss/aud)를 기존 RP 에러코드로 변환해 응답 호환을 보존한다.
            log.warn("login/complete failed: reason=id-token-invalid cause={}", e.getMessage());
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("login/complete failed: reason=id-token-verify-failed cause={}", e.toString());
            throw e;
        }

        // sub(opaque userHandle)로 사용자를 조회한다. sub 가 없으면(null) 조회가 비어
        // unknown sub(P004)로 거부된다 — fail-closed.
        RpAppUser user = users.findByUserHandle(claims.sub())
                .orElseThrow(() -> {
                    log.warn("login/complete failed: reason=unknown-sub subTail={}", idTail(claims.sub()));
                    return new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub");
                });
        log.info("login/complete ok: subTail={} userHandle={}",
                idTail(claims.sub()), idTail(user.userHandle()));
        return ApiResponse.ok(new LoginResultResp(true, user.userHandle(), user.displayName()));
    }
}
