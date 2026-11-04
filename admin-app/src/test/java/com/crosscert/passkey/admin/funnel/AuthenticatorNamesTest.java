package com.crosscert.passkey.admin.funnel;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatorNamesTest {

    @Test
    void namesKnownUnknownAndZero() {
        assertThat(AuthenticatorNames.name(HexFormat.of().parseHex("fbfc3007154e4ecc8c0b6e020557d7bd"))).isEqualTo("iCloud 키체인");
        assertThat(AuthenticatorNames.name(HexFormat.of().parseHex("ea9b8d664d011d213ce4b6b48cb575d4"))).isEqualTo("Google 비밀번호 관리자");
        // 같은 이름의 여러 AAGUID
        assertThat(AuthenticatorNames.name(HexFormat.of().parseHex("9ddd1817af5a4672a2b93e3dd95000a9"))).isEqualTo("Windows Hello");
        assertThat(AuthenticatorNames.name(HexFormat.of().parseHex("0123456789abcdef0123456789abcdef"))).isEqualTo(AuthenticatorNames.OTHER);
        assertThat(AuthenticatorNames.name(new byte[16])).isEqualTo(AuthenticatorNames.UNKNOWN);
        assertThat(AuthenticatorNames.name(null)).isEqualTo(AuthenticatorNames.UNKNOWN);
    }
}
