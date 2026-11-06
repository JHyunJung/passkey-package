package com.crosscert.passkey.app.config;

import com.crosscert.passkey.core.config.CoreJacksonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * passkey-app HTTP 표면은 JSON 전용이다 — {@code application/cbor} 를 받거나 내보내지 않는다.
 *
 * <p>Spring Framework 7 은 Jackson 3 {@code jackson-dataformat-cbor} 가 클래스패스에 있으면
 * CBOR 컨버터를 기본 등록한다(Boot 3.5/Spring 6 은 Jackson 2 CBOR 만 감지했고, 런타임에는
 * 어느 쪽도 없었다). webauthn4j 테스트 의존성이 Jackson 3 CBOR 를 끌어오므로 클래스패스에
 * 따라 표면이 달라질 수 있다. {@link PasskeyAppHttpConvertersConfig} 가 CBOR 컨버터를 제거해
 * Boot 3.5 와 같은 응답(요청 415, Accept 406)을 고정하는지 검증한다.
 */
class PasskeyAppHttpConvertersConfigTest {

    private static final MediaType CBOR = MediaType.APPLICATION_CBOR;

    private final WebApplicationContextRunner runner = baseRunner()
            .withUserConfiguration(PasskeyAppHttpConvertersConfig.class, EchoController.class);

    @Test
    void cborRequestBody_isUnsupportedMediaType() {
        runner.run(ctx -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
            mvc.perform(post("/echo").contentType(CBOR).content(new byte[]{(byte) 0xA1, 0x61, 0x76, 0x01}))
                    .andExpect(status().isUnsupportedMediaType());
        });
    }

    @Test
    void cborAccept_isNotAcceptable() {
        runner.run(ctx -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
            mvc.perform(get("/dto").accept(CBOR))
                    .andExpect(status().isNotAcceptable());
        });
    }

    @Test
    void json_stillWorks() {
        runner.run(ctx -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
            mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"v\":1}")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json("{\"v\":1}"));
        });
    }

    @Test
    void removesOnlyCborConverters() {
        runner.run(withCustomizer -> baseRunner().run(without -> {
            List<String> expected = converterNames(without).stream()
                    .filter(name -> !name.contains("Cbor"))
                    .toList();
            assertThat(converterNames(without)).anyMatch(name -> name.contains("Cbor")); // 전제: 테스트 클래스패스엔 CBOR 존재
            assertThat(converterNames(withCustomizer)).containsExactlyElementsOf(expected);
        }));
    }

    private static WebApplicationContextRunner baseRunner() {
        return new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        Jackson2AutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        WebMvcAutoConfiguration.class))
                .withUserConfiguration(CoreJacksonConfig.class, PasskeyAppJacksonConfig.class);
    }

    private static List<String> converterNames(ApplicationContext ctx) {
        return ctx.getBean(RequestMappingHandlerAdapter.class).getMessageConverters().stream()
                .map(c -> c.getClass().getName())
                .toList();
    }

    record Dto(int v) {}

    @RestController
    static class EchoController {
        @PostMapping("/echo")
        Dto echo(@RequestBody Dto body) {
            return body;
        }

        @GetMapping("/dto")
        Dto dto() {
            return new Dto(1);
        }
    }
}
