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
     *
     * For the same reason this entity does NOT extend BaseEntity.
     * createdAt/updatedAt are declared here directly to satisfy the
     * Phase 8 "every entity has updated_at" policy.
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

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected SchedulerLease() {}

    public SchedulerLease(UUID id, String name, String holder, Instant expiresAt) {
        this.id = id;
        this.name = name;
        this.holder = holder;
        this.expiresAt = expiresAt;
    }

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
    public String getName() { return name; }
    public String getHolder() { return holder; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setHolder(String holder) { this.holder = holder; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
