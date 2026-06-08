package com.crosscert.passkey.webauthn.authdata;

/** authenticatorData flags 바이트 (WebAuthn §6.1). */
public record AuthenticatorFlags(
        boolean userPresent,        // UP (bit 0)
        boolean userVerified,       // UV (bit 2)
        boolean backupEligible,     // BE (bit 3)
        boolean backupState,        // BS (bit 4)
        boolean attestedCredentialDataIncluded, // AT (bit 6)
        boolean extensionDataIncluded           // ED (bit 7)
) {
    public static AuthenticatorFlags fromByte(int b) {
        return new AuthenticatorFlags(
                (b & 0x01) != 0,
                (b & 0x04) != 0,
                (b & 0x08) != 0,
                (b & 0x10) != 0,
                (b & 0x40) != 0,
                (b & 0x80) != 0);
    }
}
