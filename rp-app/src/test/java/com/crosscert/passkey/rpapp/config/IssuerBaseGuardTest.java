package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IssuerBaseGuard — dev/local 이 아닌 환경(프로필 미지정 포함)에서 issuer-base 가 비었거나
 * localhost 폴백이면 기동을 실패시킨다. 조용히 localhost 로 떨어지면 모든 ID Token 검증이 iss 불일치로 실패한다.
 */
class IssuerBaseGuardTest {

    private static IssuerBaseGuard guard(String issuerBase, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        PasskeyProperties props = new PasskeyProperties(
                URI.create("https://passkey.example.com"), "key", "tenant",
                issuerBase == null ? null : URI.create(issuerBase),
                Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofMinutes(5));
        return new IssuerBaseGuard(props, env);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "https://localhost", "http://127.0.0.1:8080", "http://[::1]:8080",
            "http://LOCALHOST:8080", "/relative", "ftp://issuer.example.com"})
    void nonDevProfileWithLocalOrInvalidIssuer_failsFast(String issuer) {
        assertThatThrownBy(guard(issuer, "qa")::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PASSKEY_ISSUER_BASE");
    }

    @Test
    void noProfileWithMissingIssuer_failsFast() {
        assertThatThrownBy(guard(null)::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PASSKEY_ISSUER_BASE");
    }

    @Test
    void prodProfileWithRealIssuer_passes() {
        assertThatCode(guard("https://passkey.example.com", "prod")::check).doesNotThrowAnyException();
    }

    @Test
    void devAndLocalProfilesAllowLocalhost() {
        assertThatCode(guard("http://localhost:8080", "dev")::check).doesNotThrowAnyException();
        assertThatCode(guard("http://localhost:8080", "local")::check).doesNotThrowAnyException();
    }
}
