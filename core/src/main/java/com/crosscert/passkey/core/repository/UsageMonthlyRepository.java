package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.UsageMonthly;
import com.crosscert.passkey.core.entity.UsageMonthlyId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** usage_monthly(마감된 달의 테넌트별 확정값). 쓰기는 INSERT 만 한다. */
public interface UsageMonthlyRepository extends JpaRepository<UsageMonthly, UsageMonthlyId> {

    List<UsageMonthly> findByPeriod(String period);
}
