package com.crosscert.passkey.admin.scheduler;

import com.crosscert.passkey.core.entity.SchedulerLease;
import com.crosscert.passkey.core.repository.SchedulerLeaseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-backed lease for one-leader-at-a-time scheduler jobs
 * (Phase 3 T8). Each lease is identified by a name string (e.g.
 * "mds-sync", "key-expiration") and held by a host+process id.
 *
 * <p>Acquisition:
 *   - INSERT new row if name absent (catching duplicate-key race).
 *   - UPDATE row via PESSIMISTIC_WRITE lock if held by us OR expired.
 *   - If lock is obtained but another holder is still active → return false.
 *
 * <p>Release is atomic: {@code DELETE … WHERE name=? AND holder=?} so a
 * stale releaser cannot evict a row that another node has since acquired.
 *
 * <p>Phase 6 T11: SchedulerLease PK is now UUID; name is UNIQUE.
 * Public signature of tryAcquire/release is unchanged.
 */
@Service
public class SchedulerLeaseService {

    private final SchedulerLeaseRepository repo;
    private final Clock clock;

    public SchedulerLeaseService(SchedulerLeaseRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional
    public boolean tryAcquire(String name, String holder, Duration ttl) {
        Instant now = clock.instant();
        Instant newExpiry = now.plus(ttl);

        // Pessimistic-write lock on the existing row serializes concurrent callers.
        Optional<SchedulerLease> existing = repo.findByNameForUpdate(name);

        if (existing.isEmpty()) {
            // No row yet — try to INSERT.  Another node may race us here;
            // if they win the unique constraint throws and we return false.
            try {
                SchedulerLease fresh = new SchedulerLease(UUID.randomUUID(), name, holder, newExpiry);
                repo.save(fresh);
                return true;
            } catch (DataIntegrityViolationException e) {
                // Concurrent INSERT won the race — we did not acquire the lease.
                return false;
            }
        }

        SchedulerLease lease = existing.get();
        // Allow takeover if the lease belongs to us or has expired.
        if (!holder.equals(lease.getHolder()) && !lease.getExpiresAt().isBefore(now)) {
            return false;  // another holder is still active
        }
        lease.setHolder(holder);
        lease.setExpiresAt(newExpiry);
        repo.save(lease);
        return true;
    }

    /**
     * Best-effort release. The predicate {@code WHERE name=:name AND holder=:holder}
     * is evaluated atomically in the DB, so a stale releaser cannot evict a row that
     * another instance has since (re-)acquired.
     */
    @Transactional
    public void release(String name, String holder) {
        repo.deleteByNameAndHolder(name, holder);
    }
}
