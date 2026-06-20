package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.crosscert.passkey.core.config.KstTime;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Common superclass for entities that share a UUID PK (TIME-ordered)
 * and createdAt/updatedAt timestamps managed by JPA lifecycle callbacks.
 *
 * Exceptions (do NOT extend this class):
 * - MdsBlobCache: uses a fixed SINGLETON_ID
 * - SchedulerLease: relies on Oracle's DEFAULT SYS_GUID() for raw-SQL inserts
 *
 * Those two entities declare createdAt/updatedAt and the callbacks directly
 * to preserve their PK contracts while satisfying the "every entity has
 * updated_at" policy (Phase 8).
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
