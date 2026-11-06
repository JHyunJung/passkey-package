package com.crosscert.passkey.core.config;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link KstTime#dbDay}: 네이티브 쿼리 TRUNC(created_at) 값이 어떤 타입으로 와도 같은 KST 날짜. */
class KstTimeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    @Test
    void localDateTime_hibernate7Shape_isWallClockDate() {
        assertThat(KstTime.dbDay(DAY.atStartOfDay())).isEqualTo(DAY);
    }

    @Test
    void timestamp_hibernate6Shape_isReinterpretedInKst() {
        // KST 자정 = 전날 15:00 UTC. UTC 로 재해석하면 하루 밀리므로 JVM 타임존과 무관하게 KST 날짜여야 한다.
        Timestamp kstMidnight = Timestamp.from(DAY.atStartOfDay(KstTime.ZONE).toInstant());
        assertThat(KstTime.dbDay(kstMidnight)).isEqualTo(DAY);
    }

    @Test
    void sqlDate_isTakenAsIs() {
        // java.sql.Date.toInstant() 는 UnsupportedOperationException — 별도 분기로 처리해야 한다.
        assertThat(KstTime.dbDay(java.sql.Date.valueOf(DAY))).isEqualTo(DAY);
    }

    @Test
    void localDate_isTakenAsIs() {
        assertThat(KstTime.dbDay(DAY)).isEqualTo(DAY);
    }

    @Test
    void unknownType_isRejectedWithClearMessage() {
        assertThatThrownBy(() -> KstTime.dbDay("2026-11-06"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.String");
        assertThatThrownBy(() -> KstTime.dbDay(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }
}
