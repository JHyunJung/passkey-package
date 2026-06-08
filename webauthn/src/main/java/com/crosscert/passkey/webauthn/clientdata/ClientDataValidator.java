package com.crosscert.passkey.webauthn.clientdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Set;

/**
 * clientDataJSON 파싱·검증 (WebAuthn §7.1 step 7~11 / §7.2 step 11~15).
 * challenge는 상수시간 바이트 비교, origin은 화이트리스트 정확 일치.
 */
public final class ClientDataValidator {

    /** clientDataJSON은 type/challenge/origin/crossOrigin뿐이라 작다.
     *  거대 입력 파싱 DoS를 막는 보수적 상한 (codex P2). */
    private static final int MAX_CLIENT_DATA_BYTES = 16 * 1024;

    private final ObjectMapper mapper;

    public ClientDataValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param expectedType "webauthn.create"(등록) 또는 "webauthn.get"(인증)
     * @param expectedChallenge 서버가 발급한 challenge 원본 바이트
     * @param allowedOrigins 테넌트 origin 화이트리스트
     */
    public CollectedClientData validate(byte[] clientDataJson, String expectedType,
                                        byte[] expectedChallenge, Set<String> allowedOrigins) {
        if (clientDataJson == null || clientDataJson.length == 0) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientDataJSON empty");
        }
        if (clientDataJson.length > MAX_CLIENT_DATA_BYTES) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientDataJSON too large: " + clientDataJson.length);
        }
        JsonNode root;
        try {
            root = mapper.readTree(clientDataJson);
        } catch (Exception e) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientDataJSON parse failed");
        }
        String type = text(root, "type");
        String challengeB64 = text(root, "challenge");
        String origin = text(root, "origin");
        boolean crossOrigin = false;
        JsonNode crossOriginNode = root.get("crossOrigin");
        if (crossOriginNode != null && !crossOriginNode.isNull()) {
            if (!crossOriginNode.isBoolean()) {
                throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                        "clientData.crossOrigin must be a boolean");
            }
            crossOrigin = crossOriginNode.asBoolean();
        }

        if (!expectedType.equals(type)) {
            throw new ClientDataException(ClientDataException.Reason.TYPE_MISMATCH,
                    "clientData.type expected " + expectedType + " got " + type);
        }

        byte[] gotChallenge;
        try {
            gotChallenge = Base64.getUrlDecoder().decode(challengeB64);
        } catch (RuntimeException e) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientData.challenge not base64url");
        }
        if (!java.security.MessageDigest.isEqual(expectedChallenge, gotChallenge)) {
            throw new ClientDataException(ClientDataException.Reason.CHALLENGE_MISMATCH,
                    "clientData.challenge mismatch");
        }

        if (!allowedOrigins.contains(origin)) {
            throw new ClientDataException(ClientDataException.Reason.ORIGIN_MISMATCH,
                    "clientData.origin not allowed: " + origin);
        }

        return new CollectedClientData(type, challengeB64, origin, crossOrigin);
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual()) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientData missing field: " + field);
        }
        return n.asText();
    }
}
