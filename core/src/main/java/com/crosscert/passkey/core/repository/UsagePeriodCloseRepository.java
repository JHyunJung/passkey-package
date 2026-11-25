package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.UsagePeriodClose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * usage_period_close(월 마감 기록). 쓰기는 INSERT 만 한다.
 *
 * <p>마감과 일반 집계의 직렬화: 두 경로 모두 트랜잭션의 첫 문장으로 이 테이블에 테이블 락을 건다.
 * 집계는 SHARE(집계끼리는 함께 가능), 마감은 EXCLUSIVE(집계·다른 마감과 배타). 락은 커밋·롤백 때 풀린다.
 * 그래서 "그달이 마감됐나" 확인과 usage_daily MERGE 가 마감 트랜잭션과 엇갈리지 않는다.
 * 테이블 락은 이 테이블의 객체 권한(SELECT·INSERT)만으로 걸 수 있다. 둘 다 진행 중인 트랜잭션 안에서만 의미가 있어 MANDATORY 다.
 */
public interface UsagePeriodCloseRepository extends JpaRepository<UsagePeriodClose, String> {

    List<UsagePeriodClose> findAllByOrderByPeriodDesc();

    /** 일반 집계용. 진행 중인 마감(EXCLUSIVE 보유)이 끝날 때까지 기다린다. */
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "LOCK TABLE {h-schema}usage_period_close IN SHARE MODE", nativeQuery = true)
    void lockForRollup();

    /** 월 마감용. 진행 중인 집계(SHARE 보유)·다른 마감이 끝날 때까지 기다린다. */
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "LOCK TABLE {h-schema}usage_period_close IN EXCLUSIVE MODE", nativeQuery = true)
    void lockForClose();
}
