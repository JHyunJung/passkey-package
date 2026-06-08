package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AttestedCredentialData;
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
import java.util.List;

/**
 * fmt=android-key (WebAuthn §8.4, Android Key Attestation).
 *
 * <p>attStmt = {@code { alg: int, sig: bytes, x5c: [credCert, ...] }}.
 * 검증 절차:
 * <ol>
 *   <li>x5c[0](credCert) 공개키 + alg로 sig가 {@code authData || clientDataHash}에 대한
 *       유효 서명인지 확인 ({@link AttestationSignatures#verify}).</li>
 *   <li>authData.attestedCredentialData의 credential 공개키가 credCert subject 공개키와
 *       같은지 확인.</li>
 *   <li>credCert의 KeyDescription extension(OID 1.3.6.1.4.1.11129.2.1.17)에서
 *       attestationChallenge가 clientDataHash와 같은지 확인
 *       ({@link AndroidKeyExtension#challengeMatches}).</li>
 *   <li>BASIC 반환 (체인 trust anchor 검증은 상위 단계 책임).</li>
 * </ol>
 *
 * <p><b>알려진 제한(Known limitation):</b> WebAuthn §8.4는 추가로 KeyDescription의
 * {@code authorizationList.allApplications}가 <i>없을 것</i>(특정 앱 전용 키)과
 * {@code origin == KM_ORIGIN_GENERATED}, {@code purpose}에 {@code KM_PURPOSE_SIGN}이 포함될 것을
 * 요구한다. 본 구현은 핵심 보안 성질(서명·credential 공개키 일치·attestationChallenge ==
 * clientDataHash)만 강제하며, authorizationList 정책 검사는 의도적으로 생략했다. 이 정책
 * 검사가 필요하면 KeyDescription의 software/teeEnforced AuthorizationList 파싱을 추가해야 한다.
 */
public final class AndroidKeyAttestationVerifier implements AttestationVerifier {

    /** Android Key Attestation extension (KeyDescription). */
    private static final String KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17";

    @Override
    public String format() { return "android-key"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        long alg = requireInt(attStmt.get("alg"), "alg");
        byte[] signature = requireBytes(attStmt.get("sig"), "sig");
        if (!(attStmt.get("x5c") instanceof CborArray x5c) || x5c.items().isEmpty()) {
            throw new AttestationException("android-key requires non-empty x5c");
        }
        List<X509Certificate> chain = parseChain(x5c);
        X509Certificate credCert = chain.get(0);

        CoseAlgorithm coseAlg = CoseAlgorithm.fromCoseValue(alg);

        // 1) sig가 authData||clientDataHash에 대한 credCert 공개키 유효 서명인지 확인.
        boolean sigOk = AttestationSignatures.verify(
                coseAlg.jcaSignatureName(), credCert.getPublicKey(),
                rawAuthData, clientDataHash, signature);
        if (!sigOk) {
            throw new AttestationException("android-key signature invalid");
        }

        // 2) credential 공개키 == credCert subject 공개키 (§8.4 step 2).
        //    registration authData는 반드시 attestedCredentialData를 포함해야 한다.
        AttestedCredentialData acd = authData.attestedCredentialData();
        if (acd == null) {
            throw new AttestationException("android-key attestation requires attestedCredentialData");
        }
        CoseKey credKey = CoseKeyParser.parse(acd.coseKeyMap());
        if (!credKey.publicKey().equals(credCert.getPublicKey())) {
            throw new AttestationException("android-key credential public key mismatch");
        }

        // 3) KeyDescription extension의 attestationChallenge == clientDataHash (§8.4 step 3).
        byte[] ext = credCert.getExtensionValue(KEY_DESCRIPTION_OID);
        if (!AndroidKeyExtension.challengeMatches(ext, clientDataHash)) {
            throw new AttestationException("android-key attestationChallenge mismatch");
        }

        return AttestationResult.basic(chain);
    }

    private static List<X509Certificate> parseChain(CborArray x5c) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : x5c.items()) {
                if (!(c instanceof CborBytes b)) {
                    throw new AttestationException("android-key x5c entry not a byte string");
                }
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(b.value())));
            }
            return chain;
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationException("android-key x5c parse failed", e);
        }
    }

    private static long requireInt(CborValue v, String f) {
        if (v instanceof CborInt i) return i.value();
        throw new AttestationException("android-key attStmt missing int: " + f);
    }

    private static byte[] requireBytes(CborValue v, String f) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("android-key attStmt missing bytes: " + f);
    }
}
