package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;

/**
 * 포맷별 attestation statement 검증기 (WebAuthn §8).
 * attStmt 서명을 검증하고 trust path를 돌려준다. trust anchor 강제는
 * 상위(NativeWebAuthnVerifier)가 정책에 따라 수행한다.
 *
 * @param rawAuthData authData의 원시 바이트 (서명 입력 재구성에 필요)
 * @param attStmt 디코드된 attStmt CBOR map
 * @param clientDataHash SHA-256(rawClientDataJSON)
 */
public interface AttestationVerifier {
    String format();
    AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                             CborValue attStmt, byte[] clientDataHash);
}
