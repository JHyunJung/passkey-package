package com.crosscert.passkey.admin.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.annotation.Configuration

/**
 * P6 — passkey-app 의 풀 기본값을 20 으로 올리면서 공유 core application-common.yml 의
 * maximum-pool-size 를 placeholder 로 바꾼다. admin-app 도 같은 파일을 import 하므로,
 * admin-app 의 실효 DB 풀·Tomcat 설정이 전 프로필에서 변경 전과 동일함(풀 10, 워커 200)을
 * 고정하는 스냅샷. LogLevelSnapshotTest 와 같은 방식(Testcontainers 불필요).
 * Boot 4 의 Tomcat 설정 클래스는 [TomcatServerProperties] 다.
 */
class DbPoolConfigSnapshotTest {

    private fun assertAdminUnchanged(vararg profiles: String) {
        SpringApplicationBuilder(EmptyConfig::class.java)
            .web(WebApplicationType.NONE)
            .profiles(*profiles)
            .run()
            .use { ctx ->
                val binder = Binder.get(ctx.environment)
                val server = binder.bind("server", ServerProperties::class.java)
                    .orElseGet { ServerProperties() }
                val tomcat = binder.bind("server.tomcat", TomcatServerProperties::class.java)
                    .orElseGet { TomcatServerProperties() }

                assertThat(binder.bind("spring.datasource.hikari.maximum-pool-size", Int::class.javaObjectType).orElse(null))
                    .`as`("hikari maximum-pool-size").isEqualTo(10)
                assertThat(binder.bind("spring.datasource.hikari.minimum-idle", Int::class.javaObjectType).orElse(null))
                    .`as`("hikari minimum-idle").isEqualTo(2)
                assertThat(binder.bind("spring.datasource.hikari.pool-name", String::class.java).orElse(null))
                    .`as`("hikari pool-name").isEqualTo("passkey-pool")
                assertThat(binder.bind("spring.datasource.hikari.connection-init-sql", String::class.java).orElse(null))
                    .`as`("hikari connection-init-sql").isEqualTo("ALTER SESSION SET TIME_ZONE='Asia/Seoul'")
                assertThat(binder.bind("spring.datasource.hikari.connection-timeout", Long::class.javaObjectType).orElse(null))
                    .`as`("hikari connection-timeout 미설정").isNull()
                assertThat(tomcat.threads.max).`as`("tomcat threads.max").isEqualTo(200)
                assertThat(tomcat.threads.minSpare).`as`("tomcat min-spare").isEqualTo(10)
                assertThat(tomcat.threads.maxQueueCapacity)
                    .`as`("tomcat max-queue-capacity").isEqualTo(Int.MAX_VALUE)
                assertThat(server.port).`as`("server.port").isEqualTo(8081)
                assertThat(server.forwardHeadersStrategy).`as`("forward-headers-strategy")
                    .isEqualTo(ServerProperties.ForwardHeadersStrategy.NATIVE)
                assertThat(binder.bind("spring.threads.virtual.enabled", Boolean::class.javaObjectType).orElse(false))
                    .`as`("spring.threads.virtual.enabled").isFalse()
            }
    }

    @Test
    fun noProfile() {
        assertAdminUnchanged()
    }

    @Test
    fun localProfile() {
        assertAdminUnchanged("local")
    }

    @Test
    fun devProfile() {
        assertAdminUnchanged("dev")
    }

    @Test
    fun qaProfile() {
        assertAdminUnchanged("qa")
    }

    @Test
    fun prodProfile() {
        assertAdminUnchanged("prod")
    }

    @Configuration(proxyBeanMethods = false)
    class EmptyConfig
}
