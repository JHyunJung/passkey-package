package com.crosscert.passkey.admin.web.format

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** ← admin-ui/src/lib/recoveryCodeFilename.test.ts */
class RecoveryCodeFilenameTest {

    companion object {
        private val DATE: LocalDate = LocalDate.of(2026, 6, 10)
    }

    @Test
    @DisplayName("uses email local part + YYYYMMDD")
    fun usesEmailLocalPartAndDate() {
        assertThat(RecoveryCodeFilename.of("alice@corp.com", DATE)).isEqualTo("passkey-admin-recovery-codes-alice-20260610.txt")
    }

    @Test
    @DisplayName("sanitizes account: lowercases, replaces unsafe chars, trims dashes")
    fun sanitizesAccount() {
        assertThat(RecoveryCodeFilename.of("Bob.Smith+tag@x.io", DATE)).isEqualTo("passkey-admin-recovery-codes-bob.smith-tag-20260610.txt")
    }

    @Test
    @DisplayName("collapses runs of dashes and trims leading/trailing dashes")
    fun collapsesDashes() {
        assertThat(RecoveryCodeFilename.of("--a  b--@x", DATE)).isEqualTo("passkey-admin-recovery-codes-a-b-20260610.txt")
    }

    @Test
    @DisplayName("falls back to no account segment when email is empty")
    fun emptyEmailOmitsAccount() {
        assertThat(RecoveryCodeFilename.of("", DATE)).isEqualTo("passkey-admin-recovery-codes-20260610.txt")
    }

    @Test
    @DisplayName("falls back when sanitized local part is empty")
    fun emptySanitizedLocalPartOmitsAccount() {
        assertThat(RecoveryCodeFilename.of("@@@@@@@", DATE)).isEqualTo("passkey-admin-recovery-codes-20260610.txt")
    }

    @Test
    @DisplayName("zero-pads month and day")
    fun zeroPadsMonthAndDay() {
        assertThat(RecoveryCodeFilename.of("a@x", LocalDate.of(2026, 1, 3))).isEqualTo("passkey-admin-recovery-codes-a-20260103.txt")
    }
}
