package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.util.CryptoUtils
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.cfg.MapperBuilder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * base mapper 의 설정을 복사하고 ORDER_MAP_ENTRIES_BY_KEYS 만 켠 사본(원 Java 의
 * `baseMapper.rebuild().enable(...).build()` 와 동일 — 전역 mapper 는 건드리지 않는다).
 * AuditLogService·AuditChainVerifier·AuditChainBackfillService 가 공유한다.
 *
 * `rebuild()` 의 재귀 제네릭(`<M, B extends MapperBuilder<M, B>>`)은 Kotlin 이 추론하지 못해
 * 타입 인자를 명시하고 결과를 star-projection 으로 받는다(런타임은 erasure 라 동일 호출).
 */
internal fun canonicalCopy(baseMapper: ObjectMapper): ObjectMapper {
    val builder: MapperBuilder<*, *> = baseMapper.rebuild<ObjectMapper, Nothing>()
    return builder.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
}

/**
 * Appends tamper-evident rows to AUDIT_LOG via a SHA-256 hash chain.
 *
 * ## Concurrency design (closes T6 deferred P1)
 * `findLatestForUpdate()` uses a `SELECT … FOR UPDATE` with a
 * subquery (`id = (SELECT MAX(id))`). Under Oracle snapshot isolation
 * (SERIALIZABLE), two concurrent transactions can each read the same
 * predecessor and both INSERT successfully — snapshot isolation detects
 * UPDATE/DELETE conflicts via ORA-08177, but not INSERT-only "write skew" on
 * disjoint new rows. SERIALIZABLE alone does NOT prevent the chain from
 * forking.
 *
 * Resolution: `READ_COMMITTED` (Oracle default) plus a singleton
 * lock row in `SCHEDULER_LEASE` named `AUDIT_CHAIN_LOCK`.
 * Before reading the chain head, [append] executes:
 * ```
 *   SELECT 1 FROM scheduler_lease WHERE name = 'AUDIT_CHAIN_LOCK' FOR UPDATE
 * ```
 * This row-level exclusive lock serializes all concurrent appenders within
 * Oracle. Only one transaction can hold the lock at a time; the second blocks
 * until the first commits. The lock is released automatically at commit.
 * The sentinel row is seeded by V14.
 *
 * The repository's `@Lock(PESSIMISTIC_WRITE)` on
 * [AuditLogRepository.findLatestForUpdate] is belt-and-suspenders: it prevents
 * dirty reads on the latest hash even in edge cases where the lock-row SELECT
 * order changes.
 *
 * ## Hash input format
 * ```
 *   hex(prev_hash) | actor_id | action | target_type | target_id | iso_timestamp | canonical_json_payload
 * ```
 * All fields are UTF-8. `prev_hash_hex` is empty string when null (genesis
 * row). `target_type` and `target_id` collapse null → empty
 * string (matching Oracle VARCHAR2 null=empty-string semantics).  Field
 * values for controlled fields (`action`, `target_type`,
 * `target_id`) never contain `|` by convention, so pipe
 * delimiters unambiguously separate fields.  `actorEmail` is stored but
 * intentionally excluded from the hash input — `actorId` (UUID,
 * stable) is the trusted identity anchor; email can change. Null actor
 * (system/scheduler entries) collapses to empty string.
 *
 * ## Timestamp precision
 * The `OffsetDateTime` (KST, `+09:00`) is truncated to
 * microseconds before hashing and before storing in the entity.  Oracle
 * `TIMESTAMP WITH TIME ZONE` defaults to 6 fractional-second digits
 * (microsecond precision).  If we hashed nanoseconds but stored only
 * microseconds, the chain verifier would recompute a different hash from the
 * DB-read value.  Truncation to MICROS prevents this round-trip mismatch.
 * The `+09:00` offset is part of the hashed `toString()` form,
 * so the verifier must recompute from the same OffsetDateTime to match.
 */
