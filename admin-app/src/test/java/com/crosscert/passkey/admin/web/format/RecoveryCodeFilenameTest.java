package com.crosscert.passkey.admin.web.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** ← admin-ui/src/lib/recoveryCodeFilename.test.ts */
class RecoveryCodeFilenameTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 10);

    @Test
    @DisplayName("uses email local part + YYYYMMDD")
    void usesEmailLocalPartAndDate() {
        assertThat(RecoveryCodeFilename.of("alice@corp.com", DATE)).isEqualTo("passkey-admin-recovery-codes-alice-20260610.txt");
    }

    @Test
    @DisplayName("sanitizes account: lowercases, replaces unsafe chars, trims dashes")
    void sanitizesAccount() {
        assertThat(RecoveryCodeFilename.of("Bob.Smith+tag@x.io", DATE)).isEqualTo("passkey-admin-recovery-codes-bob.smith-tag-20260610.txt");
    }

    @Test
    @DisplayName("collapses runs of dashes and trims leading/trailing dashes")
    void collapsesDashes() {
        assertThat(RecoveryCodeFilename.of("--a  b--@x", DATE)).isEqualTo("passkey-admin-recovery-codes-a-b-20260610.txt");
    }

    @Test
    @DisplayName("falls back to no account segment when email is empty")
    void emptyEmailOmitsAccount() {
        assertThat(RecoveryCodeFilename.of("", DATE)).isEqualTo("passkey-admin-recovery-codes-20260610.txt");
    }

    @Test
    @DisplayName("falls back when sanitized local part is empty")
    void emptySanitizedLocalPartOmitsAccount() {
        assertThat(RecoveryCodeFilename.of("@@@@@@@", DATE)).isEqualTo("passkey-admin-recovery-codes-20260610.txt");
    }

    @Test
    @DisplayName("zero-pads month and day")
    void zeroPadsMonthAndDay() {
        assertThat(RecoveryCodeFilename.of("a@x", LocalDate.of(2026, 1, 3))).isEqualTo("passkey-admin-recovery-codes-a-20260103.txt");
    }
}
