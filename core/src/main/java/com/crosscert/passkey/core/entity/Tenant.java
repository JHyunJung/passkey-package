package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;

import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "TENANT")
public class Tenant extends BaseEntity {

    @Column(name = "SLUG", length = 64, nullable = false, unique = true)
    private String slug;

    @Column(name = "DISPLAY_NAME", length = 256, nullable = false)
    private String displayName;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Column(name = "RP_ID", length = 256, nullable = false)
    private String rpId;

    @Column(name = "RP_NAME", length = 256, nullable = false)
    private String rpName;

    @Column(name = "REQUIRE_USER_VERIFICATION", columnDefinition = "CHAR(1)", nullable = false)
    private String requireUserVerificationFlag = "Y";

    @Column(name = "MDS_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsRequiredFlag = "N";

    @Column(name = "ATTESTATION_CONVEYANCE", length = 16, nullable = false)
    private String attestationConveyance = "NONE";

    @Column(name = "WEBAUTHN_TIMEOUT_MS", nullable = false)
    private int webauthnTimeoutMs = 60000;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<TenantAllowedOrigin> allowedOrigins = new ArrayList<>();

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<TenantAcceptedFormat> acceptedFormats = new HashSet<>();

    protected Tenant() {}

    public Tenant(String slug, String displayName, String rpId, String rpName) {
        this.slug = slug;
        this.displayName = displayName;
        this.status = "active";
        this.rpId = rpId;
        this.rpName = rpName;
    }

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

    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getStatus() { return status; }
    public String getRpId() { return rpId; }
    public String getRpName() { return rpName; }
    public void setRpName(String rpName) { this.rpName = rpName; }

    public List<TenantAllowedOrigin> getAllowedOrigins() { return allowedOrigins; }
    public Set<TenantAcceptedFormat> getAcceptedFormats() { return acceptedFormats; }

    public String getAttestationConveyance() { return attestationConveyance; }
    public void setAttestationConveyance(String v) { this.attestationConveyance = v; }

    public int getWebauthnTimeoutMs() { return webauthnTimeoutMs; }
    public void setWebauthnTimeoutMs(int v) { this.webauthnTimeoutMs = v; }
}
