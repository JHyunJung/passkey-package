package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.UsagePeriodClose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** usage_period_close(월 마감 기록). 쓰기는 INSERT 만 한다. */
public interface UsagePeriodCloseRepository extends JpaRepository<UsagePeriodClose, String> {

    List<UsagePeriodClose> findAllByOrderByPeriodDesc();
}
