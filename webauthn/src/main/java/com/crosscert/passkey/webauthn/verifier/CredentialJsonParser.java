package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WebAuthn PublicKeyCredential JSON(WebAuthn §5.1, base64url 필드)을
 * 원시 바이트 구성요소로 분해. 검증은 하지 않고 추출만 한다.
 */
public final class CredentialJsonParser {

    private final ObjectMapper mapper;
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    /** credentialJson은 attestationObject(보통 수 KB) 포함이라 ClientDataValidator(16KB)보다
     *  큰 상한. 정상 등록 응답의 수배 여유 (codex P2, Task 6과 일관된 ingress 방어). */
    private static final int MAX_CREDENTIAL_JSON_CHARS = 64 * 1024;

    public CredentialJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParsedRegistration parseRegistration(String json) {
        JsonNode root = readTree(json);
        JsonNode resp = require(root, "response");
        Set<String> transports = new LinkedHashSet<>();
        JsonNode tr = resp.get("transports");
        if (tr != null && tr.isArray()) {
            for (JsonNode t : tr) if (t.isTextual()) transports.add(t.asText());
        }
        return new ParsedRegistration(
                decode(requireText(root, "rawId")),
                decode(requireText(resp, "clientDataJSON")),
                decode(requireText(resp, "attestationObject")),
                transports);
    }

    public ParsedAuthentication parseAuthentication(String json) {
        JsonNode root = readTree(json);
        JsonNode resp = require(root, "response");
        JsonNode uh = resp.get("userHandle");
        byte[] userHandle = null;
        if (uh != null && !uh.isNull()) {
            if (!uh.isTextual()) {
                throw new IllegalArgumentException("credential JSON userHandle must be a string");
            }
            if (!uh.asText().isEmpty()) {
                userHandle = decode(uh.asText());
            }
        }
        return new ParsedAuthentication(
                decode(requireText(root, "rawId")),
                decode(requireText(resp, "clientDataJSON")),
                decode(requireText(resp, "authenticatorData")),
                decode(requireText(resp, "signature")),
                userHandle);
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("credential JSON empty");
        }
        if (json.length() > MAX_CREDENTIAL_JSON_CHARS) {
            throw new IllegalArgumentException("credential JSON too large: " + json.length());
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("credential JSON parse failed", e);
        }
    }

    private static JsonNode require(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("credential JSON missing field: " + field);
        }
        return n;
    }

    /** 필수 텍스트(base64url) 필드 — 미존재/null/비-문자열이면 거부 (coercion 차단). */
    private static String requireText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            throw new IllegalArgumentException("credential JSON missing/non-string field: " + field);
        }
        return n.asText();
    }

    private static byte[] decode(String b64url) {
        try {
            return B64URL.decode(b64url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("credential JSON field not base64url");
        }
    }
}
