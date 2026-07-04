package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    /**
     * Used by AdminUserDetailsService at login time. Email is unique
     * (V9 constraint), so this returns at most one row.
     */
    Optional<AdminUser> findByEmail(String email);

    /**
     * G06 fix — locks every currently-ACTIVE PLATFORM_OPERATOR row (SELECT
     * ... FOR UPDATE) before the caller counts them, serializing the
     * last-operator lockout guard. Two concurrent suspend() calls targeting
     * distinct operators now block on each other here: the second
     * transaction's FOR UPDATE waits until the first commits its status
     * UPDATE, so it observes the post-suspend row set instead of a stale
     * pre-suspend snapshot (otherwise both see count=2 and both proceed,
     * leaving zero active operators — write-skew).
     *
     * <p>Returns rows (not COUNT) because Oracle rejects {@code SELECT
     * count(*) ... FOR UPDATE} (ORA-01786).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AdminUser u where u.role = 'PLATFORM_OPERATOR' and u.status = 'ACTIVE'")
    List<AdminUser> findActivePlatformOperatorsForUpdate();
}
