package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.AdminUserDetailsService;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.admin.auth.TenantContextAdminFilter;
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource;
import com.crosscert.passkey.admin.policy.SecurityPolicyService;
import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.util.CryptoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.crosscert.passkey.core.license.LicenseGuardFilter;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    /** Maximum email length that the ACTOR_EMAIL column (VARCHAR2 255) accepts. */
    private static final int MAX_EMAIL_LEN = 255;

    /** Fallback idle timeout (minutes) when the policy is unreadable or invalid — matches application.yml PT30M. Also used by MeController as the no-session fallback. */
    static final int DEFAULT_IDLE_MINUTES = 30;

    @Bean
    public DaoAuthenticationProvider adminAuthProvider(AdminUserDetailsService uds,
                                                       PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(encoder);
        // Surface UsernameNotFoundException to the failure handler so we
        // can classify unknown-user vs bad-password in audit + log. The
        // default behavior wraps UNF in BadCredentialsException to avoid
        // user enumeration via timing; we already perform credential-spray
        // rate limiting upstream, and admin-app is an internal surface
        // where operators need this distinction for incident response.
        p.setHideUserNotFoundExceptions(false);
        return p;
    }

    @Bean
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
                                                AuthenticationSuccessHandler ok,
                                                AuthenticationFailureHandler fail,
                                                LogoutSuccessHandler logoutOk,
                                                AccessDeniedHandler accessDenied,
                                                DynamicCorsConfigurationSource corsSource,
                                                Optional<LicenseGuardFilter> licenseGuard,
                                                TenantBoundary tenantBoundary) throws Exception {
        var csrfRepo = buildCsrfTokenRepository();
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // emit token on every response

        http
            // P0-6: security_policy.cors_allowlist 를 동적으로 적용. allowlist 가 비어
            // 있으면 corsSource 가 null 을 반환 → CORS 헤더 미발급(same-origin 만 허용).
            .cors(cors -> cors.configurationSource(corsSource))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/admin/login", "/admin/logout").permitAll()
                .requestMatchers("/admin/assets/**", "/admin/static/**", "/admin/index.html", "/admin/", "/admin", "/admin/favicon.ico", "/admin/favicon.png", "/favicon.ico").permitAll()
                // 브랜드 정적 에셋(Vite public/ → dist/ 루트로 복사되어 /admin/<name> 으로 서빙됨).
                // 로그인 전 화면(LoginPage)에서 노출되므로 미인증 허용. *.png 전체가 아니라
                // 파일명 명시 — 화이트리스트를 좁게 유지(favicon 선례와 동일).
                .requestMatchers("/admin/crosscert-logo-transparent.png").permitAll()
                .requestMatchers("/admin/tenants", "/admin/tenants/**", "/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys",
                                 "/admin/activity", "/admin/audit-chain", "/admin/settings", "/admin/license",
                                 "/admin/forgot-password", "/admin/reset-password", "/admin/signup").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // /admin/api/profile 은 active profile 만 노출. Login.tsx 가 미인증 상태에서
                // local 여부를 알아 테스트 계정 prefill 을 결정하는 데 쓴다.
                // /admin/api/license 는 DEAD 상태에서도 운영자가 라이선스 진단에 접근할 수 있어야 한다.
                .requestMatchers("/admin/api/profile", "/admin/api/license").permitAll()
                // 가입 요청(POST)만 미인증 허용 — 요청자는 아직 계정이 없다.
                // GET 목록과 /{id}/approve·reject 는 아래 /admin/api/** authenticated 에
                // 걸리고, 컨트롤러 @PreAuthorize 가 PLATFORM_OPERATOR 를 강제한다.
                .requestMatchers(HttpMethod.POST, "/admin/api/signup-requests").permitAll()
                // Self-service password reset (request + confirm) is unauthenticated —
                // the operator has no session (they forgot their password).
                .requestMatchers("/admin/api/password-reset/**").permitAll()
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
                .logoutSuccessHandler(logoutOk)
                .deleteCookies("SPRING_SESSION", "XSRF-TOKEN"))
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(csrfHandler)
                // 가입 요청 POST 와 비밀번호 재설정은 세션·XSRF-TOKEN 쿠키가 없는
                // 미인증 컨텍스트의 1회성 호출이라 CSRF 를 면제한다. 가입 요청은
                // 정확히 POST 한 건만 — 하위 경로(approve/reject)는 보호 유지.
                .ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/admin/api/signup-requests"),
                        PathPatternRequestMatcher.withDefaults().matcher("/admin/api/password-reset/**")));

        // onprem 모드에서만 존재하는 빈 — Optional 주입으로 SaaS 모드에서 absent.
        // SecurityContextHolderFilter 전에 삽입해 인증 처리 이전에 라이선스를 검사한다.
        licenseGuard.ifPresent(filter ->
                http.addFilterBefore(filter, org.springframework.security.web.context.SecurityContextHolderFilter.class));

        http.sessionManagement(s -> s
                .maximumSessions(5))    // 5 parallel browsers per operator
            // Return 401 JSON for unauthenticated API requests instead of
            // redirecting to the login page (default Spring behavior with
            // form login). The SPA checks /api/me on startup and expects
            // a 401 to know it must navigate to the login page.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(spaEntryPoint())
                .accessDeniedHandler(accessDenied))
            // Stash the authenticated principal name onto a request
            // attribute inside the Spring Security chain. The outermost
            // RequestLoggingFilter (in core) reads that attribute in its
            // finally block — by then SecurityContextHolder is already
            // cleared, but the request attribute survives. addFilterAfter
            // (AuthorizationFilter) runs once the security chain has
            // fully populated SecurityContextHolder.
            .addFilterAfter(new AdminMdcFilter(), AuthorizationFilter.class)
            // Second-factor gate. Runs AFTER AuthorizationFilter so the
            // SecurityContext is populated and the session is resolved; blocks
            // MFA-pending sessions from every /admin/api/** path except the
            // MFA endpoints + logout. The pending state is held server-side in
            // the HttpSession (set by adminLoginSuccessHandler), so it cannot
            // be spoofed by a client header.
            .addFilterAfter(new MfaPendingFilter(), AuthorizationFilter.class)
            // Defense-in-depth: for RP_ADMIN requests, set TenantContextHolder
            // so the Hibernate @Filter (activated by TenantFilterAspect) also
            // enforces tenant isolation at the ORM layer. PLATFORM_OPERATOR
            // scope is empty → no set → cross-tenant queries work as intended.
            // Placed AFTER MfaPendingFilter (which itself runs after
            // AuthorizationFilter) so it executes only once the SecurityContext
            // is populated AND the MFA gate has passed; cleared unconditionally
            // in finally to prevent thread-pool context leakage.
            .addFilterAfter(new TenantContextAdminFilter(tenantBoundary), MfaPendingFilter.class);
        return http.build();
    }

    /**
     * XSRF-TOKEN 쿠키 저장소. {@code withHttpOnlyFalse()} 로 SPA 가
     * {@code document.cookie} 로 읽어 {@code X-XSRF-TOKEN} 헤더로 echo 하게 하고,
     * {@code Path=/admin} 으로 쿠키를 스코프한다.
     *
     * <p><b>F-cookieSecure</b>: {@code Secure} 플래그는 명시적으로 세팅하지 않는다
     * (기본값 {@code null} → {@link org.springframework.security.web.csrf.CookieCsrfTokenRepository}
     * 가 매 요청마다 {@code request.isSecure()} 로 자동 판정). 이게 프로필별
     * 하드코딩보다 root-cause: {@code server.forward-headers-strategy=framework}
     * (G13 이 함께 적용) 가 켜지면 LB 뒤 TLS 종단 요청은 {@code X-Forwarded-Proto}
     * 로 {@code isSecure()=true} 가 정확히 반영되어 Secure 쿠키가 자동 발급되고,
     * dev/local 처럼 헤더가 없는 순수 http 환경은 {@code isSecure()=false} 라 로그인이
     * 깨지지 않는다. {@code SameSite=Lax} 는 {@code cookieCustomizer} 로 명시 설정
     * (spring-security-web 6.5 에는 {@code setSameSite} 가 없다) — Spring Session 의
     * {@code DefaultCookieSerializer} 는 기본값이 이미 Lax 이지만 이 저장소는
     * 명시하지 않으면 SameSite 속성 자체가 빠진다(세션 쿠키와의 일관성 확보).
     * customizer 는 {@code .secure(...)} 를 건드리지 않으므로 위 자동판정은 그대로 유지된다.
     */
    static CookieCsrfTokenRepository buildCsrfTokenRepository() {
        var repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/admin");
        repo.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repo;
    }

    /**
     * 미인증 요청 처리:
     *   - /admin/api/** (XHR/fetch) → 401 JSON. SPA 가 이 401 을 받아 로그인 화면으로
     *     navigate 한다(클라이언트 측 처리).
     *   - 그 외(페이지/루트/SPA 라우트 직접 접근) → /admin 으로 302 리다이렉트.
     *     로그인 안 된 상태에서 / 든 /something 이든 무조건 로그인 화면(/admin)으로
     *     보낸다. 페이지 요청에 401 JSON 을 주면 빈 화면이 되므로 리다이렉트가 맞다.
     */
    private AuthenticationEntryPoint spaEntryPoint() {
        return (HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) -> {
            String uri = req.getRequestURI();
            boolean isApi = uri != null && uri.startsWith("/admin/api/");
            // fetch/XHR 은 Accept 가 application/json 인 경우가 많아 보조 판별로도 씀.
            String accept = req.getHeader("Accept");
            boolean wantsJson = accept != null && accept.contains("application/json");
            if (isApi || wantsJson) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"unauthorized\"}");
            } else {
                res.sendRedirect("/admin");
            }
        };
    }

    @Bean
    public AuthenticationSuccessHandler adminLoginSuccessHandler(AuditLogService audit,
                                                                  AdminUserRepository users,
                                                                  SecurityPolicyService policy,
                                                                  Clock clock,
                                                                  ObjectMapper mapper) {
        return (HttpServletRequest req, HttpServletResponse res, Authentication auth) -> {
            String email = auth.getName();
            AdminUser u = users.findByEmail(email).orElseThrow();
            u.recordLogin(OffsetDateTime.now(clock));
            u.recordSuccessfulLogin();
            users.save(u);
            audit.append(new AuditAppendRequest(
                    u.getId(), email, "ADMIN_LOGIN",
                    null, null,
                    null,
                    Map.of("ip", req.getRemoteAddr(),
                           "ua", req.getHeader("User-Agent") == null ? "" : req.getHeader("User-Agent"))));
            // Snapshot the operator-configured idle timeout onto this session.
            // application.yml's session.timeout (PT30M) is only the pre-login
            // default; from here on the session honors the SecurityPolicy value
            // so the settings label and the front-end warning modal are truthful.
            // Defensive: if the policy row is missing or holds a non-positive
            // value (DB corruption), fall back to the 30-minute default rather
            // than failing login or setting an unbounded (negative) timeout.
            int idleMinutes = DEFAULT_IDLE_MINUTES;
            try {
                int configured = policy.get().sessionIdleTimeoutMinutes();
                if (configured >= 1) {
                    idleMinutes = configured;
                } else {
                    log.warn("security policy sessionIdleTimeoutMinutes={} is non-positive; using default {}min",
                            configured, DEFAULT_IDLE_MINUTES);
                }
            } catch (RuntimeException ex) {
                log.warn("could not read security policy for session timeout; using default {}min: {}",
                        DEFAULT_IDLE_MINUTES, ex.toString());
            }
            req.getSession().setMaxInactiveInterval(idleMinutes * 60);

            // For MFA-enabled operators, stamp the session as second-factor
            // pending. MfaPendingFilter then blocks /admin/api/** (except the
            // MFA endpoints + logout) until /admin/api/mfa/verify clears it.
            // Operators without MFA are unaffected — behavior is unchanged.
            boolean mfaRequired = u.isMfaEnabled();
            if (mfaRequired) {
                req.getSession().setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE);
            }
            log.info("admin login success: email={} role={} mfaRequired={}",
                    CryptoUtils.maskEmail(email), u.getRole(), mfaRequired);
            res.setStatus(HttpServletResponse.SC_OK);
            res.setContentType("application/json");
            // Use Jackson to avoid malformed JSON if email ever contains quotes/backslashes.
            res.getWriter().write(mapper.writeValueAsString(
                    Map.of("email", email, "role", u.getRole(), "mfaRequired", mfaRequired)));
        };
    }

    @Bean
    public AuthenticationFailureHandler adminLoginFailureHandler(
            AuditLogService audit, AdminUserRepository users, Clock clock,
            org.springframework.context.ApplicationEventPublisher events,
            @org.springframework.beans.factory.annotation.Value("${passkey.admin.lockout.max-attempts:5}") int maxAttempts,
            @org.springframework.beans.factory.annotation.Value("${passkey.admin.lockout.duration:PT15M}") java.time.Duration lockDuration) {
        return (HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) -> {
            String raw = req.getParameter("email");
            // Truncate to column limit (VARCHAR2 255) to prevent audit insert failures
            // when an attacker submits an oversized email in a credential spray.
            String email = raw == null ? "(unknown)"
                    : raw.length() > MAX_EMAIL_LEN ? raw.substring(0, MAX_EMAIL_LEN) : raw;
            String reason = (ex instanceof BadCredentialsException) ? "bad-password"
                    : (ex instanceof DisabledException) ? "user-disabled"
                    : (ex instanceof LockedException) ? "user-locked"
                    : (ex instanceof UsernameNotFoundException) ? "unknown-user"
                    : "other";
            // Count consecutive bad-password failures toward the lockout
            // threshold. recordFailedLogin auto-locks (and resets the counter)
            // once maxAttempts is reached; on the next attempt the
            // DaoAuthenticationProvider throws LockedException (audited as
            // "user-locked") without any extra wiring here.
            if (ex instanceof BadCredentialsException && raw != null) {
                users.findByEmail(raw).ifPresent(u -> {
                    u.recordFailedLogin(OffsetDateTime.now(clock), maxAttempts, lockDuration);
                    users.save(u);
                });
            }
            audit.append(new AuditAppendRequest(
                    null, email,
                    "ADMIN_LOGIN_FAILED", null, null,
                    null,
                    Map.of("ip", req.getRemoteAddr(),
                           "reason", reason)));
            log.warn("admin login failed: email={} reason={}", CryptoUtils.maskEmail(email), reason);
            events.publishEvent(new SecurityAlertEvent(
                    SecurityAlertEvent.AlertType.ADMIN_LOGIN_FAILURE,
                    SecurityAlertEvent.Severity.MEDIUM,
                    "admin login failed",
                    Map.of("email", CryptoUtils.maskEmail(email), "reason", reason)));
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
        };
    }

    @Bean
    public LogoutSuccessHandler adminLogoutSuccessHandler() {
        return (HttpServletRequest req, HttpServletResponse res, Authentication auth) -> {
            // /admin/logout is permitAll, so an unauthenticated request can
            // reach here with auth == null. Log either way so operators
            // see logout activity (including stray probes / replay attempts).
            String emailMasked = (auth == null) ? "anonymous" : CryptoUtils.maskEmail(auth.getName());
            log.info("admin logout: email={}", emailMasked);
            // Preserve prior default: redirect to /admin/login on logout success.
            res.setStatus(HttpServletResponse.SC_OK);
            res.sendRedirect("/admin/login");
        };
    }

    @Bean
    public AccessDeniedHandler adminAccessDeniedHandler() {
        return (HttpServletRequest req, HttpServletResponse res, org.springframework.security.access.AccessDeniedException ex) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String emailMasked = (auth == null) ? "anonymous" : CryptoUtils.maskEmail(auth.getName());
            log.warn("admin access denied: email={} method={} path={} cause={}",
                    emailMasked, req.getMethod(), req.getRequestURI(), ex.getClass().getSimpleName());
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"forbidden\"}");
        };
    }
}
