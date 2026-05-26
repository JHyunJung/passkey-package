package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Appends tamper-evident rows to AUDIT_LOG via a SHA-256 hash chain.
 *
 * <h2>Concurrency design (closes T6 deferred P1)</h2>
 * <p>{@code findLatestForUpdate()} uses a {@code SELECT … FOR UPDATE} with a
 * subquery ({@code id = (SELECT MAX(id))}). Under Oracle snapshot isolation
 * (SERIALIZABLE), two concurrent transactions can each read the same
 * predecessor and both INSERT successfully — snapshot isolation detects
 * UPDATE/DELETE conflicts via ORA-08177, but not INSERT-only "write skew" on
 * disjoint new rows. SERIALIZABLE alone does NOT prevent the chain from
 * forking.
 *
 * <p>Resolution: {@code READ_COMMITTED} (Oracle default) plus a singleton
 * lock row in {@code SCHEDULER_LEASE} named {@code AUDIT_CHAIN_LOCK}.
 * Before reading the chain head, {@link #append(AuditAppendRequest)} executes:
 * <pre>
 *   SELECT 1 FROM scheduler_lease WHERE name = 'AUDIT_CHAIN_LOCK' FOR UPDATE
 * </pre>
 * This row-level exclusive lock serializes all concurrent appenders within
 * Oracle. Only one transaction can hold the lock at a time; the second blocks
 * until the first commits. The lock is released automatically at commit.
 * The sentinel row is seeded by V14.
 *
 * <p>The repository's {@code @Lock(PESSIMISTIC_WRITE)} on
 * {@link com.crosscert.passkey.core.repository.AuditLogRepository#findLatestForUpdate()}
 * is belt-and-suspenders: it prevents dirty reads on the latest hash even in
 * edge cases where the lock-row SELECT order changes.
 *
 * <h2>Hash input format</h2>
 * <pre>
 *   hex(prev_hash) | actor_id | action | target_type | target_id | iso_timestamp | canonical_json_payload
 * </pre>
 * All fields are UTF-8. {@code prev_hash_hex} is empty string when null (genesis
 * row). {@code target_type} and {@code target_id} collapse null → empty
 * string (matching Oracle VARCHAR2 null=empty-string semantics).  Field
 * values for controlled fields ({@code action}, {@code target_type},
 * {@code target_id}) never contain {@code |} by convention, so pipe
 * delimiters unambiguously separate fields.  {@code actorEmail} is stored but
 * intentionally excluded from the hash input — {@code actorId} (UUID,
 * stable) is the trusted identity anchor; email can change. Null actor
 * (system/scheduler entries) collapses to empty string.
 *
 * <h2>Timestamp precision</h2>
 * <p>The {@code Instant} is truncated to microseconds before hashing and
 * before storing in the entity.  Oracle {@code TIMESTAMP WITH TIME ZONE}
 * defaults to 6 fractional-second digits (microsecond precision).  If we
 * hashed nanoseconds but stored only microseconds, the chain verifier (T9)
 * would recompute a different hash from the DB-read value.  Truncation to
 * MICROS prevents this round-trip mismatch.
 */
@Service
public class AuditLogService {

    static final String CHAIN_LOCK_NAME = "AUDIT_CHAIN_LOCK";

    private final AuditLogRepository repo;
    private final EntityManager em;
    private final ObjectMapper canonical;
    private final Clock clock;

    public AuditLogService(AuditLogRepository repo,
                           EntityManager em,
                           ObjectMapper baseMapper,
                           Clock clock) {
        this.repo = repo;
        this.em = em;
        // Defensive copy so the global Jackson mapper's settings are
        // untouched. ORDER_MAP_ENTRIES_BY_KEYS makes the serialized
        // form deterministic, which is what makes the hash reproducible.
        this.canonical = baseMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
    }

    /**
     * Append one row to the audit log, computing prev_hash + hash via
     * SHA-256 over canonical JSON.
     *
     * <p>Acquires an exclusive row-level lock on the {@code AUDIT_CHAIN_LOCK}
     * sentinel row before reading the chain head. This serializes concurrent
     * appenders — the second caller blocks on the lock until the first
     * commits, then reads the now-latest row as its predecessor.
     *
     * <p>See class-level Javadoc for the full concurrency and hash-format details.
     */
    @Transactional
    public AuditLog append(AuditAppendRequest req) {
        // Acquire the chain serialization lock before reading the head.
        // Oracle's FOR UPDATE blocks the second concurrent appender until
        // this transaction commits.
        // Schema-qualified because this is a native SQL query — Hibernate's
        // default_schema (APP_OWNER) does not rewrite ad-hoc native table
        // references, so we must qualify explicitly to avoid ORA-00942 when
        // the DB session is connected as APP_ADMIN_USER.
        em.createNativeQuery(
                "SELECT 1 FROM APP_OWNER.scheduler_lease WHERE name = :n FOR UPDATE")
            .setParameter("n", CHAIN_LOCK_NAME)
            .getSingleResult();

        byte[] prevHash = repo.findLatestForUpdate()
                .map(AuditLog::getHash)
                .orElse(null);
        // Truncate to micros so the hashed timestamp matches what Oracle
        // stores and what the verifier (T9) reads back. Oracle TIMESTAMP
        // WITH TIME ZONE defaults to 6 fractional digits (microseconds).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String payloadJson = serialize(req.payload());
        byte[] hash = computeHash(prevHash, req, payloadJson, now);
        AuditLog row = new AuditLog(
                prevHash, hash, req.actorId(), req.actorEmail(),
                req.action(),
                req.targetType(), req.targetId(),
                payloadJson, now);
        return repo.save(row);
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return canonical.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("audit payload serialization failed", e);
        }
    }

    /**
     * Hash input format (all UTF-8, pipe-delimited):
     * <pre>
     *   prev_hash_hex | actor_id | action | target_type | target_id | timestamp_iso | payload_canonical
     * </pre>
     * {@code prev_hash_hex} is empty string when prev is null. The pipe
     * separators disambiguate boundaries so a payload containing {@code "|"}
     * cannot collide with a different field split. Action/targetType/targetId
     * are controlled strings (e.g. "TENANT_CREATE") that must not contain "|".
     *
     * <p>Package-private for hash verifier (T9) reuse.
     */
    static byte[] computeHash(byte[] prevHash, AuditAppendRequest req,
                              String payloadJson, Instant now) {
        StringBuilder input = new StringBuilder();
        input.append(prevHash == null ? "" : hex(prevHash));
        input.append('|');
        // Null actorId (system/unknown actor) collapses to empty string,
        // matching the null-collapse convention for targetType/targetId.
        input.append(req.actorId() == null ? "" : req.actorId().toString());
        input.append('|');
        input.append(req.action());
        input.append('|');
        input.append(req.targetType() == null ? "" : req.targetType());
        input.append('|');
        input.append(req.targetId() == null ? "" : req.targetId());
        input.append('|');
        input.append(now.toString());
        input.append('|');
        input.append(payloadJson);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
