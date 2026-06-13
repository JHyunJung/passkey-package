package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.crosscert.passkey.rpapp.common.response.ApiResponse;
import com.crosscert.passkey.rpapp.config.PasskeyProperties;
import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.rpapp.user.RpAppUser;
import com.crosscert.passkey.rpapp.web.dto.*;
import com.crosscert.passkey.rpapp.web.relay.RegRelayCodec;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.*;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/passkey")
public class WebAuthnController {

    private final PasskeyClient passkey;
    private final InMemoryUserStore users;
    private final PasskeyProperties props;
    private final RegRelayCodec relay;

    public WebAuthnController(PasskeyClient passkey, InMemoryUserStore users,
                             PasskeyProperties props, RegRelayCodec relay) {
        this.passkey = passkey;
        this.users   = users;
        this.props   = props;
        this.relay   = relay;
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
        return ApiResponse.ok(new RegisterOptionsResp(
                sdkResp.publicKeyCredentialCreationOptions(), regRelayToken));
    }

    @PostMapping("/register/finish")
    public ApiResponse<RegistrationFinishResponse> registerComplete(@Valid @RequestBody RegisterCompleteReq req) {
        RegRelayCodec.RegRelay r;
        try {
            r = relay.decode(req.regRelayToken());
        } catch (IllegalArgumentException e) {
            log.warn("register/complete failed: reason=relay-invalid cause={}", e.getMessage());
            throw new BusinessException(ErrorCode.PENDING_REG_MISSING, e.getMessage());
        }
        log.info("register/complete entry: userHandle={}", idTail(r.userHandle()));
        // upstream finish 전 username 점유 선검사: 유효 begin 에서 온(HMAC 증명) username 이라도
        // finish 시점에 다른 사용자에게 확정돼 있으면 typed-login 탈취/충돌을 막는다. 단일 인스턴스
        // (이 store 의 설계 전제)에선 upstream credential 을 만들기 전에 거부해 rp-app↔passkey-app
        // 불일치도 피한다. confirmRegistration 의 putIfAbsent 가 최종 권위(이중 방어) — 다중
        // 인스턴스 동시 finish 같은 좁은 race 에선 선검사를 통과한 패자가 store 확정 단계에서 거부될 수
        // 있고, 그 경우 upstream credential 만 남는다(단일 인스턴스 데모 전제상 허용 범위).
        if (users.isUsernameTakenByOther(r.username(), r.userHandle())) {
            log.warn("register/complete failed: reason=username-taken userHandle={}", idTail(r.userHandle()));
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        RegistrationFinishResponse fin;
        try {
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
        log.info("login/options entry: flow={}",
                req.username() == null ? "discoverable" : "typed");
        String userHandle = req.username() == null ? null
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
            fin = passkey.authenticationFinish(
                    new AuthenticationFinishRequest(req.authenticationToken(), req.publicKeyCredential()));
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

        // passkey-app 의 IdTokenIssuer 는 issuer-base + "/" + tenantId 로 iss 를,
        // tenantId 로 aud 를 발급한다. tenantId 는 표준 UUID(소문자+대시)로 들어가는데,
        // 설정(PASSKEY_TENANT_ID)은 RAW(16) hex 32자로 들어올 수도 있다. 표기 차이로
        // 인한 거짓 mismatch 를 막기 위해 비교 전 양쪽 tenantId 를 UUID 로 정규화한다.
        String expectedTenant = normalizeTenantId(props.tenantId());

        // iss = "<issuerBase>/<tenantId>" — issuerBase prefix 와 tenantId 를 분리해 검증.
        String issPrefix = props.issuerBase().toString();
        String tokenIss  = claims.iss();
        boolean issOk = tokenIss != null
                && tokenIss.startsWith(issPrefix + "/")
                && normalizeTenantId(tokenIss.substring((issPrefix + "/").length())).equals(expectedTenant);
        if (!issOk) {
            log.warn("login/complete failed: reason=iss-mismatch expectedPrefix={} expectedTenant={} got={}",
                    issPrefix, expectedTenant, tokenIss);
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "iss mismatch");
        }
        if (!expectedTenant.equals(normalizeTenantId(claims.aud()))) {
            log.warn("login/complete failed: reason=aud-mismatch expected={} got={}",
                    expectedTenant, claims.aud());
            throw new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "aud mismatch");
        }

        RpAppUser user = users.findByUserHandle(claims.sub())
                .orElseThrow(() -> {
                    log.warn("login/complete failed: reason=unknown-sub subTail={}", idTail(claims.sub()));
                    return new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub");
                });
        log.info("login/complete ok: subTail={} userHandle={}",
                idTail(claims.sub()), idTail(user.userHandle()));
        return ApiResponse.ok(new LoginResultResp(true, user.userHandle(), user.displayName()));
    }

    /**
     * tenantId 를 표준 UUID 문자열(소문자+대시)로 정규화한다.
     * 입력은 UUID 대시 형식(`7f00dead-0000-...`) 또는 RAW(16) hex 32자(`7F00DEAD...`)
     * 모두 허용. 형식이 달라도 같은 테넌트면 같은 결과가 나오므로 iss/aud 비교가
     * 표기 차이에 영향받지 않는다. 파싱 불가하면 입력을 그대로 반환(검증은 후속 비교에서).
     */
    static String normalizeTenantId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 대시 없는 32자 hex → UUID 대시 형식으로
        if (s.matches("(?i)[0-9a-f]{32}")) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
              + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        try {
            return java.util.UUID.fromString(s).toString();   // 항상 소문자+대시
        } catch (IllegalArgumentException e) {
            return raw;   // UUID 가 아니면 원본 비교에 맡김
        }
    }

    /** Last 12 chars of a base64url id for correlation — never the full id.
     *  Short ids (≤12 chars) are masked to "***" so the full value is never logged. */
    private static String idTail(String id) {
        if (id == null) return "null";
        if (id.length() <= 12) return "***";
        return "..." + id.substring(id.length() - 12);
    }
}
