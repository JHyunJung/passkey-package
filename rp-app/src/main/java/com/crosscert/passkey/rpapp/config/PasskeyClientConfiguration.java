package com.crosscert.passkey.rpapp.config;

import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * SDK {@link PasskeyClient} 빈 구성. 고객사 RP 의 SDK 연동 레퍼런스다.
 * baseUrl·apiKey 등 {@code passkey.*} 설정을 읽어 클라이언트를 만든다. API Key 는 핫리로드 가능한 공급자로 주입한다.
 */
@Configuration
public class PasskeyClientConfiguration {

    /**
     * 재기동 없이 API Key 를 교체/회수 반영하기 위한 동적 키 소스.
     * api-key-file 이 설정되면 그 파일을 핫리로드하고, 아니면 api-key(env)를 폴백한다.
     */
    @Bean
    public Supplier<String> apiKeySupplier(PasskeyProperties props) {
        Duration reload = (props.apiKeyReload() != null) ? props.apiKeyReload() : Duration.ofSeconds(10);
        return new ReloadableApiKeySupplier(props.apiKeyFile(), reload, props.apiKey());
    }

    /**
     * 등록 릴레이 토큰 코덱. SDK 의 Spring 비의존 프리미티브에 rp.relay.* 설정(secret, ttl)을
     * 주입한다. secret 의 출처·데모키 거부는 RelayProperties/RelayKeyGuard(RP 책임)가 담당한다.
     */
    @Bean
    public RegistrationRelayCodec registrationRelayCodec(RelayProperties relayProps) {
        return new RegistrationRelayCodec(
                relayProps.secret().getBytes(StandardCharsets.UTF_8),
                relayProps.ttl(),
                Clock.systemUTC());
    }

    @Bean
    public PasskeyClient passkeyClient(PasskeyProperties props, Supplier<String> apiKeySupplier) {
        // SDK 의 Builder 는 필수값(baseUrl, apiKeySupplier)을 인자로 강제하고, 선택값은
        // null 을 넘기면 기본값으로 치환한다. baseUrl 은 필수 — 누락 시 fail-fast.
        if (props.baseUrl() == null) {
            throw new IllegalStateException("passkey.base-url 이 설정되지 않았습니다");
        }
        return PasskeyClient.of(
                PasskeyClientConfig.builder(props.baseUrl(), apiKeySupplier)
                        .connectTimeout(props.connectTimeout())
                        .readTimeout(props.readTimeout())
                        .jwksCacheTtl(props.jwksCacheTtl())
                        .build());
    }
}
