package com.crosscert.passkey.admin.policy

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.Objects

/**
 * View·UpdateRequest 는 원 record 의 compact constructor(List 정규화)를 그대로 옮기기 위해
 * data class 대신 일반 class 로 두고 equals/hashCode/toString 을 record 와 같은 의미로 구현한다
 * (data class 는 주 생성자 프로퍼티를 init 에서 다시 대입할 수 없다).
 * java.util.List.copyOf: 원 Java 처럼 null 요소면 NPE + 불변 사본.
 */
class SecurityPolicyDto private constructor() {

    class View(
        sessionIdleTimeoutMinutes: Int,
        mfaRequired: Boolean,
        corsAllowlist: List<String>?,
        updatedAt: OffsetDateTime?,
        updatedBy: String?,
    ) {
        val sessionIdleTimeoutMinutes: Int = sessionIdleTimeoutMinutes
        val mfaRequired: Boolean = mfaRequired
        val corsAllowlist: List<String> =
            if (corsAllowlist == null) java.util.List.of() else java.util.List.copyOf(corsAllowlist)
        val updatedAt: OffsetDateTime? = updatedAt
        val updatedBy: String? = updatedBy

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is View) return false
            return sessionIdleTimeoutMinutes == other.sessionIdleTimeoutMinutes &&
                mfaRequired == other.mfaRequired &&
                corsAllowlist == other.corsAllowlist &&
                updatedAt == other.updatedAt &&
                updatedBy == other.updatedBy
        }

        override fun hashCode(): Int =
            Objects.hash(sessionIdleTimeoutMinutes, mfaRequired, corsAllowlist, updatedAt, updatedBy)

        override fun toString(): String =
            "View[sessionIdleTimeoutMinutes=$sessionIdleTimeoutMinutes, mfaRequired=$mfaRequired, " +
                "corsAllowlist=$corsAllowlist, updatedAt=$updatedAt, updatedBy=$updatedBy]"
    }

    class UpdateRequest(
        sessionIdleTimeoutMinutes: Int,
        mfaRequired: Boolean?,
        corsAllowlist: List<String>?,
    ) {
        @field:Min(1)
        val sessionIdleTimeoutMinutes: Int = sessionIdleTimeoutMinutes

        @field:NotNull
        val mfaRequired: Boolean? = mfaRequired

        @field:NotNull
        @field:Size(max = 64)
        val corsAllowlist: List<@NotBlank @Size(max = 256) String>? =
            if (corsAllowlist != null) java.util.List.copyOf(corsAllowlist) else null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UpdateRequest) return false
            return sessionIdleTimeoutMinutes == other.sessionIdleTimeoutMinutes &&
                mfaRequired == other.mfaRequired &&
                corsAllowlist == other.corsAllowlist
        }

        override fun hashCode(): Int = Objects.hash(sessionIdleTimeoutMinutes, mfaRequired, corsAllowlist)

        override fun toString(): String =
            "UpdateRequest[sessionIdleTimeoutMinutes=$sessionIdleTimeoutMinutes, mfaRequired=$mfaRequired, " +
                "corsAllowlist=$corsAllowlist]"
    }
}
