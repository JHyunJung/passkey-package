package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 월 마감 기록(전체 합계). INSERT 전용 — 마감 후에는 바꾸지 않는다.
 * status: CLOSED(장부로 마감) / PARTIAL(원본 일부 삭제 후 마감).
 */
@Entity
@Table(name = "USAGE_PERIOD_CLOSE")
public class UsagePeriodClose {

    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_PARTIAL = "PARTIAL";

    /** 'YYYY-MM' (KST). */
    @Id
    @Column(name = "PERIOD", length = 7, nullable = false, updatable = false)
    private String period;

    @Column(name = "STATUS", length = 8, nullable = false, updatable = false)
    private String status;

    @Column(name = "BASIS", length = 8, nullable = false, updatable = false)
    private String basis;

    @Column(name = "REG_SUCCESS", nullable = false, updatable = false)
    private long regSuccess;

    @Column(name = "AUTH_SUCCESS", nullable = false, updatable = false)
    private long authSuccess;

    @Column(name = "REG_ATTEMPT", nullable = false, updatable = false)
    private long regAttempt;

    @Column(name = "AUTH_ATTEMPT", nullable = false, updatable = false)
    private long authAttempt;

    @Column(name = "TENANT_COUNT", nullable = false, updatable = false)
    private int tenantCount;

    @Column(name = "CLOSED_AT", nullable = false, updatable = false)
    private OffsetDateTime closedAt;

    protected UsagePeriodClose() {}

    public UsagePeriodClose(String period, String status, String basis,
                            long regSuccess, long authSuccess, long regAttempt, long authAttempt,
                            int tenantCount, OffsetDateTime closedAt) {
        this.period = period;
        this.status = status;
        this.basis = basis;
        this.regSuccess = regSuccess;
        this.authSuccess = authSuccess;
        this.regAttempt = regAttempt;
        this.authAttempt = authAttempt;
        this.tenantCount = tenantCount;
        this.closedAt = closedAt;
    }

    public String getPeriod() { return period; }
    public String getStatus() { return status; }
    public String getBasis() { return basis; }
    public long getRegSuccess() { return regSuccess; }
    public long getAuthSuccess() { return authSuccess; }
    public long getRegAttempt() { return regAttempt; }
    public long getAuthAttempt() { return authAttempt; }
    public int getTenantCount() { return tenantCount; }
    public OffsetDateTime getClosedAt() { return closedAt; }
}
