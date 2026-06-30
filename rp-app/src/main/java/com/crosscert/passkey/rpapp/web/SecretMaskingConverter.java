package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 모든 콘솔 로그 메시지에 비밀값 마스킹을 적용하는 logback MessageConverter.
 * 개발자가 실수로 API key·JWT·password 를 로그에 남겨도 출력 시점에 가린다.
 *
 * <p>logback-spring.xml 에서 {@code %msg} 변환 규칙으로 등록된다:
 * <pre>{@code <conversionRule conversionWord="msg" converterClass="...SecretMaskingConverter"/>}</pre>
 * 마스킹 로직은 {@link SecretRedactor} 를 공유한다.
 */
public class SecretMaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedactor.redact(super.convert(event));
    }
}
