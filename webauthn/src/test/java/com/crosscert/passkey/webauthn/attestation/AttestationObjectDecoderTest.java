package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AttestationObjectDecoderTest {

    @Test
    void decodesNoneAttestationObject() {
        // attestationObject = {"fmt":"none","attStmt":{},"authData":<bytes>}
        byte[] authData = new byte[37]; // rpIdHash(32)+flags(1)+signCount(4), flags=0
        byte[] ao = cborMap3(authData);

        AttestationObject obj = AttestationObjectDecoder.decode(ao);

        assertEquals("none", obj.format());
        assertArrayEquals(authData, obj.rawAuthData());
        assertNotNull(obj.authData());
    }

    @Test
    void rejectsNonMapAttestationObject() {
        // top-level is a CBOR int, not a map: 0x01
        assertThrows(AttestationException.class,
                () -> AttestationObjectDecoder.decode(new byte[]{0x01}));
    }

    @Test
    void rejectsMissingFmt() {
        // map with only authData + attStmt, no fmt
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0xa2); // map(2)
        o.write(0x67); o.writeBytes("attStmt".getBytes()); o.write(0xa0);
        o.write(0x68); o.writeBytes("authData".getBytes());
        byte[] authData = new byte[37];
        o.write(0x58); o.write(authData.length); o.writeBytes(authData);
        assertThrows(AttestationException.class,
                () -> AttestationObjectDecoder.decode(o.toByteArray()));
    }

    @Test
    void rejectsMissingAuthData() {
        // map with only fmt + attStmt, no authData
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0xa2); // map(2)
        o.write(0x63); o.writeBytes("fmt".getBytes());
        o.write(0x64); o.writeBytes("none".getBytes());
        o.write(0x67); o.writeBytes("attStmt".getBytes()); o.write(0xa0);
        assertThrows(AttestationException.class,
                () -> AttestationObjectDecoder.decode(o.toByteArray()));
    }

    /** {"fmt":"none", "attStmt":{}, "authData":<bytes>} 를 손으로 CBOR 인코드. */
    private static byte[] cborMap3(byte[] authData) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa3); // map(3)
        // "fmt":"none"
        o.write(0x63); o.writeBytes("fmt".getBytes());
        o.write(0x64); o.writeBytes("none".getBytes());
        // "attStmt":{}
        o.write(0x67); o.writeBytes("attStmt".getBytes());
        o.write(0xa0);
        // "authData": bytes
        o.write(0x68); o.writeBytes("authData".getBytes());
        o.write(0x58); o.write(authData.length); o.writeBytes(authData);
        return o.toByteArray();
    }
}
