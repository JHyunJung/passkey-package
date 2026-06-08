package com.crosscert.passkey.webauthn.clientdata;

/** 파싱된 clientDataJSON (WebAuthn §5.8.1). */
public record CollectedClientData(String type, String challenge, String origin, boolean crossOrigin) {}
