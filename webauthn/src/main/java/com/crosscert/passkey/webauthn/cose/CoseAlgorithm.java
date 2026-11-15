package com.crosscert.passkey.webauthn.cose;

/**
 * 지원하는 COSE 서명 알고리즘. coseValue는 COSE_Key의 alg(라벨 3) 값.
 * jcaSignatureName은 JDK java.security.Signature.getInstance에 넘기는 이름.
 *
 * ES256/RS256 만 지원한다. 그 외 값은 CoseException 이고, 등록 credential 키에서 나면
 * NativeWebAuthnVerifier 가 UNSUPPORTED_ALGORITHM 으로 바꾼다.
 */
public enum CoseAlgorithm {
    ES256(-7, "SHA256withECDSA"),
    RS256(-257, "SHA256withRSA");

    private final long coseValue;
    private final String jcaSignatureName;

    CoseAlgorithm(long coseValue, String jcaSignatureName) {
        this.coseValue = coseValue;
        this.jcaSignatureName = jcaSignatureName;
    }

    public long coseValue() { return coseValue; }
    public String jcaSignatureName() { return jcaSignatureName; }

    public static CoseAlgorithm fromCoseValue(long v) {
        for (CoseAlgorithm a : values()) if (a.coseValue == v) return a;
        throw new CoseException("unsupported COSE algorithm: " + v);
    }
}
