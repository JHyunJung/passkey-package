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
 *
 * <p>tenantId is nullable — null represents a platform-wide action
 * (ADMIN_LOGIN / signing-key rotation / MDS sync). Stored in
 * audit_log.tenant_id for filtering; NOT included in the SHA-256 hash
 * input (V10 chain compatibility — payload's 'tenantId' key already
 * provides tamper evidence).
 */
public record AuditAppendRequest(
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        UUID tenantId,
        Map<String, Object> payload
) {}
