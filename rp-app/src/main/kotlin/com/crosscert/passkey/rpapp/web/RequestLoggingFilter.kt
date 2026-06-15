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
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * rp-app local twin of core's `RequestLoggingFilter`. rp-app
 * does not depend on core (separate webapp), so the logic is duplicated.
 *
 * 한 줄 요약(method/path/status/durMs)은 항상 INFO/WARN/ERROR 로 남기고,
 * request/response 본문은 전용 payload 로거에 DEBUG 로 남긴다. payload 로거는
 * logback 에서 local/dev 만 DEBUG 라 qa/prod 에선 자동 억제된다(레벨 게이트).
 * 본문은 2KB 로 캡하고, %msg SecretMaskingConverter 가 비밀값을 마스킹한다(삼중 방어).
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
        private val payloadLog = LoggerFactory.getLogger("com.crosscert.passkey.payload")

        private const val MDC_ACTOR_EMAIL = "actorEmail"

        /** Mirror of core's RequestLoggingFilter.ACTOR_EMAIL_ATTR. */
        const val ACTOR_EMAIL_ATTR = "com.crosscert.passkey.actorEmail"

        private val EXCLUDED_PATHS = setOf("/actuator/health")
        private const val MAX_BODY = 2048
    }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val path = req.requestURI
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res)
            return
        }
        val debugBody = payloadLog.isDebugEnabled
        val wrappedReq = if (debugBody) ContentCachingRequestWrapper(req) else req
        val wrappedRes = if (debugBody) ContentCachingResponseWrapper(res) else res

        val startNs = System.nanoTime()
        try {
            chain.doFilter(wrappedReq, wrappedRes)
        } finally {
            val durMs = (System.nanoTime() - startNs) / 1_000_000L
            val status = wrappedRes.status
            val actorPut = populateActorEmailMdc(req)
            try {
                val msg = String.format(
                    "request: method=%s path=%s status=%d durMs=%d",
                    req.method, path, status, durMs,
                )
                if (status >= 500) log.error(msg) else if (status >= 400) log.warn(msg) else log.info(msg)

                if (debugBody) {
                    logBody(wrappedReq as ContentCachingRequestWrapper, wrappedRes as ContentCachingResponseWrapper)
                }
            } finally {
                if (debugBody) {
                    (wrappedRes as ContentCachingResponseWrapper).copyBodyToResponse()
                }
                if (actorPut) MDC.remove(MDC_ACTOR_EMAIL)
            }
        }
    }

    private fun logBody(req: ContentCachingRequestWrapper, res: ContentCachingResponseWrapper) {
        val reqBody = cap(String(req.contentAsByteArray, Charsets.UTF_8))
        val resBody = cap(String(res.contentAsByteArray, Charsets.UTF_8))
        if (reqBody.isNotEmpty()) payloadLog.debug("req body: {}", reqBody)
        if (resBody.isNotEmpty()) payloadLog.debug("resp body: {}", resBody)
    }

    private fun cap(s: String): String =
        if (s.length <= MAX_BODY) s else s.substring(0, MAX_BODY) + "…[truncated ${s.length}]"

    private fun populateActorEmailMdc(req: HttpServletRequest): Boolean {
        return try {
            val v = req.getAttribute(ACTOR_EMAIL_ATTR) ?: return false
            val name = v.toString()
            if (name.isBlank()) return false
            MDC.put(MDC_ACTOR_EMAIL, name)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
