package com.crosscert.passkey.rpapp.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * rp-app local twin of core's `RequestLoggingFilter`. rp-app
 * does not depend on core (separate webapp), so the logic is duplicated.
 *
 * Drift check: keep status-mapping + format + actorEmail handling
 * identical to core. Intentional diffs only in excluded-paths set
 * (rp-app does not expose `/.well-known/jwks.json`).
 *
 * rp-app's own `TraceIdFilter` (under `common.filter`)
 * runs at `HIGHEST_PRECEDENCE` and populates `traceId` MDC
 * before this filter logs. When rp-app calls passkey-app via the SDK,
 * `TraceIdPropagationInterceptor` carries the same `X-Trace-Id`
 * forward so the two servers' logs share one trace.
 *
 * actorEmail is read from a request attribute set by an in-security-chain
 * filter (rp-app does not yet have one — all routes are permitAll —
 * so this slot stays empty, matching the demo's no-auth posture).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestLoggingFilter : OncePerRequestFilter() {

    companion object {
        private val log = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

        private const val MDC_ACTOR_EMAIL = "actorEmail"

        /** Mirror of core's RequestLoggingFilter.ACTOR_EMAIL_ATTR. */
        const val ACTOR_EMAIL_ATTR = "com.crosscert.passkey.actorEmail"

        private val EXCLUDED_PATHS = setOf("/actuator/health")
    }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val path = req.requestURI
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res)
            return
        }
        val startNs = System.nanoTime()
        try {
            chain.doFilter(req, res)
        } finally {
            val durMs = (System.nanoTime() - startNs) / 1_000_000L
            val status = res.status
            val actorPut = populateActorEmailMdc(req)
            try {
                val msg = String.format(
                    "request: method=%s path=%s status=%d durMs=%d",
                    req.method, path, status, durMs,
                )
                if (status >= 500) {
                    log.error(msg)
                } else if (status >= 400) {
                    log.warn(msg)
                } else {
                    log.info(msg)
                }
            } finally {
                if (actorPut) {
                    MDC.remove(MDC_ACTOR_EMAIL)
                }
            }
        }
    }

    private fun populateActorEmailMdc(req: HttpServletRequest): Boolean {
        return try {
            val v = req.getAttribute(ACTOR_EMAIL_ATTR) ?: return false
            val name = v.toString()
            if (name.isBlank()) {
                return false
            }
            MDC.put(MDC_ACTOR_EMAIL, name)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
