package com.crosscert.passkey.admin.operator

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

class SignupRequestDto private constructor() {

    /**
     * 공개 가입 요청 본문. 비밀번호 정책은 초대 수락 때와 같은 12~128자.
     * role 은 선택 — PLATFORM_OPERATOR 이고 admin.signup.auto-approve-platform-operator 가
     * 켜져 있으면 승인 없이 바로 계정이 만들어진다. 그 외에는 무시되고 승인자가 역할을 정한다.
     */
    data class Create(
        @field:NotBlank @field:Email @field:Size(max = 255) val email: String?,
        @field:NotBlank @field:Size(min = 12, max = 128) val password: String?,
        @field:Size(max = 500) val reason: String?,
        @field:Pattern(regexp = "PLATFORM_OPERATOR|RP_ADMIN") val role: String?,
    ) {
        constructor(email: String?, password: String?, reason: String?) : this(email, password, reason, null)
    }

    /** 관리자 목록 뷰. bcrypt 해시는 절대 노출하지 않는다. */
    data class View(
        val id: UUID?,
        val email: String?,
        val reason: String?,
        val requestedAt: OffsetDateTime?,
    )

    /** 승인 본문. 역할·테넌트 규칙은 서비스에서 검증한다. */
    data class Approve(
        @field:NotBlank val role: String?,
        val tenantIds: List<UUID>?,
    )
}
