package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AttestedCredentialData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.List;

/**
 * fmt=fido-u2f (WebAuthn §8.6). 서명 입력:
 *   0x00 || rpIdHash || clientDataHash || credentialId || publicKeyU2F
 * publicKeyU2F = 0x04 || x(32) || y(32). x5c는 단일 leaf 인증서.
 */
public final class FidoU2fAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "fido-u2f"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        if (!(attStmt.get("x5c") instanceof CborArray x5c) || x5c.items().size() != 1) {
            throw new AttestationException("fido-u2f requires single-cert x5c");
        }
        byte[] sig = requireBytes(attStmt.get("sig"));
        AttestedCredentialData acd = authData.attestedCredentialData();
        if (acd == null) throw new AttestationException("fido-u2f missing attestedCredentialData");

        X509Certificate leaf = parseCert(((CborBytes) x5c.items().get(0)).value());

        CoseKey credKey = CoseKeyParser.parse(acd.coseKeyMap());
        if (credKey.algorithm() != CoseAlgorithm.ES256) {
            throw new AttestationException("fido-u2f credential must be ES256 (P-256)");
        }
        if (!(credKey.publicKey() instanceof ECPublicKey ec)) {
            throw new AttestationException("fido-u2f credential is not EC");
        }
        byte[] x = fixed32(ec.getW().getAffineX());
        byte[] y = fixed32(ec.getW().getAffineY());

        ByteArrayOutputStream signed = new ByteArrayOutputStream();
        signed.write(0x00);
        signed.writeBytes(authData.rpIdHash());
        signed.writeBytes(clientDataHash);
        signed.writeBytes(acd.credentialId());
        signed.write(0x04);
        signed.writeBytes(x);
        signed.writeBytes(y);

        boolean ok;
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(leaf.getPublicKey());
            s.update(signed.toByteArray());
            ok = s.verify(sig);
        } catch (Exception e) {
            throw new AttestationException("fido-u2f signature verify error", e);
        }
        if (!ok) throw new AttestationException("fido-u2f signature invalid");
        return AttestationResult.basic(List.of(leaf));
    }

    private static byte[] requireBytes(CborValue v) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("fido-u2f attStmt missing sig bytes");
    }

    private static X509Certificate parseCert(byte[] der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new AttestationException("fido-u2f cert parse failed", e);
        }
    }

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
