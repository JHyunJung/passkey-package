package com.crosscert.passkey.webauthn.verifier;

import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

/** 테스트용 유효 ES256 COSE_Key 바이트 생성. */
final class TestCose {
    static byte[] es256() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            ECPublicKey pub = (ECPublicKey) g.generateKeyPair().getPublic();
            byte[] x = fixed32(pub.getW().getAffineX());
            byte[] y = fixed32(pub.getW().getAffineY());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0xa5);
            o.write(0x01); o.write(0x02);
            o.write(0x03); o.write(0x26);
            o.write(0x20); o.write(0x01);
            o.write(0x21); o.write(0x58); o.write(0x20); o.writeBytes(x);
            o.write(0x22); o.write(0x58); o.write(0x20); o.writeBytes(y);
            return o.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
