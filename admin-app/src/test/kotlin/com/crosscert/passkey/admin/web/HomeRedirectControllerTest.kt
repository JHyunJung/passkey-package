package com.crosscert.passkey.admin.web

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.config.MeView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.UUID

/** 홈·옛 주소 리다이렉트(← App.tsx `<Route path="*">`, roles.test.ts rpTenantId, RequirePlatform.test.tsx). */
@AdminWebSliceTest(controllers = [HomeRedirectController::class])
class HomeRedirectControllerTest {

    @Autowired lateinit var mvc: MockMvc

    companion object {
        val LOW: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val HIGH: UUID = UUID.fromString("70000000-0000-0000-0000-000000000002")
        val LEGACY: List<String> = listOf("/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys")

        fun user(role: String, tenants: Set<UUID>): Authentication {
            val principal = AdminUserDetails(
                UUID.randomUUID(), "b@x.com", "x", role, tenants, true, null, Clock.systemUTC(),
            )
            return UsernamePasswordAuthenticationToken(principal, "x", principal.authorities)
        }
    }

    @Test
    fun platformHomeGoesToTenants() {
        for (url in listOf("/admin", "/admin/")) {
            mvc.perform(get(url).with(authentication(user("PLATFORM_OPERATOR", setOf()))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants"))
        }
    }

    @Test
    fun rpAdminHomeGoesToOwnTenant() {
        // tenantIds 는 MeController 가 UUID 순으로 정렬 — [0] 은 백엔드 기본 활성 RP(가장 작은 UUID)
        for (url in listOf("/admin", "/admin/")) {
            mvc.perform(get(url).with(authentication(user("RP_ADMIN", java.util.Set.of(HIGH, LOW)))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants/$LOW"))
        }
    }

    @Test
    fun rpAdminWithoutTenantGoesToTenantsListNot500() {
        // App.tsx: rpTenantId(me) 가 null 이면 /tenants(그 화면의 RequirePlatform 이 "계정 구성 오류")
        mvc.perform(get("/admin").with(authentication(user("RP_ADMIN", setOf()))))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/tenants"))
    }

    @Test
    fun legacyUrlsRedirect() {
        for (url in LEGACY) {
            mvc.perform(get(url).with(authentication(user("PLATFORM_OPERATOR", setOf()))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants"))
            mvc.perform(get("$url?x=1").with(authentication(user("RP_ADMIN", setOf(LOW)))))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/tenants/$LOW"))
        }
    }

    @Test
    fun anonymousGoesToLogin() {
        for (url in listOf("/admin", "/admin/api-keys")) {
            mvc.perform(get(url)).andExpect(status().isFound()).andExpect(redirectedUrl("/admin/login"))
        }
    }

    @Test
    fun homePathMatchesRolesTs() {
        assertThat(HomeRedirectController.homePath(MeView("a", "PLATFORM_OPERATOR", listOf(LOW), false, false, 30)))
            .isEqualTo("/admin/tenants")
        assertThat(HomeRedirectController.homePath(MeView("b", "RP_ADMIN", listOf(LOW, HIGH), false, false, 30)))
            .isEqualTo("/admin/tenants/$LOW")
        assertThat(HomeRedirectController.homePath(MeView("b", "RP_ADMIN", listOf(), false, false, 30)))
            .isEqualTo("/admin/tenants")
    }
}
