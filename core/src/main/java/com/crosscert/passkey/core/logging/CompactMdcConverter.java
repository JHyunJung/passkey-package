package com.crosscert.passkey.core.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * 값이 있는 MDC 키만 {@code [traceId=.. tenantId=.. actorEmail=.. apiKeyPrefix=..]} 형태로
 * 묶어 출력한다. 빈 키는 생략하고, MDC 가 전부 비면 빈 문자열을 반환한다.
 *
 * <p>기존 패턴의 {@code [%X{traceId:-}] [%X{tenantId:-}] ...} 4개 슬롯이 비면 {@code [] [] [] []}
 * 노이즈가 되던 것을 대체한다. logback-spring.xml 에
 * {@code <conversionRule conversionWord="compactMdc" converterClass="...CompactMdcConverter"/>}
 * 로 등록해 패턴에서 {@code %compactMdc} 로 쓴다.
 *
 * <p>SecretMaskingConverter 와 마찬가지로 rp-app 에 Kotlin 트윈이 중복 존재한다(rp-app 은
 * :core 비의존). 두 파일 동작을 동일하게 유지할 것.
 */
public class CompactMdcConverter extends ClassicConverter {

    /** 출력 순서 고정. 여기 없는 MDC 키는 무시한다. */
    private static final String[] KEYS = {"traceId", "tenantId", "actorEmail", "apiKeyPrefix"};

    private static final int MAX_VALUE_LEN = 16;

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : KEYS) {
            String v = mdc.get(key);
            if (v == null || v.isBlank()) {
                continue;
            }
            if (v.length() > MAX_VALUE_LEN) {
                v = v.substring(0, MAX_VALUE_LEN);
            }
            sb.append(sb.length() == 0 ? "[" : " ").append(key).append('=').append(v);
        }
        if (sb.length() == 0) {
            return "";
        }
        return sb.append(']').toString();
    }
}
