package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.activity.ActivityController;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditLogController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.credential.CredentialAdminController;
import com.crosscert.passkey.admin.funnel.AdoptionController;
import com.crosscert.passkey.admin.funnel.FunnelController;
import com.crosscert.passkey.admin.insights.PlatformInsightsController;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.mds.MdsAdminController;
import com.crosscert.passkey.admin.operator.AdminUserController;
import com.crosscert.passkey.admin.operator.PasswordResetController;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.policy.SecurityPolicyController;
import com.crosscert.passkey.admin.system.SystemInfoController;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.admin.web.HomeRedirectController;
import com.crosscert.passkey.admin.web.activity.ActivityPageController;
import com.crosscert.passkey.admin.web.audit.AuditChainPageController;
import com.crosscert.passkey.admin.web.auth.LoginPageController;
import com.crosscert.passkey.admin.web.auth.PasswordPagesController;
import com.crosscert.passkey.admin.web.auth.SignupPageController;
import com.crosscert.passkey.admin.web.insights.InsightsPageController;
import com.crosscert.passkey.admin.web.license.LicensePageController;
import com.crosscert.passkey.admin.web.settings.AccountTabController;
import com.crosscert.passkey.admin.web.settings.AdminUsersTabController;
import com.crosscert.passkey.admin.web.settings.MdsTabController;
import com.crosscert.passkey.admin.web.settings.SecurityPolicyTabController;
import com.crosscert.passkey.admin.web.settings.SettingsPageController;
import com.crosscert.passkey.admin.web.settings.SystemInfoTabController;
import com.crosscert.passkey.admin.web.shell.CommandPaletteController;
import com.crosscert.passkey.admin.web.shell.ShellController;
import com.crosscert.passkey.admin.web.support.ScreenErrorViewResolver;
import com.crosscert.passkey.admin.web.tenant.AaguidTabController;
import com.crosscert.passkey.admin.web.tenant.ApiKeysTabController;
import com.crosscert.passkey.admin.web.tenant.AuditTabController;
import com.crosscert.passkey.admin.web.tenant.CredentialsTabController;
import com.crosscert.passkey.admin.web.tenant.FunnelTabController;
import com.crosscert.passkey.admin.web.tenant.TenantDetailPageController;
import com.crosscert.passkey.admin.web.tenant.TenantsPageController;
import com.crosscert.passkey.admin.web.tenant.WebauthnTabController;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 렌더 HTML 스냅샷 테스트 공통 — 슬라이스 구성·결정적 스텁·요청 도우미.
 *
 * <p>모든 화면 컨트롤러를 한 슬라이스에 올리고({@link HtmlContractSlice} — 같은 구성이라 하위 테스트 클래스가 컨텍스트를 공유),
 * 화면 컨트롤러가 부르는 REST 컨트롤러는 {@link Fill#ANSWER} 기본 응답을 내는 mock 으로 둔다. mock 은 싱글턴으로 직접
 * 등록해(기존 {@code @MockitoBean} 과 같이) 메서드 보안 프록시를 거치지 않는다 — 화면 컨트롤러 자신의 {@code @PreAuthorize}
 * 와 보안 체인은 그대로 적용된다. 각 테스트는 화면에 의미 있는 값(상태·불리언 true/false 둘 다)을 {@link Stub} 으로 덧씌운다.
 * mock 의 스텁은 테스트마다 초기화된다(기본 Answer 는 유지).
 *
 * <p>시계: 슬라이스의 {@code Clock} mock 을 {@link #NOW} 로 고정한다. 스텁의 시각은 {@link Fill#ODT}(NOW 2일 전) 등 고정값.
 */
abstract class HtmlContractSupport {

    /** 화면 컨트롤러 전부 + 오류 화면 해석기 + REST 컨트롤러 mock. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @AdminWebSliceTest(controllers = {
            TenantsPageController.class, TenantDetailPageController.class, ApiKeysTabController.class,
            AaguidTabController.class, WebauthnTabController.class, AuditTabController.class,
            CredentialsTabController.class, FunnelTabController.class,
            SettingsPageController.class, AccountTabController.class, AdminUsersTabController.class,
            MdsTabController.class, SecurityPolicyTabController.class, SystemInfoTabController.class,
            LicensePageController.class, InsightsPageController.class, ActivityPageController.class,
            AuditChainPageController.class, ShellController.class, CommandPaletteController.class,
            LoginPageController.class, PasswordPagesController.class, SignupPageController.class,
            HomeRedirectController.class
    })
    @Import({ScreenErrorViewResolver.class, ControllerMocks.class})
    @interface HtmlContractSlice {}

    static final Instant NOW = Instant.parse("2026-10-13T03:34:56Z");
    static final OffsetDateTime T0 = Fill.ODT; // NOW - 2일
    static final UUID TID = Fill.ID;
    static final UUID TID2 = UUID.fromString("00000000-0000-0000-0000-00000000f222");
    static final UUID OP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID RP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final String OP_EMAIL = "op@example.com";
    static final String RP_EMAIL = "rp@example.com";

    /** REST 컨트롤러 mock 을 싱글턴으로 등록한다(프록시·후처리기 없음 — {@code @MockitoBean} 과 같은 성질). */
    static class ControllerMocks implements BeanFactoryPostProcessor {
        static final List<Class<?>> TYPES = List.of(
                TenantAdminController.class, ApiKeyAdminController.class, FunnelController.class,
                ActivityController.class, AuditLogController.class, AuditChainMonitorController.class,
                AaguidPolicyController.class, CredentialAdminController.class, AdoptionController.class,
                PlatformInsightsController.class, SignupRequestController.class, SecurityPolicyController.class,
                MfaController.class, SystemInfoController.class, AdminUserController.class,
                MdsAdminController.class, LicenseController.class, PasswordResetController.class,
                ActiveTenantController.class);
        static final List<Object> ALL = new ArrayList<>();

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
            for (Class<?> t : TYPES) {
                Object m = Mockito.mock(t, ANSWER);
                ALL.add(m);
                String n = t.getSimpleName();
                bf.registerSingleton(Character.toLowerCase(n.charAt(0)) + n.substring(1), m);
            }
        }
    }

    /**
     * {@link Fill#ANSWER} + {@code ApiResponse<Void>} 은 {@code ApiResponse.ok()}, {@code ResponseEntity<X>} 는 200 + X 값.
     * ({@code ResponseEntity<?>} 를 돌려주는 메서드는 테스트에서 직접 스텁한다.)
     */
    static final Answer<Object> ANSWER = inv -> {
        Class<?> raw = inv.getMethod().getReturnType();
        if (raw == void.class || raw == Void.class) return null;
        return value(inv.getMethod().getGenericReturnType());
    };

    private static Object value(Type t) {
        if (t instanceof ParameterizedType pt) {
            Type arg = pt.getActualTypeArguments()[0];
            if (pt.getRawType() == ApiResponse.class && arg == Void.class) return ApiResponse.ok();
            if (pt.getRawType() == org.springframework.http.ResponseEntity.class && arg == Void.class) {
                return org.springframework.http.ResponseEntity.ok().build();
            }
            if (pt.getRawType() == org.springframework.http.ResponseEntity.class) {
                return org.springframework.http.ResponseEntity.ok(value(arg));
            }
        }
        return Fill.value(t);
    }

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @Autowired AdminUserRepository admins;

    @Autowired TenantAdminController tenants;
    @Autowired ApiKeyAdminController apiKeys;
    @Autowired FunnelController funnel;
    @Autowired ActivityController activity;
    @Autowired AuditLogController auditLog;
    @Autowired AuditChainMonitorController chain;
    @Autowired AaguidPolicyController aaguid;
    @Autowired CredentialAdminController credentials;
    @Autowired AdoptionController adoption;
    @Autowired PlatformInsightsController insights;
    @Autowired SignupRequestController signups;
    @Autowired SecurityPolicyController securityPolicy;
    @Autowired MfaController mfa;
    @Autowired SystemInfoController systemInfo;
    @Autowired AdminUserController adminUsers;
    @Autowired MdsAdminController mds;
    @Autowired LicenseController license;
    @Autowired PasswordResetController passwordReset;
    @Autowired ActiveTenantController activeTenants;

    @BeforeEach
    void baseStubs() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.millis()).thenReturn(NOW.toEpochMilli());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(admins.findByEmail(anyString())).thenAnswer(inv -> Optional.of(new AdminUser(inv.getArgument(0), "x", "PLATFORM_OPERATOR")));
    }

    @AfterEach
    void resetControllerMocks() {
        ControllerMocks.ALL.forEach(Mockito::reset);
    }

    // ── principals ─────────────────────────────────────────

    static Authentication operator() {
        return auth(OP_ID, OP_EMAIL, "PLATFORM_OPERATOR", Set.of());
    }

    static Authentication rpAdmin() {
        return auth(RP_ID, RP_EMAIL, "RP_ADMIN", Set.of(TID, TID2));
    }

    static Authentication auth(UUID id, String email, String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(id, email, "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    // ── 요청 도우미 ─────────────────────────────────────────

    static MockHttpServletRequestBuilder page(String url, Authentication a) {
        return get(URI.create(url)).with(authentication(a));
    }

    static MockHttpServletRequestBuilder frag(String url, Authentication a) {
        return get(URI.create(url)).with(authentication(a)).header("HX-Request", "true");
    }

    /** htmx 쓰기 요청(POST/PUT/DELETE) — CSRF 포함. */
    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, Authentication a) {
        return b.with(authentication(a)).with(csrf()).header("HX-Request", "true");
    }

    /** 요청을 보내고 {@code contract/html/<path>} 스냅샷과 비교한다. */
    MvcResult snap(String path, MockHttpServletRequestBuilder req) throws Exception {
        MvcResult r = mvc.perform(req).andReturn();
        HtmlSnapshot.assertMatches(path, r);
        return r;
    }
}
