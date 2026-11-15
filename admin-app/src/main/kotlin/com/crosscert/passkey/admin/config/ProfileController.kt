package com.crosscert.passkey.admin.config

import com.crosscert.passkey.core.api.ApiResponse
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 비인증. 로그인 화면(LoginPageController)이 메서드로 불러 데모 계정 카드를 보일지 정하고,
 * 가입 화면(SignupPageController)이 PLATFORM_OPERATOR 즉시 가입 선택지를 보일지 정한다. 모든 화면 모델의 `profile`
 * (ViewModelAdvice)도 이 값이다. 노출하는 것은 활성 profile 이름, local 여부, PLATFORM_OPERATOR 즉시 가입 설정
 * 여부([ProfileView])뿐이다.
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
