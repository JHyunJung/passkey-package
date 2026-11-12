package com.crosscert.passkey.admin.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * admin-app 의 base `application.yml` 이 `server.forward-headers-strategy=native` 인지 고정한다.
 * framework 는 X-Forwarded-For 맨 왼쪽(클라이언트가 채울 수 있는 값)을 믿어 감사 로그 IP(ADMIN_LOGIN 등)를 위조할 수 있었다.
 * native 의 실제 동작(위조 무시·스킴/포트 복원)은 rp-app `ProxiedSameOriginPostTest` 가 실제 Tomcat 으로 고정한다.
 */
class ForwardHeadersConfigTest {

    @Test
    fun baseApplicationYml_usesNativeForwardHeaders() {
        val value = YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
            .firstNotNullOfOrNull { it.getProperty("server.forward-headers-strategy") }
        assertThat(value).isEqualTo("native")
    }
}
