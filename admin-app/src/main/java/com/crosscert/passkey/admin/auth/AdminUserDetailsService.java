package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repo;
    private final java.time.Clock clock;

    public AdminUserDetailsService(AdminUserRepository repo, java.time.Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Override
    public AdminUserDetails loadUserByUsername(String email) {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("admin not found: " + email));
        return new AdminUserDetails(
                u.getId(),
                u.getEmail(),
                u.getBcryptHash(),
                u.getRole(),
                u.getTenantId(),
                u.isEnabled(),
                u.getLockedUntil(),
                clock
        );
    }
}
