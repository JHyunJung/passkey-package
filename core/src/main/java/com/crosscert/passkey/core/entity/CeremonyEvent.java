package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 등록/인증 ceremony 집계용 경량 이벤트. hash chain 없음(단순 카운트 지표).
 * FunnelService 가 (tenant_id, action, created_at) 으로 집계한다.
 */
@Entity
@Table(name = "CEREMONY_EVENT")
public class CeremonyEvent extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID tenantId;

    @Column(name = "ACTION", length = 32, nullable = false)
    private String action;

    protected CeremonyEvent() {}

    public CeremonyEvent(UUID tenantId, String action) {
        this.tenantId = tenantId;
        this.action = action;
    }

    public UUID getTenantId() { return tenantId; }
    public String getAction() { return action; }
}
