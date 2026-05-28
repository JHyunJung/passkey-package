package com.crosscert.passkey.admin.mds;

public record MdsStatusView(
        long version,
        String nextUpdate,
        String fetchedAt,
        int trustAnchorCount,
        String trustMode,
        SuccessRate successRate30d
) {
    public record SuccessRate(int ok, int total) {}
}
