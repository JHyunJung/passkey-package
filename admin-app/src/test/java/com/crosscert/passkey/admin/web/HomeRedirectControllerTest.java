package com.crosscert.passkey.admin.web;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.config.MeView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 홈·옛 주소 리다이렉트(← App.tsx {@code <Route path="*">}, roles.test.ts rpTenantId, RequirePlatform.test.tsx). */
@AdminWebSliceTest(controllers = HomeRedirectController.class)
class HomeRedirectControllerTest {

    @Autowired MockMvc mvc;

    static final UUID LOW = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID HIGH = UUID.fromString("70000000-0000-0000-0000-000000000002");
    static final List<String> LEGACY = List.of("/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys");

    static Authentication user(String role, Set<UUID> tenants) {
        AdminUserDetails principal = new AdminUserDetails(
                UUID.randomUUID(), "b@x.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(principal, "x", principal.getAuthorities());
    }

    @Test
    void platformHomeGoesToTenants() throws Exception {
        for (String url : List.of("/admin", "/admin/")) {
            mvc.perform(get(url).with(authentication(user("PLATFORM_OPERATOR", Set.of()))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants"));
        }
    }

    @Test
    void rpAdminHomeGoesToOwnTenant() throws Exception {
        // tenantIds 는 MeController 가 UUID 순으로 정렬 — [0] 은 백엔드 기본 활성 RP(가장 작은 UUID)
        for (String url : List.of("/admin", "/admin/")) {
            mvc.perform(get(url).with(authentication(user("RP_ADMIN", Set.of(HIGH, LOW)))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants/" + LOW));
        }
    }

    @Test
    void rpAdminWithoutTenantGoesToTenantsListNot500() throws Exception {
        // App.tsx: rpTenantId(me) 가 null 이면 /tenants(그 화면의 RequirePlatform 이 "계정 구성 오류")
        mvc.perform(get("/admin").with(authentication(user("RP_ADMIN", Set.of()))))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/tenants"));
    }

    @Test
    void legacyUrlsRedirect() throws Exception {
        for (String url : LEGACY) {
            mvc.perform(get(url).with(authentication(user("PLATFORM_OPERATOR", Set.of()))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants"));
            mvc.perform(get(url + "?x=1").with(authentication(user("RP_ADMIN", Set.of(LOW)))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants/" + LOW));
        }
    }

    @Test
    void anonymousGoesToLogin() throws Exception {
        for (String url : List.of("/admin", "/admin/api-keys")) {
            mvc.perform(get(url)).andExpect(status().isFound()).andExpect(redirectedUrl("/admin/login"));
        }
    }

    @Test
    void homePathMatchesRolesTs() {
        assertThat(HomeRedirectController.homePath(new MeView("a", "PLATFORM_OPERATOR", List.of(LOW), false, false, 30)))
                .isEqualTo("/admin/tenants");
        assertThat(HomeRedirectController.homePath(new MeView("b", "RP_ADMIN", List.of(LOW, HIGH), false, false, 30)))
                .isEqualTo("/admin/tenants/" + LOW);
        assertThat(HomeRedirectController.homePath(new MeView("b", "RP_ADMIN", List.of(), false, false, 30)))
                .isEqualTo("/admin/tenants");
    }
}
