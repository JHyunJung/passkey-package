package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필별 설정 해석 회귀 방지 — thymeleaf 캐시와 앱 로거 레벨을 base yml 에서
 * local/dev yml 로 옮긴 뒤에도 의도한 값으로 해석되는지 확인한다.
 * (prod/qa 는 두 키를 선언하지 않아 Boot 기본값 = 템플릿 캐시 on 이 적용된다.)
 */
class ProfilePropertyResolutionTest {

    private ConfigurableEnvironment environmentFor(String... profiles) {
        return new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .run()
                .getEnvironment();
    }

    @Test
    void localProfile_enablesDebugLoggingAndDisablesTemplateCache() {
        ConfigurableEnvironment env = environmentFor("local");
        assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("DEBUG");
        assertThat(env.getProperty("spring.thymeleaf.cache")).isEqualTo("false");
    }

    @Test
    void devProfile_enablesDebugLoggingAndDisablesTemplateCache() {
        ConfigurableEnvironment env = environmentFor("dev");
        assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("DEBUG");
        assertThat(env.getProperty("spring.thymeleaf.cache")).isEqualTo("false");
    }

    /** prod 는 thymeleaf 캐시 키를 선언하지 않는다 → Boot 기본값(캐시 on). */
    @Test
    void prodProfile_leavesTemplateCacheAtBootDefault() {
        ConfigurableEnvironment env = environmentFor("prod");
        assertThat(env.getProperty("spring.thymeleaf.cache")).isNull();
        assertThat(env.getProperty("logging.level.com.crosscert.passkey")).isEqualTo("INFO");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {}
}
