package com.crosscert.passkey.app.api.v1.rp

import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView
import com.crosscert.passkey.app.fido2.credential.CredentialSelfService
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

/**
 * P0-4: RP 백엔드가 X-API-Key 인증 상태에서 특정 user(userHandle)의 passkey 를 관리.
 * tenant 격리는 ApiKeyAuthFilter 가 set 한 context + 앱 레벨 @Filter 가 담당.
 *
 * 응답은 다른 RP 컨트롤러(RegistrationController 등)와 동일하게 [ApiResponse] envelope 로 감싼다.
 *
 * [Validated] (클래스 레벨) 는 `@RequestParam @NotBlank` 같은 method-parameter 제약을 활성화한다
 * — 없으면 무시된다. body 검증은 `@Valid @RequestBody` 가 담당. 검증 실패는 GlobalExceptionHandler 가
 * 400 (INVALID_INPUT) 으로 매핑 (ConstraintViolationException / MethodArgumentNotValidException).
 *
 * `@RequestParam`·`@RequestBody` 파라미터는 non-null — Kotlin nullable 이면 Spring 이 optional 로
 * 취급해 누락 시 거부(MISSING_PARAMETER / 바디 누락)하지 않으므로 원 Java(required=true)와 달라진다.
 */
@RestController
@RequestMapping("/api/v1/rp/credentials")
@Validated
class CredentialSelfServiceController(private val service: CredentialSelfService) {

    @GetMapping
    fun list(@RequestParam @NotBlank userHandle: String): ApiResponse<List<CredentialView>> =
        ApiResponse.ok(service.list(decode(userHandle)))

    /** 검증 대상 필드는 nullable — 누락/null 이 역직렬화가 아니라 Bean Validation 에서 거부되도록. */
    data class RenameRequest(
        @field:NotBlank val userHandle: String?,
        @field:NotBlank @field:Size(max = 128) val label: String?,
    )

    @PostMapping("/{credentialId}/label")
    fun rename(@PathVariable credentialId: String, @Valid @RequestBody req: RenameRequest): ApiResponse<Void> {
        service.rename(decode(req.userHandle), decode(credentialId), req.label)
        return ApiResponse.ok()
    }

    @DeleteMapping("/{credentialId}")
    fun delete(@PathVariable credentialId: String, @RequestParam @NotBlank userHandle: String): ApiResponse<Void> {
        service.delete(decode(userHandle), decode(credentialId))
        return ApiResponse.ok()
    }

    /**
     * base64url decode — 형식이 잘못되면 400 (INVALID_INPUT).
     * 인자가 nullable 인 것은 RenameRequest 필드 때문(@Valid 로 null 은 이미 거부됨) — 원 Java 와 같이
     * 그대로 디코더에 넘긴다.
     */
    private fun decode(b64url: String?): ByteArray =
        try {
            Base64.getUrlDecoder().decode(b64url)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "base64url 형식이 아님")
        }
}
