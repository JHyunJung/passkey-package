package com.crosscert.passkey.webauthn.cose;

/**
 * 지원하는 COSE 서명 알고리즘. coseValue는 COSE_Key의 alg(라벨 3) 값.
 * jcaSignatureName은 JDK java.security.Signature.getInstance에 넘기는 이름.
 *
 * 이번 범위는 ES256/RS256 한정 (spec §2 allowedAlgorithms). 그 외는
 * UNSUPPORTED_ALGORITHM으로 거부한다.
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
