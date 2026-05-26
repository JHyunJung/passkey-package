package com.crosscert.passkey.admin.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SPA bootstrap call. Returns the logged-in operator's identity so the
 * frontend can decide what to render. Returns 401 (handled by Spring
 * Security) when the session cookie is missing or expired.
 */
@RestController
@RequestMapping("/admin/api")
public class MeController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst().orElse("UNKNOWN");
        return Map.of("email", auth.getName(), "role", role);
    }
}
