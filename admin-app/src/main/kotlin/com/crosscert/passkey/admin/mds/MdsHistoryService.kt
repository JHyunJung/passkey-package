package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.core.config.DbSchemaProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Reads + writes the `<schema>.mds_sync_history` append-only log
 * (V34 migration). Uses [JdbcTemplate] with an explicit schema prefix
 * injected from [DbSchemaProperties] — matching the existing pattern in
 * [MdsBlobStore] and [MdsAdminController].
 *
 * Note: the Hibernate `{h-schema}` placeholder is **not** substituted
 * in raw JdbcTemplate SQL (only inside Hibernate-managed native queries), so the
 * schema comes from configuration, same as the rest of this package.
 */
@Service
class MdsHistoryService(
    private val jdbc: JdbcTemplate,
    dbSchema: DbSchemaProperties,
) {

    // 원 Java 처럼 생성 시 null 검증을 하지 않는다(DbSchemaProperties 가 검증; 슬라이스 테스트 mock 은 null 을 준다).
    private val schema: String? = dbSchema.schema()

    @Transactional(readOnly = true)
    fun recent(limit: Int): List<MdsHistoryView> {
        val capped = maxOf(1, minOf(50, limit))
        return jdbc.query(
            "SELECT id, started_at, finished_at, version, status, change_summary, duration_ms, error_message " +
                "FROM " + schema + ".mds_sync_history " +
                "ORDER BY started_at DESC FETCH FIRST ? ROWS ONLY",
            RowMapper { rs, _ ->
                MdsHistoryView.of(
                    rs.getLong("id"),
                    rs.getTimestamp("started_at")?.toInstant(),
                    rs.getTimestamp("finished_at")?.toInstant(),
                    // Long::class.java 는 primitive long — 박싱 타입(Long.class)으로 읽어 NULL 을 보존한다.
                    rs.getObject("version", Long::class.javaObjectType),
                    rs.getString("status"),
                    rs.getString("change_summary"),
                    rs.getObject("duration_ms", Int::class.javaObjectType),
                    rs.getString("error_message"),
                )
            },
            capped,
        )
    }

    @Transactional
    fun append(
        startedAt: Instant?,
        finishedAt: Instant?,
        version: Long?,
        status: String?,
        changeSummary: String?,
        durationMs: Int?,
        errorMessage: String?,
    ) {
        // 원 Java 의 Objects.requireNonNull(startedAt, ...) 과 같은 예외·메시지.
        if (startedAt == null) throw NullPointerException("startedAt must not be null")
        jdbc.update(
            "INSERT INTO " + schema + ".mds_sync_history " +
                "(id, started_at, finished_at, version, status, change_summary, duration_ms, error_message) " +
                "VALUES (" + schema + ".mds_sync_history_seq.NEXTVAL, ?, ?, ?, ?, ?, ?, ?)",
            Timestamp.from(startedAt),
            finishedAt?.let { Timestamp.from(it) },
            version, status, changeSummary, durationMs, errorMessage,
        )
    }

    /**
     * P1-4 retention: started_at 이 cutoff 이전인 sync 이력 삭제. 삭제 건수 반환.
     * recent()/append() 와 동일하게 주입된 스키마 prefix 명시(JdbcTemplate raw SQL).
     *
     * Batched: ROWNUM 으로 한 트랜잭션 삭제 행 수를 batchSize 로 캡한다(첫 prod 실행 시
     * 수개월 누적분 unbounded DELETE 방지). 호출측(RetentionPurgeService)이 0 행 될 때까지 반복.
     */
    @Transactional
    fun purgeStartedBefore(cutoff: OffsetDateTime, batchSize: Int): Int {
        // JDBC raw path — bind the absolute instant as a Timestamp (mds_sync_history
        // is JdbcTemplate-managed, not a Hibernate entity). The caller now supplies a
        // KST OffsetDateTime; .toInstant() preserves the exact instant for the compare.
        return jdbc.update(
            "DELETE FROM " + schema + ".mds_sync_history WHERE started_at < ? AND ROWNUM <= ?",
            Timestamp.from(cutoff.toInstant()), batchSize,
        )
    }

    @Transactional(readOnly = true)
    fun successRate30dCountOk(): Int {
        val since = Instant.now().minus(Duration.ofDays(30))
        val n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + schema + ".mds_sync_history WHERE status='SYNCED' AND started_at >= ?",
            Int::class.javaObjectType,
            Timestamp.from(since),
        )
        return n ?: 0
    }

    @Transactional(readOnly = true)
    fun successRate30dCountTotal(): Int {
        val since = Instant.now().minus(Duration.ofDays(30))
        val n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + schema + ".mds_sync_history WHERE started_at >= ?",
            Int::class.javaObjectType,
            Timestamp.from(since),
        )
        return n ?: 0
    }
}
