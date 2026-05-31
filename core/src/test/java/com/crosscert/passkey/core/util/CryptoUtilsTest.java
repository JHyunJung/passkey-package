package com.crosscert.passkey.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoUtilsTest {

    @Test
    void sha256HexOfEmptyString() {
        assertThat(CryptoUtils.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256HexOfAbc() {
        assertThat(CryptoUtils.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hexLowercasesAndPadsBytes() {
        assertThat(CryptoUtils.hex(new byte[]{0x00, (byte) 0xff, 0x0a}))
                .isEqualTo("00ff0a");
    }

    @Test
    void hexMatchesHexFormatForAllByteValuesAndIsLowercasePadded() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        String out = CryptoUtils.hex(all);
        assertThat(out).isEqualTo(java.util.HexFormat.of().formatHex(all));
        assertThat(out).hasSize(512);
        assertThat(out).isEqualTo(out.toLowerCase());
        assertThat(out).startsWith("000102");
        assertThat(out).endsWith("fdfeff");
    }

    @Test
    void maskEmailMasksLocalPart() {
        assertThat(CryptoUtils.maskEmail("john@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    void maskEmailHandlesNullBlankAndNoAt() {
        assertThat(CryptoUtils.maskEmail(null)).isEqualTo("(unknown)");
        assertThat(CryptoUtils.maskEmail("  ")).isEqualTo("(unknown)");
        assertThat(CryptoUtils.maskEmail("noat")).isEqualTo("***");
    }

    @Test
    void randomTokenHasPrefixExpectedLengthAndIsUnique() {
        String a = CryptoUtils.randomToken("rst_");
        String b = CryptoUtils.randomToken("rst_");
        assertThat(a).startsWith("rst_");
        assertThat(a).hasSize(4 + 64); // prefix + 32 bytes * 2 hex chars
        assertThat(a).isNotEqualTo(b);
    }
}
