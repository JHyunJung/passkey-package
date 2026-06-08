package com.crosscert.passkey.webauthn.authdata;

/** 파싱된 authenticatorData (WebAuthn §6.1). attestedCredentialData는 등록 시에만 존재(null 가능). */
public record AuthenticatorData(
        byte[] rpIdHash,                              // 32바이트
        AuthenticatorFlags flags,
        long signCount,                              // unsigned 32-bit
        AttestedCredentialData attestedCredentialData // nullable
) {}
