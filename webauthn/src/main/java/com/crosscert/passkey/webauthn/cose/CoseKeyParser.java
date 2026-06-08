package com.crosscert.passkey.webauthn.cose;

import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * COSE_Key (RFC 9052) CBOR map을 java.security.PublicKey로 복원.
 *
 * 라벨: kty=1, alg=3, EC2: crv=-1, x=-2, y=-3 / RSA: n=-1, e=-2.
 * kty: 2=EC2, 3=RSA. 이번 범위는 ES256(EC2 P-256), RS256(RSA)만 지원.
 */
public final class CoseKeyParser {

    private CoseKeyParser() {}

    private static final long KTY_EC2 = 2;
    private static final long KTY_RSA = 3;

    public static CoseKey parse(CborValue coseMap) {
        long alg = requireInt(coseMap.get(3), "alg");
        CoseAlgorithm algorithm = CoseAlgorithm.fromCoseValue(alg);
        long kty = requireInt(coseMap.get(1), "kty");

        PublicKey pub;
        if (kty == KTY_EC2) pub = parseEc2(coseMap, algorithm);
        else if (kty == KTY_RSA) pub = parseRsa(coseMap, algorithm);
        else throw new CoseException("unsupported COSE kty: " + kty);
        return new CoseKey(algorithm, pub);
    }

    private static PublicKey parseEc2(CborValue m, CoseAlgorithm alg) {
        if (alg != CoseAlgorithm.ES256) {
            throw new CoseException("EC2 key with non-ES256 alg: " + alg);
        }
        long crv = requireInt(m.get(-1), "crv");
        if (crv != 1) throw new CoseException("unsupported EC curve (need P-256): " + crv);
        byte[] xb = requireBytes(m.get(-2), "x");
        byte[] yb = requireBytes(m.get(-3), "y");
        if (xb.length != 32 || yb.length != 32) {
            throw new CoseException("P-256 x/y must be 32 bytes");
        }
        BigInteger x = new BigInteger(1, xb);
        BigInteger y = new BigInteger(1, yb);
        P256.requireOnCurve(x, y);   // 좌표 범위 + 곡선 방정식 검증 (invalid curve attack 방어)
        try {
            ECParameterSpec p256 = P256.parameterSpec();
            ECPoint point = new ECPoint(x, y);
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, p256));
        } catch (Exception e) {
            throw new CoseException("failed to build EC public key", e);
        }
    }

    private static PublicKey parseRsa(CborValue m, CoseAlgorithm alg) {
        if (alg != CoseAlgorithm.RS256) {
            throw new CoseException("RSA key with non-RS256 alg: " + alg);
        }
        byte[] nb = requireBytes(m.get(-1), "n");
        byte[] eb = requireBytes(m.get(-2), "e");
        BigInteger n = new BigInteger(1, nb);
        BigInteger e = new BigInteger(1, eb);
        if (n.bitLength() < 2048) {
            throw new CoseException("RSA modulus too small (need >= 2048 bits)");
        }
        if (!n.testBit(0)) {
            throw new CoseException("RSA modulus must be odd");
        }
        if (e.compareTo(BigInteger.valueOf(3)) < 0 || !e.testBit(0)) {
            throw new CoseException("RSA exponent must be odd and >= 3");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        } catch (Exception ex) {
            throw new CoseException("failed to build RSA public key", ex);
        }
    }

    private static long requireInt(CborValue v, String field) {
        if (v instanceof CborInt i) return i.value();
        throw new CoseException("COSE_Key missing/invalid int field: " + field);
    }

    private static byte[] requireBytes(CborValue v, String field) {
        if (v instanceof CborBytes b) return b.value();
        throw new CoseException("COSE_Key missing/invalid bytes field: " + field);
    }
}
