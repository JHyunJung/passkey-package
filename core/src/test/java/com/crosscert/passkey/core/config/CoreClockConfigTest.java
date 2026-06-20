package com.crosscert.passkey.core.config;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class CoreClockConfigTest {

    @Test
    void clockUsesAsiaSeoulZone() {
        Clock clock = new CoreClockConfig().clock();
        assertThat(clock.getZone()).isEqualTo(KstTime.ZONE);
    }

    @Test
    void offsetDateTimeNowHasPlus9Offset() {
        Clock clock = new CoreClockConfig().clock();
        OffsetDateTime now = OffsetDateTime.now(clock);
        assertThat(now.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }
}
