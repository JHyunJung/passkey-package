package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.attestation.TpmStructures.CertInfo;
import com.crosscert.passkey.webauthn.attestation.TpmStructures.PubArea;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;

/**
 * fmt=tpm (WebAuthn §8.3, TPM 2.0).
 *
 * <p>attStmt = {ver:"2.0", alg:int, sig:bytes, x5c:[aikCert,...], certInfo:bytes, pubArea:bytes}.
 * ecdaaKeyId 경로(x5c 부재)는 미지원으로 거부한다.
 *
 * <p>검증 순서:
 * <ol>
 *   <li>ver == "2.0"</li>
 *   <li>pubArea 공개키 == credential COSE 공개키</li>
 *   <li>attToBeSigned = rawAuthData || clientDataHash</li>
 *   <li>certInfo: magic==TPM_GENERATED, type==CERTIFY, extraData==hash(attToBeSigned),
 *       attestedName==nameAlg||hash_nameAlg(pubArea)</li>
 *   <li>AIK(x5c[0]) 공개키로 certInfo 서명 검증</li>
 *   <li>AIK cert 요구사항: basicConstraints CA=false, EKU 2.23.133.8.3 포함</li>
 *   <li>ATT_CA 반환 (체인 trust anchor 강제는 상위 책임)</li>
 * </ol>
 *
 * <p>문서화된 한계: AIK SAN(TPM manufacturer/model/version)과 TPM vendor root까지의
 * 체인 검증은 이 검증기 범위 밖이다. version 3 + basicConstraints CA=false + EKU만 강제한다.
 */
public final class TpmAttestationVerifier implements AttestationVerifier {

    private static final String TPM_AIK_EKU = "2.23.133.8.3"; // id-tcg-kp-AIKCertificate
    private static final BigInteger DEFAULT_RSA_EXPONENT = BigInteger.valueOf(65537);

    @Override
    public String format() { return "tpm"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        // 1. ver == "2.0"
        if (!(attStmt.get("ver") instanceof CborText ver) || !"2.0".equals(ver.value())) {
            throw new AttestationException("tpm ver must be \"2.0\"");
        }

        long alg = requireInt(attStmt.get("alg"), "alg");
        byte[] signature = requireBytes(attStmt.get("sig"), "sig");
        byte[] certInfoBytes = requireBytes(attStmt.get("certInfo"), "certInfo");
        byte[] pubAreaBytes = requireBytes(attStmt.get("pubArea"), "pubArea");

        CborValue x5c = attStmt.get("x5c");
        if (!(x5c instanceof CborArray arr) || arr.items().isEmpty()) {
            // ecdaaKeyId 경로는 미지원 — x5c가 없으면 거부.
            throw new AttestationException("tpm requires x5c (ecdaa not supported)");
        }

        CoseAlgorithm coseAlg = CoseAlgorithm.fromCoseValue(alg);

        // 2. pubArea 공개키 == credential COSE 공개키
        if (authData.attestedCredentialData() == null) {
            throw new AttestationException("tpm attestation requires attestedCredentialData");
        }
        PubArea pubArea = TpmStructures.parsePubArea(pubAreaBytes);
        CoseKey credKey = CoseKeyParser.parse(authData.attestedCredentialData().coseKeyMap());
        verifyPubAreaMatchesCredential(pubArea, credKey.publicKey());

        // 3. attToBeSigned = rawAuthData || clientDataHash
        byte[] attToBeSigned = concat(rawAuthData, clientDataHash);

        // 4. certInfo 검증
        CertInfo certInfo = TpmStructures.parseCertInfo(certInfoBytes);
        if (certInfo.magic() != TpmStructures.TPM_GENERATED_VALUE) {
            throw new AttestationException("tpm certInfo magic invalid: 0x"
                    + Long.toHexString(certInfo.magic()));
        }
        if (certInfo.type() != TpmStructures.TPM_ST_ATTEST_CERTIFY) {
            throw new AttestationException("tpm certInfo type must be TPM_ST_ATTEST_CERTIFY: 0x"
                    + Integer.toHexString(certInfo.type()));
        }
        // extraData == hash(attToBeSigned), 해시는 alg와 쌍을 이루는 것 (ES256/RS256 → SHA-256)
        byte[] expectedExtraData = hashForSigAlg(coseAlg, attToBeSigned);
        if (!TpmStructures.constantTimeEquals(expectedExtraData, certInfo.extraData())) {
            throw new AttestationException("tpm certInfo extraData != hash(attToBeSigned)");
        }
        // attestedName == nameAlg(2바이트 BE) || hash_nameAlg(pubArea)
        byte[] expectedName = computeAttestedName(pubArea);
        if (!TpmStructures.constantTimeEquals(expectedName, certInfo.attestedName())) {
            throw new AttestationException("tpm certInfo attestedName mismatch");
        }

        // 5. AIK 공개키로 certInfo 서명 검증
        List<X509Certificate> chain = parseChain(arr);
        X509Certificate aik = chain.get(0);
        if (!verifySignature(coseAlg.jcaSignatureName(), aik.getPublicKey(), certInfoBytes, signature)) {
            throw new AttestationException("tpm certInfo signature invalid");
        }

        // 6. AIK cert 요구사항
        verifyAikRequirements(aik);

        // 7. ATT_CA
        return new AttestationResult(AttestationResult.Type.ATT_CA, chain);
    }

