package com.crosscert.passkey.webauthn.cose;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;

/** secp256r1(P-256) 곡선 파라미터를 JDK에서 한 번 얻어 캐시. */
final class P256 {
    private P256() {}

    private static final ECParameterSpec SPEC;

    // secp256r1 (P-256) domain parameters (FIPS 186-4)
    private static final BigInteger P = new BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16);
    private static final BigInteger A = new BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16);
    private static final BigInteger B = new BigInteger(
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16);

    static {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            SPEC = ap.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static ECParameterSpec parameterSpec() { return SPEC; }

    /** x,y가 [0,p) 범위이고 곡선 방정식 y^2 ≡ x^3 + a*x + b (mod p)를 만족하는지 검증. */
    static void requireOnCurve(BigInteger x, BigInteger y) {
        if (x.signum() < 0 || x.compareTo(P) >= 0 || y.signum() < 0 || y.compareTo(P) >= 0) {
            throw new CoseException("EC coordinate out of field range");
        }
        BigInteger lhs = y.multiply(y).mod(P);
        BigInteger rhs = x.multiply(x).multiply(x)
                .add(A.multiply(x)).add(B).mod(P);
        if (!lhs.equals(rhs)) {
            throw new CoseException("EC point not on P-256 curve");
        }
    }
}
