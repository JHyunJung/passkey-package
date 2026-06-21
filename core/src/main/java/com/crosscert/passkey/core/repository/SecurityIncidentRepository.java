package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SecurityIncident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface SecurityIncidentRepository extends JpaRepository<SecurityIncident, UUID> {
    List<SecurityIncident> findAllByOrderByCreatedAtDesc();
    boolean existsByTenantIdAndStatus(UUID tenantId, String status);

    /**
     * 조건부 원자 UPDATE — OPEN 인 경우에만 RESOLVED 로 전이한다.
     *
     * <p>두 운영자가 같은 OPEN incident 를 동시에 resolve 해도 {@code WHERE status='OPEN'}
     * 이 DB 레벨에서 직렬화하므로 나중 flush 가 앞 값을 덮어쓰지 못한다. 1행이면 승자,
     * 0행이면 이미 RESOLVED 됐거나 없음(race 의 패자도 안전하게 여기로 떨어진다).
     *
     * <p>{@code clearAutomatically=true} 로 UPDATE 후 영속성 컨텍스트를 비운다 — 같은 트랜잭션에서
     * 이어지는 {@code findById} 가 1차 캐시의 stale(OPEN) 엔티티 대신 항상 fresh DB read 를 보게 한다.
     * (코드베이스의 다른 @Modifying UPDATE 6개와 동일 패턴.)
     *
     * @return 영향받은 행 수(1=전이 성공, 0=OPEN 아님/없음)
     */
    @Modifying(clearAutomatically = true)
    @Query("update SecurityIncident i set i.status='RESOLVED', i.resolvedBy=:by, "
            + "i.resolutionNote=:note, i.resolvedAt=:at "
            + "where i.id=:id and i.status='OPEN'")
    int resolveIfOpen(@Param("id") UUID id, @Param("by") UUID by,
                      @Param("note") String note, @Param("at") OffsetDateTime at);
}
