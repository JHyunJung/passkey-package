package com.crosscert.passkey.admin.scheduler

import com.crosscert.passkey.core.entity.SchedulerLease
import com.crosscert.passkey.core.repository.SchedulerLeaseRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Database-backed lease for one-leader-at-a-time scheduler jobs.
 * Each lease is identified by a name string (e.g.
 * "mds-sync", "key-expiration") and held by a host+process id.
 *
 * Acquisition:
 * - INSERT new row if name absent (catching duplicate-key race).
 * - UPDATE row via PESSIMISTIC_WRITE lock if held by us OR expired.
 * - If lock is obtained but another holder is still active → return false.
 *
 * Release is atomic: `DELETE … WHERE name=? AND holder=?` so a
 * stale releaser cannot evict a row that another node has since acquired.
 *
 * SchedulerLease PK is a UUID; name is UNIQUE (uq_scheduler_lease_name — the INSERT race above relies on it).
 *
 * 사용처: RetentionPurgeJob("retention-purge")만 끝나면 [release] 한다. KeyExpirationJob("key-expiration", TTL 30초)·
 * MdsSchedulerService("mds-sync", TTL 5분)·KeyRotationService("key-rotation", TTL 30초)는 release 하지 않아
 * TTL 이 지나야 다른 holder 가 잡는다. 같은 holder 는 만료 전에도 다시 잡는데, KeyRotationService 는 호출마다
 * holder 가 새 UUID 라 그런 일이 없다. `AUDIT_CHAIN_LOCK` 행은 lease 가 아니라
 * AuditLogService·AuditChainBackfillService 가 FOR UPDATE 로 잡는 락 행이다.
 */
@Service
class SchedulerLeaseService(
    private val repo: SchedulerLeaseRepository,
    private val clock: Clock,
) {

    @Transactional
    fun tryAcquire(name: String, holder: String, ttl: Duration): Boolean {
        val now = OffsetDateTime.now(clock)
        val newExpiry = now.plus(ttl)

        // Pessimistic-write lock on the existing row serializes concurrent callers.
        val existing = repo.findByNameForUpdate(name)

        if (existing.isEmpty) {
            // No row yet — try to INSERT.  Another node may race us here;
            // if they win the unique constraint throws and we return false.
            return try {
                val fresh = SchedulerLease(UUID.randomUUID(), name, holder, newExpiry, now)
                repo.save(fresh)
                true
            } catch (e: DataIntegrityViolationException) {
                // Concurrent INSERT won the race — we did not acquire the lease.
                false
            }
        }

        val lease = existing.get()
        // Allow takeover if the lease belongs to us or has expired.
        if (holder != lease.holder && !lease.expiresAt.isBefore(now)) {
            return false // another holder is still active
        }
        lease.holder = holder
        lease.expiresAt = newExpiry
        repo.save(lease)
        return true
    }

    /**
     * Best-effort release. The predicate `WHERE name=:name AND holder=:holder`
     * is evaluated atomically in the DB, so a stale releaser cannot evict a row that
     * another instance has since (re-)acquired.
     */
    @Transactional
    fun release(name: String, holder: String) {
        repo.deleteByNameAndHolder(name, holder)
    }
}
