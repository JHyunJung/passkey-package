package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView;
import com.crosscert.passkey.app.fido2.credential.CredentialSelfService;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;

/**
 * P0-4: RP 백엔드가 X-API-Key 인증 상태에서 특정 user(userHandle)의 passkey 를 관리.
 * tenant 격리는 ApiKeyAuthFilter 가 set 한 context + VPD 가 담당.
 *
 * <p>응답은 다른 RP 컨트롤러(RegistrationController 등)와 동일하게
 * {@link ApiResponse} envelope 로 감싼다.
 */
@RestController
@RequestMapping("/api/v1/rp/credentials")
public class CredentialSelfServiceController {

    private final CredentialSelfService service;

    public CredentialSelfServiceController(CredentialSelfService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CredentialView>> list(@RequestParam @NotBlank String userHandle) {
        return ApiResponse.ok(service.list(decode(userHandle)));
    }

    public record RenameRequest(@NotBlank String userHandle, @NotBlank String label) {}

    @PostMapping("/{credentialId}/label")
    public ApiResponse<Void> rename(@PathVariable String credentialId, @RequestBody RenameRequest req) {
        service.rename(decode(req.userHandle()), decode(credentialId), req.label());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{credentialId}")
    public ApiResponse<Void> delete(@PathVariable String credentialId, @RequestParam @NotBlank String userHandle) {
        service.delete(decode(userHandle), decode(credentialId));
        return ApiResponse.ok();
    }

    /** base64url decode — 형식이 잘못되면 400 (INVALID_INPUT). */
    private static byte[] decode(String b64url) {
        try {
            return Base64.getUrlDecoder().decode(b64url);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "base64url 형식이 아님");
        }
    }
}