    /** pubArea의 공개키 파라미터가 credential COSE 공개키와 일치하는지 검증. */
    private static void verifyPubAreaMatchesCredential(PubArea pubArea, PublicKey credKey) {
        if (pubArea.type() == TpmStructures.TPM_ALG_RSA) {
            if (!(credKey instanceof RSAPublicKey rsa)) {
                throw new AttestationException("tpm pubArea is RSA but credential key is not");
            }
            BigInteger pubAreaModulus = new BigInteger(1, pubArea.uniqueModulus());
            if (!rsa.getModulus().equals(pubAreaModulus)) {
                throw new AttestationException("tpm pubArea modulus != credential modulus");
            }
            // exponent 0은 default 65537을 뜻한다.
            BigInteger pubAreaExp = pubArea.exponent() == 0
                    ? DEFAULT_RSA_EXPONENT : BigInteger.valueOf(pubArea.exponent());
            if (!rsa.getPublicExponent().equals(pubAreaExp)) {
                throw new AttestationException("tpm pubArea exponent != credential exponent");
            }
        } else if (pubArea.type() == TpmStructures.TPM_ALG_ECC) {
            if (!(credKey instanceof ECPublicKey ec)) {
                throw new AttestationException("tpm pubArea is ECC but credential key is not");
            }
            // 곡선 일치 (codex P2). credential COSE 키는 CoseKeyParser가 이미 P-256만 허용하므로,
            // pubArea도 P-256(TPM_ECC_NIST_P256)임을 확인하면 곡선 일치가 보장된다.
            if (pubArea.eccCurveId() != TpmStructures.TPM_ECC_NIST_P256) {
                throw new AttestationException(
                        "tpm pubArea ECC curve is not P-256: 0x" + Integer.toHexString(pubArea.eccCurveId()));
            }
            BigInteger x = new BigInteger(1, pubArea.eccX());
            BigInteger y = new BigInteger(1, pubArea.eccY());
            if (!ec.getW().getAffineX().equals(x) || !ec.getW().getAffineY().equals(y)) {
                throw new AttestationException("tpm pubArea ECC point != credential point");
            }
        } else {
            throw new AttestationException("tpm pubArea unsupported type: 0x"
                    + Integer.toHexString(pubArea.type()));
        }
    }

    /** attestedName = nameAlg(UINT16 BE) || hash_nameAlg(pubAreaBytes). */
    private static byte[] computeAttestedName(PubArea pubArea) {
        MessageDigest md = TpmStructures.digestForNameAlg(pubArea.nameAlg());
        byte[] digest = md.digest(pubArea.raw());
        byte[] out = new byte[2 + digest.length];
        out[0] = (byte) ((pubArea.nameAlg() >> 8) & 0xff);
        out[1] = (byte) (pubArea.nameAlg() & 0xff);
        System.arraycopy(digest, 0, out, 2, digest.length);
        return out;
    }

    /** ES256/RS256은 SHA-256으로 attToBeSigned를 해시한다 (extraData 비교용). */
    private static byte[] hashForSigAlg(CoseAlgorithm alg, byte[] data) {
        String jca = switch (alg) {
            case ES256, RS256 -> "SHA-256";
        };
        try {
            return MessageDigest.getInstance(jca).digest(data);
        } catch (Exception e) {
            throw new AttestationException("tpm hash unavailable: " + jca, e);
        }
    }

    /** WebAuthn §8.3 AIK cert 요구사항(부분): basicConstraints CA=false + EKU 2.23.133.8.3. */
    private static void verifyAikRequirements(X509Certificate aik) {
        if (aik.getVersion() != 3) {
            throw new AttestationException("tpm AIK cert must be X.509 v3");
        }
        // basicConstraints extension이 존재하고 CA=false여야 함 (codex P2).
        // getBasicConstraints()==-1은 "CA=false" 또는 "BC extension 부재" 둘 다이므로,
        // getExtensionValue로 extension 존재를 먼저 확인한다 (2.5.29.19 = basicConstraints OID).
        if (aik.getExtensionValue("2.5.29.19") == null) {
            throw new AttestationException("tpm AIK missing basicConstraints extension");
        }
        if (aik.getBasicConstraints() != -1) {
            throw new AttestationException("tpm AIK must not be a CA");
        }
        // EKU에 id-tcg-kp-AIKCertificate (2.23.133.8.3) 포함
        try {
            List<String> ekus = aik.getExtendedKeyUsage();
            if (ekus == null || !ekus.contains(TPM_AIK_EKU)) {
                throw new AttestationException("tpm AIK cert missing EKU " + TPM_AIK_EKU);
            }
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationException("tpm AIK cert EKU read failed", e);
        }
    }

    private static boolean verifySignature(String jcaName, PublicKey key,
                                           byte[] signed, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(jcaName);
            sig.initVerify(key);
            sig.update(signed);
            return sig.verify(signature);
        } catch (Exception e) {
            throw new AttestationException("tpm signature verify error", e);
        }
    }

    private static List<X509Certificate> parseChain(CborArray arr) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : arr.items()) {
                if (!(c instanceof CborBytes b)) {
                    throw new AttestationException("tpm x5c entry not a byte string");
                }
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(b.value())));
            }
            return chain;
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationException("tpm x5c parse failed", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static long requireInt(CborValue v, String f) {
        if (v instanceof CborInt i) return i.value();
        throw new AttestationException("tpm attStmt missing int: " + f);
    }

    private static byte[] requireBytes(CborValue v, String f) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("tpm attStmt missing bytes: " + f);
    }
}
