package com.crosscert.passkey.webauthn.verifier;

/** DB에서 로드한 credential (인증 검증 입력). cosePublicKey는 COSE_Key CBOR 바이트. */
public record StoredCredential(byte[] credentialId, byte[] cosePublicKey, long signCount) {}
