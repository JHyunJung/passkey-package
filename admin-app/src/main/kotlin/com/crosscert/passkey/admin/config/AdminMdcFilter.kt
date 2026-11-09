package com.crosscert.passkey.admin.config

import com.crosscert.passkey.core.api.RequestLoggingFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads the currently authenticated principal from [SecurityContextHolder]
 * and stores its name into the request attribute [RequestLoggingFilter.ACTOR_EMAIL_ATTR].
 *
 * Wired by `AdminSecurityConfig` via
 * `http.addFilterAfter(..., AuthorizationFilter.class)`, so it runs
 * AFTER the Spring Security chain has populated the [SecurityContextHolder]
 * but still INSIDE the chain — meaning the context is populated when this
 * filter writes the attribute, then the outermost
 * `SecurityContextHolderFilter` clears the holder on the way out. The
 * core `RequestLoggingFilter` (which sits OUTSIDE the Spring Security
 * chain) later reads the request attribute in its finally block; attributes
 * survive the context clear because they are attached to the request, not
 * a thread-local.
 *
 * The attribute is only set when a non-anonymous `Authentication`
 * is present, so unauthenticated requests do not leak a stray
 * "anonymousUser" string into request logs.
 */
class AdminMdcFilter : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth != null && auth.isAuthenticated) {
            val name: String? = auth.name
            // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
            if (name != null && !name.all(Character::isWhitespace) && "anonymousUser" != name) {
                req.setAttribute(RequestLoggingFilter.ACTOR_EMAIL_ATTR, name)
            }
        }
        chain.doFilter(req, res)
    }
}
