package com.crosscert.passkey.app.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.annotation.Configuration

/**
 * P6 — afterCommit/afterRollback 이벤트 기록(CeremonyEventRecorder·CredentialAuthEventRecorder)이
 * outer 커넥션을 쥔 채 REQUIRES_NEW 로 두 번째 커넥션을 요청하므로, passkey-app 의 Tomcat 워커 수가
 * Hikari 최대 풀 이상이면 "모두 하나 쥐고 하나 기다리는" 정지(connection-timeout 30s 후 이벤트 유실)가
 * 가능하다. 이 테스트는 실제 배포 yml(application.yml + import 된 core application-common.yml +
 * 프로필 yml)을 Spring Boot 가 런타임에 해석하는 그대로 로드해 실효값을 검사한다
 * (LogLevelSnapshotTest 와 같은 방식 — Testcontainers 불필요).
 *
 * Boot 4 에서 Tomcat 설정은 [TomcatServerProperties] 로 바인딩되므로(키 server.tomcat.*),
 * 키가 조용히 무시되지 않고 실제 Boot 4 프로퍼티 클래스에 바인딩됨을 함께 증명한다.
 *
 * 특성화 메서드(characterization_*)는 변경 전후 모두 같아야 하는 값을, 불변식 메서드
 * (budget_*)는 P6 로 새로 보장하는 관계를 고정한다.
 */
class DbPoolThreadBudgetConfigTest {

    private data class Effective(
        val poolMax: Int?,
        val minIdle: Int?,
        val poolName: String?,
        val initSql: String?,
        val connectionTimeout: Long?,
        val tomcatThreadsMax: Int,
        val tomcatMinSpare: Int,
        val tomcatMaxQueueCapacity: Int,
        val serverPort: Int?,
        val forwardHeaders: ServerProperties.ForwardHeadersStrategy?,
        val virtualThreads: Boolean,
    )

    private fun effective(vararg profiles: String): Effective =
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
                Effective(
                    binder.bind("spring.datasource.hikari.maximum-pool-size", Int::class.javaObjectType).orElse(null),
                    binder.bind("spring.datasource.hikari.minimum-idle", Int::class.javaObjectType).orElse(null),
                    binder.bind("spring.datasource.hikari.pool-name", String::class.java).orElse(null),
                    binder.bind("spring.datasource.hikari.connection-init-sql", String::class.java).orElse(null),
                    binder.bind("spring.datasource.hikari.connection-timeout", Long::class.javaObjectType).orElse(null),
                    tomcat.threads.max,
                    tomcat.threads.minSpare,
                    tomcat.threads.maxQueueCapacity,
                    server.port,
                    server.forwardHeadersStrategy,
                    binder.bind("spring.threads.virtual.enabled", Boolean::class.javaObjectType).orElse(false) == true,
                )
            }

    // ---- 특성화: P6 전후로 같아야 하는 값 -------------------------------------------

    private fun assertUnchanged(e: Effective) {
        assertThat(e.minIdle).`as`("hikari minimum-idle").isEqualTo(2)
        assertThat(e.poolName).`as`("hikari pool-name").isEqualTo("passkey-pool")
        assertThat(e.initSql).`as`("hikari connection-init-sql")
            .isEqualTo("ALTER SESSION SET TIME_ZONE='Asia/Seoul'")
        assertThat(e.connectionTimeout).`as`("hikari connection-timeout 미설정(Hikari 기본 30s)").isNull()
        assertThat(e.tomcatMinSpare).`as`("tomcat min-spare").isEqualTo(10)
        // 무제한 큐 — 워커 상한을 넘는 요청은 거절되지 않고 대기만 한다(P6 의 동작 보존 전제).
        assertThat(e.tomcatMaxQueueCapacity).`as`("tomcat max-queue-capacity").isEqualTo(Int.MAX_VALUE)
        assertThat(e.serverPort).`as`("server.port").isEqualTo(8080)
        assertThat(e.forwardHeaders).`as`("forward-headers-strategy")
            .isEqualTo(ServerProperties.ForwardHeadersStrategy.FRAMEWORK)
        // 가상 스레드가 켜지면 server.tomcat.threads.max 가 무시되므로 꺼져 있어야 한다.
        assertThat(e.virtualThreads).`as`("spring.threads.virtual.enabled").isFalse()
    }

    @Test
    fun characterization_noProfile() {
        assertUnchanged(effective())
    }

    @Test
    fun characterization_devProfile() {
        assertUnchanged(effective("dev"))
    }

    @Test
    fun characterization_qaProfile() {
        assertUnchanged(effective("qa"))
    }

    @Test
    fun characterization_prodProfile() {
        assertUnchanged(effective("prod"))
    }

    // ---- P6 불변식: Tomcat 워커 ≤ Hikari 최대 풀 − 1 ---------------------------------

    private fun assertBudget(e: Effective) {
        assertThat(e.poolMax).`as`("hikari maximum-pool-size 기본값").isEqualTo(20)
        assertThat(e.tomcatThreadsMax).`as`("tomcat threads.max 기본값").isEqualTo(19)
        // 두 번째 커넥션을 요청할 수 있는 스레드(요청 워커) 수가 풀−1 이하면, 모든 커넥션이
        // "하나 쥐고 하나 기다리는" 스레드에 묶이는 상태가 불가능하다(항상 1개 여유).
        assertThat(e.tomcatThreadsMax)
            .`as`("server.tomcat.threads.max 는 maximum-pool-size − 1 이하여야 afterCommit 이중 커넥션 정지가 없다")
            .isLessThanOrEqualTo(e.poolMax!! - 1)
    }

    @Test
    fun budget_noProfile() {
        assertBudget(effective())
    }

    @Test
    fun budget_devProfile() {
        assertBudget(effective("dev"))
    }

    @Test
    fun budget_qaProfile() {
        assertBudget(effective("qa"))
    }

    @Test
    fun budget_prodProfile() {
        assertBudget(effective("prod"))
    }

    /**
     * 기존 yml 의 ${ENV:default} 관례 그대로 두 값 모두 env 로 덮어쓸 수 있어야 한다.
     * 플레이스홀더는 systemProperties 소스에서도 해석되므로 시스템 프로퍼티로 env 주입을 흉내낸다.
     */
    @Test
    fun budget_defaultsAreOverridableByEnvNames() {
        System.setProperty("SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE", "8")
        System.setProperty("SERVER_TOMCAT_THREADS_MAX", "7")
        try {
            val e = effective("prod")
            assertThat(e.poolMax).isEqualTo(8)
            assertThat(e.tomcatThreadsMax).isEqualTo(7)
        } finally {
            System.clearProperty("SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE")
            System.clearProperty("SERVER_TOMCAT_THREADS_MAX")
        }
    }

    @Configuration(proxyBeanMethods = false)
    class EmptyConfig
}
