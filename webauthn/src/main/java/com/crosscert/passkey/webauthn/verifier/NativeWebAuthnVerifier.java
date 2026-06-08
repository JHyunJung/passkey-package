package com.crosscert.passkey.webauthn.verifier;

import com.crosscert.passkey.webauthn.attestation.AttestationObject;
import com.crosscert.passkey.webauthn.attestation.AttestationObjectDecoder;
import com.crosscert.passkey.webauthn.attestation.AttestationException;
import com.crosscert.passkey.webauthn.attestation.AttestationResult;
import com.crosscert.passkey.webauthn.attestation.AttestationVerifier;
import com.crosscert.passkey.webauthn.attestation.AttestationVerifiers;
import com.crosscert.passkey.webauthn.authdata.AttestedCredentialData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborDecoder;
import com.crosscert.passkey.webauthn.clientdata.ClientDataException;
import com.crosscert.passkey.webauthn.clientdata.ClientDataValidator;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;
import com.crosscert.passkey.webauthn.cose.CoseException;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;
import com.crosscert.passkey.webauthn.trust.CertPathVerifier;
import com.crosscert.passkey.webauthn.trust.EmptyTrustAnchorProvider;
import com.crosscert.passkey.webauthn.trust.TrustAnchorProvider;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException.Reason;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Set;

/**
 * 자체 구현 WebAuthn 검증 오케스트레이터. CBOR/COSE/authData/clientData
 * 빌딩블록과 attestation 검증기를 spec 세리머니(WebAuthn §7.1/§7.2) 순서로 묶는다.
 */
public final class NativeWebAuthnVerifier implements WebAuthnVerifier {

    private final CredentialJsonParser jsonParser;
    private final ClientDataValidator clientDataValidator;
    private final AttestationVerifiers attestationVerifiers;
    private final TrustAnchorProvider trustAnchors;
    private final CertPathVerifier certPathVerifier;

    public NativeWebAuthnVerifier(ObjectMapper mapper,
                                  AttestationVerifiers attestationVerifiers,
                                  TrustAnchorProvider trustAnchors) {
        this.jsonParser = new CredentialJsonParser(mapper);
        this.clientDataValidator = new ClientDataValidator(mapper);
        this.attestationVerifiers = attestationVerifiers;
        this.trustAnchors = trustAnchors;
        this.certPathVerifier = new CertPathVerifier();
    }

    /** 기본 구성 — 등록된 모든 포맷 + 빈 trust anchor(self/none만 통과). */
    public static NativeWebAuthnVerifier withDefaults(ObjectMapper mapper) {
        return new NativeWebAuthnVerifier(mapper, AttestationVerifiers.defaults(),
                new EmptyTrustAnchorProvider());
    }

    @Override
    public RegistrationResult verifyRegistration(RegistrationInput input)
            throws WebAuthnVerificationException {
        ParsedRegistration parsed;
        try {
            parsed = jsonParser.parseRegistration(input.credentialJson());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "registration JSON parse failed", e);
        }

        // 1) clientData 검증
        try {
            clientDataValidator.validate(parsed.clientDataJson(), "webauthn.create",
                    input.challenge(), input.allowedOrigins());
        } catch (ClientDataException e) {
            throw mapClientData(e);
        }

