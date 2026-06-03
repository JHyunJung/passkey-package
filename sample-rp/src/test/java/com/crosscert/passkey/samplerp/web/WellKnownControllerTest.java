package com.crosscert.passkey.samplerp.web;

import com.crosscert.passkey.samplerp.config.WellKnownProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WellKnownController 단위 테스트. Security 필터 체인 없이 MVC 슬라이스만 띄우고
 * WellKnownProperties 는 테스트 전용 고정값 Bean 으로 주입한다. Security 통과 검증은
 * 별도 Task 에서 수동 curl 로 확인한다.
 */
@WebMvcTest(WellKnownController.class)
@AutoConfigureMockMvc(addFilters = false)
class WellKnownControllerTest {

    @Autowired
    MockMvc mvc;

    @TestConfiguration
    static class FixtureConfig {
        @Bean
        WellKnownProperties wellKnownProperties() {
            return new WellKnownProperties(
                    List.of(new WellKnownProperties.AndroidApp(
                            "com.example.app",
                            List.of("AA:BB", "CC:DD"))),
                    new WellKnownProperties.Ios(List.of(
                            "TEAMID1.com.example.app",
                            "TEAMID2.com.example.app")));
        }
    }

    @Test
    void assetLinks_returns200_applicationJson_withConfiguredValues() throws Exception {
        mvc.perform(get("/.well-known/assetlinks.json"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
           .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
           .andExpect(jsonPath("$[0].relation[1]").value("delegate_permission/common.get_login_creds"))
           .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
           .andExpect(jsonPath("$[0].target.package_name").value("com.example.app"))
           .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value("AA:BB"))
           .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[1]").value("CC:DD"));
    }

    @Test
    void appleAppSiteAssociation_returns200_applicationJson_withConfiguredApps() throws Exception {
        mvc.perform(get("/.well-known/apple-app-site-association"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
           .andExpect(jsonPath("$.webcredentials.apps[0]").value("TEAMID1.com.example.app"))
           .andExpect(jsonPath("$.webcredentials.apps[1]").value("TEAMID2.com.example.app"));
    }
}
