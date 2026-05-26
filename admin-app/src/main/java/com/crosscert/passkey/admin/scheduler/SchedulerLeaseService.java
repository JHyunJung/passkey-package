package com.crosscert.passkey.admin.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Database-backed lease for one-leader-at-a-time scheduler jobs
 * (Phase 3 T8). Each lease is identified by a name string (e.g.
 * "mds-sync", "key-expiration") and held by a host+process id.
 *
 * <p>Acquisition:
 *   - INSERT new row if name absent.
 *   - UPDATE row if held by us OR expired (expires_at &lt; now()).
 *   - UPDATE matches 0 rows → another holder is active → return false.
 *
 * <p>Release is best-effort (TTL eventually expires anyway).
 */
@Service
public class SchedulerLeaseService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SchedulerLeaseService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public boolean tryAcquire(String name, String holder, Duration ttl) {
        Instant now = clock.instant();
        Instant newExpiry = now.plus(ttl);
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.scheduler_lease WHERE name=?",
                Integer.class, name);
        if (existing == null || existing == 0) {
            try {
                jdbc.update(
                        "INSERT INTO APP_OWNER.scheduler_lease (name, holder, expires_at) " +
                        "VALUES (?, ?, ?)",
                        name, holder, Timestamp.from(newExpiry));
                return true;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Race: another instance INSERTed between our SELECT and INSERT.
                // Fall through to the UPDATE-takeover path below.
            }
        }

        int updated = jdbc.update(
                "UPDATE APP_OWNER.scheduler_lease " +
                "SET holder=?, expires_at=? " +
                "WHERE name=? AND (holder=? OR expires_at < ?)",
                holder, Timestamp.from(newExpiry),
                name, holder, Timestamp.from(now));
        return updated > 0;
    }

    @Transactional
    public void release(String name, String holder) {
        jdbc.update(
                "DELETE FROM APP_OWNER.scheduler_lease WHERE name=? AND holder=?",
                name, holder);
    }
}
