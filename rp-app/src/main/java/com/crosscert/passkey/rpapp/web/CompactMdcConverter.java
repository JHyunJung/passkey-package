package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * 값이 있는 MDC 키만 {@code [traceId=.. tenantId=.. ..]} 로 묶어 출력한다. 빈 키 생략, 전부 비면 "".
 *
 * <p>core 의 {@code com.crosscert.passkey.core.logging.CompactMdcConverter} Java 트윈
 * — rp-app 은 :core 비의존이라 중복. 두 파일 동작을 동일하게 유지할 것.
 */
public class CompactMdcConverter extends ClassicConverter {

    private static final String[] KEYS = {"traceId", "tenantId", "actorEmail", "apiKeyPrefix"};
    private static final int MAX_VALUE_LEN = 16;

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String key : KEYS) {
            String v = mdc.get(key);
            if (v == null || v.isBlank()) continue;
            if (v.length() > MAX_VALUE_LEN) v = v.substring(0, MAX_VALUE_LEN);
            sb.append(sb.length() == 0 ? "[" : " ").append(key).append('=').append(v);
        }
        return sb.length() == 0 ? "" : sb.append(']').toString();
    }
}
