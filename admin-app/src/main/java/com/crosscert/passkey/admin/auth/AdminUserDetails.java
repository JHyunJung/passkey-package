package com.crosscert.passkey.admin.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring Security principal — login 시 AdminUserDetailsService 가 채워서
 * SecurityContext 에 박는다. tenantId 가 null 이면 PLATFORM_OPERATOR.
 *
 * Service 계층은 SecurityContextHolder 에서 이 객체를 꺼내 tenant boundary 검사.
 */
public final class AdminUserDetails implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final String role;          // "PLATFORM_OPERATOR" | "RP_ADMIN"
    private final UUID tenantId;        // RP_ADMIN 은 non-null
    private final boolean enabled;
    private final java.time.Instant lockedUntil;
    private final java.time.Clock clock;

    public AdminUserDetails(UUID id, String email, String passwordHash,
                            String role, UUID tenantId, boolean enabled,
                            java.time.Instant lockedUntil, java.time.Clock clock) {
        this.id = Objects.requireNonNull(id);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.role = Objects.requireNonNull(role);
        this.tenantId = tenantId;
        this.enabled = enabled;
        this.lockedUntil = lockedUntil;
        this.clock = Objects.requireNonNull(clock);
    }

    public UUID getId()       { return id; }
    public String getRole()   { return role; }
    public UUID getTenantId() { return tenantId; }

    public boolean isPlatformOperator() { return "PLATFORM_OPERATOR".equals(role); }
    public boolean isRpAdmin()          { return "RP_ADMIN".equals(role); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword()              { return passwordHash; }
    @Override public String getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked() {
        return lockedUntil == null || clock.instant().isAfter(lockedUntil);
    }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return enabled; }
}
