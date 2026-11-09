package com.crosscert.passkey.admin.web.format

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** @fmt 빈의 위임 동작(새 테스트 — 원본 vitest 없음). */
class ViewHelpersTest {

    private val fmt = ViewHelpers(Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC))

    @Test
    @DisplayName("라벨·조사·시각·오류가 해당 클래스에 위임된다")
    fun delegates() {
        assertThat(fmt.status("ACTIVE")).isEqualTo("활성")
        assertThat(fmt.aaguidMode("DENYLIST")).isEqualTo("차단 목록")
        assertThat(fmt.josa("목록", "을/를")).isEqualTo("을")
        assertThat(fmt.josa("정보", "을/를")).isEqualTo("를")
        assertThat(fmt.withJosa("키", "이/가")).isEqualTo("키가")
        assertThat(fmt.kst(OffsetDateTime.parse("2026-10-11T05:02:03Z"))).isEqualTo("2026-10-11 14:02:03")
        assertThat(fmt.kst(null as OffsetDateTime?)).isEqualTo("—")
        assertThat(fmt.kstDate(Instant.parse("2026-10-11T15:10:00Z"))).isEqualTo("2026-10-12")
        assertThat(fmt.relative(Instant.parse("2026-10-10T23:55:00Z"))).isEqualTo("5분 전")
        assertThat(fmt.today().toString()).isEqualTo("2026-10-11")
        assertThat(fmt.recoveryCodeFilename("Alice@x.com")).isEqualTo("passkey-admin-recovery-codes-alice-20261011.txt")
        assertThat(fmt.platform("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)")).isEqualTo("⌘K")
        assertThat(fmt.platform(null)).isEqualTo("Ctrl K")
        assertThat(fmt.error(ConnectException("x"))).isEqualTo("네트워크에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도하세요.")
    }

    @Test
    @DisplayName("apiKeyExpiry 는 Clock 기준 30일 이내 여부")
    fun apiKeyExpiryUsesClock() {
        assertThat(fmt.apiKeyExpiry(OffsetDateTime.parse("2026-10-21T00:00:00Z"))).isTrue()
        assertThat(fmt.apiKeyExpiry(OffsetDateTime.parse("2026-12-21T00:00:00Z"))).isFalse()
        assertThat(fmt.apiKeyExpiry(null)).isFalse()
        assertThat(fmt.isExpiringSoon(OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-21T00:00:00Z"))).isFalse()
    }

    @Test
    @DisplayName("pager: pageSize 0 이하여도 0 나눗셈 없이 동작, 범위 보정")
    fun pagerGuardsPageSize() {
        assertThat(fmt.pager(1, 0, 5).last).isEqualTo(5)
        assertThat(fmt.pager(1, -3, 0)).isEqualTo(ViewHelpers.Pager(1, 1, 0, 0))
        assertThat(fmt.pager(9, 50, 120)).isEqualTo(ViewHelpers.Pager(3, 3, 101, 120))
    }

    @Test
    @DisplayName("json 은 주입된 ObjectMapper(java.time 내장)로 날짜를 직렬화한다")
    fun jsonUsesInjectedMapper() {
        val om: tools.jackson.databind.ObjectMapper = tools.jackson.databind.json.JsonMapper.builder()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()
        val f = ViewHelpers(Clock.systemUTC(), om)
        assertThat(f.json(java.util.Map.of("t", java.time.LocalDate.of(2026, 1, 2)))).isEqualTo("{\"t\":\"2026-01-02\"}")
        assertThat(fmt.json(java.util.Map.of("t", java.time.LocalDate.of(2026, 1, 2)))).contains("2026")
    }
}
