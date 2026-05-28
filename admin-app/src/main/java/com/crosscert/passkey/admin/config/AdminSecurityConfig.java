package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.AdminUserDetailsService;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.time.Clock;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    /** Maximum email length that the ACTOR_EMAIL column (VARCHAR2 255) accepts. */
    private static final int MAX_EMAIL_LEN = 255;

    @Bean
    public DaoAuthenticationProvider adminAuthProvider(AdminUserDetailsService uds,
                                                       PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(encoder);
        return p;
    }

    @Bean
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
                                                AuthenticationSuccessHandler ok,
                                                AuthenticationFailureHandler fail) throws Exception {
        // CookieCsrfTokenRepository.withHttpOnlyFalse() lets the SPA
        // read the XSRF-TOKEN cookie via document.cookie and echo it
        // back in X-XSRF-TOKEN. Path=/admin scopes the cookie.
        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/admin");
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // emit token on every response

        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/admin/login", "/admin/logout").permitAll()
                .requestMatchers("/admin/assets/**", "/admin/static/**", "/admin/index.html", "/admin/", "/admin", "/admin/favicon.ico", "/favicon.ico").permitAll()
                .requestMatchers("/admin/tenants", "/admin/tenants/**", "/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // /admin/api/profile 은 active profile 만 노출. Login.tsx 가 미인증 상태에서
                // local 여부를 알아 테스트 계정 prefill 을 결정하는 데 쓴다.
                .requestMatchers("/admin/api/profile").permitAll()
                // Invitation token check (GET) and accept (POST) are unauthenticated —
                // the invited user has no session yet.
                .requestMatchers("/admin/api/invitations/**").permitAll()
                .requestMatchers("/admin/api/**").authenticated()
                .anyRequest().denyAll())
            .formLogin(form -> form
                .loginProcessingUrl("/admin/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler(ok)
                .failureHandler(fail))
            .logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/admin/login")
                .deleteCookies("SPRING_SESSION", "XSRF-TOKEN"))
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(csrfHandler)
                // Invitation accept is a one-time POST from an unauthenticated context
                // (no session, no XSRF-TOKEN cookie). Exempt the entire path so that
                // the SPA can call it without a prior GET to seed the CSRF cookie.
                .ignoringRequestMatchers("/admin/api/invitations/**"))
            .sessionManagement(s -> s
                .maximumSessions(5))    // 5 parallel browsers per operator
            // Return 401 JSON for unauthenticated API requests instead of
            // redirecting to the login page (default Spring behavior with
            // form login). The SPA checks /api/me on startup and expects
            // a 401 to know it must navigate to the login page.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(spaEntryPoint()))
            // Stash the authenticated principal name onto a request
            // attribute inside the Spring Security chain. The outermost
            // RequestLoggingFilter (in core) reads that attribute in its
            // finally block — by then SecurityContextHolder is already
            // cleared, but the request attribute survives. addFilterAfter
            // (AuthorizationFilter) runs once the security chain has
            // fully populated SecurityContextHolder.
            .addFilterAfter(new AdminMdcFilter(), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Return 401 JSON instead of redirecting to /login for unauthenticated
     * API requests. The SPA handles the 401 by navigating to the login page.
     */
    private AuthenticationEntryPoint spaEntryPoint() {
        return (HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) -> {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
        };
    }

    @Bean
    public AuthenticationSuccessHandler adminLoginSuccessHandler(AuditLogService audit,
                                                                  AdminUserRepository users,
                                                                  Clock clock,
                                                                  ObjectMapper mapper) {
        return (HttpServletRequest req, HttpServletResponse res, Authentication auth) -> {
            String email = auth.getName();
            AdminUser u = users.findByEmail(email).orElseThrow();
            u.recordLogin(clock.instant());
            users.save(u);
            audit.append(new AuditAppendRequest(
                    u.getId(), email, "ADMIN_LOGIN",
                    null, null,
                    null,
                    Map.of("ip", req.getRemoteAddr(),
                           "ua", req.getHeader("User-Agent") == null ? "" : req.getHeader("User-Agent"))));
            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType("application/json");
            // Use Jackson to avoid malformed JSON if email ever contains quotes/backslashes.
            res.getWriter().write(mapper.writeValueAsString(Map.of("email", email, "role", u.getRole())));
        };
    }

    @Bean
    public AuthenticationFailureHandler adminLoginFailureHandler(AuditLogService audit) {
        return (HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) -> {
            String raw = req.getParameter("email");
            // Truncate to column limit (VARCHAR2 255) to prevent audit insert failures
            // when an attacker submits an oversized email in a credential spray.
            String email = raw == null ? "(unknown)"
                    : raw.length() > MAX_EMAIL_LEN ? raw.substring(0, MAX_EMAIL_LEN) : raw;
            audit.append(new AuditAppendRequest(
                    null, email,
                    "ADMIN_LOGIN_FAILED", null, null,
                    null,
                    Map.of("ip", req.getRemoteAddr(),
                           "reason", ex.getClass().getSimpleName())));
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
        };
    }
}
