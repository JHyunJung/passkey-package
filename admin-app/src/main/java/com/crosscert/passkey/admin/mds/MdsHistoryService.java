package com.crosscert.passkey.admin.mds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Reads + writes the {@code APP_OWNER.mds_sync_history} append-only log
 * (V34 migration). Uses {@link JdbcTemplate} with an explicit {@code APP_OWNER.}
 * schema prefix — matching the existing pattern in {@link MdsBlobStore} and
 * {@link MdsAdminController}.
 *
 * <p>Note: the Hibernate {@code {h-schema}} placeholder is <b>not</b> substituted
 * in raw JdbcTemplate SQL (only inside Hibernate-managed native queries), so we
 * hard-code the schema literally, same as the rest of this package.
 */
@Service
public class MdsHistoryService {

    private final JdbcTemplate jdbc;

    public MdsHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<MdsHistoryView> recent(int limit) {
        int capped = Math.max(1, Math.min(50, limit));
        return jdbc.query(
                "SELECT id, started_at, finished_at, version, status, change_summary, duration_ms, error_message "
              + "FROM APP_OWNER.mds_sync_history "
              + "ORDER BY started_at DESC FETCH FIRST ? ROWS ONLY",
                (rs, n) -> MdsHistoryView.of(
                        rs.getLong("id"),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        rs.getObject("version", Long.class),
                        rs.getString("status"),
                        rs.getString("change_summary"),
                        rs.getObject("duration_ms", Integer.class),
                        rs.getString("error_message")
                ),
                capped);
    }

    @Transactional
    public void append(Instant startedAt, Instant finishedAt, Long version, String status,
                       String changeSummary, Integer durationMs, String errorMessage) {
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        jdbc.update(
                "INSERT INTO APP_OWNER.mds_sync_history "
              + "(id, started_at, finished_at, version, status, change_summary, duration_ms, error_message) "
              + "VALUES (APP_OWNER.mds_sync_history_seq.NEXTVAL, ?, ?, ?, ?, ?, ?, ?)",
                java.sql.Timestamp.from(startedAt),
                finishedAt == null ? null : java.sql.Timestamp.from(finishedAt),
                version, status, changeSummary, durationMs, errorMessage);
    }

    @Transactional(readOnly = true)
    public int successRate30dCountOk() {
        Instant since = Instant.now().minus(Duration.ofDays(30));
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.mds_sync_history WHERE status='SYNCED' AND started_at >= ?",
                Integer.class,
                java.sql.Timestamp.from(since));
        return n == null ? 0 : n;
    }

    @Transactional(readOnly = true)
    public int successRate30dCountTotal() {
        Instant since = Instant.now().minus(Duration.ofDays(30));
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.mds_sync_history WHERE started_at >= ?",
                Integer.class,
                java.sql.Timestamp.from(since));
        return n == null ? 0 : n;
    }
}
