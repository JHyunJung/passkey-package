package com.crosscert.passkey.admin.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * admin-app 은 운영자 계정을 DB(AdminUserDetailsService)에서 읽으므로 Spring Security 의
 * 기본 인메모리 사용자를 쓰지 않는다. 예전에는 부팅 로그의 "Using generated security
 * password" 한 줄을 지우려고 application.yml 에 더미 spring.security.user.password 를
 * 두었는데, 그 키가 없어도 로그가 찍히지 않는다. 이 테스트는 그 back-off 메커니즘을
 * 증명한다 — 실제 admin-app 배선에서의 확인은 컨텍스트를 통째로 띄우는
 * {@code AdminFlowIT.security_usesDbBackedUserDetailsService_noInMemoryFallback()} 이 담당한다.
 *
 * <p>근거는 {@link UserDetailsServiceAutoConfiguration} 의 @ConditionalOnMissingBean 이다.
 * 애플리케이션이 {@link UserDetailsService} 빈(admin-app 의 AdminUserDetailsService)을 이미
 * 제공하면 auto-config 가 back off 하여 임의 비밀번호 생성 자체를 하지 않는다. 아래 두
 * 테스트는 (1) 빈이 있을 때 auto-config 가 물러나며 로그가 없음, (2) 빈이 없을 때는 실제로
 * 그 로그가 찍힘 — 즉 이 테스트가 로그를 탐지할 능력이 있음(negative control) — 을 함께 본다.
 */
class GeneratedSecurityPasswordAbsentTest {

    /** UserDetailsServiceAutoConfiguration 이 생성 비밀번호를 찍는 로거. */
    private static final String PASSWORD_LOGGER =
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration";

    private static final String GENERATED_PASSWORD_MESSAGE = "Using generated security password";

    // UserDetailsServiceAutoConfiguration 이 실제로 평가되려면 두 조건이 필요하다:
    // @ConditionalOnWebApplication(SERVLET) → WebApplicationContextRunner,
    // @ConditionalOnBean(ObjectPostProcessor) → 아래 스텁 빈(실제 앱에서는 Spring
    // Security 설정이 제공). 둘 중 하나라도 빠지면 auto-config 가 조건 미충족으로
    // 물러나 버려서 "로그가 안 찍힌다"는 관찰이 무의미해진다. SecurityProperties 는
    // 생성되는 InMemoryUserDetailsManager 의 주입 대상이라 함께 등록한다.
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UserDetailsServiceAutoConfiguration.class))
            .withUserConfiguration(SecurityAutoConfigPrerequisites.class);

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(PASSWORD_LOGGER);
        appender = new ListAppender<>();
        appender.start();
        // 메시지는 WARN 으로 찍힌다. 원래 레벨을 보관했다가 복원해야 같은 JVM 의
        // 후속 테스트로 레벨 변경이 새지 않는다(null = 상위에서 상속받던 상태).
        originalLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    void doesNotLogGeneratedPassword_whenApplicationProvidesUserDetailsService() {
        // admin-app 의 AdminUserDetailsService(@Service) 에 해당하는 빈을 등록한 상태.
        runner.withUserConfiguration(DbBackedUserDetailsServiceConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // auto-config 가 back off — 기본 인메모리 사용자 관리자가 없다.
            assertThat(ctx).doesNotHaveBean(InMemoryUserDetailsManager.class);
            assertThat(messages()).noneMatch(m -> m.contains(GENERATED_PASSWORD_MESSAGE));
        });
    }

    @Test
    void logsGeneratedPassword_whenNoUserDetailsServiceIsPresent() {
        // Negative control: 위 테스트의 "로그 없음" 이 캡처 실패 때문이 아님을 증명한다.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(messages()).anyMatch(m -> m.contains(GENERATED_PASSWORD_MESSAGE));
        });
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** 실제 admin-app 에서는 Spring Security 설정이 제공하는 전제 빈들. */
    @Configuration(proxyBeanMethods = false)
    static class SecurityAutoConfigPrerequisites {
        @Bean
        ObjectPostProcessor<Object> objectPostProcessor() {
            return new ObjectPostProcessor<>() {
                @Override
                public <O> O postProcess(O object) {
                    return object;
                }
            };
        }

        @Bean
        SecurityProperties securityProperties() {
            return new SecurityProperties();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DbBackedUserDetailsServiceConfig {
        @Bean
        UserDetailsService userDetailsService() {
            return username -> {
                throw new UsernameNotFoundException(username);
            };
        }
    }
}
