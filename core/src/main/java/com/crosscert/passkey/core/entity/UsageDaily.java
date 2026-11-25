package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 테넌트·KST 일별 사용량 요약. 행은 {@code UsageDailyRepository.mergeFromLedger} 가 만들고 덮어쓴다
 * (장부 {@code ceremony_event} 에서 재계산하므로 멱등). 앱 코드는 이 엔티티를 직접 저장하지 않는다.
 * basis: LEDGER(장부 기록 이후 날짜) / LEGACY(장부 이전 방식으로 집계된 날짜).
 */
@Entity
@Table(name = "USAGE_DAILY")
@IdClass(UsageDailyId.class)
public class UsageDaily {

    public static final String BASIS_LEDGER = "LEDGER";
    public static final String BASIS_LEGACY = "LEGACY";

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "TENANT_ID", columnDefinition = "RAW(16)", nullable = false)
    private UUID tenantId;

    @Id
    @Column(name = "USAGE_DATE", nullable = false)
    private LocalDate usageDate;

    @Column(name = "REG_SUCCESS", nullable = false)
    private long regSuccess;

    @Column(name = "AUTH_SUCCESS", nullable = false)
    private long authSuccess;

    @Column(name = "REG_ATTEMPT", nullable = false)
    private long regAttempt;

    @Column(name = "AUTH_ATTEMPT", nullable = false)
    private long authAttempt;

    @Column(name = "BASIS", length = 8, nullable = false)
    private String basis;

    @Column(name = "AGGREGATED_AT", nullable = false)
    private OffsetDateTime aggregatedAt;

    protected UsageDaily() {}

    public UUID getTenantId() { return tenantId; }
    public LocalDate getUsageDate() { return usageDate; }
    public long getRegSuccess() { return regSuccess; }
    public long getAuthSuccess() { return authSuccess; }
    public long getRegAttempt() { return regAttempt; }
    public long getAuthAttempt() { return authAttempt; }
    public String getBasis() { return basis; }
    public OffsetDateTime getAggregatedAt() { return aggregatedAt; }
}
