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
        assertThat(resolver.requiredScope("/api/v1/rp/other")).isEmpty();
        assertThat(resolver.requiredScope("/actuator/health")).isEmpty();
        assertThat(resolver.requiredScope(null)).isEmpty();
    }
}
