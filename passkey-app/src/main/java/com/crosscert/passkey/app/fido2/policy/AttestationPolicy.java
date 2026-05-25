package com.crosscert.passkey.app.fido2.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * Parsed form of a tenant's {@code attestation_policy} JSON column.
 *
 * <p>Failure model: null/blank JSON falls back to a conservative
 * default (accept all 6 attestation formats + require UV + MDS not
 * required) — appropriate when a tenant has not customized policy.
 * Malformed JSON or invalid policy shapes throw IllegalArgumentException
 * so the registration ceremony fails closed rather than silently
 * widening the policy.
 */
public record AttestationPolicy(
        Set<String> acceptedFormats,
        boolean requireUserVerification,
        boolean mdsRequired
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SAFE_DEFAULT_FORMATS = Set.of(
            "none","packed","android-key","android-safetynet","fido-u2f","apple","tpm");

    /** Compact constructor: defensively copy the formats set so callers cannot mutate it. */
    public AttestationPolicy {
        acceptedFormats = Set.copyOf(acceptedFormats);
    }

    public static AttestationPolicy fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new AttestationPolicy(SAFE_DEFAULT_FORMATS, true, false);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "attestation_policy JSON could not be parsed: " + e.getMessage(), e);
        }

        // Fail closed on invalid schema: if acceptedFormats is present
        // but not an array, that's a misconfiguration the operator
        // should fix — not a signal to silently widen the policy.
        JsonNode fmtArray = root.path("acceptedFormats");
        if (!fmtArray.isMissingNode() && !fmtArray.isArray()) {
            throw new IllegalArgumentException(
                    "attestation_policy JSON 'acceptedFormats' must be an array");
        }

        Set<String> formats = new HashSet<>();
        if (fmtArray.isArray()) {
            for (JsonNode n : fmtArray) {
                if (!n.isTextual()) {
                    throw new IllegalArgumentException(
                            "attestation_policy JSON 'acceptedFormats' entries must be strings");
                }
                formats.add(n.asText());
            }
            if (formats.isEmpty()) {
                throw new IllegalArgumentException(
                        "attestation_policy JSON 'acceptedFormats' must not be empty");
            }
        } else {
            // acceptedFormats missing entirely — fall back to safe defaults.
            formats.addAll(SAFE_DEFAULT_FORMATS);
        }

        boolean uv = root.path("requireUserVerification").asBoolean(true);
        boolean mds = root.path("mdsRequired").asBoolean(false);
        return new AttestationPolicy(formats, uv, mds);
    }
}
