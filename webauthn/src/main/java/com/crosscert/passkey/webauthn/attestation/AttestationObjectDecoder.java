package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborDecoder;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;

/** attestationObject CBOR map → AttestationObject. */
public final class AttestationObjectDecoder {

    private AttestationObjectDecoder() {}

    public static AttestationObject decode(byte[] attestationObject) {
        CborValue root = CborDecoder.decode(attestationObject);
        if (!(root instanceof CborMap)) {
            throw new AttestationException("attestationObject is not a CBOR map");
        }
        CborValue fmtNode = root.get("fmt");
        if (!(fmtNode instanceof CborText fmt)) {
            throw new AttestationException("attestationObject.fmt missing");
        }
        CborValue authDataNode = root.get("authData");
        if (!(authDataNode instanceof CborBytes ad)) {
            throw new AttestationException("attestationObject.authData missing");
        }
        CborValue attStmt = root.get("attStmt");
        if (attStmt == null) {
            throw new AttestationException("attestationObject.attStmt missing");
        }
        byte[] rawAuthData = ad.value();
        AuthenticatorData parsed;
        try {
            parsed = AuthenticatorDataParser.parse(rawAuthData);
        } catch (RuntimeException e) {
            // authData 파싱 실패도 decoder 경계의 일관된 예외 계약으로 정규화 (codex P2).
            throw new AttestationException("attestationObject.authData parse failed: " + e.getMessage(), e);
        }
        return new AttestationObject(fmt.value(), rawAuthData, parsed, attStmt);
    }
}
