package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * WebAuthn config 변경 직전 스냅샷 — append-only 히스토리.
 *
 * <p>diff 미리보기 + 운영 사고 추적 용도.
 * TenantAdminService.create() 에서 신규 tenant 초기 snapshot INSERT.
 * TenantAdminService.update() 에서 변경 직전 값으로 snapshot INSERT.
 *
 * <p>allowed_origins / accepted_formats 는 JSON 배열 문자열로 저장.
 */
@Entity
@Table(name = "TENANT_WEBAUTHN_SNAPSHOT")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class TenantWebauthnSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tenant_webauthn_snapshot_seq")
    @SequenceGenerator(
            name = "tenant_webauthn_snapshot_seq",
            sequenceName = "TENANT_WEBAUTHN_SNAPSHOT_SEQ",
            allocationSize = 1
    )
    @Column(name = "ID")
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID tenantId;

    @Column(name = "RP_ID", length = 256, nullable = false)
    private String rpId;

    @Column(name = "RP_NAME", length = 256, nullable = false)
    private String rpName;

    @Lob
    @Column(name = "ALLOWED_ORIGINS", nullable = false)
    private String allowedOriginsJson;

    @Lob
    @Column(name = "ACCEPTED_FORMATS", nullable = false)
    private String acceptedFormatsJson;

    @Column(name = "REQUIRE_USER_VERIFICATION", columnDefinition = "CHAR(1)", nullable = false)
    private String requireUserVerificationFlag;

    @Column(name = "MDS_REQUIRED", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsRequiredFlag;

    @Column(name = "TAKEN_AT", nullable = false)
    private Instant takenAt = Instant.now();

    @Column(name = "TAKEN_BY", length = 255)
    private String takenBy;

    // ──────────────────────────────────────────────────────────────────
    // Constructors

    protected TenantWebauthnSnapshot() {}

    public TenantWebauthnSnapshot(UUID tenantId, String rpId, String rpName,
                                   String allowedOriginsJson, String acceptedFormatsJson,
                                   boolean requireUserVerification, boolean mdsRequired,
                                   String takenBy) {
        this.tenantId = tenantId;
        this.rpId = rpId;
        this.rpName = rpName;
        this.allowedOriginsJson = allowedOriginsJson;
        this.acceptedFormatsJson = acceptedFormatsJson;
        this.requireUserVerificationFlag = requireUserVerification ? "Y" : "N";
        this.mdsRequiredFlag = mdsRequired ? "Y" : "N";
        this.takenBy = takenBy;
    }

    // ──────────────────────────────────────────────────────────────────
    // Accessors

    public Long getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getRpId() { return rpId; }
    public String getRpName() { return rpName; }
    public String getAllowedOriginsJson() { return allowedOriginsJson; }
    public String getAcceptedFormatsJson() { return acceptedFormatsJson; }
    public boolean isRequireUserVerification() { return "Y".equals(requireUserVerificationFlag); }
    public boolean isMdsRequired() { return "Y".equals(mdsRequiredFlag); }
    public Instant getTakenAt() { return takenAt; }
    public String getTakenBy() { return takenBy; }
}
