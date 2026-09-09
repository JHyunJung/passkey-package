package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필별 설정 해석 회귀 방지 — thymeleaf 캐시와 앱 로거 레벨을 base yml 에서
 * local/dev yml 로 옮긴 뒤에도 의도한 값으로 해석되는지 확인한다.
 * (prod/qa 는 두 키를 선언하지 않아 Boot 기본값 = 템플릿 캐시 on 이 적용된다.)
 *
 * <p>각 테스트는 두 층을 함께 본다: (1) Environment 의 원시 문자열 — "prod/qa 는 키를
 * 아예 선언하지 않는다" 는 사실까지 고정한다, (2) 그 원시값이 실제로 바인딩된
 * {@link ThymeleafProperties#isCache()} — "키 미선언 = 캐시 on" 이 Boot 기본값 가정이
 * 아니라 실측이 되도록 한다. 그래서 EmptyConfig 가 ThymeleafProperties 를 활성화한다.
 *
 * <p>⚠️ {@code @ActiveProfiles("test")} 를 붙이면 안 된다 — src/test/resources 의
 * application-test.yml 이 cache=false 로 덮어써 prod/qa 단언이 무의미해진다.
 * 이 테스트는 {@code .profiles(profile)} 로 대상 프로필만 켠다.
 */
class ProfilePropertyResolutionTest {

    /** 컨텍스트를 띄워 검사하고 반드시 닫는다. */
    private void withContext(String profile, Consumer<ConfigurableApplicationContext> assertions) {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .run()) {
            assertions.accept(ctx);
        }
    }

    /** 실제로 바인딩된 thymeleaf 캐시 값(Environment 문자열이 아니라 프로퍼티 객체). */
    private static boolean boundThymeleafCache(ConfigurableApplicationContext ctx) {
        return ctx.getBean(ThymeleafProperties.class).isCache();
    }

    @Test
    void localProfile_enablesDebugLoggingAndDisablesTemplateCache() {
        withContext("local", ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("DEBUG");
            assertThat(env.getProperty("spring.thymeleaf.cache")).isEqualTo("false");
            assertThat(boundThymeleafCache(ctx)).isFalse();
        });
    }

    @Test
    void devProfile_enablesDebugLoggingAndDisablesTemplateCache() {
        withContext("dev", ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("DEBUG");
            assertThat(env.getProperty("spring.thymeleaf.cache")).isEqualTo("false");
            assertThat(boundThymeleafCache(ctx)).isFalse();
        });
    }

    /** prod 는 thymeleaf 캐시 키를 선언하지 않는다 → Boot 기본값(캐시 on). */
    @Test
    void prodProfile_leavesTemplateCacheAtBootDefault() {
        withContext("prod", ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.thymeleaf.cache")).isNull();
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("INFO");
            assertThat(boundThymeleafCache(ctx)).isTrue();
        });
    }

    /**
     * qa 도 prod 와 같다 — application-qa.yml 은 로거 레벨만 선언하고 thymeleaf 키는
     * 두지 않으므로 Boot 기본값(캐시 on)이 적용된다.
     */
    @Test
    void qaProfile_leavesTemplateCacheAtBootDefault() {
        withContext("qa", ctx -> {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.thymeleaf.cache")).isNull();
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("INFO");
            assertThat(boundThymeleafCache(ctx)).isTrue();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ThymeleafProperties.class)
    static class EmptyConfig {}
}
