package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.ActiveTenantResolver;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.AdminUserDetailsService;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.admin.policy.SecurityPolicyService;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 세션 속성의 JDK 직렬화 왕복 (Kotlin 전환 안전망, Review Focus 2).
 *
 * <p>Spring Session(Redis) 은 세션 속성을 JDK 직렬화로 저장한다({@code JdkSerializationRedisSerializer} 기본).
 * 로그인 → 다음 요청 사이에 속성이 왕복되지 못하면 로그아웃·500 이 된다. 구·신버전 공존은 없으므로(스펙 §4A-3)
 * 같은 코드로 직렬화·역직렬화되는지만 본다.
 *
 * <p><b>세션에 들어가는 객체 전수</b>({@code grep -rn "setAttribute\|@SessionAttributes\|HttpSession" admin-app/src/main},
 * 2026-11-07 Java base 기준 — {@code @SessionAttributes}·Flash 속성·요청 캐시 직접 사용 없음):
 * <ol>
 *   <li>{@code SPRING_SECURITY_CONTEXT} — {@link SecurityContext}({@code SecurityContextImpl}) ⊃
 *       {@link UsernamePasswordAuthenticationToken} ⊃ {@link AdminUserDetails}(id·email·hash·role·allowedTenantIds·
 *       enabled·lockedUntil·{@link Clock}) + {@code SimpleGrantedAuthority} + {@link WebAuthenticationDetails}.
 *       폼 로그인이 Spring Security 저장소로 넣는다.</li>
 *   <li>{@code MFA_PENDING}({@link MfaPendingFilter#MFA_PENDING_ATTR}) — {@link Boolean}. MFA 사용 운영자 로그인 성공 처리기.</li>
 *   <li>{@code ACTIVE_TENANT_ID}({@link ActiveTenantResolver#ACTIVE_TENANT_ATTR}) — {@link UUID}. 활성 RP 결정·전환.</li>
 *   <li>{@code SPRING_SECURITY_SAVED_REQUEST} — {@link DefaultSavedRequest}(프레임워크 타입). 미인증 화면 접근 시
 *       기본 요청 캐시가 넣는다.</li>
 * </ol>
 * 아래 테스트는 실제 로그인·요청 흐름(슬라이스 보안 체인)으로 세션을 채운 뒤 관찰된 속성 이름 집합이 이 목록과 같은지
 * 단언하고, 각 속성을 왕복시켜 핵심 필드를 비교한다. 목록이 바뀌면(새 세션 속성) 이 테스트가 먼저 깨진다.
 */
@AdminWebSliceTest(controllers = ActiveTenantController.class)
@Import(ActiveTenantResolver.class)
class SessionSerializationTest {

    static final Set<String> DOCUMENTED_ATTRIBUTES = Set.of(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            MfaPendingFilter.MFA_PENDING_ATTR,
            ActiveTenantResolver.ACTIVE_TENANT_ATTR,
            "SPRING_SECURITY_SAVED_REQUEST");

    static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    /** 지난 잠금 — 역직렬화된 Clock 으로 isAccountNonLocked 가 계산되는지 보기 위해 null 이 아닌 값을 둔다. */
    static final OffsetDateTime PAST_LOCK = OffsetDateTime.parse("2020-01-01T00:00:00+09:00");

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @Autowired AdminUserDetailsService uds;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired AdminUserRepository users;
    @Autowired SecurityPolicyService policy;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Fill.INSTANT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(encoder.matches(anyString(), anyString())).thenReturn(true);
        when(policy.get()).thenAnswer(Fill.ANSWER);
    }

    /** 운영과 같은 모양의 principal — AdminUserDetailsService 는 주입된 Clock(= Clock.system(KST)) 을 넘긴다. */
    private static AdminUserDetails principal(String email, String role, Set<UUID> tenants) {
        return new AdminUserDetails(ADMIN_ID, email, "$2a$12$hash", role, tenants, true, PAST_LOCK, Clock.system(KstTime.ZONE));
    }

    private void account(String email, String role, Set<UUID> tenants, boolean mfa) {
        when(uds.loadUserByUsername(email)).thenReturn(principal(email, role, tenants));
        AdminUser u = new AdminUser(email, "$2a$12$hash", role);
        u.setMfaEnabled(mfa);
        when(users.findByEmail(email)).thenReturn(Optional.of(u));
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/admin/login").with(csrf().asHeader())
                        .param("email", email).param("password", "pw"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private static Map<String, Object> attributes(MockHttpSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String n : Collections.list(s.getAttributeNames())) m.put(n, s.getAttribute(n));
        return m;
    }

    @SuppressWarnings("unchecked")
    static <T> T roundTrip(T value) throws Exception {
        assertThat(value).as("세션 속성은 Serializable").isInstanceOf(Serializable.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @Test
    void observedSessionAttributesMatchDocumentedListAndRoundTrip() throws Exception {
        Map<String, Object> observed = new LinkedHashMap<>();

        // A. RP_ADMIN(MFA 없음) 로그인 → 같은 세션으로 활성 RP 조회(세션 SecurityContext 로 인증, ACTIVE_TENANT_ID 생성).
        account("rp@example.com", "RP_ADMIN", Set.of(T2, T1), false);
        MockHttpSession rp = login("rp@example.com");
        MvcResult active = mvc.perform(get("/admin/api/active-tenant").session(rp)).andReturn();
        assertThat(active.getResponse().getStatus()).isEqualTo(200);
        assertThat(rp.getAttribute(ActiveTenantResolver.ACTIVE_TENANT_ATTR)).isEqualTo(T1); // TreeSet.first
        observed.putAll(attributes(rp));

        // B. MFA 사용 운영자 로그인 → MFA_PENDING.
        account("mfa@example.com", "PLATFORM_OPERATOR", Set.of(), true);
        MockHttpSession mfa = login("mfa@example.com");
        assertThat(mfa.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isEqualTo(Boolean.TRUE);
        observed.putAll(attributes(mfa));

        // C. 미인증 화면 접근 → 요청 캐시(SPRING_SECURITY_SAVED_REQUEST).
        MvcResult anon = mvc.perform(get("/admin/tenants")).andReturn();
        MockHttpSession anonSession = (MockHttpSession) anon.getRequest().getSession(false);
        if (anonSession != null) observed.putAll(attributes(anonSession));

        // 테스트 도구 산물: spring-security-test 의 csrf() 후처리기가 HttpSessionCsrfTokenRepository 로 토큰을 세션에 둔다.
        // 운영 저장소는 CookieCsrfTokenRepository(AdminSecurityConfig.buildCsrfTokenRepository)라 세션 속성이 아니다.
        assertThat(observed.remove("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN"))
                .as("csrf() 후처리기 산물").isNotNull();

        assertThat(new TreeSet<>(observed.keySet())).isEqualTo(new TreeSet<>(DOCUMENTED_ATTRIBUTES));

        // 각 속성 왕복.
        for (Map.Entry<String, Object> e : observed.entrySet()) {
            Object back = roundTrip(e.getValue());
            assertThat(back).as(e.getKey()).isNotNull().hasSameClassAs(e.getValue());
        }

        SecurityContext ctx = (SecurityContext) rp.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertSecurityContextRoundTrip(ctx, "rp@example.com", "RP_ADMIN", Set.of(T1, T2));
        SecurityContext mfaCtx = (SecurityContext) mfa.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertSecurityContextRoundTrip(mfaCtx, "mfa@example.com", "PLATFORM_OPERATOR", Set.of());

        assertThat(roundTrip(rp.getAttribute(ActiveTenantResolver.ACTIVE_TENANT_ATTR))).isEqualTo(T1);
        assertThat(roundTrip(mfa.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))).isEqualTo(Boolean.TRUE);
        DefaultSavedRequest saved = (DefaultSavedRequest) observed.get("SPRING_SECURITY_SAVED_REQUEST");
        DefaultSavedRequest savedBack = roundTrip(saved);
        assertThat(savedBack.getRequestURI()).isEqualTo(saved.getRequestURI()).isEqualTo("/admin/tenants");
        assertThat(savedBack.getMethod()).isEqualTo("GET");
    }

    private static void assertSecurityContextRoundTrip(SecurityContext ctx, String email, String role, Set<UUID> tenants)
            throws Exception {
        assertThat(ctx.getAuthentication()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        SecurityContext back = roundTrip(ctx);
        UsernamePasswordAuthenticationToken token = (UsernamePasswordAuthenticationToken) back.getAuthentication();
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getName()).isEqualTo(email);
        assertThat(token.getCredentials()).isNull(); // 인증 후 자격 증명 삭제(eraseCredentials) 그대로
        // 토큰 권한 = principal 의 ROLE_* + Security 7 이 인증 방식으로 더하는 FACTOR_PASSWORD.
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_" + role, "FACTOR_PASSWORD");
        assertThat(token.getDetails()).isInstanceOf(WebAuthenticationDetails.class);
        assertThat(token.getDetails()).isEqualTo(ctx.getAuthentication().getDetails());
        assertPrincipal((AdminUserDetails) token.getPrincipal(), email, role, tenants);
    }

    private static void assertPrincipal(AdminUserDetails p, String email, String role, Set<UUID> tenants) {
        assertThat(p.getId()).isEqualTo(ADMIN_ID);
        assertThat(p.getUsername()).isEqualTo(email);
        assertThat(p.getRole()).isEqualTo(role);
        assertThat(p.getAllowedTenantIds()).isEqualTo(tenants);
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.isAccountNonLocked()).isTrue(); // 역직렬화된 Clock 으로 PAST_LOCK 이후임을 계산
        assertThat(p.isAccountNonExpired()).isTrue();
        assertThat(p.isCredentialsNonExpired()).isTrue();
        assertThat(p.isPlatformOperator()).isEqualTo("PLATFORM_OPERATOR".equals(role));
        assertThat(p.isRpAdmin()).isEqualTo("RP_ADMIN".equals(role));
        assertThat(p.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_" + role);
    }

    @Test
    void adminUserDetailsRoundTripsStandalone() throws Exception {
        // 잠금 중(미래 lockedUntil)·잠금 없음(null) 두 경우 모두 Clock 과 함께 복원된다.
        AdminUserDetails locked = new AdminUserDetails(ADMIN_ID, "a@example.com", "h", "PLATFORM_OPERATOR", Set.of(),
                false, OffsetDateTime.parse("2999-01-01T00:00:00+09:00"), Clock.system(KstTime.ZONE));
        AdminUserDetails back = roundTrip(locked);
        assertThat(back.isAccountNonLocked()).isFalse();
        assertThat(back.isEnabled()).isFalse();
        assertThat(back.getPassword()).isEqualTo("h");

        AdminUserDetails open = new AdminUserDetails(ADMIN_ID, "b@example.com", "h", "RP_ADMIN", Set.of(T1),
                true, null, Clock.systemUTC());
        AdminUserDetails openBack = roundTrip(open);
        assertPrincipal(openBack, "b@example.com", "RP_ADMIN", Set.of(T1));
    }
}
