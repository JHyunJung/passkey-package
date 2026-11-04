package com.crosscert.passkey.admin.funnel;

import java.util.HexFormat;
import java.util.Map;

/**
 * AAGUID → 사람이 읽는 인증기 이름. FIDO MDS 에는 iCloud 키체인·Google 비밀번호 관리자 같은 플랫폼·비밀번호 관리자
 * 패스키가 없고(MdsAaguidCache 도 상태만 담는다), 기기 분포를 고객사에 보여주려면 이름이 필요해 널리 쓰이는 것만 둔다.
 * 출처: 공개 커뮤니티 목록(passkey-authenticator-aaguids). 모르는 AAGUID 는 "기타 인증기", 0 이거나 없으면 "알 수 없음".
 */
public final class AuthenticatorNames {
    private AuthenticatorNames() {}

    public static final String UNKNOWN = "알 수 없음";
    public static final String OTHER = "기타 인증기";

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("fbfc3007154e4ecc8c0b6e020557d7bd", "iCloud 키체인"),
            Map.entry("dd4ec289e01d41c9bb8970fa845d4bf2", "iCloud 키체인"),
            Map.entry("ea9b8d664d011d213ce4b6b48cb575d4", "Google 비밀번호 관리자"),
            Map.entry("adce000235bcc60a648b0b25f1f05503", "Chrome (Mac)"),
            Map.entry("08987058cadc4b81b6e130de50dcbe96", "Windows Hello"),
            Map.entry("9ddd1817af5a4672a2b93e3dd95000a9", "Windows Hello"),
            Map.entry("6028b017b1d44c02b4b3afcdafc96bb2", "Windows Hello"),
            Map.entry("53414d53554e47000000000000000000", "Samsung Pass"),
            Map.entry("bada5566a7aa401fbd9645619a55120d", "1Password"),
            Map.entry("d548826e79b4db40a3d811116f7e8349", "Bitwarden"),
            Map.entry("531126d6e717415c93203d9aa6981239", "Dashlane"),
            Map.entry("cb69481e8ff7403993ec0a2729a154a8", "YubiKey 5"),
            Map.entry("ee882879721c491397753dfcce97072a", "YubiKey 5"),
            Map.entry("fa2b99dc9e3942578f924a30d23c4118", "YubiKey 5"),
            Map.entry("2fc0579f811347eab116bb5a8db9202a", "YubiKey 5"),
            Map.entry("73bb0cd4e50249b89c6fb59445bf720b", "YubiKey 5 FIPS"));

    public static String name(byte[] aaguid) {
        if (aaguid == null) return UNKNOWN;
        boolean zero = true;
        for (byte b : aaguid) if (b != 0) { zero = false; break; }
        if (zero) return UNKNOWN;
        return NAMES.getOrDefault(HexFormat.of().formatHex(aaguid), OTHER);
    }
}
