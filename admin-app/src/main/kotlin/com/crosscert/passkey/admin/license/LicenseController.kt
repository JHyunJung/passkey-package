package com.crosscert.passkey.admin.license

import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.license.LicenseProperties
import com.crosscert.passkey.core.license.LicenseState
import com.crosscert.passkey.core.license.LicenseStateMachine
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration

/**
 * GET /admin/api/license — returns the current LicenseView.
 *
 * Always available; in SaaS mode it returns a sentinel view
 * (deploymentMode=saas, state=null) so callers can use the same
 * call on both modes.
 * 화면(LicensePageController, ShellController, ShellAdvice)은 HTTP 가 아니라 [get] 을 직접 부른다.
 * LicenseStateMachine 빈은 passkey.deployment.mode=onprem 일 때만 있다(core LicenseBootstrap) — 없으면 위 sentinel.
 */
@RestController
@RequestMapping("/admin/api/license")
class LicenseController(
    @Value("\${passkey.deployment.mode:saas}") private val deploymentMode: String,
    private val stateMachine: ObjectProvider<LicenseStateMachine>,
    private val props: LicenseProperties,
    private val clock: Clock,
) {

    @GetMapping
    fun get(): ApiResponse<LicenseView> {
        val sm = stateMachine.getIfAvailable()
            ?: return ApiResponse.ok(
                LicenseView(deploymentMode, null, null, null, null, 0L, listOf(), null, null, null),
            )
        val s = sm.snapshot()
        val now = clock.instant()
        val daysLeft = maxOf(0L, Duration.between(now, s.token().expiresAt()).toDays())
        var graceLeft: Long? = null
        if (s.state() == LicenseState.NETWORK_GRACE && s.lastVerifiedAt() != null) {
            val max = s.token().limits().graceHoursWhenOffline().toLong()
            val elapsed = Duration.between(s.lastVerifiedAt(), now).toHours()
            graceLeft = maxOf(0L, max - elapsed)
        }
        val nextBeat = s.lastVerifiedAt()?.plus(props.heartbeatInterval())
        return ApiResponse.ok(
            LicenseView(
                deploymentMode,
                s.state().name,
                s.token().sub(),
                s.token().jti(),
                s.token().expiresAt(),
                daysLeft,
                // List.copyOf: 불변 사본(null 요소면 NPE).
                java.util.List.copyOf(s.token().features()),
                s.lastVerifiedAt(),
                graceLeft,
                nextBeat,
            ),
        )
    }
}
