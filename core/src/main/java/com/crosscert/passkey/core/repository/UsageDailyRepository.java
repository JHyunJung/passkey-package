package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.UsageDaily;
import com.crosscert.passkey.core.entity.UsageDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * usage_daily(테넌트·KST 일별 사용량 요약). 원본은 장부 {@code ceremony_event} 이고,
 * 이 테이블은 {@link #mergeFromLedger} 로 언제든 다시 만들 수 있는 요약이다.
 */
public interface UsageDailyRepository extends JpaRepository<UsageDaily, UsageDailyId> {

    /**
     * 장부의 {@code [from, toExclusive)} 구간을 테넌트·KST 일별로 묶어 usage_daily 에 병합한다(멱등).
     * 같은 (tenant, 날짜) 행이 있으면 여섯 값과 aggregated_at 을 덮어쓰므로 재호출해도 중복 집계되지 않는다.
     *
     * <p>성공 = REGISTRATION_FINISH_OK / AUTHENTICATION_FINISH_OK, 시도 = REGISTRATION_BEGIN / AUTHENTICATION_BEGIN.
     * basis 는 날짜가 {@code ledgerStart} 이상이면 LEDGER, 아니면 LEGACY. {@code ledgerStart} 는 null 을 넘기지 않는다
     * (미설정이면 {@code LocalDate.of(9999, 12, 31)} — 전부 LEGACY).
     * {@code from}/{@code toExclusive} 는 KST 자정 경계의 순간이어야 일 버킷이 KST 와 맞는다.
     *
     * @return 병합(삽입+갱신)된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "MERGE INTO {h-schema}usage_daily t USING ("
         + "SELECT tenant_id, TRUNC(created_at) AS usage_date, "
         + "  SUM(CASE WHEN action = :regOk    THEN 1 ELSE 0 END) AS reg_success, "
         + "  SUM(CASE WHEN action = :authOk   THEN 1 ELSE 0 END) AS auth_success, "
         + "  SUM(CASE WHEN action = :regBegin THEN 1 ELSE 0 END) AS reg_attempt, "
         + "  SUM(CASE WHEN action = :authBegin THEN 1 ELSE 0 END) AS auth_attempt, "
         + "  CASE WHEN TRUNC(created_at) >= :ledgerStart THEN 'LEDGER' ELSE 'LEGACY' END AS basis "
         + "FROM {h-schema}ceremony_event "
         + "WHERE created_at >= :from AND created_at < :toExclusive "
         + "  AND action IN (:regOk, :authOk, :regBegin, :authBegin) "
         + "GROUP BY tenant_id, TRUNC(created_at)"
         + ") s ON (t.tenant_id = s.tenant_id AND t.usage_date = s.usage_date) "
         + "WHEN MATCHED THEN UPDATE SET "
         + "  t.reg_success = s.reg_success, t.auth_success = s.auth_success, "
         + "  t.reg_attempt = s.reg_attempt, t.auth_attempt = s.auth_attempt, "
         + "  t.basis = s.basis, t.aggregated_at = :aggregatedAt "
         + "WHEN NOT MATCHED THEN INSERT "
         + "  (tenant_id, usage_date, reg_success, auth_success, reg_attempt, auth_attempt, basis, aggregated_at) "
         + "  VALUES (s.tenant_id, s.usage_date, s.reg_success, s.auth_success, s.reg_attempt, s.auth_attempt, "
         + "          s.basis, :aggregatedAt)",
           nativeQuery = true)
    int mergeFromLedgerInternal(@Param("from") OffsetDateTime from,
                                @Param("toExclusive") OffsetDateTime toExclusive,
                                @Param("ledgerStart") LocalDate ledgerStart,
                                @Param("aggregatedAt") OffsetDateTime aggregatedAt,
                                @Param("regOk") String regOk,
                                @Param("authOk") String authOk,
                                @Param("regBegin") String regBegin,
                                @Param("authBegin") String authBegin);

    /** {@link #mergeFromLedgerInternal} 를 {@code CeremonyAction} 상수로 부른다. */
    default int mergeFromLedger(OffsetDateTime from, OffsetDateTime toExclusive,
                                LocalDate ledgerStart, OffsetDateTime aggregatedAt) {
        return mergeFromLedgerInternal(from, toExclusive, ledgerStart, aggregatedAt,
                com.crosscert.passkey.core.ceremony.CeremonyAction.REGISTRATION_SUCCESS,
                com.crosscert.passkey.core.ceremony.CeremonyAction.AUTHENTICATION_SUCCESS,
                com.crosscert.passkey.core.ceremony.CeremonyAction.REGISTRATION_BEGIN,
                com.crosscert.passkey.core.ceremony.CeremonyAction.AUTHENTICATION_BEGIN);
    }

    /**
     * 구간 {@code [from, toExclusive)} (usage_date 기준) 의 테넌트별 합계. JPQL 이라 tenant_id 는 UUID 로 나온다.
     * 반환 행: {@code [UUID tenantId, Long regSuccess, Long authSuccess, Long regAttempt, Long authAttempt]}
     * (열 순서 고정; 합계가 NULL 일 수 없다 — 행이 있으면 모든 열이 NOT NULL).
     */
    @Query("select u.tenantId, sum(u.regSuccess), sum(u.authSuccess), sum(u.regAttempt), sum(u.authAttempt) "
         + "from UsageDaily u where u.usageDate >= :from and u.usageDate < :toExclusive "
         + "group by u.tenantId")
    List<Object[]> sumByTenant(@Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive);

    @Query("select min(u.usageDate) from UsageDaily u")
    Optional<LocalDate> findMinUsageDate();

    @Query("select max(u.usageDate) from UsageDaily u")
    Optional<LocalDate> findMaxUsageDate();

    /** 구간 {@code [from, toExclusive)} 행들의 가장 늦은 aggregated_at. 행이 없으면 empty. */
    @Query("select max(u.aggregatedAt) from UsageDaily u where u.usageDate >= :from and u.usageDate < :toExclusive")
    Optional<OffsetDateTime> findMaxAggregatedAt(@Param("from") LocalDate from,
                                                 @Param("toExclusive") LocalDate toExclusive);
}
