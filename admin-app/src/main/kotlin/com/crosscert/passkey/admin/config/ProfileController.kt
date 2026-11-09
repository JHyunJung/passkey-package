package com.crosscert.passkey.admin.config

import com.crosscert.passkey.core.api.ApiResponse
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 비인증 — Login.tsx 가 로그인 전에 호출해서 local profile 여부를 판단한다.
 * local 이면 테스트 계정을 입력란에 prefill 한다. 활성 profile 이름만 노출하므로
 * 정보 유출 위험은 없다 (이미 README 등 공개 문서에 적힌 정보).
 *
 * AdminSecurityConfig 의 permitAll 화이트리스트에 /admin/api/profile 추가됨.
 */
@RestController
@RequestMapping("/admin/api")
class ProfileController(private val env: Environment) {

    @GetMapping("/profile")
    fun profile(): ApiResponse<ProfileView> {
        val profiles: List<String> = java.util.List.of(*env.activeProfiles)
        val isLocal = profiles.contains("local")
        // 가입 화면이 PLATFORM_OPERATOR 즉시 가입 선택지를 보일지 정한다(SignupRequestService 와 같은 속성).
        val selfSignup: Boolean =
            env.getProperty("admin.signup.auto-approve-platform-operator", Boolean::class.javaObjectType, false)
        return ApiResponse.ok(ProfileView(profiles, isLocal, selfSignup))
    }

    data class ProfileView(val active: List<String>?, val local: Boolean, val platformOperatorSelfSignup: Boolean)
}
