package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * credential 단위 인증 이벤트. hash chain 없는 경량 기록(ceremony_event 와 동일 철학).
 * 성공/실패를 credential 내부 PK 기준으로 적재한다. admin-app 이 credential 상세의
 * "인증 기록" 으로 조회한다. credential 삭제 시 FK ON DELETE CASCADE 로 동반 삭제.
 */
@Entity
@Table(name = "CREDENTIAL_AUTH_EVENT")
public class CredentialAuthEvent extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "CREDENTIAL_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID credentialId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID tenantId;

    @Column(name = "RESULT", length = 16, nullable = false)
    private String result;

    @Column(name = "FAILURE_REASON", length = 64)
    private String failureReason;

    @Column(name = "SIGN_COUNT", nullable = false)
    private long signCount;

    protected CredentialAuthEvent() {}

    public CredentialAuthEvent(UUID credentialId, UUID tenantId, String result,
                               String failureReason, long signCount) {
        this.credentialId = credentialId;
        this.tenantId = tenantId;
        this.result = result;
        this.failureReason = failureReason;
        this.signCount = signCount;
    }

    public UUID getCredentialId() { return credentialId; }
    public UUID getTenantId()     { return tenantId; }
    public String getResult()     { return result; }
    public String getFailureReason() { return failureReason; }
    public long getSignCount()    { return signCount; }
}
