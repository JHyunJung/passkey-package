package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "MDS_BLOB_CACHE")
public class MdsBlobCache {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mds_blob_cache_seq")
    @SequenceGenerator(name = "mds_blob_cache_seq", sequenceName = "MDS_BLOB_CACHE_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

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

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public LocalDate getNextUpdate() { return nextUpdate; }
    public String getBlobJwt() { return blobJwt; }
}
