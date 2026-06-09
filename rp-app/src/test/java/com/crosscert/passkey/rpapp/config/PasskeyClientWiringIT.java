package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동적 API Key 빈 배선의 경량 컨텍스트 로드 테스트.
 *
 * <p>유일한 @SpringBootTest 인 {@link com.crosscert.passkey.rpapp.RpAppSmokeIT} 는
 * docker compose + passkey-app + admin-app 풀 인프라를 요구해 @Disabled 라,
 * {@link PasskeyClientConfiguration} 의 {@code apiKeySupplier}/{@code passkeyClient}
 * 빈 배선이 컴파일로만 검증되던 갭이 있었다. 본 IT 는 외부 인프라 없이 컨텍스트를
 * 띄워 두 빈의 존재 + api-key-file 미설정 시 env 폴백을 실증한다.
 *
 * <p>webEnvironment 가 NONE 이 아닌 RANDOM_PORT 인 이유: {@code WebSecurityConfig.chain}
 * 빈이 {@code HttpSecurity} 를 주입받는데, 이는 servlet 웹 컨텍스트에서만 자동 구성된다
 * (Spring Security 의 HttpSecurityConfiguration 은 @ConditionalOnWebApplication(SERVLET)).
 * NONE 이면 HttpSecurity 빈 부재로 컨텍스트 로드가 실패하므로 RpAppSmokeIT 와 동일하게
 * RANDOM_PORT 를 쓴다. 서블릿 컨테이너만 뜰 뿐 외부 호출은 없다(PasskeyClient.of 는
 * RestClient/JwksCache 만 구성, 기동 시 네트워크 I/O 없음).
 *
 * <p>properties: api-key 를 명시 값으로 세팅하고 api-key-file 을 빈 문자열로 명시해
 * (Spring 이 빈 문자열 → null Path 로 바인딩) ReloadableApiKeySupplier 가 env 로
 * 폴백하는 경로를 실제 Spring 바인딩을 통해 검증한다. base-url 등 나머지 필수 프로퍼티는
 * application.yml 기본값(localhost:8080 등)으로 충족된다.
 *
 * <p>rp.relay.secret 을 강한 값으로 명시하는 이유: test 프로필은 dev/local 이 아니므로
 * {@link RelayKeyGuard} 가 ApplicationReadyEvent 에서 데모 기본 키를 거부한다. 본 IT 와
 * 무관한 기존 가드이므로, 컨텍스트가 뜨도록 비-데모 키를 최소 주입한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "passkey.api-key=pk_envFallbackKey",
                "passkey.api-key-file=",  // 빈 문자열 → null Path → env 폴백
                "rp.relay.secret=test-relay-secret-not-demo-0123456789"
        })
@ActiveProfiles("test")
class PasskeyClientWiringIT {

    @Autowired
    ApplicationContext ctx;

    @Test
    void dynamicApiKeyBeansAreWired() {
        assertThat(ctx.containsBean("apiKeySupplier")).isTrue();
        assertThat(ctx.getBean(com.crosscert.passkey.sdk.PasskeyClient.class)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void supplierFallsBackToEnvWhenNoFileConfigured() {
        var supplier = (Supplier<String>) ctx.getBean("apiKeySupplier");
        assertThat(supplier.get()).isEqualTo("pk_envFallbackKey");
    }
}
