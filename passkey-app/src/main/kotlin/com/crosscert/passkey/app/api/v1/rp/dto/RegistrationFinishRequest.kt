package com.crosscert.passkey.app.api.v1.rp.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import tools.jackson.databind.JsonNode

/**
 * `publicKeyCredential` 은 기본값 없는 `JsonNode?` — 누락은 @NotNull fieldError, 명시적 JSON null 은
 * NullNode 로 바인딩돼 검증을 통과한다(이후 서비스 단계에서 처리).
 */
data class RegistrationFinishRequest(
    @field:NotBlank val registrationToken: String?,
    @field:NotNull val publicKeyCredential: JsonNode?,
)
