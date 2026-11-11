package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /passkey/**} 응답 JSON 계약(docs/rp-server-api.md)을 실제 앱 구성(application.yml 포함)으로 고정한다.
 *
 * <p>rp-app 은 sdk-java 가 돌려주는 Jackson 2 {@link JsonNode}(등록/인증 options)를 응답에 그대로
 * 싣는다. MVC JSON 컨버터가 Jackson 2 가 아니면(예: Boot 4 기본 Jackson 3) 이 노드가 JSON 객체가 아니라
 * bean 프로퍼티({@code nodeType}, {@code array} …)로 직렬화돼 클라이언트가 options 를 쓸 수 없다.
 * 또한 봉투의 null 생략({@code @JsonInclude(NON_NULL)}), 날짜 문자열({@code timestamp}),
 * 어노테이션 없는 record 의 null 유지, 알 수 없는 요청 필드 허용이 Boot 3.5 기본값 그대로인지 본다.
 */
@SpringBootTest(properties = "rp.relay.secret=test-relay-secret-not-demo-0123456789")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RpAppJsonContractTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    RequestMappingHandlerAdapter handlerAdapter;

    @MockitoBean
    PasskeyClient passkey;
    @MockitoBean
    InMemoryUserStore users;
    @MockitoBean
    RegistrationRelayCodec relay;

    @BeforeEach
    void stubUpstream() throws Exception {
        given(passkey.registrationStart(any())).willReturn(new RegistrationStartResponse(
                "reg-token",
                json.readTree("{\"challenge\":\"abc\",\"rp\":{\"id\":\"localhost\"},\"pubKeyCredParams\":[{\"type\":\"public-key\",\"alg\":-7}]}")));
        given(users.createPending(any(), any())).willReturn("h1");
        given(relay.encode(any(), any(), any(), any())).willReturn("relay.tok");
    }

    @Test
    void jsonConverterIsJackson2_andNoBinaryDataformatConverters() {
        List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();
        HttpMessageConverter<?> firstJson = converters.stream()
                .filter(c -> c.getSupportedMediaTypes().stream().anyMatch(MediaType.APPLICATION_JSON::equalsTypeAndSubtype))
                .findFirst().orElseThrow();
        assertThat(firstJson).isInstanceOf(MappingJackson2HttpMessageConverter.class);
        // Boot 3.5 와 같은 HTTP 표면: Jackson CBOR/Smile/YAML 등 바이너리·추가 dataformat 컨버터가 없다.
        // (Jaxb2RootElementHttpMessageConverter 는 spring-boot-starter-test 가 끌어오는 jakarta.xml.bind-api
        //  때문에 테스트 클래스패스에서만 등록된다 — 런타임에는 JAXB 가 없고, Boot 3.5 테스트에서도 같았다.)
        assertThat(converters).noneMatch(c -> c.getSupportedMediaTypes().stream().anyMatch(mt ->
                mt.getSubtype().contains("cbor") || mt.getSubtype().contains("smile")
                        || mt.getSubtype().contains("yaml")));
    }

    @Test
    void successEnvelope_embedsSdkJsonNodeAsObject_andOmitsNulls() throws Exception {
        String body = mvc.perform(post("/passkey/register/begin")
                        .contentType(MediaType.APPLICATION_JSON)
                        // 알 수 없는 필드는 무시된다(Boot 기본 FAIL_ON_UNKNOWN_PROPERTIES=false).
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\",\"extra\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        // 봉투 필드 집합: data 있음, error 는 null 이라 생략(@JsonInclude NON_NULL).
        assertThat(root.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("success", "code", "message", "data", "traceId", "timestamp");
        assertThat(root.get("success").asBoolean()).isTrue();
        assertThat(root.get("code").asText()).isEqualTo("OK");
        // LocalDateTime 은 ISO-8601 문자열(타임스탬프 배열/숫자 아님).
        assertThat(root.get("timestamp").isTextual()).isTrue();
        assertThat(root.get("timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");

        JsonNode data = root.get("data");
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("publicKeyCredentialCreationOptions", "regRelayToken");
        // SDK 의 Jackson 2 JsonNode 가 원본 JSON 객체 그대로 직렬화된다(bean 프로퍼티 아님).
        assertThat(data.get("publicKeyCredentialCreationOptions")).isEqualTo(json.readTree(
                "{\"challenge\":\"abc\",\"rp\":{\"id\":\"localhost\"},\"pubKeyCredParams\":[{\"type\":\"public-key\",\"alg\":-7}]}"));
    }

    @Test
    void validationErrorEnvelope_keepsShape() throws Exception {
        String body = mvc.perform(post("/passkey/register/begin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        assertThat(root.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("success", "code", "message", "error", "traceId", "timestamp");
        assertThat(root.get("success").asBoolean()).isFalse();
        JsonNode error = root.get("error");
        assertThat(error.fieldNames()).toIterable().containsExactlyInAnyOrder("errorCode", "fieldErrors");
        JsonNode fe = error.get("fieldErrors").get(0);
        // FieldError 는 @JsonInclude 가 없어 null 도 그대로 나간다(전역 non_null 미적용 = Boot 3.5 동일).
        assertThat(fe.fieldNames()).toIterable().containsExactlyInAnyOrder("field", "rejectedValue", "reason");
        assertThat(fe.get("field").asText()).isEqualTo("username");
        assertThat(fe.get("rejectedValue").isNull()).isTrue();
    }

    /**
     * G02 — passkey-app 과 같은 파서 상한(중첩 64, 문자열 256KB)이 MVC JSON 컨버터와 ObjectMapper 빈 양쪽에 걸린다.
     * 문서 전체 크기 상한은 두지 않는다 — 같은 빈이 InMemoryUserStore 파일 로드에도 쓰여 사용자 수에 비례해 커진다.
     */
    @Test
    void jacksonParserConstraintsApplyToConverterAndBean() throws Exception {
        MappingJackson2HttpMessageConverter conv = (MappingJackson2HttpMessageConverter) handlerAdapter.getMessageConverters().stream()
                .filter(c -> c instanceof MappingJackson2HttpMessageConverter).findFirst().orElseThrow();
        for (ObjectMapper m : List.of(conv.getObjectMapper(), json)) {
            var c = m.getFactory().streamReadConstraints();
            assertThat(c.getMaxNestingDepth()).isEqualTo(64);
            assertThat(c.getMaxStringLength()).isEqualTo(256 * 1024);
        }
        String deep = "[".repeat(65) + "]".repeat(65);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> json.readTree(deep))
                .isInstanceOf(com.fasterxml.jackson.core.exc.StreamConstraintsException.class);
        assertThat(json.readTree("[".repeat(64) + "]".repeat(64))).isNotNull();
    }
}
