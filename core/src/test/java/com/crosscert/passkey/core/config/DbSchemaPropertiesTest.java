package com.crosscert.passkey.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbSchemaPropertiesTest {

    @Test
    void acceptsValidIdentifier() {
        assertThat(new DbSchemaProperties("PSK_APP_OWNER").schema()).isEqualTo("PSK_APP_OWNER");
    }

    @Test
    void acceptsLegacyIdentifier() {
        // 이름은 배포자가 정한다. PSK_ 를 강제하지 않는다.
        assertThat(new DbSchemaProperties("APP_OWNER").schema()).isEqualTo("APP_OWNER");
    }

    @Test
    void rejectsSqlInjectionAttempt() {
        // 값의 출처는 설정 파일이지만, 오설정을 부팅 시점에 잡는다.
        assertThatThrownBy(() -> new DbSchemaProperties("APP_OWNER; DROP TABLE tenant"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passkey.db.schema");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> new DbSchemaProperties("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLeadingDigit() {
        assertThatThrownBy(() -> new DbSchemaProperties("1APP"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
