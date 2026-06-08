package com.crosscert.passkey.webauthn.authdata;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatorDataParserTest {

    /** rpIdHash(32) + flags(1) + signCount(4) 만 있는 인증용 authData. */
    @Test
    void parsesAssertionAuthData() {
        byte[] rpIdHash = new byte[32];
        for (int i = 0; i < 32; i++) rpIdHash[i] = (byte) i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(rpIdHash);
        out.write(0x05); // UP(0x01) + UV(0x04)
        out.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x2a}); // signCount = 42

        AuthenticatorData ad = AuthenticatorDataParser.parse(out.toByteArray());

        assertArrayEquals(rpIdHash, ad.rpIdHash());
        assertTrue(ad.flags().userPresent());
        assertTrue(ad.flags().userVerified());
        assertFalse(ad.flags().attestedCredentialDataIncluded());
        assertEquals(42L, ad.signCount());
        assertNull(ad.attestedCredentialData());
    }

    /** AT 플래그 + attestedCredentialData(aaguid + credId + COSE_Key). */
    @Test
    void parsesRegistrationAuthDataWithAttestedCredential() {
        byte[] rpIdHash = new byte[32];
        byte[] aaguid = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        byte[] credId = HexFormat.of().parseHex("cafe");
        // 최소 COSE_Key: {1:2} (kty=EC2) — 파서는 바이트만 떼어내면 됨
        byte[] coseKey = HexFormat.of().parseHex("a10102");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(rpIdHash);
        out.write(0x41); // UP(0x01) + AT(0x40)
        out.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00}); // signCount = 0
        out.writeBytes(aaguid);
        out.writeBytes(new byte[]{0x00, 0x02}); // credIdLen = 2
        out.writeBytes(credId);
        out.writeBytes(coseKey);

        AuthenticatorData ad = AuthenticatorDataParser.parse(out.toByteArray());

        assertTrue(ad.flags().attestedCredentialDataIncluded());
        assertNotNull(ad.attestedCredentialData());
        assertArrayEquals(aaguid, ad.attestedCredentialData().aaguid());
        assertArrayEquals(credId, ad.attestedCredentialData().credentialId());
        assertArrayEquals(coseKey, ad.attestedCredentialData().coseKeyBytes());
    }

    @Test
    void rejectsTooShortAuthData() {
        assertThrows(AuthDataException.class,
                () -> AuthenticatorDataParser.parse(new byte[10]));
    }

    @Test
    void rejectsCredentialIdLengthExceedingBuffer() {
        // AT flag set, credIdLen claims 0xffff but no data follows
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[32]);            // rpIdHash
        out.write(0x41);                          // UP + AT
        out.writeBytes(new byte[]{0, 0, 0, 0});   // signCount
        out.writeBytes(new byte[16]);             // aaguid
        out.writeBytes(new byte[]{(byte) 0xff, (byte) 0xff}); // credIdLen = 65535
        // no credId/COSE bytes follow
        assertThrows(AuthDataException.class,
                () -> AuthenticatorDataParser.parse(out.toByteArray()));
    }

    @Test
    void rejectsTrailingBytesAfterAssertionAuthData() {
        // fixed 37바이트(ED=false, AT=false) 뒤에 잉여 1바이트
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[32]);
        out.write(0x01);                          // UP only (ED=false, AT=false)
        out.writeBytes(new byte[]{0, 0, 0, 1});   // signCount
        out.write(0x99);                          // 잉여 바이트
        assertThrows(AuthDataException.class,
                () -> AuthenticatorDataParser.parse(out.toByteArray()));
    }

    @Test
    void rejectsTrailingBytesAfterAttestedCredential() {
        // AT 있음, COSE_Key 뒤 잉여 바이트 (ED=false)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[32]);
        out.write(0x41);                          // UP + AT
        out.writeBytes(new byte[]{0, 0, 0, 0});
        out.writeBytes(new byte[16]);             // aaguid
        out.writeBytes(new byte[]{0, 0});         // credIdLen = 0
        out.writeBytes(HexFormat.of().parseHex("a10102")); // COSE_Key {1:2}
        out.write(0x99);                          // 잉여 바이트
        assertThrows(AuthDataException.class,
                () -> AuthenticatorDataParser.parse(out.toByteArray()));
    }
}
