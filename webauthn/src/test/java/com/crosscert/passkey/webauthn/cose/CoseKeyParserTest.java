package com.crosscert.passkey.webauthn.cose;

import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoseKeyParserTest {

    /** kty=2(EC2), alg=-7(ES256), crv=1(P-256), x/y 32바이트인 COSE_Key map을 구성. */
    private CborMap es256CoseMap(byte[] x, byte[] y) {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborInt(1), new CborInt(2));    // kty = EC2
        m.put(new CborInt(3), new CborInt(-7));   // alg = ES256
        m.put(new CborInt(-1), new CborInt(1));   // crv = P-256
        m.put(new CborInt(-2), new CborBytes(x)); // x
        m.put(new CborInt(-3), new CborBytes(y)); // y
        return new CborMap(m);
    }

    @Test
    void parsesEs256Key() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey pub = (ECPublicKey) g.generateKeyPair().getPublic();

        byte[] x = toFixed32(pub.getW().getAffineX());
        byte[] y = toFixed32(pub.getW().getAffineY());

        CoseKey key = CoseKeyParser.parse(es256CoseMap(x, y));

        assertEquals(CoseAlgorithm.ES256, key.algorithm());
        ECPublicKey parsed = (ECPublicKey) key.publicKey();
        assertEquals(pub.getW().getAffineX(), parsed.getW().getAffineX());
        assertEquals(pub.getW().getAffineY(), parsed.getW().getAffineY());
    }

    @Test
    void rejectsUnsupportedAlgorithm() {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborInt(1), new CborInt(1));    // kty = OKP (Ed25519 계열)
        m.put(new CborInt(3), new CborInt(-8));   // alg = EdDSA (미지원)
        assertThrows(CoseException.class, () -> CoseKeyParser.parse(new CborMap(m)));
    }

    @Test
    void rejectsEcPointNotOnCurve() {
        // 유효한 길이지만 곡선 위에 없는 좌표 (x=1, y=1)
        byte[] x = new byte[32]; x[31] = 1;
        byte[] y = new byte[32]; y[31] = 1;
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborInt(1), new CborInt(2));
        m.put(new CborInt(3), new CborInt(-7));
        m.put(new CborInt(-1), new CborInt(1));
        m.put(new CborInt(-2), new CborBytes(x));
        m.put(new CborInt(-3), new CborBytes(y));
        assertThrows(CoseException.class, () -> CoseKeyParser.parse(new CborMap(m)));
    }

    @Test
    void rejectsRsaModulusTooSmall() {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborInt(1), new CborInt(3));
        m.put(new CborInt(3), new CborInt(-257));
        m.put(new CborInt(-1), new CborBytes(new byte[]{0x01, 0x00, 0x01})); // 작은 modulus
        m.put(new CborInt(-2), new CborBytes(new byte[]{0x01, 0x00, 0x01}));
        assertThrows(CoseException.class, () -> CoseKeyParser.parse(new CborMap(m)));
    }

    @Test
    void parsesRs256Key() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        java.security.interfaces.RSAPublicKey pub =
                (java.security.interfaces.RSAPublicKey) g.generateKeyPair().getPublic();
        byte[] n = unsigned(pub.getModulus());
        byte[] e = unsigned(pub.getPublicExponent());
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborInt(1), new CborInt(3));     // kty = RSA
        m.put(new CborInt(3), new CborInt(-257));  // alg = RS256
        m.put(new CborInt(-1), new CborBytes(n));  // n
        m.put(new CborInt(-2), new CborBytes(e));  // e
        CoseKey key = CoseKeyParser.parse(new CborMap(m));
        assertEquals(CoseAlgorithm.RS256, key.algorithm());
        java.security.interfaces.RSAPublicKey parsed =
                (java.security.interfaces.RSAPublicKey) key.publicKey();
        assertEquals(pub.getModulus(), parsed.getModulus());
        assertEquals(pub.getPublicExponent(), parsed.getPublicExponent());
    }

    private static byte[] toFixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    /** BigInteger를 부호 없는 big-endian 바이트로 (선행 0x00 제거). */
    private static byte[] unsigned(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] out = new byte[raw.length - 1];
            System.arraycopy(raw, 1, out, 0, out.length);
            return out;
        }
        return raw;
    }
}
