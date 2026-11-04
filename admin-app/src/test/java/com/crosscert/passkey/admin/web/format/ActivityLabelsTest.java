package com.crosscert.passkey.admin.web.format;

import com.crosscert.passkey.admin.web.format.ActivityLabels.EventSentenceInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** pages/activityLabels.test.ts 의 케이스를 1:1 로 옮긴 것. */
class ActivityLabelsTest {

    @Test
    @DisplayName("매핑된 액션은 한글 라벨")
    void mapped() {
        assertThat(ActivityLabels.actionLabel("API_KEY_ISSUE")).isEqualTo("API 키 발급");
        assertThat(ActivityLabels.actionLabel("WEBAUTHN_CONFIG_UPDATED")).isEqualTo("설정 변경");
        assertThat(ActivityLabels.actionLabel("ADMIN_LOGIN_FAILED")).isEqualTo("로그인 실패");
    }

    @Test
    @DisplayName("매핑 없는 액션은 원문 fallback")
    void unmapped() {
        assertThat(ActivityLabels.actionLabel("SOME_UNKNOWN_ACTION")).isEqualTo("SOME_UNKNOWN_ACTION");
    }

    @Test
    @DisplayName("행위자·테넌트·대상을 문장으로")
    void sentence() {
        String s = ActivityLabels.eventSentence(new EventSentenceInput("API_KEY_ISSUE", "admin@acme.com", "acme", "API_KEY", "pk_abcdef123456"));
        assertThat(s).contains("admin@acme.com").contains("acme").contains("API 키 발급");
        assertThat(s).isEqualTo("admin@acme.com 님이 acme 테넌트에 API_KEY pk_abcdef123456 · API 키 발급");
    }

    @Test
    @DisplayName("행위자 없으면 system")
    void system() {
        String s = ActivityLabels.eventSentence(new EventSentenceInput("ADMIN_LOGIN_FAILED", "", null, null, null));
        assertThat(s).contains("로그인 실패");
        assertThat(s).isEqualTo("system · 플랫폼 · 로그인 실패");
    }

    @Test
    @DisplayName("(추가) 대상 유형 없이 id 만 / 공백 행위자")
    void targetIdOnlyAndBlankActor() {
        assertThat(ActivityLabels.eventSentence(new EventSentenceInput("TENANT_UPDATE", "  ", "acme", null, "—")))
                .isEqualTo("system 님이 acme 테넌트에 — · 테넌트 수정");
    }
}
