package com.crosscert.passkey.admin.web.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** @fmt 빈의 위임 동작(새 테스트 — 원본 vitest 없음). */
class ViewHelpersTest {

    private final ViewHelpers fmt = new ViewHelpers(Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("라벨·조사·시각·오류가 해당 클래스에 위임된다")
    void delegates() {
        assertThat(fmt.status("ACTIVE")).isEqualTo("활성");
        assertThat(fmt.aaguidMode("DENYLIST")).isEqualTo("차단 목록");
        assertThat(fmt.josa("목록", "을/를")).isEqualTo("을");
        assertThat(fmt.josa("정보", "을/를")).isEqualTo("를");
        assertThat(fmt.withJosa("키", "이/가")).isEqualTo("키가");
        assertThat(fmt.kst(OffsetDateTime.parse("2026-10-11T05:02:03Z"))).isEqualTo("2026-10-11 14:02:03");
        assertThat(fmt.kst((OffsetDateTime) null)).isEqualTo("—");
        assertThat(fmt.kstDate(Instant.parse("2026-10-11T15:10:00Z"))).isEqualTo("2026-10-12");
        assertThat(fmt.relative(Instant.parse("2026-10-10T23:55:00Z"))).isEqualTo("5분 전");
        assertThat(fmt.today().toString()).isEqualTo("2026-10-11");
        assertThat(fmt.recoveryCodeFilename("Alice@x.com")).isEqualTo("passkey-admin-recovery-codes-alice-20261011.txt");
        assertThat(fmt.platform("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)")).isEqualTo("⌘K");
        assertThat(fmt.platform(null)).isEqualTo("Ctrl K");
        assertThat(fmt.error(new ConnectException("x"))).isEqualTo("네트워크에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도하세요.");
    }

    @Test
    @DisplayName("apiKeyExpiry 는 Clock 기준 30일 이내 여부")
    void apiKeyExpiryUsesClock() {
        assertThat(fmt.apiKeyExpiry(OffsetDateTime.parse("2026-10-21T00:00:00Z"))).isTrue();
        assertThat(fmt.apiKeyExpiry(OffsetDateTime.parse("2026-12-21T00:00:00Z"))).isFalse();
        assertThat(fmt.apiKeyExpiry(null)).isFalse();
        assertThat(fmt.isExpiringSoon(OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-21T00:00:00Z"))).isFalse();
    }
}
