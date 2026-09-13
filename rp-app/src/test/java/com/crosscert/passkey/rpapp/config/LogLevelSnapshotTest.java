package com.crosscert.passkey.rpapp.config;

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
 * Task 13(#34) — logback 의 {@code <logger>}/{@code <root level>} 선언을 제거하고
 * yml {@code logging.level.*} 을 단일 출처로 만들기 전, "실효 레벨은 이미 yml 이
 * 결정한다"는 전제를 프로필별로 증명하는 스냅샷.
 *
 * <p>로거 집합은 rp-app 의 {@code logback-spring.xml}·{@code application*.yml} 에
 * 등장하는 이름 전수(grep) — ROOT, com.crosscert.passkey, com.crosscert.passkey.payload,
 * org.springframework, org.springframework.web. rp-app 은 core 의 공유 hibernate/hikari/
 * tomcat/lettuce 로거(application-common.yml)를 임포트하지 않으므로(자체 base yml 이
 * logging.level 을 직접 선언) 이 목록에 없다.
 *
 * <p>각 프로필 부팅 직후 바로 읽는다 — Spring 이 logback 을 재초기화하므로 컨텍스트
 * 오염을 피하기 위해 매 프로필마다 새 컨텍스트를 띄우고 닫는다. {@code logging.level.*}
 * 은 주입하지 않는다(웹 NONE, 최소 설정 — {@link ProfilePropertyResolutionTest} 패턴).
 */
class LogLevelSnapshotTest {

    private static final List<String> LOGGERS = List.of(
            "ROOT",
            "com.crosscert.passkey",
            "com.crosscert.passkey.payload",
            "org.springframework",
            "org.springframework.web"
    );

    /** 프로필별 기대 effective level. 변경 전 실제 실행 결과로 채움(report 표와 동일). */
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
        assertThat(snapshot())
                .isEqualTo(expected("INFO", "INFO", "INFO", "INFO", "WARN"));
    }

    @Test
    void localProfile() {
        assertThat(snapshot("local"))
                .isEqualTo(expected("INFO", "DEBUG", "DEBUG", "INFO", "WARN"));
    }

    @Test
    void devProfile() {
        assertThat(snapshot("dev"))
                .isEqualTo(expected("INFO", "DEBUG", "DEBUG", "INFO", "INFO"));
    }

    @Test
    void qaProfile() {
        assertThat(snapshot("qa"))
                .isEqualTo(expected("INFO", "INFO", "INFO", "INFO", "WARN"));
    }

    @Test
    void prodProfile() {
        assertThat(snapshot("prod"))
                .isEqualTo(expected("WARN", "INFO", "INFO", "WARN", "WARN"));
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {}
}
