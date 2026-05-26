package com.crosscert.passkey.core.jwt;

import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: Hibernate 6 native UUID classes resolve on classpath.
 * Doesn't exercise the generator; just confirms imports work and
 * UuidGenerator.Style.TIME enum value exists (RFC 4122 v1-style, time-based).
 *
 * NOTE: Style.TIME in Hibernate 6.6.x is RFC 4122 version 1 (IP-based, not MAC),
 * not RFC 9562 UUID v7. Later tasks decide the actual generation strategy.
 *
 * Phase 6 T6..T13 will actually persist UUIDs via these annotations.
 */
class UuidGeneratorSmokeTest {

    @Test
    void uuidGeneratorStyleTimeExists() {
        // Style.TIME = RFC 4122 v1-style (time-based, IP address instead of MAC).
        // Not UUID v7; later tasks will determine final generation strategy.
        assertThat(UuidGenerator.Style.TIME).isNotNull();
        assertThat(UuidGenerator.Style.TIME.name()).isEqualTo("TIME");
    }

    @Test
    void sqlTypesUuidConstantExists() {
        // SqlTypes.UUID is the int constant used in @JdbcTypeCode(SqlTypes.UUID).
        // Should be 3000 in Hibernate 6 but we don't assert on the value.
        int code = SqlTypes.UUID;
        assertThat(code).isPositive();
    }
}
