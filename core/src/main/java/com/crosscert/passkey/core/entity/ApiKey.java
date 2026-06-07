package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "API_KEY")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ApiKey extends BaseEntity {

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
    }

    public void addScope(String scope) {
        boolean exists = scopes.stream().anyMatch(s -> scope.equals(s.getScope()));
        if (!exists) {
            scopes.add(new ApiKeyScope(this, scope));
        }
    }

    public void clearScopes() { scopes.clear(); }

    public Set<String> getScopeValues() {
        return scopes.stream().map(ApiKeyScope::getScope).collect(Collectors.toSet());
    }

    public Set<ApiKeyScope> getScopes() { return scopes; }

    public UUID getTenantId() { return tenantId; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public String getName() { return name; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isActive(Instant now) {
        if (revokedAt != null) return false;
        if (expiresAt != null && !expiresAt.isAfter(now)) return false;
        return true;
    }

    public void touchLastUsed(Instant now) {
        this.lastUsedAt = now;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    /**
     * 만료 시각을 설정한다. 두 용도:
     * (1) 발급 시 now+N개월 만료(ApiKeyAdminService.issue),
     * (2) P1-5 rotation 의 구 키 grace 만료.
     */
    public void expireAt(Instant when) {
        this.expiresAt = when;
    }
}
