package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ADMIN_USER")
public class AdminUser {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "EMAIL", length = 255, nullable = false)
    private String email;

    @Column(name = "BCRYPT_HASH", length = 72, nullable = false)
    private String bcryptHash;

    @Column(name = "ROLE", length = 16, nullable = false)
    private String role;

    @Column(name = "ENABLED", columnDefinition = "CHAR(1)", nullable = false)
    private String enabledFlag;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "LAST_LOGIN_AT")
    private Instant lastLoginAt;

    protected AdminUser() {}

    public AdminUser(String email, String bcryptHash, String role) {
        this.email = email;
        this.bcryptHash = bcryptHash;
        this.role = role;
        this.enabledFlag = "Y";
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getBcryptHash() { return bcryptHash; }
    public String getRole() { return role; }
    public boolean isEnabled() { return "Y".equals(enabledFlag); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }
}
