package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.core.entity.MdsBlobCache;
import com.crosscert.passkey.webauthn.mds.MdsBlob;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Persists the most recent verified MDS BLOB metadata into the singleton
 * row of {@code mds_blob_cache} (seeded by V19, id = SINGLETON_ID).
 *
 * <p>The verifier path uses the parsed entries cached in Redis
 * (MdsSchedulerService T16); only version/next_update/fetched_at are stored here.
 */
@Service
public class MdsBlobStore {

    private static final String SINGLETON_HEX =
            MdsBlobCache.SINGLETON_ID.toString().replace("-", "");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MdsBlobStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public void store(MdsBlob blob) {
        long version = blob.no();
        LocalDate nextUpdate = blob.nextUpdate();
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE APP_OWNER.mds_blob_cache " +
                "SET version=?, next_update=?, fetched_at=? " +
                "WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
                version,
                java.sql.Date.valueOf(nextUpdate),
                Timestamp.from(now));
        if (updated != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "mds_blob_cache sentinel row missing — V19 migration may not have run");
        }
    }
}
