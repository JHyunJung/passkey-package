package com.crosscert.passkey.app.config;

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
 * 전, "실효 레벨은 이미 yml 이 결정한다"는 전제를 passkey-app 프로필별로 증명하는
 * 스냅샷. logback 은 core 와 공유하지만 yml 이 앱마다 다르므로 앱 단위로 둔다.
 *
 * <p>로거 집합은 core {@code logback-spring.xml}·passkey-app/common
 * {@code application*.yml} 에 등장하는 이름 전수(grep).
 *
 * <p>passkey-app 은 {@code local} 이 실사용 프로필이 아니다(docs/dev-setup.md:151-154
 * — base {@code application.yml} 에 datasource/Redis 설정이 없어 local 로 띄우면
 * 기동 실패, dev 하나가 local/dev 를 겸한다). 변경 전 스냅샷에서 core logback 의
 * {@code <springProfile name="local,dev">} 블록이 payload 로거를 DEBUG 로 게이트하는
 * 것이 관측됐으나, 이는 애초에 도달 불가능한 프로필의 죽은 선언이라 yml 로 옮기지
 * 않고 제거한다(Task 13 게이트 R1 반영) — localProfile() 기대값은 DEBUG 가 아니라
 * INFO(부모 com.crosscert.passkey 로부터 상속).
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
                // getLogger (not exists): effective level is inherited even for a logger
                // that was never explicitly declared in this app's logback/yml.
                Logger logger = loggerContext.getLogger(name);
                Level level = logger.getEffectiveLevel();
                result.put(name, level == null ? null : level.toString());
            }
            return result;
        }
    }

    @Test
    void noProfile() {
        // 폴백 springProfile(!local & !dev & !qa & !prod) 은 payload 로거를 선언하지
        // 않는다 — com.crosscert.passkey(INFO) 로부터 상속.
        assertThat(snapshot())
                .isEqualTo(expected("INFO", "INFO", "INFO", "INFO", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN"));
    }

    @Test
    void localProfile() {
        // passkey-app 은 local 전용 yml 이 없다(docs/dev-setup.md:151-154 — local 은
        // passkey-app 이 지원하지 않는 프로필, 실제로 띄우면 datasource 부재로 기동
        // 실패). 이 테스트는 부팅 자체는 되는 EmptyConfig 컨텍스트라 통과하지만, 값은
        // common yml(WARN 계열)만 적용되고 payload 는 com.crosscert.passkey(INFO) 로부터
        // 상속 — logback 의 "local,dev" 게이트는 죽은 선언이었다(§ 클래스 doc).
        assertThat(snapshot("local"))
                .isEqualTo(expected("INFO", "INFO", "INFO", "INFO", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN"));
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
        assertThat(snapshot("prod"))
                .isEqualTo(expected("WARN", "INFO", "INFO", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN", "WARN"));
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {}
}
