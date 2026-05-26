package com.crosscert.passkey.admin.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Caller-supplied data for AuditLogService.append. Timestamps and
 * hashes are computed by the service itself.
 *
 * <p>payload is a Map&lt;String,Object&gt; — service writes it as canonical
 * JSON (keys sorted alphabetically) so the hash is reproducible.
 *
 * <p>actorId is nullable — null represents a system/unknown actor (e.g.,
 * unauthenticated login failures). The hash input uses empty string for
 * null actorId, matching the null-collapse convention for other optional fields.
 */
public record AuditAppendRequest(
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> payload
) {}
