package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 비밀번호 재설정 토큰 레코드 (1회용 토큰의 sha-256 hash).
 *
 * <p>BaseEntity 상속 안 함 — PK 가 Oracle SEQUENCE + NUMBER(19,0).
 * UUID 기반 BaseEntity 와 PK 타입이 다르므로 독립 선언 (AdminUserInvitation 패턴).
 *
 * <p>admin_user_id 는 RAW(16) FK → admin_user(id).
 * token_hash 는 SHA-256 hex (64자, UNIQUE), token_prefix 는 8자 식별용.
 */
@Entity
@Table(name = "ADMIN_PASSWORD_RESET_TOKEN")
@SequenceGenerator(name = "admin_password_reset_token_seq_gen",
        sequenceName = "ADMIN_PASSWORD_RESET_TOKEN_SEQ",
        allocationSize = 1)
public class AdminPasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
            generator = "admin_password_reset_token_seq_gen")
    @Column(name = "ID", nullable = false)
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ADMIN_USER_ID", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID adminUserId;

    @Column(name = "TOKEN_HASH", length = 64, nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "TOKEN_PREFIX", length = 8, nullable = false, updatable = false)
    private String tokenPrefix;

    @Column(name = "EXPIRES_AT", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "CONSUMED_AT")
    private Instant consumedAt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminPasswordResetToken() {}

    public AdminPasswordResetToken(UUID adminUserId,
                                   String tokenHash,
                                   String tokenPrefix,
                                   Instant expiresAt,
                                   Instant now) {
        this.adminUserId = adminUserId;
        this.tokenHash   = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.expiresAt   = expiresAt;
        this.createdAt   = now;
    }

    // ──────────────────────────────────────────────────────────────────
    // Accessors

    public Long getId() { return id; }

    public UUID getAdminUserId() { return adminUserId; }

    public String getTokenHash() { return tokenHash; }

    public String getTokenPrefix() { return tokenPrefix; }

    public Instant getExpiresAt() { return expiresAt; }

    public Instant getConsumedAt() { return consumedAt; }

    public Instant getCreatedAt() { return createdAt; }

    // ──────────────────────────────────────────────────────────────────
    // Business helpers

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }

    public boolean isConsumed() { return consumedAt != null; }

    public void consume(Instant now) { this.consumedAt = now; }
}
