package com.crosscert.passkey.admin.mds;

import com.webauthn4j.metadata.data.MetadataBLOB;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Persists the most recent verified MDS BLOB into the singleton
 * row id=1 of {@code mds_blob_cache} (seeded by V17).
 *
 * <p>The raw JWT bytes are not surfaced by webauthn4j MetadataBLOB,
 * so this Phase 3 store passes an empty JSON ("{}") for blob_jwt
 * — Phase 4+ may capture the raw form via a custom HttpClient.
 * The verifier path doesn't re-verify from the column; it uses
 * the parsed entries cached in Redis (MdsSchedulerService T16).
 */
@Service
public class MdsBlobStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MdsBlobStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public void store(String rawJwt, MetadataBLOB blob) {
        long version = blob.getPayload().getNo();
        LocalDate nextUpdate = blob.getPayload().getNextUpdate();
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE APP_OWNER.mds_blob_cache " +
                "SET version=?, next_update=?, fetched_at=?, blob_jwt=? " +
                "WHERE id=1",
                version,
                java.sql.Date.valueOf(nextUpdate),
                Timestamp.from(now),
                rawJwt);
        if (updated != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "mds_blob_cache sentinel row (id=1) missing — V17 migration may not have run");
        }
    }
}
