package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필별 설정 해석 회귀 방지 — thymeleaf 캐시와 앱 로거 레벨을 base yml 에서
 * local/dev yml 로 옮긴 뒤에도 의도한 값으로 해석되는지 확인한다.
 * (prod/qa 는 두 키를 선언하지 않아 Boot 기본값 = 템플릿 캐시 on 이 적용된다.)
 */
class ProfilePropertyResolutionTest {

    /** 컨텍스트를 띄워 Environment 를 검사하고 반드시 닫는다. */
    private void withEnvironment(String profile, Consumer<ConfigurableEnvironment> assertions) {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .run()) {
            assertions.accept(ctx.getEnvironment());
        }
    }

    @Test
    void localProfile_enablesDebugLoggingAndDisablesTemplateCache() {
        withEnvironment("local", env -> {
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("DEBUG");
            assertThat(env.getProperty("spring.thymeleaf.cache")).isEqualTo("false");
        });
    }

    @Test
    void devProfile_enablesDebugLoggingAndDisablesTemplateCache() {
        withEnvironment("dev", env -> {
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("DEBUG");
            assertThat(env.getProperty("spring.thymeleaf.cache")).isEqualTo("false");
        });
    }

    /** prod 는 thymeleaf 캐시 키를 선언하지 않는다 → Boot 기본값(캐시 on). */
    @Test
    void prodProfile_leavesTemplateCacheAtBootDefault() {
        withEnvironment("prod", env -> {
            assertThat(env.getProperty("spring.thymeleaf.cache")).isNull();
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("INFO");
        });
    }

    /**
     * qa 도 prod 와 같다 — application-qa.yml 은 로거 레벨만 선언하고 thymeleaf 키는
     * 두지 않으므로 Boot 기본값(캐시 on)이 적용된다.
     */
    @Test
    void qaProfile_leavesTemplateCacheAtBootDefault() {
        withEnvironment("qa", env -> {
            assertThat(env.getProperty("spring.thymeleaf.cache")).isNull();
            assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("INFO");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {}
}
