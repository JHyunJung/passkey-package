package com.crosscert.passkey.admin.audit

import java.util.UUID

/**
 * Caller-supplied data for AuditLogService.append. Timestamps and
 * hashes are computed by the service itself.
 *
 * payload is a Map<String,Object> — service writes it as canonical
 * JSON (keys sorted alphabetically) so the hash is reproducible.
 *
 * actorId is nullable — null represents a system/unknown actor (e.g.,
 * unauthenticated login failures). The hash input uses empty string for
 * null actorId, matching the null-collapse convention for other optional fields.
 *
 * tenantId is nullable — null represents a platform-wide action
 * (ADMIN_LOGIN / signing-key rotation / MDS sync). Stored in
 * audit_log.tenant_id for filtering; NOT included in the SHA-256 hash
 * input (V10 chain compatibility — payload's 'tenantId' key already
 * provides tamper evidence).
 *
 * 모든 참조형 컴포넌트는 원 record 처럼 nullable(호출부·테스트가 null 을 넘긴다).
 */
data class AuditAppendRequest(
    val actorId: UUID?,
    val actorEmail: String?,
    val action: String?,
    val targetType: String?,
    val targetId: String?,
    val tenantId: UUID?,
    val payload: Map<String, Any?>?,
)
