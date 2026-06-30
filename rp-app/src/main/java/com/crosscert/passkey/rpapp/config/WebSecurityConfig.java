package com.crosscert.passkey.rpapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class WebSecurityConfig {

    private final CorsProperties cors;

    public WebSecurityConfig(CorsProperties cors) {
        this.cors = cors;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(
                                "/", "/register", "/login",
                                "/passkey/**", "/css/**", "/js/**",
                                "/.well-known/**", "/robots.txt")
                        .permitAll()
                        .anyRequest().permitAll()) // 데모용 — 보호 리소스 없음
                // 무상태 클라이언트: 서버 세션·CSRF 토큰을 쿠키에 담지 않으므로 CSRF 비활성.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .formLogin(f -> f.disable())
                .httpBasic(h -> h.disable())
                .logout(l -> l.disable());
        return http.build();
    }

    /**
     * /passkey/ 경로(**)에만 적용되는 CORS 정책.
     * ⚠️ 정확한 origin 목록만 허용(reflected-origin·와일드카드 금지, spec §3).
     * allowedOrigins 가 비면(기본) 매칭 origin 이 없으므로 cross-origin 요청은 막힌다(같은-origin 데모만).
     * 자격증명(쿠키)을 보내지 않으므로 allowCredentials=false.
     */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.allowedOrigins()); // 정확 목록, 반사 금지
        config.setAllowedMethods(List.of("POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/passkey/**", config);
        return source;
    }
}
