package com.crosscert.passkey.samplerp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/", "/register", "/login", "/logout",
                                     "/passkey/**", "/css/**", "/js/**").permitAll()
                    .anyRequest().permitAll())   // 데모용 — 보호 리소스 없음
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .formLogin(f -> f.disable())
            .httpBasic(h -> h.disable())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/"));
        return http.build();
    }
}
