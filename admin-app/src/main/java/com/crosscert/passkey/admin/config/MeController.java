package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.core.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPA bootstrap call. AdminUserDetails 에서 직접 email/role/tenantId 추출.
 */
@RestController
@RequestMapping("/admin/api")
public class MeController {

    @GetMapping("/me")
    public ApiResponse<MeView> me(Authentication auth) {
        AdminUserDetails principal = (AdminUserDetails) auth.getPrincipal();
        return ApiResponse.ok(new MeView(
                principal.getUsername(),
                principal.getRole(),
                principal.getTenantId()
        ));
    }
}
