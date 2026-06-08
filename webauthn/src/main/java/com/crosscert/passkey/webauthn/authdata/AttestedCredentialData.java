package com.crosscert.passkey.webauthn.authdata;

import com.crosscert.passkey.webauthn.cbor.CborValue;

/** AT 플래그가 켜졌을 때 authData에 포함되는 등록 자격증명 데이터. */
public record AttestedCredentialData(
        byte[] aaguid,            // 16바이트
        byte[] credentialId,      // 가변 길이
        CborValue coseKeyMap,     // 원시 COSE_Key CBOR map (CoseKeyParser가 소비)
        byte[] coseKeyBytes       // 그 map의 정확한 바이트 표현 (저장용)
) {}
