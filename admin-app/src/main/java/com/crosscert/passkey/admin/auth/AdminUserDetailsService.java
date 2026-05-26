package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads an AdminUser by email. Spring Security's DaoAuthenticationProvider
 * then calls PasswordEncoder.matches(submitted, user.getPassword()) — see
 * AdminSecurityConfig for how the encoder is wired. The "ROLE_" prefix is
 * required by Spring's hasRole() expression evaluator.
 *
 * <p>codex (Phase 1 lesson): we deliberately do NOT pre-check for
 * non-existence here. Spring Security's DAO provider already runs a
 * dummy BCrypt match on failure (via prepareTimingAttackProtection() +
 * mitigateAgainstTimingAttack()), so timing across "user not found" vs
 * "wrong password" stays equal. If a future Spring upgrade removes that
 * behavior, add an explicit DUMMY_HASH check here.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository repo;

    public AdminUserDetailsService(AdminUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AdminUser u = repo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("no such admin user"));
        return User.builder()
                .username(u.getEmail())
                .password(u.getBcryptHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole())))
                .disabled(!u.isEnabled())
                .build();
    }
}
