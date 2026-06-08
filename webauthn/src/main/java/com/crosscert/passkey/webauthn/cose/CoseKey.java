package com.crosscert.passkey.webauthn.cose;

import java.security.PublicKey;

/** 파싱된 COSE_Key — 알고리즘과 복원된 JDK 공개키. */
public record CoseKey(CoseAlgorithm algorithm, PublicKey publicKey) {}
