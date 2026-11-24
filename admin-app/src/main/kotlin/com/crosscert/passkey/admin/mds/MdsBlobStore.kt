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
 * row of `mds_blob_cache` (seeded by the schema SQL, id = SINGLETON_ID).
 *
 * The verifier path uses the parsed entries cached in Redis
 * (MdsSchedulerService); only version/next_update/fetched_at are stored here.
 */
@Service
class MdsBlobStore(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    dbSchema: DbSchemaProperties,
) {

    // String?: 생성 시 null 검사를 하지 않는다(mock 한 DbSchemaProperties 는 null 을 준다). 운영 값은 DbSchemaProperties 가 부팅 때 검증한다.
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
                "mds_blob_cache sentinel row missing — schema SQL (full-schema-part2-schema.sql) may not have been applied",
            )
        }
    }
}
