package com.crosscert.passkey.core.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code @Transactional} 메서드 진입 시 현재 {@link TenantContextHolder}의 tenant 로
 * Hibernate {@code tenantFilter}를 enable 한다. context가 null 이면 enable 하지
 * 않는다(=cross-tenant; admin PLATFORM_OPERATOR 케이스).
 *
 * <p>VPD와 독립적인 앱 레벨 격리의 중앙 hook. VPD off(Oracle SE2) 에서도 이 Aspect가
 * tenant 격리를 보장한다.
 *
 * <h2>@Order 결정: {@code Ordered.LOWEST_PRECEDENCE}</h2>
 *
 * <p>Spring AOP advice ordering: 낮은 order 값 = 높은 우선순위 = 바깥쪽 래퍼.
 * Spring의 {@code TransactionInterceptor} 기본 order는 {@code Ordered.LOWEST_PRECEDENCE}
 * ({@code Integer.MAX_VALUE}) — 가장 안쪽 래퍼(innermost) 직전에 메서드를 호출한다.
 *
 * <p>이 aspect도 동일한 {@code Ordered.LOWEST_PRECEDENCE}로 설정한다. 같은 order 값을
 * 가진 두 advisor가 충돌하면 Spring은 definition order를 사용한다. 인프라 advisor
 * ({@code TransactionInterceptor})는 애플리케이션 {@code @Aspect} 컴포넌트보다 먼저
 * 등록되므로, 같은 order에서 tx가 바깥쪽, 이 aspect가 안쪽이 된다.
 *
 * <p>결과적인 실행 흐름:
 * <pre>
 *   tx advice.before() [트랜잭션 열기, JPA 세션 thread-bind]
 *     → our aspect.before() [em.unwrap(Session) → 트랜잭션 bound session → filter 설정]
 *       → target method [filter가 활성화된 세션으로 쿼리 실행]
 *     → our aspect.after()
 *   tx advice.after() [commit/rollback]
 * </pre>
 *
 * <p>{@code LOWEST_PRECEDENCE - 1} (바깥쪽)로 설정하면 tx가 트랜잭션을 열기 전에
 * {@code em.unwrap(Session)} 이 호출되어 non-transactional 임시 세션을 반환하고,
 * 그 세션에 설정한 filter는 실제 쿼리 세션에 영향을 주지 않는다.
 * {@code LOWEST_PRECEDENCE} (안쪽)로 설정하면 트랜잭션이 이미 열린 상태에서
 * {@code em.unwrap(Session)}이 transaction-bound 세션을 반환하므로 filter가 올바르게
 * 적용된다. {@link com.crosscert.passkey.core.tenant.TenantFilterAspectIT}에서 검증됨.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager em;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
          + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object enableTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
        UUID tid = TenantContextHolder.get();
        if (tid != null) {
            Session session = em.unwrap(Session.class);
            session.enableFilter("tenantFilter").setParameter("tenantId", tid);
        }
        return pjp.proceed();
    }
}
