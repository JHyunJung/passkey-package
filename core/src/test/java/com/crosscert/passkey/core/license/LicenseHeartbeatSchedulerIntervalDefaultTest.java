package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D#30 단일 출처 고정 테스트.
 *
 * <p>{@link LicenseHeartbeatScheduler#heartbeat()} 의 {@code @Scheduled(fixedDelayString =
 * "${passkey.license.heartbeat-interval:PT1H}")} 폴백은 프로퍼티가 바인딩되지 않았을 때만 쓰이는
 * 두 번째 정의다. 단일 출처는 어디까지나 {@link LicenseProperties} 레코드의 컴팩트 생성자이므로,
 * 이 테스트는 어노테이션 문자열을 리플렉션으로 파싱해 프로퍼티 이름과 {@code :} 뒤 기본값이
 * 레코드 기본값과 항상 같음을 고정한다. 둘 중 하나가 어긋나면(레코드만 바뀌고 어노테이션을
 * 깜빡하는 등) 이 테스트가 즉시 깨진다.
 */
class LicenseHeartbeatSchedulerIntervalDefaultTest {

    @Test
    void scheduledFallback_matchesLicensePropertiesDefault() throws NoSuchMethodException {
        Method heartbeat = LicenseHeartbeatScheduler.class.getDeclaredMethod("heartbeat");
        Scheduled scheduled = heartbeat.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();

        String spel = scheduled.fixedDelayString();
        // 형식: ${<property-name>:<default>}
        assertThat(spel).startsWith("${").endsWith("}");
        String inner = spel.substring(2, spel.length() - 1);
        int colon = inner.indexOf(':');
        assertThat(colon).as("property expression must carry a ':' default").isPositive();

        String propertyName = inner.substring(0, colon);
        String annotationDefault = inner.substring(colon + 1);

        assertThat(propertyName).isEqualTo("passkey.license.heartbeat-interval");

        // 전부 null 로 생성 — LicenseGuardFilterIT:81 과 동일 패턴 — 하여 record 컴팩트 생성자의
        // 기본값만 얻는다.
        LicenseProperties allDefaults = new LicenseProperties(null, null, null, null, null, null);

        assertThat(Duration.parse(annotationDefault)).isEqualTo(allDefaults.heartbeatInterval());
    }
}
