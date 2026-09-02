package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminSignupRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AdminSignupRequestRepository extends JpaRepository<AdminSignupRequest, UUID> {

    boolean existsByEmail(String email);

    List<AdminSignupRequest> findAllByOrderByRequestedAtAsc();

    /**
     * 건수 반환 삭제 — 승인/거절의 경합 판정용. 두 관리자가 같은 요청을 동시에
     * 처리하면 DB 가 직렬화해 한쪽만 1 을 받는다. 0 이면 이미 처리된 요청이므로
     * 호출측은 409 로 거부한다(계정 이중 생성 방지).
     *
     * <p>flushAutomatically/clearAutomatically: 호출측(SignupRequestService.approve)
     * 은 이 삭제를 admin_user INSERT 보다 **먼저** 실행한다. clearAutomatically 가
     * 영속성 컨텍스트를 비우므로, 삭제 뒤에 만든 엔티티는 영향받지 않지만 삭제
     * 전에 save() 만 하고 flush 되지 않은 엔티티는 유실된다 — 순서를 바꾸지 말 것.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AdminSignupRequest r where r.id = :id")
    int deleteIfPresent(@Param("id") UUID id);

    /**
     * 보존 정리: requested_at 이 cutoff 이전인 미처리 요청을 배치 삭제.
     * 방치된 요청이 대기 상한(100)을 영구히 점유하지 못하게 한다.
     * ROWNUM 캡 + 반복 호출 패턴은 다른 retention 쿼리와 동일.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM {h-schema}admin_signup_request WHERE id IN ("
         + "SELECT id FROM {h-schema}admin_signup_request WHERE requested_at < :cutoff "
         + "AND ROWNUM <= :batchSize)", nativeQuery = true)
    int deleteRequestedBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
