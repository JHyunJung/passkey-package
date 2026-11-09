package com.crosscert.passkey.admin.funnel

import java.util.HexFormat

/**
 * AAGUID → 사람이 읽는 인증기 이름. FIDO MDS 에는 iCloud 키체인·Google 비밀번호 관리자 같은 플랫폼·비밀번호 관리자
 * 패스키가 없고(MdsAaguidCache 도 상태만 담는다), 기기 분포를 고객사에 보여주려면 이름이 필요해 널리 쓰이는 것만 둔다.
 * 출처: 공개 커뮤니티 목록(passkey-authenticator-aaguids). 모르는 AAGUID 는 "기타 인증기", 0 이거나 없으면 "알 수 없음".
 */
object AuthenticatorNames {

    const val UNKNOWN = "알 수 없음"
    const val OTHER = "기타 인증기"

    private val NAMES: Map<String, String> = mapOf(
        "fbfc3007154e4ecc8c0b6e020557d7bd" to "iCloud 키체인",
        "dd4ec289e01d41c9bb8970fa845d4bf2" to "iCloud 키체인",
        "ea9b8d664d011d213ce4b6b48cb575d4" to "Google 비밀번호 관리자",
        "adce000235bcc60a648b0b25f1f05503" to "Chrome (Mac)",
        "08987058cadc4b81b6e130de50dcbe96" to "Windows Hello",
        "9ddd1817af5a4672a2b93e3dd95000a9" to "Windows Hello",
        "6028b017b1d44c02b4b3afcdafc96bb2" to "Windows Hello",
        "53414d53554e47000000000000000000" to "Samsung Pass",
        "bada5566a7aa401fbd9645619a55120d" to "1Password",
        "d548826e79b4db40a3d811116f7e8349" to "Bitwarden",
        "531126d6e717415c93203d9aa6981239" to "Dashlane",
        "cb69481e8ff7403993ec0a2729a154a8" to "YubiKey 5",
        "ee882879721c491397753dfcce97072a" to "YubiKey 5",
        "fa2b99dc9e3942578f924a30d23c4118" to "YubiKey 5",
        "2fc0579f811347eab116bb5a8db9202a" to "YubiKey 5",
        "73bb0cd4e50249b89c6fb59445bf720b" to "YubiKey 5 FIPS",
    )

    fun name(aaguid: ByteArray?): String {
        if (aaguid == null) return UNKNOWN
        if (aaguid.all { it.toInt() == 0 }) return UNKNOWN
        return NAMES.getOrDefault(HexFormat.of().formatHex(aaguid), OTHER)
    }
}
