package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.config.MeView;

import java.util.List;
import java.util.UUID;

/**
 * 화면 모델의 {@code me}. {@code MeController.me(auth, req).data()} 를 그대로 옮기고,
 * 템플릿에서 역할 분기를 쉽게 하도록 {@code platform}(PLATFORM_OPERATOR 여부)을 더한다.
 */
public record CurrentAdmin(String email, String role, List<UUID> tenantIds,
                           boolean mfaEnabled, boolean mfaRequired,
                           int sessionIdleTimeoutMinutes, boolean platform) {

    public static final String PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    public static CurrentAdmin from(MeView v) {
        return new CurrentAdmin(v.getEmail(), v.getRole(), v.getTenantIds(),
                v.getMfaEnabled(), v.getMfaRequired(), v.getSessionIdleTimeoutMinutes(),
                PLATFORM_OPERATOR.equals(v.getRole()));
    }
}
