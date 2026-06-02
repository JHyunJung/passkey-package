package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant 별 AAGUID 허용/차단 정책.
 *
 * <p>BaseEntity 상속 안 함 — tenant_id 가 PK 라 별도 surrogate id 없음.
 * createdAt/updatedAt 은 직접 컬럼으로 관리.
 *
 * <p>entries 는 @ElementCollection 패턴으로 {@link Entry} 내장.
 * 별도 TenantAaguidPolicyEntry entity 불필요 — JPA cascading 자동.
 */
@Entity
@Table(name = "TENANT_AAGUID_POLICY")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class TenantAaguidPolicy {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "POLICY_MODE", length = 16, nullable = false)
    private Mode mode = Mode.ANY;

    @Column(name = "MDS_STRICT", columnDefinition = "CHAR(1)", nullable = false)
    private String mdsStrictFlag = "N";

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "UPDATED_BY", length = 255)
    private String updatedBy;

    // tenant isolation via parent TenantAaguidPolicy @Filter; @ElementCollection cannot carry @Filter directly
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "TENANT_AAGUID_POLICY_ENTRY",
            joinColumns = @JoinColumn(name = "TENANT_ID")
    )
    private Set<Entry> entries = new HashSet<>();

    // ──────────────────────────────────────────────────────────────────
    // Enum

    public enum Mode { ANY, ALLOWLIST, DENYLIST }

    // ──────────────────────────────────────────────────────────────────
    // Embeddable Entry

    @Embeddable
    public static class Entry {

        @JdbcTypeCode(SqlTypes.UUID)
        @Column(name = "AAGUID", columnDefinition = "RAW(16)", nullable = false)
        private UUID aaguid;

        @Column(name = "NOTE", length = 256)
        private String note;

        protected Entry() {}

        public Entry(UUID aaguid, String note) {
            this.aaguid = aaguid;
            this.note = note;
        }

        public UUID getAaguid() { return aaguid; }
        public String getNote() { return note; }

        /** equality 는 aaguid 만으로 — UNIQUE KEY (tenant_id, aaguid). */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Entry e)) return false;
            return Objects.equals(aaguid, e.aaguid);
        }

        @Override
        public int hashCode() { return Objects.hash(aaguid); }
    }

    // ──────────────────────────────────────────────────────────────────
    // Constructors

    protected TenantAaguidPolicy() {}

    public TenantAaguidPolicy(UUID tenantId, Mode mode, boolean mdsStrict, String updatedBy) {
        this.tenantId = tenantId;
        this.mode = mode;
        this.mdsStrictFlag = mdsStrict ? "Y" : "N";
        this.updatedBy = updatedBy;
    }

    // ──────────────────────────────────────────────────────────────────
    // Accessors

    public UUID getTenantId() { return tenantId; }

    public Mode getMode() { return mode; }
    public void setMode(Mode m) { this.mode = m; }

    public boolean isMdsStrict() { return "Y".equals(mdsStrictFlag); }
    public void setMdsStrict(boolean v) { this.mdsStrictFlag = v ? "Y" : "N"; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String s) { this.updatedBy = s; }

    public Set<Entry> getEntries() { return entries; }

    // ──────────────────────────────────────────────────────────────────
    // Mutation helpers

    public void addEntry(UUID aaguid, String note) {
        entries.add(new Entry(aaguid, note));
    }

    public void clearEntries() { entries.clear(); }
}
