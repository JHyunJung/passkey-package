package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    /**
     * Used by AdminUserDetailsService at login time. Email is unique
     * (V9 constraint), so this returns at most one row.
     */
    Optional<AdminUser> findByEmail(String email);
}
