package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "API_KEY")
public class ApiKey {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;

    @Column(name = "KEY_PREFIX", length = 16, nullable = false)
    private String keyPrefix;

    @Column(name = "KEY_HASH", length = 255, nullable = false)
    private String keyHash;

    @Column(name = "NAME", length = 256, nullable = false)
    private String name;

    @OneToMany(mappedBy = "apiKey", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<ApiKeyScope> scopes = new HashSet<>();

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "LAST_USED_AT")
    private Instant lastUsedAt;

    @Column(name = "EXPIRES_AT")
    private Instant expiresAt;

    @Column(name = "REVOKED_AT")
    private Instant revokedAt;

    protected ApiKey() {}

    public ApiKey(UUID tenantId, String keyPrefix, String keyHash, String name) {
        this.tenantId = tenantId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.name = name;
        this.createdAt = Instant.now();
    }

    // ── Collection helpers (keeps parent in sync) ──

    public void addScope(String scope) {
        // String dedup (transient safety per T4 P2 pattern)
        boolean exists = scopes.stream().anyMatch(s -> scope.equals(s.getScope()));
        if (!exists) {
            scopes.add(new ApiKeyScope(this, scope));
        }
    }

    public void clearScopes() { scopes.clear(); }

    // ── Typed convenience getters ──

    public Set<String> getScopeValues() {
        return scopes.stream().map(ApiKeyScope::getScope).collect(Collectors.toSet());
    }

    // For test-only reflective access
    public Set<ApiKeyScope> getScopes() { return scopes; }

    // ── Standard getters ──

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    /** Active = not expired AND not revoked. Used by ApiKeyAuthFilter. */
    public boolean isActive(Instant now) {
        if (revokedAt != null) return false;
        if (expiresAt != null && !expiresAt.isAfter(now)) return false;
        return true;
    }

    /** Called after a successful authentication. Caller saves the entity. */
    public void touchLastUsed(Instant now) {
        this.lastUsedAt = now;
    }

    /**
     * Soft-revoke this key. Called by ApiKeyAdminService and persisted
     * by the caller. Reusing isActive(now) means a revoked key fails
     * the next /api/v1/rp/** auth attempt.
     */
    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
