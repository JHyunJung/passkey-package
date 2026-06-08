package com.crosscert.passkey.webauthn.verifier;

import java.io.ByteArrayOutputStream;

/** 테스트용 none attestationObject CBOR 인코더. */
final class TestAttestationObject {
    static byte[] none(byte[] authData) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa3); // map(3)
        o.write(0x63); o.writeBytes("fmt".getBytes());
        o.write(0x64); o.writeBytes("none".getBytes());
        o.write(0x67); o.writeBytes("attStmt".getBytes());
        o.write(0xa0);
        o.write(0x68); o.writeBytes("authData".getBytes());
        if (authData.length <= 23) {
            o.write(0x40 | authData.length);
        } else if (authData.length < 256) {
            o.write(0x58); o.write(authData.length);
        } else {
            o.write(0x59); o.write((authData.length >> 8) & 0xff); o.write(authData.length & 0xff);
        }
        o.writeBytes(authData);
        return o.toByteArray();
    }
}
