package com.crosscert.passkey.app.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiKeyScopeResolverTest {

    private val resolver = ApiKeyScopeResolver()

    @Test
    fun registration_paths_require_registration_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/registration/start")).contains("registration")
        assertThat(resolver.requiredScope("/api/v1/rp/registration/finish")).contains("registration")
    }

    @Test
    fun authentication_paths_require_authentication_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/authentication/start")).contains("authentication")
        assertThat(resolver.requiredScope("/api/v1/rp/authentication/finish")).contains("authentication")
    }

    @Test
    fun credentials_self_service_requires_registration_scope() {
        assertThat(resolver.requiredScope("/api/v1/rp/credentials")).contains("registration")
        assertThat(resolver.requiredScope("/api/v1/rp/credentials/abc/label")).contains("registration")
    }

    @Test
    fun unmapped_path_requires_no_scope() {
        // /api/v1/rp 밖은 scope 불요(인증만 통과하면 됨)
        assertThat(resolver.requiredScope("/actuator/health")).isEmpty()
        assertThat(resolver.requiredScope("/.well-known/jwks.json")).isEmpty()
        assertThat(resolver.requiredScope(null)).isEmpty()
    }

    @Test
    fun unmapped_rp_path_is_fail_closed() {
        // fail-closed: /api/v1/rp 하위 미매핑 경로는 보유 불가능한 sentinel scope 요구 → 필터가 403
        assertThat(resolver.requiredScope("/api/v1/rp/futurefeature")).contains("__unmapped_rp__")
        assertThat(resolver.requiredScope("/api/v1/rp/other")).contains("__unmapped_rp__")
    }

    @Test
    fun segment_boundary_prevents_false_match() {
        // registration 의 prefix 이지만 다른 라우트는 registration scope 를 받지 않는다.
        // fail-closed 라 sentinel 을 받지만(empty 아님) — 핵심은 잘못된 실제 scope 를 안 받는 것.
        // hasValue(sentinel) 은 정확히 sentinel 임을 증명 → registration/authentication 아님.
        assertThat(resolver.requiredScope("/api/v1/rp/registrationXYZ")).hasValue("__unmapped_rp__")
        assertThat(resolver.requiredScope("/api/v1/rp/authentication-config")).hasValue("__unmapped_rp__")
    }

    @Test
    fun base_paths_and_boundaries() {
        // 특성: 세그먼트 base 자체도 매칭, /api/v1/rp 자체와 trailing slash 는 sentinel,
        // /api/v1/rp 경계 밖(rpx)·빈 문자열은 scope 불요.
        assertThat(resolver.requiredScope("/api/v1/rp/registration")).hasValue("registration")
        assertThat(resolver.requiredScope("/api/v1/rp/authentication")).hasValue("authentication")
        assertThat(resolver.requiredScope("/api/v1/rp/credentials/")).hasValue("registration")
        assertThat(resolver.requiredScope("/api/v1/rp")).hasValue("__unmapped_rp__")
        assertThat(resolver.requiredScope("/api/v1/rp/")).hasValue("__unmapped_rp__")
        assertThat(resolver.requiredScope("/api/v1/rpx")).isEmpty()
        assertThat(resolver.requiredScope("")).isEmpty()
    }
}
