package com.crosscert.passkey.admin.web.format

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** ← admin-ui/src/lib/platform.test.ts */
class PlatformLabelsTest {

    @Test
    @DisplayName("Mac·iOS 는 Mac 으로 본다")
    fun macAndIosAreMac() {
        assertThat(PlatformLabels.isMacPlatform("MacIntel", null)).isTrue()
        assertThat(PlatformLabels.isMacPlatform("iPhone", null)).isTrue()
    }

    @Test
    @DisplayName("Windows·Linux 는 Mac 이 아니다")
    fun windowsAndLinuxAreNotMac() {
        assertThat(PlatformLabels.isMacPlatform("Win32", null)).isFalse()
        assertThat(PlatformLabels.isMacPlatform("Linux x86_64", null)).isFalse()
    }

    @Test
    @DisplayName("platform 이 비면 userAgent 로 판단")
    fun emptyPlatformFallsBackToUserAgent() {
        assertThat(PlatformLabels.isMacPlatform("", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)")).isTrue()
        assertThat(PlatformLabels.isMacPlatform("", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).isFalse()
    }

    @Test
    @DisplayName("navigator 가 없으면 Mac 아님")
    fun noNavigatorIsNotMac() {
        assertThat(PlatformLabels.isMacPlatform(null, null)).isFalse()
    }

    @Test
    @DisplayName("팔레트 단축키 표기")
    fun paletteShortcutLabel() {
        assertThat(PlatformLabels.paletteShortcutLabel(true)).isEqualTo("⌘K")
        assertThat(PlatformLabels.paletteShortcutLabel(false)).isEqualTo("Ctrl K")
    }
}
