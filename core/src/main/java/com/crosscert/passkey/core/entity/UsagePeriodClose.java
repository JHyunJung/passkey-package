package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * 월 마감 기록(전체 합계). INSERT 전용 — 마감 후에는 바꾸지 않는다.
 * status: CLOSED(장부로 마감) / PARTIAL(원본 일부 삭제 후 마감).
 *
 * <p>{@link Persistable} 을 구현해 새로 만든 인스턴스의 save 가 항상 persist(INSERT)가 되게 한다. ID 가 지정값이라
 * 기본 판정으로는 merge(SELECT 후 있으면 아무것도 안 함)가 되어, 다른 트랜잭션이 먼저 마감한 달에도 예외 없이 지나간다.
 * persist 면 같은 기간의 두 번째 INSERT 가 PK 에서 막혀(ORA-00001) 중복 마감이 드러난다.
 */
@Entity
@Table(name = "USAGE_PERIOD_CLOSE")
public class UsagePeriodClose implements Persistable<String> {

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

    /** 아직 저장되지 않은 인스턴스인가. 저장(@PostPersist)·조회(@PostLoad) 뒤에는 false. */
    @Transient
    private boolean isNew = true;

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

    @Override
    public String getId() { return period; }

    @Override
    public boolean isNew() { return isNew; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.isNew = false; }

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
