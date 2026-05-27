package com.crosscert.passkey.samplerp.config;

import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PasskeyClientConfiguration {

    @Bean
    public PasskeyClient passkeyClient(PasskeyProperties props) {
        return PasskeyClient.of(new PasskeyClientConfig(
                props.baseUrl(),
                props.apiKey(),
                props.connectTimeout(),
                props.readTimeout(),
                props.jwksCacheTtl(),
                Clock.systemUTC(),
                null   // SDK 가 MDC.get("traceId") 기본값 사용
        ));
    }
}
