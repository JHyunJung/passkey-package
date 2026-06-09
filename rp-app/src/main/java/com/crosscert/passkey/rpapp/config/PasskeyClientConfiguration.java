package com.crosscert.passkey.rpapp.config;

import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

@Configuration
public class PasskeyClientConfiguration {

    /**
     * 재기동 없이 API Key 를 교체/회수 반영하기 위한 동적 키 소스.
     * api-key-file 이 설정되면 그 파일을 핫리로드하고, 아니면 api-key(env)를 폴백한다.
     */
    @Bean
    public Supplier<String> apiKeySupplier(PasskeyProperties props) {
        Duration reload = props.apiKeyReload() == null
                ? Duration.ofSeconds(10) : props.apiKeyReload();
        return new ReloadableApiKeySupplier(props.apiKeyFile(), reload, props.apiKey());
    }

    @Bean
    public PasskeyClient passkeyClient(PasskeyProperties props, Supplier<String> apiKeySupplier) {
        return PasskeyClient.of(new PasskeyClientConfig(
                props.baseUrl(),
                apiKeySupplier,
                props.connectTimeout(),
                props.readTimeout(),
                props.jwksCacheTtl(),
                Clock.systemUTC(),
                null   // SDK 가 MDC.get("traceId") 기본값 사용
        ));
    }
}
