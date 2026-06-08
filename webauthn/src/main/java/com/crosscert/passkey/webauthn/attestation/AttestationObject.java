package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;

/**
 * 디코드된 attestationObject (WebAuthn §6.5): fmt + authData + attStmt.
 *
 * <p>rawAuthData는 서명 검증 입력이라 변조되면 안 된다. 방어적 복사로
 * 생성 후 호출자 변경에 노출되지 않게 한다 (codex P2).
 */
public record AttestationObject(
        String format,
        byte[] rawAuthData,
        AuthenticatorData authData,
        CborValue attStmt
) {
    public AttestationObject {
        rawAuthData = rawAuthData.clone();
    }

    @Override
    public byte[] rawAuthData() {
        return rawAuthData.clone();
    }
}
