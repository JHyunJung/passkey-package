package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "AUDIT_LOG")
public class AuditLog extends BaseEntity {

    @Column(name = "PREV_HASH", length = 32)
    private byte[] prevHash;

    @Column(name = "HASH", length = 32, nullable = false)
    private byte[] hash;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ACTOR_ID", columnDefinition = "RAW(16)")
    private UUID actorId;

    @Column(name = "ACTOR_EMAIL", length = 255, nullable = false)
    private String actorEmail;

    @Column(name = "ACTION", length = 64, nullable = false)
    private String action;

    @Column(name = "TARGET_TYPE", length = 32)
    private String targetType;

    @Column(name = "TARGET_ID", length = 64)
    private String targetId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)")
    private UUID tenantId;

    // Phase B — per-tenant hash chain (V25)
    @Column(name = "TENANT_PREV_HASH", length = 32)
    private byte[] tenantPrevHash;

    @Column(name = "TENANT_HASH", length = 32)
    private byte[] tenantHash;

    @Lob
    @Column(name = "PAYLOAD", nullable = false)
    private String payload;

    protected AuditLog() {}

    public AuditLog(byte[] prevHash, byte[] hash, UUID actorId, String actorEmail,
                    String action, String targetType, String targetId,
                    UUID tenantId,
                    byte[] tenantPrevHash, byte[] tenantHash,
                    String payload, Instant createdAtArg) {
        this.prevHash = prevHash;
        this.hash = hash;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.tenantId = tenantId;
        this.tenantPrevHash = tenantPrevHash;
        this.tenantHash = tenantHash;
        this.payload = payload;
        // Pre-set BaseEntity.createdAt + updatedAt so @PrePersist's null-check
        // preserves caller-supplied value for hash-chain integrity. AuditLog
        // is append-only — updatedAt always equals createdAt at insert and
        // never advances (no @PreUpdate path).
        seedTimestamps(createdAtArg);
    }

    /**
     * Reflectively seed BaseEntity's private createdAt and updatedAt fields.
     * Required because:
     *   1. AuditLog's hash chain depends on a caller-chosen createdAt that
     *      must survive @PrePersist (which uses Instant.now() by default).
     *   2. BaseEntity intentionally exposes no setters for createdAt/updatedAt
     *      to keep the lifecycle contract uniform across the other 9 entities.
     * @PrePersist's null-check ({@code if (createdAt == null) createdAt = now})
     * then leaves the seeded value alone.
     */
    private void seedTimestamps(Instant t) {
        try {
            java.lang.reflect.Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(this, t);
            java.lang.reflect.Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(this, t);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to seed AuditLog timestamps", e);
        }
    }

    public byte[] getPrevHash() { return prevHash; }
    public byte[] getHash() { return hash; }
    public UUID getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public UUID getTenantId() { return tenantId; }
    // Phase B — per-tenant chain
    public byte[] getTenantPrevHash() { return tenantPrevHash; }
    public byte[] getTenantHash() { return tenantHash; }
    /** 백필 endpoint 전용 package-private setter */
    void setTenantPrevHash(byte[] v) { this.tenantPrevHash = v; }
    void setTenantHash(byte[] v) { this.tenantHash = v; }
    public String getPayload() { return payload; }
}
