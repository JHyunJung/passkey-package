package com.crosscert.passkey.admin.web.support

import com.crosscert.passkey.admin.config.MeView
import java.util.UUID

/**
 * 화면 모델의 `me`. `MeController.me(auth, req).data()` 를 그대로 옮기고,
 * 템플릿에서 역할 분기를 쉽게 하도록 `platform`(PLATFORM_OPERATOR 여부)을 더한다.
 */
data class CurrentAdmin(
    val email: String?,
    val role: String?,
    val tenantIds: List<UUID>?,
    val mfaEnabled: Boolean,
    val mfaRequired: Boolean,
    val sessionIdleTimeoutMinutes: Int,
    val platform: Boolean,
) {
    companion object {
        const val PLATFORM_OPERATOR = "PLATFORM_OPERATOR"

        @JvmStatic
        fun from(v: MeView): CurrentAdmin = CurrentAdmin(
            v.email, v.role, v.tenantIds,
            v.mfaEnabled, v.mfaRequired, v.sessionIdleTimeoutMinutes,
            PLATFORM_OPERATOR == v.role,
        )
    }
}
