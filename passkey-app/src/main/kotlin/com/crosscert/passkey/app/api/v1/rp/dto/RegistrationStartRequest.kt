package com.crosscert.passkey.app.api.v1.rp.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 검증 대상 필드는 nullable — 누락/null 입력이 역직렬화 단계가 아니라 Bean Validation 에서
 * fieldError 로 거부되도록(에러 봉투 계약 유지). 제약은 `@field:` 로 필드에 건다.
 *
 * 요청 DTO 전체에 같은 규칙이다. non-null 로 바꾸면 누락 필드가 Jackson 역직렬화 실패가 되어
 * 400 "Malformed JSON request"(fieldErrors 없음)로 응답이 바뀐다. `@field:` 를 빼면 Kotlin 은
 * 애너테이션을 생성자 파라미터에 붙이고, 필드 기준으로 도는 `@Valid` 검증은 조용히 빠진다.
 * 이 응답 계약은 RequestDtoMissingFieldContractIT 가 스냅샷으로 고정한다.
 */
data class RegistrationStartRequest(
    // 문자열 상한은 여기서 @Size 로. userHandle 은 base64url 인코딩이라
    // 바이트 길이 상한(WebAuthn user.id 64바이트)은 문자열 길이와 다르게 계산돼야
    // 하므로 여기서 커버하지 않는다 — RegistrationStartService.decodeAndValidateUserHandle()
    // 이 디코드 후 바이트 단위로 검증한다. 이 필드의 @Size(max=88)은 64바이트를
    // base64url(패딩 없음, 4/3 확장) 인코딩했을 때의 문자열 길이 상한(ceil(64*4/3)=86,
    // 여유 포함 88)으로, 디코드 전 명백히 초과인 입력을 컨트롤러 바인딩 단계에서
    // 조기 거부하는 방어선이며 디코드 후 바이트 검증을 대체하지 않는다.
    @field:NotBlank @field:Size(max = 88) val userHandle: String?, // base64url
    @field:NotBlank @field:Size(max = 256) val displayName: String?,
    @field:NotBlank @field:Size(max = 256) val username: String?,
)
