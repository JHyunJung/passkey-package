package com.crosscert.passkey.core.license;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-request VPD tenant pinning in on-prem (single-tenant) mode.
 * Pins TenantContextHolder to the licensed tenantId at request entry,
 * clears in finally. Runs even before LicenseGuardFilter so the
 * 503 response path (rendered by ApiResponse / GlobalExceptionHandler)
 * also sees the right tenant context if any DB lookup is needed.
 *
 * Order = HIGHEST_PRECEDENCE: must run before any DB-touching filter.
 */
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class OnpremTenantPinFilter extends OncePerRequestFilter {

    private final LicenseStateMachine stateMachine;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        UUID tenantId = UUID.fromString(stateMachine.token().tenantId());
        TenantContextHolder.set(tenantId);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
