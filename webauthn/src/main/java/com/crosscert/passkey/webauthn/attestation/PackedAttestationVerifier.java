package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * fmt=packed (WebAuthn §8.2).
 *  - x5c 있음: leaf 인증서 공개키로 sig 검증 → BASIC (체인은 상위가 trust anchor로 검증)
 *  - x5c 없음(self): credential 공개키로 sig 검증, alg가 credential alg와 일치 → SELF
 */
public final class PackedAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "packed"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        long alg = requireInt(attStmt.get("alg"), "alg");
        byte[] signature = requireBytes(attStmt.get("sig"), "sig");
        CborValue x5c = attStmt.get("x5c");

        CoseAlgorithm coseAlg = CoseAlgorithm.fromCoseValue(alg);

        if (x5c instanceof CborArray arr && !arr.items().isEmpty()) {
            // x5c 경로: leaf 인증서로 검증
            List<X509Certificate> chain = parseChain(arr);
            X509Certificate leaf = chain.get(0);
            boolean ok = AttestationSignatures.verify(
                    coseAlg.jcaSignatureName(), leaf.getPublicKey(),
                    rawAuthData, clientDataHash, signature);
            if (!ok) throw new AttestationException("packed x5c signature invalid");
            verifyPackedLeafRequirements(leaf, authData);   // WebAuthn §8.2.1
            return AttestationResult.basic(chain);
        }

        // self 경로: credential 공개키로 검증, alg 일치 강제
        if (authData.attestedCredentialData() == null) {
            throw new AttestationException("packed self attestation requires attestedCredentialData");
        }
        CoseKey credKey = CoseKeyParser.parse(authData.attestedCredentialData().coseKeyMap());
        if (credKey.algorithm() != coseAlg) {
            throw new AttestationException("packed self attestation alg mismatch");
        }
        boolean ok = AttestationSignatures.verify(
                coseAlg.jcaSignatureName(), credKey.publicKey(),
                rawAuthData, clientDataHash, signature);
        if (!ok) throw new AttestationException("packed self signature invalid");
        return AttestationResult.self();
    }

    private static List<X509Certificate> parseChain(CborArray arr) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : arr.items()) {
                if (!(c instanceof CborBytes b)) {
                    throw new AttestationException("x5c entry not a byte string");
                }
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(b.value())));
            }
            return chain;
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationException("x5c parse failed", e);
        }
    }

    /** WebAuthn §8.2 packed x5c leaf 인증서 요구사항. */
    private static void verifyPackedLeafRequirements(X509Certificate leaf, AuthenticatorData authData) {
        // (1) version 3
        if (leaf.getVersion() != 3) {
            throw new AttestationException("packed x5c leaf must be X.509 v3");
        }
        // (2) basic constraints: not a CA (getBasicConstraints() == -1 이면 CA 아님)
        if (leaf.getBasicConstraints() != -1) {
            throw new AttestationException("packed x5c leaf must not be a CA");
        }
        // (3) id-fido-gen-ce-aaguid extension이 있으면 authData AAGUID와 일치
        byte[] ext = leaf.getExtensionValue("1.3.6.1.4.1.45724.1.1.4");
        if (ext != null) {
            byte[] certAaguid = extractFidoAaguid(ext);
            byte[] authAaguid = authData.attestedCredentialData() == null
                    ? null : authData.attestedCredentialData().aaguid();
            if (authAaguid == null || !Arrays.equals(certAaguid, authAaguid)) {
                throw new AttestationException("packed x5c AAGUID extension mismatch");
            }
        }
    }

    /**
     * id-fido-gen-ce-aaguid extension value 파싱.
     * extension value는 DER OCTET STRING으로 감싼 (OCTET STRING(16바이트 AAGUID)).
     * 즉 바깥 OCTET STRING 안에 다시 OCTET STRING(AAGUID 16바이트)이 들어있다.
     * getExtensionValue는 바깥 OCTET STRING(DER)을 반환하므로 두 겹을 벗긴다.
     */
    private static byte[] extractFidoAaguid(byte[] extensionValue) {
        // 바깥 OCTET STRING 언랩
        byte[] inner = unwrapDerOctetString(extensionValue);
        // 안쪽 OCTET STRING 언랩 → 16바이트 AAGUID
        byte[] aaguid = unwrapDerOctetString(inner);
        if (aaguid.length != 16) {
            throw new AttestationException("packed x5c AAGUID extension malformed (len=" + aaguid.length + ")");
        }
        return aaguid;
    }

    /** DER OCTET STRING(tag 0x04) 한 겹 언랩. tag/len 검증 후 value 반환. */
    private static byte[] unwrapDerOctetString(byte[] der) {
        if (der == null || der.length < 2 || (der[0] & 0xff) != 0x04) {
            throw new AttestationException("expected DER OCTET STRING in AAGUID extension");
        }
        int idx = 1;
        int len = der[idx++] & 0xff;
        if (len >= 0x80) {
            int numBytes = len & 0x7f;
            if (numBytes == 0 || numBytes > 4 || idx + numBytes > der.length) {
                throw new AttestationException("invalid DER length in AAGUID extension");
            }
            len = 0;
            for (int i = 0; i < numBytes; i++) len = (len << 8) | (der[idx++] & 0xff);
        }
        if (idx + len > der.length || len < 0) {
            throw new AttestationException("DER OCTET STRING length overrun in AAGUID extension");
        }
        return Arrays.copyOfRange(der, idx, idx + len);
    }

    private static long requireInt(CborValue v, String f) {
        if (v instanceof CborInt i) return i.value();
        throw new AttestationException("packed attStmt missing int: " + f);
    }

    private static byte[] requireBytes(CborValue v, String f) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("packed attStmt missing bytes: " + f);
    }
}
