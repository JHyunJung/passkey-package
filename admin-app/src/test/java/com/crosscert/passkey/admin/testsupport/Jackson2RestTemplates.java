package com.crosscert.passkey.admin.testsupport;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * IT 용 평범한 {@link RestTemplate} — JSON 은 Jackson 2 로 읽고 쓴다.
 *
 * <p>Spring 7 의 {@code new RestTemplate()} 은 Jackson 3 가 클래스패스에 있으면 Jackson 3 JSON 컨버터만
 * 등록한다. 테스트는 응답을 Jackson 2 {@code JsonNode} 로 받으므로(서버도 {@code preferred-json-mapper:
 * jackson2}) 그 자리를 Jackson 2 컨버터로 바꿔 Spring 6 기본 구성과 같게 둔다. 순서는 유지 — String
 * 컨버터가 앞에 있어야 미리 직렬화한 JSON 문자열 본문이 따옴표로 다시 감싸이지 않는다.
 */
@SuppressWarnings("removal")
public final class Jackson2RestTemplates {

    private Jackson2RestTemplates() {}

    public static RestTemplate create() {
        RestTemplate template = new RestTemplate();
        List<HttpMessageConverter<?>> converters = template.getMessageConverters();
        converters.replaceAll(c -> c instanceof JacksonJsonHttpMessageConverter
                ? new MappingJackson2HttpMessageConverter() : c);
        if (converters.stream().noneMatch(MappingJackson2HttpMessageConverter.class::isInstance)) {
            throw new IllegalStateException("Jackson JSON converter not found in RestTemplate defaults: " + converters);
        }
        return template;
    }
}
