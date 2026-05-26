package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "SCHEDULER_LEASE")
public class SchedulerLease {

    /**
     * UUID primary key. Oracle's DEFAULT SYS_GUID() in V19 assigns the id
     * when the row is inserted without an explicit id (e.g. raw-SQL IT inserts).
     * When inserting via JPA, the caller must set id explicitly via the constructor
     * or the service must generate one before persisting. No @UuidGenerator here
     * because the V19 schema retains DEFAULT SYS_GUID() to keep IT test raw-SQL
     * inserts (which omit the id column) valid.
     */
    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "NAME", length = 64, nullable = false, unique = true)
    private String name;

    @Column(name = "HOLDER", length = 256, nullable = false)
    private String holder;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    protected SchedulerLease() {}

    public SchedulerLease(UUID id, String name, String holder, Instant expiresAt) {
        this.id = id;
        this.name = name;
        this.holder = holder;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getHolder() { return holder; }
    public Instant getExpiresAt() { return expiresAt; }

    public void setHolder(String holder) { this.holder = holder; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
