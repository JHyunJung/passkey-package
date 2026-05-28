package com.crosscert.passkey.admin.tenant;

import java.util.List;
import java.util.Set;

public record WebauthnConfigDiff(
        Current current,
        Proposed proposed,
        List<FieldChange> changes,
        List<String> warnings   // RP_ID_CHANGED / ORIGIN_REMOVED / UV_RAISED_TO_REQUIRED / MDS_RAISED_TO_REQUIRED
) {
    public record Current(String rpId, String rpName, List<String> origins, List<String> formats,
                          boolean requireUserVerification, boolean mdsRequired) {}
    public record Proposed(String rpId, String rpName, List<String> origins, List<String> formats,
                            boolean requireUserVerification, boolean mdsRequired) {}
    public record FieldChange(String field, Object from, Object to, List<String> added, List<String> removed) {}
}
