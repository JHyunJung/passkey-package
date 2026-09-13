package com.crosscert.passkey.admin.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13(#34) — core 의 공유 {@code logback-spring.xml} 이 정의하는 {@code <logger>}/
 * {@code <root level>} 를 제거하고 yml {@code logging.level.*} 을 단일 출처로 만들기
 * 전, "실효 레벨은 이미 yml 이 결정한다"는 전제를 admin-app 프로필별로 증명하는
 * 스냅샷. logback 은 core 와 공유하지만 yml 이 앱마다 다르므로 앱 단위로 둔다.
 *
 * <p>로거 집합은 core {@code logback-spring.xml}·admin-app/common
 * {@code application*.yml} 에 등장하는 이름 전수(grep). admin-app 은
 * {@code com.crosscert.passkey.payload} 로거를 쓰는 코드가 없지만(rp-app 전용,
 * RequestLoggingFilter), 변경 전 core logback(19319183:20)이 이 로거를
 * {@code local,dev} springProfile 블록에 선언하고 있었으므로 "logback 이 없어져도
 * 실효 레벨이 동일한가"를 증명해야 하는 대상이다(게이트 R1 반영) — 제외하지 않는다.
 *
 * <p>기대값은 base 커밋(19319183) 워크트리에서 이 스냅샷을 실행해 실측했다
 * (payload 이외 로거는 이미 공통/앱별 yml 이 명시하거나 ROOT 상속만으로 값이
 * 정해져 코드 판독만으로 충분했음을 passkey-app 스냅샷에서 확인). 이 클래스는
 * 브리프 지침대로 report 의 `:admin-app:test` 검증 수치에는 포함하지 않지만
 * (compileTestJava 만 검증 대상), 클래스 자체는 Testcontainers 불필요해
 * 실행·green 확인은 했다.
 */
class LogLevelSnapshotTest {

    private static final List<String> LOGGERS = List.of(
            "ROOT",
            "com.crosscert.passkey",
            "com.crosscert.passkey.payload",
            "org.springframework",
            "org.springframework.web",
            "org.hibernate",
            "org.hibernate.SQL",
            "org.hibernate.orm.connections",
            "com.zaxxer.hikari",
            "org.apache.tomcat",
            "io.lettuce"
    );

    private static Map<String, String> expected(String... levels) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < LOGGERS.size(); i++) {
            m.put(LOGGERS.get(i), levels[i]);
        }
        return m;
    }

    private Map<String, String> snapshot(String... profiles) {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .run()) {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            Map<String, String> result = new LinkedHashMap<>();
            for (String name : LOGGERS) {
                Logger logger = loggerContext.getLogger(name);
                Level level = logger.getEffectiveLevel();
                result.put(name, level == null ? null : level.toString());
            }
            return result;
        }
    }

    @Test
    void noProfile() {
        assertThat(snapshot())
                .isEqualTo(expected("INFO", "INFO", "INFO", "INFO", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN"));
    }

    @Test
    void localProfile() {
        // admin-app local yml 이 com.crosscert.passkey: DEBUG 를 직접 선언해(부모)
        // payload 는 로거 단독 게이트가 아니라 순수 상속으로 DEBUG — passkey-app 의
        // local(부모가 INFO 로 남는 경우)과는 다른 케이스.
        assertThat(snapshot("local"))
                .isEqualTo(expected("INFO", "DEBUG", "DEBUG", "INFO", "WARN", "WARN", "DEBUG", "WARN", "WARN", "WARN", "WARN"));
    }

    @Test
    void devProfile() {
        assertThat(snapshot("dev"))
                .isEqualTo(expected("INFO", "DEBUG", "DEBUG", "INFO", "WARN", "WARN", "DEBUG", "WARN", "WARN", "WARN", "WARN"));
    }

    @Test
    void qaProfile() {
        assertThat(snapshot("qa"))
                .isEqualTo(expected("INFO", "INFO", "INFO", "INFO", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN"));
    }

    @Test
    void prodProfile() {
        // org.springframework 는 어느 yml 도 명시하지 않지만 ROOT 가 WARN 이라 상속으로
        // WARN — logback 의 <logger name="org.springframework" level="WARN"/> 선언은
        // 원래도 상속값과 동일해 잉여였다(제거 후에도 diff 0 인 이유).
        assertThat(snapshot("prod"))
                .isEqualTo(expected("WARN", "INFO", "INFO", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN"));
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {}
}
