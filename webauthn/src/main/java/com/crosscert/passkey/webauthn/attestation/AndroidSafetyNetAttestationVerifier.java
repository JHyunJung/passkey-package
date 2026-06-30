package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.JsonMappers;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * fmt=android-safetynet (WebAuthn §8.5, Android SafetyNet Attestation).
 *
 * <p>attStmt = {@code { ver: text, response: bytes }} 에서 {@code response}는
 * SafetyNet JWS(compact serialization, base64url 3파트 header.payload.signature)이다.
 *
 * <p>검증 절차:
 * <ol>
 *   <li>response(UTF-8 문자열)를 '.'으로 정확히 3파트로 분할.</li>
 *   <li>header(base64url JSON)의 {@code alg}(RS256/ES256)와 {@code x5c}(base64 DER 인증서 배열)에서
 *       leaf = x5c[0]을 얻는다.</li>
 *   <li>signingInput = ASCII(parts[0] + "." + parts[1])을 leaf 공개키 + alg로 JWS 서명 검증.
 *       ES256은 raw R||S(64바이트)이므로 {@link JwsEcdsa#toDer}로 DER 변환 후 검증.</li>
 *   <li>payload(base64url JSON)의 {@code nonce}(base64 STANDARD)가
 *       base64Std(SHA-256(authData || clientDataHash))와 일치하는지 확인.</li>
 *   <li>leaf cert subject의 CN RDN 값이 {@code attest.android.com}과 정확히 일치(WebAuthn §8.5).
 *       substring 우회를 막기 위해 DN을 파싱해 CN을 정확 비교한다.</li>
 *   <li>payload.{@code ctsProfileMatch} == true.</li>
 *   <li>BASIC 반환.</li>
 * </ol>
 *
 * <p><b>알려진 제한(Known limitation):</b>
 * <ul>
 *   <li>x5c 체인이 Google SafetyNet 루트(GlobalSign 등)로 연결되는지는 여기서 강제하지 않는다.
 *       이는 상위 단계(NativeWebAuthnVerifier / TrustAnchorProvider) 책임이다.</li>
 *   <li>payload.{@code timestampMs} 신선도(freshness)는 검사하지 않는다.</li>
 * </ul>
 */
public final class AndroidSafetyNetAttestationVerifier implements AttestationVerifier {

    /** WebAuthn §8.5: SafetyNet JWS leaf cert subject hostname. */
    private static final String EXPECTED_HOSTNAME = "attest.android.com";

    /** header/payload JSON 파싱 전용 — 스레드 안전(공유 가능). */
    private static final ObjectMapper JSON = JsonMappers.secure();

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Decoder B64STD = Base64.getDecoder();

    @Override
    public String format() { return "android-safetynet"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        byte[] response = requireResponse(attStmt.get("response"));

        // 1) JWS compact: header.payload.signature
        String jws = new String(response, StandardCharsets.UTF_8);
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3) {
            throw new AttestationException("android-safetynet response is not a 3-part JWS");
        }
        // compact JWS 파트는 base64url no-padding 이어야 한다 ('=' 포함 시 거부) (codex P2)
        for (String p : parts) {
            if (p.indexOf('=') >= 0) {
                throw new AttestationException("android-safetynet JWS part has base64 padding");
            }
        }
        byte[] headerBytes, payloadBytes, signature;
        try {
            headerBytes = B64URL.decode(parts[0]);
            payloadBytes = B64URL.decode(parts[1]);
            signature = B64URL.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new AttestationException("android-safetynet JWS part not valid base64url", e);
        }

        JsonNode header = parseJson(headerBytes, "header");
        JsonNode payload = parseJson(payloadBytes, "payload");

        // 2) header.alg + x5c → leaf
        String alg = textField(header, "alg", "header.alg");
        List<X509Certificate> chain = parseX5c(header);
        X509Certificate leaf = chain.get(0);

        // 3) JWS 서명 검증 (leaf 공개키, signingInput = ASCII(parts[0].parts[1]))
        //    서명 검증 직전에 header.alg ↔ leaf 공개키 타입을 명시 매칭한다(방어심화, F32).
        //    JCA(Signature)가 키/alg 불일치를 어차피 예외로 막지만, 그 예외는 verifyJws에서
        //    false로 흡수되어 "signature invalid"로만 보인다. alg-confusion(예: ES256 헤더에
        //    RSA leaf) 회귀를 명시 거부로 표면화해 정상 검증 결과는 그대로 두고 거부 경로만 핀한다.
        //    ES256/RS256 외 alg는 verifyJws에서 미지원으로 거부되므로 여기서도 둘만 매칭한다.
        PublicKey leafKey = leaf.getPublicKey();
        boolean algKeyMatch =
                ("ES256".equals(alg) && leafKey instanceof java.security.interfaces.ECPublicKey)
             || ("RS256".equals(alg) && leafKey instanceof java.security.interfaces.RSAPublicKey);
        if (!algKeyMatch) {
            throw new AttestationException("android-safetynet alg/leaf-key mismatch: alg=" + alg);
        }
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (!verifyJws(alg, leafKey, signingInput, signature)) {
            throw new AttestationException("android-safetynet JWS signature invalid");
        }

        // 4) nonce == base64Std(SHA-256(authData || clientDataHash))
        String nonceB64 = textField(payload, "nonce", "payload.nonce");
        byte[] claimedNonce = decodeStdBase64(nonceB64);
        byte[] expectedNonce = sha256(concat(rawAuthData, clientDataHash));
        if (!MessageDigest.isEqual(expectedNonce, claimedNonce)) {
            throw new AttestationException("android-safetynet nonce mismatch");
        }

        // 5) leaf CN == "attest.android.com" 정확 일치 (substring 우회 차단, codex P1)
        if (!hasExactCn(leaf, EXPECTED_HOSTNAME)) {
            throw new AttestationException("android-safetynet leaf CN is not " + EXPECTED_HOSTNAME);
        }

        // 6) ctsProfileMatch == true (없거나 false면 거부)
        JsonNode cts = payload.get("ctsProfileMatch");
        if (cts == null || !cts.isBoolean() || !cts.booleanValue()) {
            throw new AttestationException("android-safetynet ctsProfileMatch not true");
        }

        return AttestationResult.basic(chain);
    }

    /**
     * leaf subject DN을 파싱해 CN RDN 값이 {@code expected}와 정확히 일치하는지 확인 (codex P1).
     * {@code subject.contains("attest.android.com")} substring 검사는
     * {@code CN=evil-attest.android.com.example}나 {@code OU=attest.android.com,CN=evil}로 우회된다.
     * CN이 여러 개면 첫 CN으로 판단 — Android SafetyNet 인증서는 CN 하나뿐이다.
     */
    private static boolean hasExactCn(X509Certificate leaf, String expected) {
        try {
            String dn = leaf.getSubjectX500Principal().getName(); // RFC2253
            javax.naming.ldap.LdapName ldap = new javax.naming.ldap.LdapName(dn);
            for (javax.naming.ldap.Rdn rdn : ldap.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return expected.equals(String.valueOf(rdn.getValue()));
                }
            }
            return false;
        } catch (javax.naming.InvalidNameException e) {
            throw new AttestationException("android-safetynet leaf subject DN unparseable", e);
        }
    }

    /** RS256 → SHA256withRSA, ES256 → SHA256withECDSA(raw R||S → DER). */
    private static boolean verifyJws(String alg, PublicKey key, byte[] signingInput, byte[] signature) {
        try {
            switch (alg) {
                case "RS256" -> {
                    Signature s = Signature.getInstance("SHA256withRSA");
                    s.initVerify(key);
                    s.update(signingInput);
                    return s.verify(signature);
                }
                case "ES256" -> {
                    Signature s = Signature.getInstance("SHA256withECDSA");
                    s.initVerify(key);
                    s.update(signingInput);
                    return s.verify(JwsEcdsa.toDer(signature));
                }
                default -> throw new AttestationException("android-safetynet unsupported alg: " + alg);
            }
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    /** response는 CborBytes가 기본; 일부 인코더는 CborText로 담으므로 UTF-8 바이트로 fallback. */
    private static byte[] requireResponse(CborValue v) {
        if (v instanceof CborBytes b) return b.value();
        if (v instanceof CborText t) return t.value().getBytes(StandardCharsets.UTF_8);
        throw new AttestationException("android-safetynet attStmt missing response (bytes/text)");
    }

    private static List<X509Certificate> parseX5c(JsonNode header) {
        JsonNode x5c = header.get("x5c");
        if (x5c == null || !x5c.isArray() || x5c.isEmpty()) {
            throw new AttestationException("android-safetynet header.x5c missing or empty");
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (JsonNode entry : x5c) {
                if (!entry.isTextual()) {
                    throw new AttestationException("android-safetynet x5c entry not a string");
                }
                byte[] der = B64STD.decode(entry.textValue()); // x5c는 base64 STANDARD
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
            return chain;
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationException("android-safetynet x5c parse failed", e);
        }
    }

    private static JsonNode parseJson(byte[] bytes, String what) {
        try {
            return JSON.readTree(bytes);
        } catch (Exception e) {
            throw new AttestationException("android-safetynet " + what + " JSON parse failed", e);
        }
    }

    private static String textField(JsonNode node, String field, String what) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            throw new AttestationException("android-safetynet missing text field: " + what);
        }
        return v.textValue();
    }

    private static byte[] decodeStdBase64(String s) {
        try {
            return B64STD.decode(s);
        } catch (IllegalArgumentException e) {
            throw new AttestationException("android-safetynet nonce not valid base64", e);
        }
    }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
