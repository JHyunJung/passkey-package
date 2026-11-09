package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.entity.MdsBlobCache
import com.crosscert.passkey.webauthn.mds.MdsBlob
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock

private val SINGLETON_HEX: String = MdsBlobCache.SINGLETON_ID.toString().replace("-", "")

/**
 * Persists the most recent verified MDS BLOB metadata into the singleton
 * row of `mds_blob_cache` (seeded by V19, id = SINGLETON_ID).
 *
 * The verifier path uses the parsed entries cached in Redis
 * (MdsSchedulerService T16); only version/next_update/fetched_at are stored here.
 */
@Service
class MdsBlobStore(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    dbSchema: DbSchemaProperties,
) {

    // 원 Java 처럼 생성 시 null 검증을 하지 않는다(DbSchemaProperties 가 검증; 슬라이스 테스트 mock 은 null 을 준다).
    private val schema: String? = dbSchema.schema()

    @Transactional
    fun store(blob: MdsBlob) {
        val version = blob.no().toLong()
        val nextUpdate = blob.nextUpdate()
        val now = clock.instant()
        val updated = jdbc.update(
            "UPDATE " + schema + ".mds_blob_cache " +
                "SET version=?, next_update=?, fetched_at=? " +
                "WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
            version,
            java.sql.Date.valueOf(nextUpdate),
            Timestamp.from(now),
        )
        if (updated != 1) {
            throw IncorrectUpdateSemanticsDataAccessException(
                "mds_blob_cache sentinel row missing — V19 migration may not have run",
            )
        }
    }
}
