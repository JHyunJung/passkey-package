package com.crosscert.passkey.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA routing fallback: any GET /admin/* request that isn't a static
 * asset or REST endpoint forwards to index.html, so client-side React
 * Router can render the right page. /admin/api/** is already mapped to
 * @RestController endpoints, so it takes precedence.
 */
@Configuration
public class AdminWebMvcConfig {

    @Bean
    public WebMvcConfigurer adminSpaForwarding() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                // 루트(/) 접속 시 admin 콘솔(/admin)로 자동 리다이렉트. redirect 라
                // 브라우저 주소창이 /admin 으로 바뀐다(forward 가 아님).
                registry.addRedirectViewController("/", "/admin");
                registry.addViewController("/admin").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/login").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/tenants").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/tenants/**").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/api-keys").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/audit").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/mds").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/keys").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/activity").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/audit-chain").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/settings").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/license").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/forgot-password").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/reset-password").setViewName("forward:/admin/index.html");
            }
        };
    }
}
