package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 관리자 초대 토큰 레코드.
 *
 * <p>BaseEntity 상속 안 함 — PK 가 Oracle SEQUENCE + NUMBER(19,0).
 * UUID 기반 BaseEntity 와 PK 타입이 다르므로 독립 선언.
 *
 * <p>admin_user_id 는 RAW(16) FK (V19 이후 admin_user.id 는 UUID).
 * token_hash 는 SHA-256 hex (64자), token_prefix 는 8자 식별용.
 */
@Entity
@Table(name = "ADMIN_USER_INVITATION")
@SequenceGenerator(name = "admin_user_invitation_seq_gen",
        sequenceName = "ADMIN_USER_INVITATION_SEQ",
        allocationSize = 1)
public class AdminUserInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
            generator = "admin_user_invitation_seq_gen")
    @Column(name = "ID", nullable = false)
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ADMIN_USER_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID adminUserId;

    @Column(name = "TOKEN_HASH", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "TOKEN_PREFIX", length = 8, nullable = false)
    private String tokenPrefix;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "CREATED_BY", length = 255, nullable = false)
    private String createdBy;

    @Column(name = "EXPIRES_AT", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "ACCEPTED_AT")
    private OffsetDateTime acceptedAt;

    @Column(name = "RESENT_COUNT", nullable = false)
    private int resentCount = 0;

    @Column(name = "RESENT_AT")
    private OffsetDateTime resentAt;

    protected AdminUserInvitation() {}

    public AdminUserInvitation(UUID adminUserId,
                               String tokenHash,
                               String tokenPrefix,
                               String createdBy,
                               OffsetDateTime createdAt,
                               OffsetDateTime expiresAt) {
        this.adminUserId = adminUserId;
        this.tokenHash   = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.createdBy   = createdBy;
        this.createdAt   = createdAt;
        this.expiresAt   = expiresAt;
        this.resentCount = 0;
    }

    // ──────────────────────────────────────────────────────────────────
    // Accessors

    public Long getId() { return id; }

    public UUID getAdminUserId() { return adminUserId; }

    public String getTokenHash() { return tokenHash; }

    public String getTokenPrefix() { return tokenPrefix; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public String getCreatedBy() { return createdBy; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }

    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }

    public int getResentCount() { return resentCount; }
    public void setResentCount(int resentCount) { this.resentCount = resentCount; }

    public OffsetDateTime getResentAt() { return resentAt; }
    public void setResentAt(OffsetDateTime resentAt) { this.resentAt = resentAt; }

    // ──────────────────────────────────────────────────────────────────
    // Business helpers

    public boolean isPending(OffsetDateTime now) { return acceptedAt == null && now.isBefore(expiresAt); }

    public boolean isExpired(OffsetDateTime now) { return now.isAfter(expiresAt); }

    public boolean isAccepted() { return acceptedAt != null; }

    public void accept(OffsetDateTime now) {
        this.acceptedAt = now;
    }

    public void recordResend(OffsetDateTime now) {
        this.resentCount++;
        this.resentAt = now;
    }

    /**
     * Invalidate this invitation by forcing {@code expiresAt} into the past.
     * Used when an admin resends an invitation — the previously-issued token
     * must not remain usable in parallel with the new one (security: stop
     * accepting tokens that the inviter intended to replace).
     */
    public void markRevoked(OffsetDateTime now) {
        this.expiresAt = now.minusSeconds(1);
    }
}
