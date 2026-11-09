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
 * Database-backed lease for one-leader-at-a-time scheduler jobs
 * (Phase 3 T8). Each lease is identified by a name string (e.g.
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
 * Phase 6 T11: SchedulerLease PK is now UUID; name is UNIQUE.
 * Public signature of tryAcquire/release is unchanged.
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
