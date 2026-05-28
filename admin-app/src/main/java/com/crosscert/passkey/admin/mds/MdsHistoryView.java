package com.crosscert.passkey.admin.mds;

import java.time.Instant;

public record MdsHistoryView(
        long id,
        String startedAt,
        String finishedAt,
        Long version,
        String status,
        String changeSummary,
        Integer durationMs,
        String errorMessage
) {
    public static MdsHistoryView of(
            long id, Instant startedAt, Instant finishedAt, Long version,
            String status, String changeSummary, Integer durationMs, String errorMessage) {
        return new MdsHistoryView(
                id,
                startedAt == null ? null : startedAt.toString(),
                finishedAt == null ? null : finishedAt.toString(),
                version, status, changeSummary, durationMs, errorMessage);
    }
}
