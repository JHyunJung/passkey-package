package com.crosscert.passkey.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 루트(/) → /admin 리다이렉트. 화면은 Thymeleaf 컨트롤러(admin.web 패키지)가 그린다.
 * /admin 과 옛 URL(/admin/api-keys 등) 리다이렉트는 HomeRedirectController 가 맡는다.
 */
@Configuration
class AdminWebMvcConfig {

    @Bean
    fun adminRootRedirect(): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addViewControllers(registry: ViewControllerRegistry) {
            // 루트(/) 접속 시 admin 콘솔(/admin)로 자동 리다이렉트. redirect 라
            // 브라우저 주소창이 /admin 으로 바뀐다(forward 가 아님).
            registry.addRedirectViewController("/", "/admin")
        }
    }
}
