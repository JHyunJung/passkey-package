package com.crosscert.passkey.core.logging;

/**
 * Standardized redaction helpers for log output. Use these in all
 * log.info/warn/error calls when emitting user-supplied or secret-adjacent
 * values. Direct raw value emission is forbidden by spec § 7.
 */
public final class LogRedact {

    private LogRedact() {}

    /**
     * API key header value → first 11 chars only (the "pk_xxxxxxxx" prefix).
     * Anything ≤ 11 chars → "&lt;redacted&gt;" (input that short cannot be safely
     * truncated without leaking the entire value).
     */
    public static String apiKey(String raw) {
        if (raw == null || raw.length() <= 11) return "<redacted>";
        return raw.substring(0, 11);
    }

    /**
     * Email → first char + "***" + domain. "alice@x.com" → "a***@x.com".
     * No '@' or empty local → "&lt;redacted&gt;".
     */
    public static String email(String raw) {
        if (raw == null) return "";
        int at = raw.indexOf('@');
        if (at < 1) return "<redacted>";
        return raw.charAt(0) + "***" + raw.substring(at);
    }

    /**
     * Returns the last n chars of raw, or "***" if raw is too short to
     * usefully truncate (≤ n chars).
     */
    public static String idTail(String raw, int n) {
        if (raw == null) return "";
        if (raw.length() <= n) return "***";
        return raw.substring(raw.length() - n);
    }

    /**
     * JWT or long opaque token → first 5 chars + redact marker.
     * Anything shorter than 8 chars → "&lt;redacted&gt;".
     */
    public static String token(String raw) {
        if (raw == null || raw.length() < 8) return "<redacted>";
        return raw.substring(0, 5) + "…<redacted>";
    }
}
