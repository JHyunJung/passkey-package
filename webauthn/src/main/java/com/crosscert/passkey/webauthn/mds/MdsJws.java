package com.crosscert.passkey.webauthn.mds;

import com.crosscert.passkey.webauthn.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * JWS compact 직렬화(RFC 7515) 파싱: header.payload.signature.
 * MDS3 BLOB은 RS256/ES256으로 서명되며 header에 x5c(서명 인증서 체인)를 담는다.
 * 네트워크 무의존 — 순수 파싱.
 */
public final class MdsJws {

    private static final ObjectMapper MAPPER = JsonMappers.secure();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Decoder B64STD = Base64.getDecoder();

    private final String alg;
    private final List<byte[]> x5c;
    private final byte[] signingInput;
    private final byte[] payloadBytes;
    private final byte[] signature;

    private MdsJws(String alg, List<byte[]> x5c, byte[] signingInput,
                   byte[] payloadBytes, byte[] signature) {
        this.alg = alg;
        // 방어적 깊은 복사 — 파싱 후 호출자가 체인/인증서 바이트를 변조하지 못하게 (codex P2).
        List<byte[]> copy = new ArrayList<>(x5c.size());
        for (byte[] c : x5c) copy.add(c.clone());
        this.x5c = java.util.Collections.unmodifiableList(copy);
        this.signingInput = signingInput;
        this.payloadBytes = payloadBytes;
        this.signature = signature;
    }

    public String alg() { return alg; }
    /** 불변 리스트 + 복사본 — 호출자 변조 차단 (codex P2). */
    public List<byte[]> x5c() {
        List<byte[]> copy = new ArrayList<>(x5c.size());
        for (byte[] c : x5c) copy.add(c.clone());
        return java.util.Collections.unmodifiableList(copy);
    }
    public byte[] signingInput() { return signingInput.clone(); }
    public byte[] payloadBytes() { return payloadBytes.clone(); }
    public byte[] signature() { return signature.clone(); }

    public static MdsJws parse(String jws) throws MdsException {
        if (jws == null) throw new MdsException(MdsException.Reason.MALFORMED_JWS, "null JWS");
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS must have 3 parts");
        }
        for (String p : parts) {
            if (p.indexOf('=') >= 0) {
                throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS part is base64url-padded");
            }
        }
        byte[] headerBytes, payloadBytes, signature;
        try {
            headerBytes = B64URL.decode(parts[0]);
            payloadBytes = B64URL.decode(parts[1]);
            signature = B64URL.decode(parts[2]);
        } catch (RuntimeException e) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS part not base64url", e);
        }

        JsonNode header;
        try {
            header = MAPPER.readTree(headerBytes);
        } catch (Exception e) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS header not JSON", e);
        }
        JsonNode algNode = header.get("alg");
        if (algNode == null || !algNode.isTextual()) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS header missing alg");
        }
        String alg = algNode.asText();
        if (!"RS256".equals(alg) && !"ES256".equals(alg)) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "unsupported JWS alg: " + alg);
        }

        List<byte[]> x5c = new ArrayList<>();
        JsonNode x5cNode = header.get("x5c");
        if (x5cNode != null && x5cNode.isArray()) {
            for (JsonNode c : x5cNode) {
                if (!c.isTextual()) {
                    throw new MdsException(MdsException.Reason.MALFORMED_JWS, "x5c entry not text");
                }
                try {
                    x5c.add(B64STD.decode(c.asText()));
                } catch (RuntimeException e) {
                    throw new MdsException(MdsException.Reason.MALFORMED_JWS, "x5c entry not base64", e);
                }
            }
        }

        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        return new MdsJws(alg, x5c, signingInput, payloadBytes, signature);
    }
}
