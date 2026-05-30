package com.crosscert.passkey.app.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyScopeResolverTest {

    private final ApiKeyScopeResolver resolver = new ApiKeyScopeResolver();

    @Test
    void registration_paths_require_registration_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/registration/start")).contains("registration");
        assertThat(resolver.requiredScope("/api/v1/rp/registration/finish")).contains("registration");
    }

    @Test
    void authentication_paths_require_authentication_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/authentication/start")).contains("authentication");
        assertThat(resolver.requiredScope("/api/v1/rp/authentication/finish")).contains("authentication");
    }

    @Test
    void credentials_self_service_requires_registration_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/credentials")).contains("registration");
        assertThat(resolver.requiredScope("/api/v1/rp/credentials/abc/label")).contains("registration");
    }

    @Test
    void unmapped_path_requires_no_scope() {
        // /api/v1/rp 밖은 scope 불요(인증만 통과하면 됨)
        assertThat(resolver.requiredScope("/actuator/health")).isEmpty();
        assertThat(resolver.requiredScope("/.well-known/jwks.json")).isEmpty();
        assertThat(resolver.requiredScope(null)).isEmpty();
    }

    @Test
    void unmapped_rp_path_is_fail_closed() {
        // fail-closed: /api/v1/rp 하위 미매핑 경로는 보유 불가능한 sentinel scope 요구 → 필터가 403
        assertThat(resolver.requiredScope("/api/v1/rp/futurefeature"))
                .contains("__unmapped_rp__");
        assertThat(resolver.requiredScope("/api/v1/rp/other"))
                .contains("__unmapped_rp__");
    }

    @Test
    void segment_boundary_prevents_false_match() {
        // registration 의 prefix 이지만 다른 라우트는 registration scope 를 받지 않는다.
        // fail-closed 라 sentinel 을 받지만(empty 아님) — 핵심은 잘못된 실제 scope 를 안 받는 것.
        // hasValue(sentinel) 은 정확히 sentinel 임을 증명 → registration/authentication 아님.
        assertThat(resolver.requiredScope("/api/v1/rp/registrationXYZ"))
                .hasValue("__unmapped_rp__");
        assertThat(resolver.requiredScope("/api/v1/rp/authentication-config"))
                .hasValue("__unmapped_rp__");
    }
}