@Service
class AuditLogService(
    private val repo: AuditLogRepository,
    private val em: EntityManager,
    baseMapper: ObjectMapper,
    private val clock: Clock,
    dbSchema: DbSchemaProperties,
) {

    // 원 Java 처럼 생성 시 null 검증을 하지 않는다(DbSchemaProperties 가 검증; 슬라이스 테스트 mock 은 null 을 준다).
    private val schema: String? = dbSchema.schema()

    // Defensive copy so the global Jackson mapper's settings are
    // untouched. ORDER_MAP_ENTRIES_BY_KEYS makes the serialized
    // form deterministic, which is what makes the hash reproducible.
    private val canonical: ObjectMapper = canonicalCopy(baseMapper)

    /**
     * Append one row to the audit log, computing prev_hash + hash via
     * SHA-256 over canonical JSON.
     *
     * Acquires an exclusive row-level lock on the `AUDIT_CHAIN_LOCK`
     * sentinel row before reading the chain head. This serializes concurrent
     * appenders — the second caller blocks on the lock until the first
     * commits, then reads the now-latest row as its predecessor.
     *
     * See class-level KDoc for the full concurrency and hash-format details.
     */
    @Transactional
    fun append(req: AuditAppendRequest): AuditLog? {
        // Acquire the chain serialization lock before reading the head.
        // Oracle's FOR UPDATE blocks the second concurrent appender until
        // this transaction commits.
        // Schema-qualified because this is a native SQL query — Hibernate's
        // default_schema does not rewrite ad-hoc native table
        // references, so we must qualify explicitly to avoid ORA-00942 when
        // the DB session is connected as PSK_APP_ADMIN_USER.
        em.createNativeQuery(
            "SELECT 1 FROM " + schema + ".scheduler_lease WHERE name = :n FOR UPDATE",
        )
            .setParameter("n", CHAIN_LOCK_NAME)
            .singleResult

        val latest = repo.findLatestForUpdate()
        val prevHash: ByteArray? = latest.map { it.hash }.orElse(null)
        // Phase B — per-tenant chain head (lock 없이 읽어도 됨: global chain lock 이 직렬화 보장)
        val latestTenantList = repo.findLatestByTenant(req.tenantId, PageRequest.of(0, 1))
        val tenantPrev: ByteArray? = if (latestTenantList.isEmpty()) null else latestTenantList[0].tenantHash
        // Truncate to micros so the hashed timestamp matches what Oracle
        // stores and what the verifier reads back. Oracle TIMESTAMP
        // WITH TIME ZONE defaults to 6 fractional digits (microseconds).
        // OffsetDateTime.now(clock) carries the KST (+09:00) offset so the
        // hashed toString() and the stored value share one wall-clock form.
        val now = nextChainTime(
            OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS),
            latest.map { it.createdAt }.orElse(null),
        )
        val payloadJson = serialize(req.payload)
        val hash = computeHash(prevHash, req, payloadJson, now)
        // Phase B — tenant-scoped chain hash (동일 알고리즘, tenantPrev 만 다름)
        val tenantHash = computeHash(tenantPrev, req, payloadJson, now)
        val row = AuditLog(
            prevHash, hash, req.actorId, req.actorEmail,
            req.action,
            req.targetType, req.targetId,
            req.tenantId,
            tenantPrev, tenantHash,
            payloadJson, now,
        )
        return repo.save(row)
    }

    private fun serialize(payload: Map<String, Any?>?): String {
        try {
            // 원 Java 의 Map.of() 와 같은 빈 불변 맵(직렬화 결과 "{}").
            return canonical.writeValueAsString(payload ?: java.util.Map.of<String, Any>())
        } catch (e: Exception) {
            throw IllegalStateException("audit payload serialization failed", e)
        }
    }

    companion object {

        const val CHAIN_LOCK_NAME = "AUDIT_CHAIN_LOCK"

        /**
         * 체인 순서 키(createdAt)를 직전 row 보다 반드시 뒤로 만든다. 시계가 뒤로 가거나(NTP 보정),
         * 여러 서버의 시계가 어긋나거나, 같은 마이크로초에 두 건이 들어와도 createdAt 순서 = append 순서가
         * 유지되어야 chain head 조회·검증(둘 다 createdAt 순서)이 같은 체인을 본다.
         * latest 는 global chain 의 최신 row 이고, 어떤 tenant chain 의 최신 row 보다도 늦거나 같다.
         * 보정한 값도 candidate 의 offset(KST)으로 표현해 해시 입력 형태를 바꾸지 않는다.
         *
         * 원 Java 는 package-private static — Java 테스트가 호출하므로 public(@JvmStatic).
         */
        @JvmStatic
        fun nextChainTime(candidate: OffsetDateTime, latest: OffsetDateTime?): OffsetDateTime {
            if (latest == null || candidate.isAfter(latest)) return candidate
            return latest.toInstant().plus(1, ChronoUnit.MICROS).atOffset(candidate.offset)
                .truncatedTo(ChronoUnit.MICROS)
        }

        /**
         * Hash input format (all UTF-8, pipe-delimited):
         * ```
         *   prev_hash_hex | actor_id | action | target_type | target_id | timestamp_iso | payload_canonical
         * ```
         * `prev_hash_hex` is empty string when prev is null. The pipe
         * separators disambiguate boundaries so a payload containing `"|"`
         * cannot collide with a different field split. Action/targetType/targetId
         * are controlled strings (e.g. "TENANT_CREATE") that must not contain "|".
         *
         * 원 Java 는 package-private static(hash verifier(T9)·Java 테스트 재사용) — public(@JvmStatic).
         * action·payloadJson 이 null 이면 원 Java StringBuilder 처럼 "null" 이 들어간다.
         */
        @JvmStatic
        fun computeHash(
            prevHash: ByteArray?,
            req: AuditAppendRequest,
            payloadJson: String?,
            now: OffsetDateTime,
        ): ByteArray {
            val input = StringBuilder()
            input.append(if (prevHash == null) "" else CryptoUtils.hex(prevHash))
            input.append('|')
            // Null actorId (system/unknown actor) collapses to empty string,
            // matching the null-collapse convention for targetType/targetId.
            input.append(if (req.actorId == null) "" else req.actorId.toString())
            input.append('|')
            input.append(req.action)
            input.append('|')
            input.append(if (req.targetType == null) "" else req.targetType)
            input.append('|')
            input.append(if (req.targetId == null) "" else req.targetId)
            input.append('|')
            // hash 안정성은 동일 OffsetDateTime 필드값(동일 MICROS 정밀도 + +09:00 offset)에
            // 의존한다. 다른 정밀도/offset 값을 넣으면 안 됨 — verifier 는 row.getCreatedAt()
            // (저장된 그 값)을 그대로 다시 넣어야 stored==recomputed 가 성립한다.
            input.append(now.toString())
            input.append('|')
            input.append(payloadJson)
            try {
                return MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().toByteArray(StandardCharsets.UTF_8))
            } catch (e: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 unavailable", e)
            }
        }
    }
}
