package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "MDS_BLOB_CACHE")
public class MdsBlobCache {

    /**
     * The singleton row id seeded by V19 migration.
     * Matches HEXTORAW('00000000000000000000000000000001').
     * No @UuidGenerator — this id is fixed; the app never inserts another row.
     */
    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "VERSION", nullable = false)
    private long version;

    @Column(name = "NEXT_UPDATE", nullable = false)
    private LocalDate nextUpdate;

    @Column(name = "FETCHED_AT", nullable = false)
    private Instant fetchedAt;

    @Lob
    @Column(name = "BLOB_JWT", nullable = false)
    private String blobJwt;

    protected MdsBlobCache() {}

    public UUID getId() { return id; }
    public long getVersion() { return version; }
    public LocalDate getNextUpdate() { return nextUpdate; }
    public String getBlobJwt() { return blobJwt; }
}
