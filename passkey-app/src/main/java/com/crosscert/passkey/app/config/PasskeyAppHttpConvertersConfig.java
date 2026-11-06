package com.crosscert.passkey.app.config;

import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

/**
 * passkey-app 의 HTTP 표면을 JSON 전용으로 고정한다 — {@code application/cbor} 컨버터 제거.
 *
 * <p>Spring Framework 7 은 Jackson 3 {@code jackson-dataformat-cbor} 가 클래스패스에 있으면
 * CBOR 서버 컨버터를 기본 등록한다(Spring 6 은 Jackson 2 CBOR 만 감지했다). 그러면 같은
 * 엔드포인트가 클래스패스 구성에 따라 CBOR 요청을 받아들이고 CBOR 응답을 내보내게 되는데,
 * 이 경로는 JSON 매퍼 설정({@code spring.jackson.*}, null 생략, 날짜 형식, 파서 상한)을
 * 거치지 않는다. 업그레이드 전과 같이 CBOR 은 415(요청)/406(Accept) 로 거절되도록 둔다.
 */
@Configuration
public class PasskeyAppHttpConvertersConfig {

    @Bean
    public ServerHttpMessageConvertersCustomizer noCborServerConverters() {
        return builder -> builder.configureMessageConvertersList(converters -> converters.removeIf(
                converter -> converter.getSupportedMediaTypes().stream()
                        // isCompatibleWith 는 */* 컨버터(String/ByteArray/Resource)까지 지우므로 정확 비교.
                        .anyMatch(MediaType.APPLICATION_CBOR::equalsTypeAndSubtype)));
    }
}
