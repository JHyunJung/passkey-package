package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityTimestampTest {

    static class Sample extends BaseEntity {}

    @Test
    void prePersistSetsKstOffsetTimestamps() {
        Sample s = new Sample();
        s.onCreate(); // @PrePersist 콜백 직접 호출 (protected, 동일 패키지 접근)
        OffsetDateTime created = s.getCreatedAt();
        assertThat(created).isNotNull();
        assertThat(created.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(s.getUpdatedAt()).isEqualTo(created);
    }

    @Test
    void preUpdateAdvancesUpdatedAt() {
        Sample s = new Sample();
        s.onCreate();
        s.onUpdate();
        assertThat(s.getUpdatedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }
}
