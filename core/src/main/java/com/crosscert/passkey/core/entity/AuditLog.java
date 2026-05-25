package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "AUDIT_LOG")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_log_seq")
    @SequenceGenerator(name = "audit_log_seq", sequenceName = "AUDIT_LOG_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "PREV_HASH", length = 32)
    private byte[] prevHash;

    @Column(name = "HASH", length = 32, nullable = false)
    private byte[] hash;

    @Column(name = "ACTOR_ID", nullable = false)
    private Long actorId;

    @Column(name = "ACTOR_EMAIL", length = 255, nullable = false)
    private String actorEmail;

    @Column(name = "ACTION", length = 64, nullable = false)
    private String action;

    @Column(name = "TARGET_TYPE", length = 32)
    private String targetType;

    @Column(name = "TARGET_ID", length = 64)
    private String targetId;

    @Lob
    @Column(name = "PAYLOAD", nullable = false)
    private String payload;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {}

    public AuditLog(byte[] prevHash, byte[] hash, long actorId, String actorEmail,
                    String action, String targetType, String targetId,
                    String payload, Instant createdAt) {
        this.prevHash = prevHash;
        this.hash = hash;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public byte[] getPrevHash() { return prevHash; }
    public byte[] getHash() { return hash; }
    public Long getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
