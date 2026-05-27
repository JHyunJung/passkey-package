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
     * For the same reason this entity does NOT extend BaseEntity (which would
     * impose a TIME-ordered UUID generator). createdAt/updatedAt are declared
     * here directly to satisfy the Phase 8 "every entity has updated_at" policy.
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

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected MdsBlobCache() {}

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public long getVersion() { return version; }
    public LocalDate getNextUpdate() { return nextUpdate; }
    public String getBlobJwt() { return blobJwt; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
