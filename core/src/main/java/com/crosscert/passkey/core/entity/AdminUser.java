package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ADMIN_USER")
public class AdminUser extends BaseEntity {

    @Column(name = "EMAIL", length = 255, nullable = false)
    private String email;

    @Column(name = "BCRYPT_HASH", length = 72, nullable = false)
    private String bcryptHash;

    @Column(name = "ROLE", length = 16, nullable = false)
    private String role;

    @Column(name = "ENABLED", columnDefinition = "CHAR(1)", nullable = false)
    private String enabledFlag;

    @Column(name = "LAST_LOGIN_AT")
    private Instant lastLoginAt;

    protected AdminUser() {}

    public AdminUser(String email, String bcryptHash, String role) {
        this.email = email;
        this.bcryptHash = bcryptHash;
        this.role = role;
        this.enabledFlag = "Y";
    }

    public String getEmail() { return email; }
    public String getBcryptHash() { return bcryptHash; }
    public String getRole() { return role; }
    public boolean isEnabled() { return "Y".equals(enabledFlag); }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }
}
