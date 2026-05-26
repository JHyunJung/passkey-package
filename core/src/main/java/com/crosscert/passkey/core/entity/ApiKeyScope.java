package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "API_KEY_SCOPE")
public class ApiKeyScope extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "API_KEY_ID", nullable = false, columnDefinition = "RAW(16)")
    private ApiKey apiKey;

    @Column(name = "SCOPE", length = 32, nullable = false)
    private String scope;

    protected ApiKeyScope() {}  // JPA

    public ApiKeyScope(ApiKey apiKey, String scope) {
        this.apiKey = apiKey;
        this.scope = scope;
    }

    public ApiKey getApiKey() { return apiKey; }
    public String getScope() { return scope; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiKeyScope other)) return false;
        // Equality by (apiKeyId, scope) — the natural key (UNIQUE constraint).
        // Require both apiKey IDs to be non-null (i.e. persisted) to avoid
        // two transient ApiKeyScope instances comparing equal when
        // their apiKey UUIDs have not yet been assigned by the persistence context.
        if (apiKey == null || other.apiKey == null) return false;
        UUID aid = apiKey.getId();
        UUID oaid = other.apiKey.getId();
        if (aid == null || oaid == null) return false;
        return Objects.equals(aid, oaid)
            && Objects.equals(scope, other.scope);
    }

    @Override
    public int hashCode() {
        UUID aid = (apiKey == null) ? null : apiKey.getId();
        // Use a constant bucket for transient instances (aid == null) so that
        // hash does not change after persist — safer than hashing null.
        return (aid == null) ? 0 : Objects.hash(aid, scope);
    }
}
