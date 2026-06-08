package com.crosscert.passkey.webauthn.verifier;

/**
 * attestation trust 강제 수준.
 *  - NONE_ONLY: fmt=none만 허용 (attestation trust 검사 안 함)
 *  - SELF_ALLOWED: self/none 허용, x5c가 있으면 서명만 검증(체인 미강제)
 *  - TRUST_CHAIN_REQUIRED: x5c 체인을 TrustAnchorProvider로 검증
 */
public enum AttestationTrustPolicy { NONE_ONLY, SELF_ALLOWED, TRUST_CHAIN_REQUIRED }
