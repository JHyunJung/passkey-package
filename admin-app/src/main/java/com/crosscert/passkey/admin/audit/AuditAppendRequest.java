package com.crosscert.passkey.admin.audit;

import java.util.Map;

/**
 * Caller-supplied data for AuditLogService.append. Timestamps and
 * hashes are computed by the service itself.
 *
 * <p>payload is a Map&lt;String,Object&gt; — service writes it as canonical
 * JSON (keys sorted alphabetically) so the hash is reproducible.
 */
public record AuditAppendRequest(
        long actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> payload
) {}
