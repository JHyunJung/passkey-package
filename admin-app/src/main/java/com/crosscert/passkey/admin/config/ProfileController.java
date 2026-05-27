package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 비인증 — Login.tsx 가 로그인 전에 호출해서 local profile 여부를 판단한다.
 * local 이면 테스트 계정을 입력란에 prefill 한다. 활성 profile 이름만 노출하므로
 * 정보 유출 위험은 없다 (이미 README 등 공개 문서에 적힌 정보).
 *
 * AdminSecurityConfig 의 permitAll 화이트리스트에 /admin/api/profile 추가됨.
 */
@RestController
@RequestMapping("/admin/api")
public class ProfileController {

    private final Environment env;

    public ProfileController(Environment env) {
        this.env = env;
    }

    @GetMapping("/profile")
    public ApiResponse<ProfileView> profile() {
        List<String> profiles = List.of(env.getActiveProfiles());
        boolean isLocal = profiles.contains("local");
        return ApiResponse.ok(new ProfileView(profiles, isLocal));
    }

    public record ProfileView(List<String> active, boolean local) {}
}
