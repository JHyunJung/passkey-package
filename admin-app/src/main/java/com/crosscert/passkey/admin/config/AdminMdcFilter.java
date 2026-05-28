package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.core.api.RequestLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the currently authenticated principal from {@link SecurityContextHolder}
 * and stores its name into the request attribute {@link RequestLoggingFilter#ACTOR_EMAIL_ATTR}.
 *
 * <p>Wired by {@code AdminSecurityConfig} via
 * {@code http.addFilterAfter(..., AuthorizationFilter.class)}, so it runs
 * AFTER the Spring Security chain has populated the {@link SecurityContextHolder}
 * but still INSIDE the chain — meaning the context is populated when this
 * filter writes the attribute, then the outermost
 * {@code SecurityContextHolderFilter} clears the holder on the way out. The
 * core {@code RequestLoggingFilter} (which sits OUTSIDE the Spring Security
 * chain) later reads the request attribute in its finally block; attributes
 * survive the context clear because they are attached to the request, not
 * a thread-local.
 *
 * <p>The attribute is only set when a non-anonymous {@link Authentication}
 * is present, so unauthenticated requests do not leak a stray
 * "anonymousUser" string into request logs.
 */
public class AdminMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String name = auth.getName();
            if (name != null && !name.isBlank() && !"anonymousUser".equals(name)) {
                req.setAttribute(RequestLoggingFilter.ACTOR_EMAIL_ATTR, name);
            }
        }
        chain.doFilter(req, res);
    }
}
