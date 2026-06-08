package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * fmt=apple (WebAuthn §8.8, Apple Anonymous Attestation).
 *  1) nonceToHash = authData || clientDataHash
 *  2) nonce = SHA-256(nonceToHash)
 *  3) credCert(x5c[0])의 extension(OID 1.2.840.113635.100.8.2) 안의 nonce와 일치 확인
 *  4) credCert subject 공개키 == credential 공개키 확인
 *  5) ATT_CA 반환 (체인은 상위가 TrustAnchorProvider로 검증)
 */
public final class AppleAttestationVerifier implements AttestationVerifier {

    private static final String APPLE_NONCE_OID = "1.2.840.113635.100.8.2";

    @Override
    public String format() { return "apple"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        if (!(attStmt.get("x5c") instanceof CborArray x5c) || x5c.items().isEmpty()) {
            throw new AttestationException("apple requires x5c");
        }
        List<X509Certificate> chain = parseChain(x5c);
        X509Certificate credCert = chain.get(0);

        // nonce 검증
        byte[] nonceToHash = concat(rawAuthData, clientDataHash);
        byte[] expectedNonce = sha256(nonceToHash);
        byte[] certNonce = extractNonce(credCert);
        if (!Arrays.equals(expectedNonce, certNonce)) {
            throw new AttestationException("apple nonce mismatch");
        }

        // credential 공개키 == credCert 공개키 검증 (§8.8 step 5).
        // registration authData는 반드시 attestedCredentialData를 포함해야 한다.
        var acd = authData.attestedCredentialData();
        if (acd == null) {
            throw new AttestationException("apple attestation requires attestedCredentialData");
        }
        CoseKey credKey = CoseKeyParser.parse(acd.coseKeyMap());
        if (!credKey.publicKey().equals(credCert.getPublicKey())) {
            throw new AttestationException("apple credential public key mismatch");
        }
        return new AttestationResult(AttestationResult.Type.ATT_CA, chain);
    }

    /** extension value(DER OCTET STRING wrapping SEQUENCE{[1] EXPLICIT OCTET STRING nonce})에서 32바이트 nonce 추출. */
    private static byte[] extractNonce(X509Certificate cert) {
        byte[] ext = cert.getExtensionValue(APPLE_NONCE_OID);
        if (ext == null) throw new AttestationException("apple nonce extension missing");
        // ext = OCTET STRING( SEQUENCE { [1] EXPLICIT OCTET STRING (32 nonce) } )
        byte[] inner = unwrapOctetString(ext);              // → SEQUENCE bytes
        byte[] nonce = findNonceInSequence(inner);          // → 32-byte nonce
        if (nonce == null || nonce.length != 32) {
            throw new AttestationException("apple nonce extension malformed");
        }
        return nonce;
    }

    /** OCTET STRING(tag 0x04) 한 겹 언랩. */
    private static byte[] unwrapOctetString(byte[] der) {
        if (der.length < 2 || (der[0] & 0xff) != 0x04) {
            throw new AttestationException("apple ext: expected OCTET STRING");
        }
        int[] lo = readLen(der, 1);
        if (lo[0] > der.length - lo[1]) {
            throw new AttestationException("apple ext: OCTET STRING length overrun");
        }
        return Arrays.copyOfRange(der, lo[1], lo[1] + lo[0]);
    }

    /** SEQUENCE(0x30) 안에서 [1] context tag(0xA1) → OCTET STRING(0x04)의 값(nonce)을 찾는다. */
    private static byte[] findNonceInSequence(byte[] der) {
        if (der.length < 2 || (der[0] & 0xff) != 0x30) return null;
        int[] seq = readLen(der, 1);
        int pos = seq[1], end = seq[1] + seq[0];
        if (seq[0] > der.length - seq[1]) throw new AttestationException("apple ext: SEQUENCE length overrun");
        while (pos < end && pos < der.length) {
            int tag = der[pos] & 0xff;
            int[] tl = readLen(der, pos + 1);
            int len = tl[0], valOff = tl[1];
            if (len > der.length - valOff) {
                throw new AttestationException("apple ext: element length overrun");
            }
            // [1] EXPLICIT(0xA1) 안에 OCTET STRING이 들어있음
            if (tag == 0xA1) {
                byte[] explicit = Arrays.copyOfRange(der, valOff, valOff + len);
                // explicit 안의 OCTET STRING
                if (explicit.length >= 2 && (explicit[0] & 0xff) == 0x04) {
                    int[] ol = readLen(explicit, 1);
                    if (ol[0] > explicit.length - ol[1]) {
                        throw new AttestationException("apple ext: nonce OCTET STRING overrun");
                    }
                    return Arrays.copyOfRange(explicit, ol[1], ol[1] + ol[0]);
                }
                return null;
            }
            pos = valOff + len;
        }
        return null;
    }

    /** der[offset]부터 DER 길이 읽어 {length, valueOffset} 반환. */
    private static int[] readLen(byte[] der, int offset) {
        if (offset >= der.length) throw new AttestationException("apple ext: truncated DER length");
        int b = der[offset] & 0xff;
        if (b < 0x80) return new int[]{b, offset + 1};
        int n = b & 0x7f;
        if (n == 0 || n > 4 || n > der.length - (offset + 1)) {
            throw new AttestationException("apple ext: bad DER length");
        }
        int len = 0;
        for (int i = 0; i < n; i++) len = (len << 8) | (der[offset + 1 + i] & 0xff);
        if (len < 0) throw new AttestationException("apple ext: negative DER length");
        return new int[]{len, offset + 1 + n};
    }

    private static List<X509Certificate> parseChain(CborArray x5c) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : x5c.items()) {
                if (!(c instanceof CborBytes b)) throw new AttestationException("apple x5c entry not bytes");
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(b.value())));
            }
            return chain;
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationException("apple x5c parse failed", e);
        }
    }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
