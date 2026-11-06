package com.crosscert.passkey.app.security

import com.crosscert.passkey.core.config.DbSchemaProperties
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

private val log = LoggerFactory.getLogger(ApiKeyLookupService::class.java)

/**
 * Resolves API keys for the two operations the auth filter needs, using
 * direct [JdbcTemplate] SQL (no PL/SQL package, no VPD).
 *
 * - [findByPrefix] — native SELECT by the globally-unique `key_prefix`.
 *   Authentication runs BEFORE any tenant context is set, so the lookup must
 *   work without one. A JdbcTemplate query does NOT pass through Hibernate's
 *   `@Filter` (the app-level tenant isolation mechanism), so it sees every
 *   tenant's rows — exactly what is needed to resolve a tenant-agnostic prefix.
 *   `key_prefix` is UNIQUE (V7), so at most one row matches.
 * - [touchLastUsed] — native UPDATE of `last_used_at` after successful
 *   authentication. The UPDATE is constrained by both `id` AND `tenant_id` so a
 *   missing context can never be treated as authority over another tenant's row.
 *
 * Previously these two calls went through a definer-rights PL/SQL package
 * (`PSK_APP_OWNER.api_key_lookup_pkg`) whose only purpose was to bypass the
 * Oracle VPD predicate (`tenant_id = SYS_CONTEXT(...)`) that would otherwise
 * return zero rows when no context was set. With VPD removed the bypass is
 * unnecessary: a plain SQL query already sees all rows, so the package
 * indirection is gone. Tenant isolation for the request path is provided by the
 * app-level Hibernate `@Filter`; this auth lookup runs before that filter
 * applies, by design.
 *
 * The [ApiKeyAuthRow] type and the RAW(16) ↔ UUID helpers are unchanged — the
 * public method signatures are stable, so `ApiKeyAuthFilter` needs no changes.
 */
@Service
class ApiKeyLookupService(dataSource: DataSource, dbSchema: DbSchemaProperties) {

    private val jdbc = JdbcTemplate(dataSource)
    private val schema = dbSchema.schema()

    fun findByPrefix(keyPrefix: String): Optional<ApiKeyAuthRow> {
        // 인증 시점에는 아직 tenant context 가 없다. key_prefix 는 전역 UNIQUE(V7)
        // 이므로 tenant 없이 1행 룩업이 정확하다. JdbcTemplate 의 직접 SQL 은
        // Hibernate @Filter(ORM 레벨) 를 거치지 않으므로 모든 테넌트의 행을 본다.
        // 테이블은 PSK_APP_OWNER.api_key 로 명시 — hibernate.default_schema 는 ORM 에만
        // 적용되고 raw JDBC 의 미수식 이름은 로그인 사용자 스키마로 해석되기 때문이다
        // (AuditLogService 의 ad-hoc native 쿼리와 동일 규칙).
        val row = jdbc.query(
            "SELECT id, tenant_id, key_hash, expires_at, revoked_at FROM $schema.api_key WHERE key_prefix = ?",
            PreparedStatementSetter { ps -> ps.setString(1, keyPrefix) },
            ResultSetExtractor { rs ->
                if (!rs.next()) return@ResultSetExtractor null
                ApiKeyAuthRow(
                    id = toUuidOrFail(rs.getBytes("id"), "id"),
                    tenantId = toUuidOrFail(rs.getBytes("tenant_id"), "tenant_id"),
                    // V1 스키마의 key_hash 는 NOT NULL.
                    keyHash = rs.getString("key_hash"),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                )
            },
        )
        return Optional.ofNullable(row)
    }

    fun touchLastUsed(apiKeyId: UUID, tenantId: UUID, now: Instant) {
        // Best-effort: touch 실패가 유효 인증을 500 으로 만들면 안 된다.
        // tenant_id 를 WHERE 에 명시 — context 부재를 인가로 취급하지 않는다(타 테넌트
        // 행 변조 차단). VPD 제거 후에도 격리 의미를 명시 검증으로 유지.
        try {
            jdbc.update(
                "UPDATE $schema.api_key SET last_used_at = ? WHERE id = ? AND tenant_id = ?",
                PreparedStatementSetter { ps ->
                    ps.setTimestamp(1, Timestamp.from(now))
                    ps.setBytes(2, apiKeyId.toRawBytes())
                    ps.setBytes(3, tenantId.toRawBytes())
                },
            )
        } catch (e: DataAccessException) {
            // last_used_at 가 한 트랜잭션만큼 stale 해질 뿐이다. 지속 실패(= stale-key
            // 탐지 저하)는 보여야 하므로 로그만 남기고 인증은 성공으로 통과시킨다.
            log.warn("api-key touch_last_used failed (best-effort): {}", e.toString())
        }
    }

    /** Minimal row data the filter needs from the api_key table. */
    data class ApiKeyAuthRow(
        val id: UUID,
        val tenantId: UUID,
        val keyHash: String,
        val expiresAt: Instant?,
        val revokedAt: Instant?,
    ) {
        fun isActive(now: Instant): Boolean =
            revokedAt == null && (expiresAt == null || expiresAt.isAfter(now))
    }
}

/**
 * Converts a 16-byte Oracle RAW value to a UUID.
 * Throws if bytes is null or not exactly 16 bytes — fail-closed to prevent
 * a malformed row from proceeding with null tenant context.
 */
private fun toUuidOrFail(bytes: ByteArray?, columnName: String): UUID {
    check(bytes != null && bytes.size == 16) {
        "api_key returned malformed RAW for $columnName: expected 16 bytes, got ${bytes?.size ?: "null"}"
    }
    val bb = ByteBuffer.wrap(bytes)
    return UUID(bb.long, bb.long)
}

/** Converts a UUID to a 16-byte array suitable for Oracle RAW(16) binding. */
private fun UUID.toRawBytes(): ByteArray =
    ByteBuffer.allocate(16)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()
