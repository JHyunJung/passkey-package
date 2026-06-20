package com.crosscert.passkey.admin.mds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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

    /**
     * P1-4 retention: started_at 이 cutoff 이전인 sync 이력 삭제. 삭제 건수 반환.
     * recent()/append() 와 동일하게 APP_OWNER. 스키마 prefix 명시(JdbcTemplate raw SQL).
     *
     * <p>Batched: ROWNUM 으로 한 트랜잭션 삭제 행 수를 batchSize 로 캡한다(첫 prod 실행 시
     * 수개월 누적분 unbounded DELETE 방지). 호출측(RetentionPurgeService)이 0 행 될 때까지 반복.
     */
    @Transactional
    public int purgeStartedBefore(OffsetDateTime cutoff, int batchSize) {
        // JDBC raw path — bind the absolute instant as a Timestamp (mds_sync_history
        // is JdbcTemplate-managed, not a Hibernate entity). The caller now supplies a
        // KST OffsetDateTime; .toInstant() preserves the exact instant for the compare.
        return jdbc.update(
                "DELETE FROM APP_OWNER.mds_sync_history WHERE started_at < ? AND ROWNUM <= ?",
                java.sql.Timestamp.from(cutoff.toInstant()), batchSize);
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
