package com.crosscert.passkey.rpapp.config;

import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.function.Supplier;

/**
 * SDK {@link PasskeyClient} 빈 구성. 고객사 RP 의 SDK 연동 레퍼런스다.
 * baseUrl·apiKey 등 {@code passkey.*} 설정을 읽어 클라이언트를 만든다. API Key 는 기동 시 env(api-key)에서 읽어 고정 공급한다.
 */
@Configuration
public class PasskeyClientConfiguration {

    /**
     * SDK 는 매 요청 get() 을 호출하지만(런타임 키 교체를 허용하는 계약), RP 레퍼런스는
     * 기동 시 env(passkey.api-key)를 한 번 읽어 고정 공급한다. 따라서 키를 교체하려면
     * PASSKEY_API_KEY(또는 application.yml 의 api-key)를 바꾼 뒤 애플리케이션을 재기동한다.
     */
    @Bean
    public Supplier<String> apiKeySupplier(PasskeyProperties props) {
        String key = props.apiKey();
        return () -> key;
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
