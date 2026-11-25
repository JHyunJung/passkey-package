package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 마감된 달의 테넌트별 확정 사용량. INSERT 전용 — 한 번 쓰면 바꾸지 않는다
 * (DB 권한도 SELECT·INSERT 뿐이므로 수정 경로를 만들지 않는다).
 */
@Entity
@Table(name = "USAGE_MONTHLY")
@IdClass(UsageMonthlyId.class)
public class UsageMonthly {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID tenantId;

    /** 'YYYY-MM' (KST). */
    @Id
    @Column(name = "PERIOD", length = 7, nullable = false, updatable = false)
    private String period;

    @Column(name = "REG_SUCCESS", nullable = false, updatable = false)
    private long regSuccess;

    @Column(name = "AUTH_SUCCESS", nullable = false, updatable = false)
    private long authSuccess;

    @Column(name = "REG_ATTEMPT", nullable = false, updatable = false)
    private long regAttempt;

    @Column(name = "AUTH_ATTEMPT", nullable = false, updatable = false)
    private long authAttempt;

    protected UsageMonthly() {}

    public UsageMonthly(UUID tenantId, String period,
                        long regSuccess, long authSuccess, long regAttempt, long authAttempt) {
        this.tenantId = tenantId;
        this.period = period;
        this.regSuccess = regSuccess;
        this.authSuccess = authSuccess;
        this.regAttempt = regAttempt;
        this.authAttempt = authAttempt;
    }

    public UUID getTenantId() { return tenantId; }
    public String getPeriod() { return period; }
    public long getRegSuccess() { return regSuccess; }
    public long getAuthSuccess() { return authSuccess; }
    public long getRegAttempt() { return regAttempt; }
    public long getAuthAttempt() { return authAttempt; }
}
