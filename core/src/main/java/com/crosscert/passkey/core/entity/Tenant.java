package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "TENANT")
public class Tenant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "SLUG", length = 64, nullable = false, unique = true)
    private String slug;

    @Column(name = "DISPLAY_NAME", length = 256, nullable = false)
    private String displayName;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;  // "active" or "suspended"

    @Column(name = "RP_ID", length = 256, nullable = false)
    private String rpId;

    @Column(name = "RP_NAME", length = 256, nullable = false)
    private String rpName;

    @Column(name = "REQUIRE_USER_VERIFICATION", columnDefinition = "CHAR(1)", nullable = false)
    private String requireUserVerificationFlag = "Y";

    @Column(name = "MDS_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsRequiredFlag = "N";

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<TenantAllowedOrigin> allowedOrigins = new ArrayList<>();

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<TenantAcceptedFormat> acceptedFormats = new HashSet<>();

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected Tenant() {}  // JPA

    public Tenant(String slug, String displayName, String rpId, String rpName) {
        this.slug = slug;
        this.displayName = displayName;
        this.status = "active";
        this.rpId = rpId;
        this.rpName = rpName;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ── Collection helpers (keeps parent in sync) ──
    public void addAllowedOrigin(String origin, int sortOrder) {
        boolean duplicate = allowedOrigins.stream()
                .anyMatch(o -> o.getOrigin().equals(origin));
        if (!duplicate) {
            allowedOrigins.add(new TenantAllowedOrigin(this, origin, sortOrder));
        }
    }

    public void clearAllowedOrigins() { allowedOrigins.clear(); }

    public void addAcceptedFormat(String format) {
        boolean duplicate = acceptedFormats.stream()
                .anyMatch(f -> f.getFormat().equals(format));
        if (!duplicate) {
            acceptedFormats.add(new TenantAcceptedFormat(this, format));
        }
    }

    public void clearAcceptedFormats() { acceptedFormats.clear(); }

    // ── Typed convenience getters ──
    public boolean isRequireUserVerification() { return "Y".equals(requireUserVerificationFlag); }
    public boolean isMdsRequired() { return "Y".equals(mdsRequiredFlag); }
    public void setRequireUserVerification(boolean v) { this.requireUserVerificationFlag = v ? "Y" : "N"; }
    public void setMdsRequired(boolean v) { this.mdsRequiredFlag = v ? "Y" : "N"; }

    public List<String> getAllowedOriginValues() {
        return allowedOrigins.stream().map(TenantAllowedOrigin::getOrigin).toList();
    }

    public Set<String> getAcceptedFormatValues() {
        return acceptedFormats.stream().map(TenantAcceptedFormat::getFormat).collect(Collectors.toSet());
    }

    // ── Standard getters/setters ──
    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getStatus() { return status; }
    public String getRpId() { return rpId; }
    public String getRpName() { return rpName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touchUpdatedAt() { this.updatedAt = Instant.now(); }

    // For test-only reflective access
    public List<TenantAllowedOrigin> getAllowedOrigins() { return allowedOrigins; }
    public Set<TenantAcceptedFormat> getAcceptedFormats() { return acceptedFormats; }
}
