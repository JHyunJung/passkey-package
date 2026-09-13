package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CorsProperties} 가 환경변수 문자열에서 어떻게 바인딩되는지 고정한다.
 *
 * <p>운영 배선은 {@code RP_CORS_ALLOWED_ORIGINS} → application.yml 의
 * {@code ${RP_CORS_ALLOWED_ORIGINS:}} → 이 record 다. 즉 "cross-origin 을 쓰지 않는
 * 배포" 는 이 값을 비워 두고, 그때 CORS 가 비활성이어야 한다. 여기서 중요한 건
 * <b>빈 문자열이 빈 목록으로 바인딩되어야 한다</b>는 점이다 — 만약 Boot 가 이를
 * {@code [""]}(빈 문자열 원소 1개)로 만들면 compact 생성자의 "빈 값 금지" 검사에
 * 걸려 앱이 부팅조차 못 한다. 반대로 {@code null} 이나 빈 목록이면
 * {@code WebSecurityConfig} 의 allowedOrigins 가 비어 어떤 Origin 도 매칭되지 않는다
 * (= CORS 비활성, {@code WebAuthnCorsDisabledTest} 가 그 결과를 HTTP 레벨에서 단언).
 */
class CorsPropertiesBindingTest {

    /** 실제 배선과 같은 경로(prefix rp.cors)로 문자열 하나를 바인딩한다. */
    private static CorsProperties bind(String rawValue) {
        var source = new MapConfigurationPropertySource(
                Map.of("rp.cors.allowed-origins", rawValue));
        return new Binder(source).bind("rp.cors", Bindable.of(CorsProperties.class)).get();
    }

    /**
     * 핵심 회귀 가드: 빈 env 값은 빈 목록이어야 하며 {@code [""]} 이면 안 된다.
     * ({@code [""]} 이었다면 compact 생성자가 예외를 던져 부팅이 깨진다.)
     */
    @Test
    void emptyEnvValue_bindsToEmptyList_notBlankElement() {
        assertThat(bind("").allowedOrigins()).isEmpty();
    }

    /** 콤마 구분 목록은 정확히 그 원소들로 분리된다(공백 없이 그대로). */
    @Test
    void commaList_splitsIntoExactOrigins() {
        assertThat(bind("https://a.example.com,https://b.example.com").allowedOrigins())
                .containsExactly("https://a.example.com", "https://b.example.com");
    }

    /**
     * 와일드카드는 바인딩 시점에 거부된다 — 잘못된 설정으로 cross-origin 이 무차별
     * 허용되는 것을 부팅 실패로 막는다. Binder 는 생성자 예외를 감싸므로 root cause 로 본다.
     */
    @Test
    void wildcard_isRejectedAtBind() {
        assertThatThrownBy(() -> bind("https://a.example.com,*"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
