package com.crosscert.passkey.core.entity;

import com.crosscert.passkey.core.config.KstTime;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
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
    private OffsetDateTime expiresAt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    protected SchedulerLease() {}

    public SchedulerLease(UUID id, String name, String holder, OffsetDateTime expiresAt) {
        this.id = id;
        this.name = name;
        this.holder = holder;
        this.expiresAt = expiresAt;
    }

    /**
     * Single-source-of-time constructor. The caller supplies one {@code now}
     * (the same instant from which {@code expiresAt = now + ttl} is derived) so
     * createdAt/updatedAt and expiresAt share a single time source. Pre-seeding
     * createdAt/updatedAt makes @PrePersist's null-check leave them alone —
     * preventing fixed-clock nondeterminism (and createdAt > expiresAt) that
     * arises when @PrePersist self-sources a second {@code .now()}.
     */
    public SchedulerLease(UUID id, String name, String holder,
                          OffsetDateTime expiresAt, OffsetDateTime now) {
        this.id = id;
        this.name = name;
        this.holder = holder;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(KstTime.ZONE);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(KstTime.ZONE);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getHolder() { return holder; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setHolder(String holder) { this.holder = holder; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
