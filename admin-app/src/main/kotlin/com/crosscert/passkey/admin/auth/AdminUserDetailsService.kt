package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AdminUserTenantRepository
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class AdminUserDetailsService(
    private val repo: AdminUserRepository,
    private val mappingRepo: AdminUserTenantRepository,
    private val clock: Clock,
) : UserDetailsService {

    override fun loadUserByUsername(email: String): AdminUserDetails {
        val u = repo.findByEmail(email)
            .orElseThrow { UsernameNotFoundException("admin not found: $email") }
        val allowed: Set<UUID> = java.util.HashSet(mappingRepo.findTenantIdsByAdminUserId(u.id))
        return AdminUserDetails(
            u.id,
            u.email,
            u.bcryptHash,
            u.role,
            allowed, // PLATFORM_OPERATOR 는 빈 Set, RP_ADMIN 은 1개 이상
            u.isEnabled,
            u.lockedUntil,
            clock,
        )
    }
}
