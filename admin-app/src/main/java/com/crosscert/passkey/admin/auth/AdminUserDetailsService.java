package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AdminUserTenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repo;
    private final AdminUserTenantRepository mappingRepo;
    private final java.time.Clock clock;

    @Override
    public AdminUserDetails loadUserByUsername(String email) {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("admin not found: " + email));
        Set<UUID> allowed = new HashSet<>(mappingRepo.findTenantIdsByAdminUserId(u.getId()));
        return new AdminUserDetails(
                u.getId(),
                u.getEmail(),
                u.getBcryptHash(),
                u.getRole(),
                allowed,          // PLATFORM_OPERATOR 는 빈 Set, RP_ADMIN 은 1개 이상
                u.isEnabled(),
                u.getLockedUntil(),
                clock
        );
    }
}
