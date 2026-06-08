package com.crosscert.passkey.webauthn.authdata;

import com.crosscert.passkey.webauthn.cbor.CborDecoder;
import com.crosscert.passkey.webauthn.cbor.CborException;
import com.crosscert.passkey.webauthn.cbor.CborValue;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * authenticatorData 파서 (WebAuthn §6.1 고정 오프셋):
 *   rpIdHash(32) || flags(1) || signCount(4, big-endian)
 *   [ AT 플래그 시: aaguid(16) || credIdLen(2) || credId || COSE_Key ]
 *   [ ED 플래그 시: extensions CBOR map ]
 */
public final class AuthenticatorDataParser {

    private AuthenticatorDataParser() {}

    private static final int RP_ID_HASH_LEN = 32;
    private static final int FLAGS_LEN = 1;
    private static final int SIGN_COUNT_LEN = 4;
    private static final int FIXED_LEN = RP_ID_HASH_LEN + FLAGS_LEN + SIGN_COUNT_LEN; // 37
    private static final int AAGUID_LEN = 16;

    public static AuthenticatorData parse(byte[] data) {
        if (data.length < FIXED_LEN) {
            throw new AuthDataException("authenticatorData too short: " + data.length);
        }
        byte[] rpIdHash = Arrays.copyOfRange(data, 0, RP_ID_HASH_LEN);
        AuthenticatorFlags flags = AuthenticatorFlags.fromByte(data[RP_ID_HASH_LEN] & 0xff);
        long signCount = ByteBuffer.wrap(data, RP_ID_HASH_LEN + FLAGS_LEN, SIGN_COUNT_LEN)
                .getInt() & 0xffffffffL;

        AttestedCredentialData acd = null;
        int pos = FIXED_LEN;
        if (flags.attestedCredentialDataIncluded()) {
            if (data.length < pos + AAGUID_LEN + 2) {
                throw new AuthDataException("authenticatorData missing attestedCredentialData");
            }
            byte[] aaguid = Arrays.copyOfRange(data, pos, pos + AAGUID_LEN);
            pos += AAGUID_LEN;
            int credIdLen = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
            pos += 2;
            if (data.length < pos + credIdLen) {
                throw new AuthDataException("credentialId length exceeds authenticatorData");
            }
            byte[] credId = Arrays.copyOfRange(data, pos, pos + credIdLen);
            pos += credIdLen;

            // 남은 바이트 앞쪽에서 COSE_Key 한 항목을 떼어내고 그 길이를 측정.
            int coseLen;
            CborValue coseMap;
            try {
                coseLen = CborDecoder.decodeFirstItemLength(data, pos);
                coseMap = CborDecoder.decode(Arrays.copyOfRange(data, pos, pos + coseLen));
            } catch (CborException e) {
                throw new AuthDataException("invalid COSE_Key in attestedCredentialData: " + e.getMessage());
            }
            byte[] coseBytes = Arrays.copyOfRange(data, pos, pos + coseLen);
            pos += coseLen;

            acd = new AttestedCredentialData(aaguid, credId, coseMap, coseBytes);
        }
        // extensions(ED) 처리 + 전체 바이트 소비 검증.
        // WebAuthn §6.1: authData = fixed(37) || [ACD if AT] || [extensions if ED].
        // 서명이 authData 전체에 걸리므로 파서는 정확히 끝까지 소비해야 한다.
        // 남는 바이트가 있으면(또는 ED인데 extensions가 malformed면) 거부한다.
        if (flags.extensionDataIncluded()) {
            int extLen;
            try {
                extLen = CborDecoder.decodeFirstItemLength(data, pos);
            } catch (CborException e) {
                throw new AuthDataException("invalid extensions CBOR: " + e.getMessage());
            }
            pos += extLen;
        }
        if (pos != data.length) {
            throw new AuthDataException(
                    "authenticatorData has " + (data.length - pos) + " trailing byte(s)");
        }

        return new AuthenticatorData(rpIdHash, flags, signCount, acd);
    }
}
