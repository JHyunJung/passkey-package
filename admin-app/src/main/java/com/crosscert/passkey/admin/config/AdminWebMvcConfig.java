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
                registry.addViewController("/admin").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/login").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/tenants").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/tenants/**").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/api-keys").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/audit").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/mds").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/keys").setViewName("forward:/admin/index.html");
            }
        };
    }
}
