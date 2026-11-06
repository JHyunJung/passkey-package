package com.crosscert.passkey.app.config

import com.crosscert.passkey.core.config.CoreJacksonConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter

private val CBOR = MediaType.APPLICATION_CBOR

private fun baseRunner(): WebApplicationContextRunner =
    WebApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                HttpMessageConvertersAutoConfiguration::class.java,
                WebMvcAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(CoreJacksonConfig::class.java, PasskeyAppJacksonConfig::class.java)

private fun converterNames(ctx: ApplicationContext): List<String> =
    ctx.getBean(RequestMappingHandlerAdapter::class.java).messageConverters.map { it.javaClass.name }

/**
 * passkey-app HTTP 표면은 JSON 전용이다 — `application/cbor` 를 받거나 내보내지 않는다.
 *
 * Spring Framework 7 은 Jackson 3 `jackson-dataformat-cbor` 가 클래스패스에 있으면
 * CBOR 컨버터를 기본 등록한다(Boot 3.5/Spring 6 은 Jackson 2 CBOR 만 감지했고, 런타임에는
 * 어느 쪽도 없었다). webauthn4j 테스트 의존성이 Jackson 3 CBOR 를 끌어오므로 클래스패스에
 * 따라 표면이 달라질 수 있다. [PasskeyAppHttpConvertersConfig] 가 CBOR 컨버터를 제거해
 * Boot 3.5 와 같은 응답(요청 415, Accept 406)을 고정하는지 검증한다.
 */
class PasskeyAppHttpConvertersConfigTest {

    private val runner = baseRunner()
        .withUserConfiguration(PasskeyAppHttpConvertersConfig::class.java, EchoController::class.java)

    @Test
    fun cborRequestBody_isUnsupportedMediaType() {
        runner.run { ctx ->
            val mvc = MockMvcBuilders.webAppContextSetup(ctx).build()
            mvc.perform(post("/echo").contentType(CBOR).content(byteArrayOf(0xA1.toByte(), 0x61, 0x76, 0x01)))
                .andExpect(status().isUnsupportedMediaType())
        }
    }

    @Test
    fun cborAccept_isNotAcceptable() {
        runner.run { ctx ->
            val mvc = MockMvcBuilders.webAppContextSetup(ctx).build()
            mvc.perform(get("/dto").accept(CBOR))
                .andExpect(status().isNotAcceptable())
        }
    }

    @Test
    fun json_stillWorks() {
        runner.run { ctx ->
            val mvc = MockMvcBuilders.webAppContextSetup(ctx).build()
            mvc.perform(
                post("/echo").contentType(MediaType.APPLICATION_JSON).content("""{"v":1}""")
                    .accept(MediaType.APPLICATION_JSON),
            )
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""{"v":1}"""))
        }
    }

    @Test
    fun removesOnlyCborConverters() {
        runner.run { withCustomizer ->
            baseRunner().run { without ->
                val expected = converterNames(without).filterNot { it.contains("Cbor") }
                // 전제: 테스트 클래스패스엔 CBOR 존재
                assertThat(converterNames(without)).anyMatch { it.contains("Cbor") }
                assertThat(converterNames(withCustomizer)).containsExactlyElementsOf(expected)
            }
        }
    }

    data class Dto(val v: Int)

    @RestController
    class EchoController {
        @PostMapping("/echo")
        fun echo(@RequestBody body: Dto): Dto = body

        @GetMapping("/dto")
        fun dto(): Dto = Dto(1)
    }
}
