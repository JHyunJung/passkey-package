package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * audit chain 위변조 incident. OPEN 으로 생성되어 RESOLVED 로 종결된다.
 * 테넌트당 OPEN 1건은 DB 부분 유니크 인덱스(V50)로 강제된다.
 */
@Entity
@Table(name = "SECURITY_INCIDENT")
public class SecurityIncident {

    public static final String TYPE_AUDIT_CHAIN_TAMPER = "AUDIT_CHAIN_TAMPER";
    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TAMPERED_ENTRY_ID", columnDefinition = "RAW(16)", updatable = false)
    private UUID tamperedEntryId;

    @Column(name = "TYPE", length = 64, nullable = false, updatable = false)
    private String type;

    @Column(name = "SEVERITY", length = 16, nullable = false, updatable = false)
    private String severity;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Column(name = "DETAIL", length = 1024, updatable = false)
    private String detail;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "CREATED_BY", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "RESOLVED_AT")
    private OffsetDateTime resolvedAt;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "RESOLVED_BY", columnDefinition = "RAW(16)")
    private UUID resolvedBy;

    @Column(name = "RESOLUTION_NOTE", length = 1024)
    private String resolutionNote;

    protected SecurityIncident() {}

    public static SecurityIncident open(UUID tenantId, UUID tamperedEntryId,
                                        String detail, UUID createdBy, OffsetDateTime now) {
        SecurityIncident i = new SecurityIncident();
        i.id = UUID.randomUUID();
        i.tenantId = tenantId;
        i.tamperedEntryId = tamperedEntryId;
        i.type = TYPE_AUDIT_CHAIN_TAMPER;
        i.severity = SEVERITY_CRITICAL;
        i.status = STATUS_OPEN;
        i.detail = detail;
        i.createdAt = now;
        i.createdBy = createdBy;
        return i;
    }

    /** OPEN → RESOLVED. 호출 전 상태 검증은 서비스 책임. */
    public void resolve(UUID resolvedByUser, String note, OffsetDateTime now) {
        this.status = STATUS_RESOLVED;
        this.resolvedBy = resolvedByUser;
        this.resolutionNote = note;
        this.resolvedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTamperedEntryId() { return tamperedEntryId; }
    public String getType() { return type; }
    public String getSeverity() { return severity; }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public UUID getResolvedBy() { return resolvedBy; }
    public String getResolutionNote() { return resolutionNote; }
}
