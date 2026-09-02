package com.crosscert.passkey.core.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSignupRequestTest {

    @Test
    void constructorKeepsAllFieldsAndIdIsAssignedByPersistence() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-03T10:00:00+09:00");
        AdminSignupRequest r = new AdminSignupRequest("new@x.com", "$2a$12$hash", "RP 담당자입니다", now);

        assertThat(r.getEmail()).isEqualTo("new@x.com");
        assertThat(r.getBcryptHash()).isEqualTo("$2a$12$hash");
        assertThat(r.getReason()).isEqualTo("RP 담당자입니다");
        assertThat(r.getRequestedAt()).isEqualTo(now);
        assertThat(r.getId()).as("id 는 persist 시 Hibernate 가 채운다").isNull();
    }

    @Test
    void reasonMayBeNull() {
        AdminSignupRequest r = new AdminSignupRequest("a@x.com", "h", null, OffsetDateTime.now());
        assertThat(r.getReason()).isNull();
    }
}