        // 2) attestationObject 디코드
        AttestationObject ao;
        try {
            ao = AttestationObjectDecoder.decode(parsed.attestationObject());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "attestationObject decode failed", e);
        }
        AuthenticatorData authData = ao.authData();

        // 3) rpIdHash 대조
        if (!MessageDigest.isEqual(authData.rpIdHash(), sha256(input.rpId()))) {
            throw fail(Reason.RP_ID_HASH_MISMATCH, "rpIdHash mismatch");
        }
        // 4) UP/UV 플래그
        if (!authData.flags().userPresent()) {
            throw fail(Reason.UP_REQUIRED, "user presence flag not set");
        }
        if (input.userVerificationRequired() && !authData.flags().userVerified()) {
            throw fail(Reason.UV_REQUIRED, "user verification required but not set");
        }
        // BE/BS 일관성 (WebAuthn §6.1): backup-eligible 아니면 backup-state도 false여야 함.
        if (!authData.flags().backupEligible() && authData.flags().backupState()) {
            throw fail(Reason.MALFORMED_INPUT, "backup state set without backup eligibility");
        }
        // 5) attestedCredentialData 존재
        AttestedCredentialData acd = authData.attestedCredentialData();
        if (acd == null) {
            throw fail(Reason.MALFORMED_INPUT, "registration authData has no attestedCredentialData");
        }
        // 6) credential 알고리즘이 허용 목록에 있는지
        CoseKey credKey;
        try {
            credKey = CoseKeyParser.parse(acd.coseKeyMap());
        } catch (CoseException e) {
            throw fail(Reason.UNSUPPORTED_ALGORITHM, "credential COSE key unsupported", e);
        }
        if (!input.allowedAlgorithms().contains(toBoundaryAlg(credKey.algorithm()))) {
            throw fail(Reason.UNSUPPORTED_ALGORITHM, "credential algorithm not allowed");
        }
        // 7) attestation 포맷이 테넌트 정책에 허용되는지
        if (!input.acceptedAttestationFormats().contains(ao.format())) {
            throw fail(Reason.ATTESTATION_FORMAT_NOT_ACCEPTED,
                    "attestation format not accepted: " + ao.format());
        }
        AttestationVerifier av = attestationVerifiers.forFormat(ao.format());
        if (av == null) {
            throw fail(Reason.UNSUPPORTED_ATTESTATION_FORMAT,
                    "unsupported attestation format: " + ao.format());
        }
        // 8) attestation statement 검증
        byte[] clientDataHash = sha256Bytes(parsed.clientDataJson());
        AttestationResult attResult;
        try {
            attResult = av.verify(authData, ao.rawAuthData(), ao.attStmt(), clientDataHash);
        } catch (AttestationException e) {
            throw fail(Reason.BAD_SIGNATURE, "attestation verify failed: " + e.getMessage(), e);
        }
        // 9) trust policy 강제
        enforceTrust(input.trustPolicy(), ao.format(), acd.aaguid(), attResult);

        return new RegistrationResult(
                acd.credentialId(),
                acd.coseKeyBytes(),
                authData.signCount(),
                acd.aaguid(),
                ao.format(),
                parsed.transports(),
                authData.flags().userVerified(),
                authData.flags().userPresent(),
                authData.flags().backupEligible(),
                authData.flags().backupState());
    }

    @Override
    public AuthenticationResult verifyAuthentication(AuthenticationInput input)
            throws WebAuthnVerificationException {
        ParsedAuthentication parsed;
        try {
            parsed = jsonParser.parseAuthentication(input.credentialJson());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "authentication JSON parse failed", e);
        }

        // 1) clientData 검증 (webauthn.get)
        try {
            clientDataValidator.validate(parsed.clientDataJson(), "webauthn.get",
                    input.challenge(), input.allowedOrigins());
        } catch (ClientDataException e) {
            throw mapClientData(e);
        }

        // assertion의 credential id가 저장된 credential과 일치하는지 (WebAuthn §7.2).
        // 호출자가 storedCredential을 id로 조회하지만, verifier가 경계의 단일 진실로서
        // 자체 바인딩한다 (codex P1, defense-in-depth).
        if (!MessageDigest.isEqual(parsed.rawId(), input.storedCredential().credentialId())) {
            throw fail(Reason.MALFORMED_INPUT, "asserted credential id does not match stored credential");
        }

        // 2) authData 파싱 (AT 없음)
        AuthenticatorData authData;
        try {
            authData = AuthenticatorDataParser.parse(parsed.authenticatorData());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "authenticatorData parse failed", e);
        }

        // 3) rpIdHash 대조
        if (!MessageDigest.isEqual(authData.rpIdHash(), sha256(input.rpId()))) {
            throw fail(Reason.RP_ID_HASH_MISMATCH, "rpIdHash mismatch");
        }
        // 4) UP/UV
        if (!authData.flags().userPresent()) {
            throw fail(Reason.UP_REQUIRED, "user presence flag not set");
        }
        if (input.userVerificationRequired() && !authData.flags().userVerified()) {
            throw fail(Reason.UV_REQUIRED, "user verification required but not set");
        }

        // 5) 저장 COSE 키 복원
        CoseKey storedKey;
        try {
            storedKey = CoseKeyParser.parse(
                    CborDecoder.decode(input.storedCredential().cosePublicKey()));
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "stored COSE key invalid", e);
        }

        // 6) 서명 검증: authData || SHA-256(clientDataJSON)
        byte[] clientDataHash = sha256Bytes(parsed.clientDataJson());
        boolean ok = verifySignature(storedKey, parsed.authenticatorData(), clientDataHash,
                parsed.signature());
        if (!ok) {
            throw fail(Reason.BAD_SIGNATURE, "assertion signature invalid");
        }

        return new AuthenticationResult(
                input.storedCredential().credentialId(),
                authData.signCount(),
                authData.flags().userVerified(),
                authData.flags().userPresent(),
                authData.flags().backupState());
    }

    private static boolean verifySignature(CoseKey key, byte[] authData,
                                           byte[] clientDataHash, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(key.algorithm().jcaSignatureName());
            sig.initVerify(key.publicKey());
            sig.update(authData);
            sig.update(clientDataHash);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // --- trust 강제 ---

    private void enforceTrust(AttestationTrustPolicy policy, String format,
                              byte[] aaguid, AttestationResult attResult)
            throws WebAuthnVerificationException {
        switch (policy) {
            case NONE_ONLY -> {
                if (!"none".equals(format)) {
                    throw fail(Reason.ATTESTATION_UNTRUSTED, "policy NONE_ONLY but format=" + format);
                }
            }
            case SELF_ALLOWED -> { /* none/self/x5c 서명 검증만으로 충분 */ }
            case TRUST_CHAIN_REQUIRED -> {
                if (attResult.type() == AttestationResult.Type.NONE
                        || attResult.type() == AttestationResult.Type.SELF) {
                    throw fail(Reason.ATTESTATION_UNTRUSTED,
                            "TRUST_CHAIN_REQUIRED but attestation is none/self");
                }
                boolean trusted;
                try {
                    Set<java.security.cert.TrustAnchor> anchors =
                            trustAnchors.trustAnchors(aaguid, format);
                    trusted = certPathVerifier.verify(attResult.trustPath(), anchors);
                } catch (RuntimeException e) {
                    // trust anchor provider/path 검증 중 예외도 fail-closed로 정규화 (codex P2).
                    throw fail(Reason.ATTESTATION_UNTRUSTED,
                            "trust anchor resolution/validation failed: " + e.getMessage(), e);
                }
                if (!trusted) {
                    throw fail(Reason.ATTESTATION_UNTRUSTED, "attestation chain not trusted");
                }
            }
        }
    }

    // --- 헬퍼 ---

    private static COSEAlgorithm toBoundaryAlg(CoseAlgorithm a) {
        return switch (a) {
            case ES256 -> COSEAlgorithm.ES256;
            case RS256 -> COSEAlgorithm.RS256;
        };
    }

    private static byte[] sha256(String s) {
        return sha256Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256Bytes(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static WebAuthnVerificationException fail(Reason reason, String msg) {
        return new WebAuthnVerificationException(reason, msg);
    }

    private static WebAuthnVerificationException fail(Reason reason, String msg, Throwable cause) {
        return new WebAuthnVerificationException(reason, msg, cause);
    }

    private static WebAuthnVerificationException mapClientData(ClientDataException e) {
        Reason r = switch (e.reason()) {
            case MALFORMED -> Reason.MALFORMED_INPUT;
            case TYPE_MISMATCH -> Reason.TYPE_MISMATCH;
            case CHALLENGE_MISMATCH -> Reason.CHALLENGE_MISMATCH;
            case ORIGIN_MISMATCH -> Reason.ORIGIN_MISMATCH;
        };
        return new WebAuthnVerificationException(r, e.getMessage(), e);
    }
}
