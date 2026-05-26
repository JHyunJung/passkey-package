package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.SchedulerLease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link SchedulerLease}.
 *
 * <p>PK is UUID (RAW(16) in Oracle). {@code findByNameForUpdate} is used by
 * {@code SchedulerLeaseService.tryAcquire(name, ...)} to obtain a pessimistic
 * write lock on the existing row — preserving the original atomic update
 * semantics. {@code deleteByNameAndHolder} ensures release is an atomic
 * predicate delete (no read-then-delete race).
 */
public interface SchedulerLeaseRepository extends JpaRepository<SchedulerLease, UUID> {

    /**
     * Plain lookup — used for read-only access (e.g. admin queries).
     */
    Optional<SchedulerLease> findByName(String name);

    /**
     * Pessimistic-write lookup for {@code tryAcquire}.
     * Issues {@code SELECT ... FOR UPDATE}, serializing concurrent lease
     * acquisition attempts on the same name at the DB level.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SchedulerLease l where l.name = :name")
    Optional<SchedulerLease> findByNameForUpdate(@Param("name") String name);

    /**
     * Atomic predicate delete — only removes the row when both name and holder
     * match, preventing a stale releaser from deleting a row that another
     * instance has since acquired.
     */
    @Modifying
    @Query("delete from SchedulerLease l where l.name = :name and l.holder = :holder")
    int deleteByNameAndHolder(@Param("name") String name, @Param("holder") String holder);
}
