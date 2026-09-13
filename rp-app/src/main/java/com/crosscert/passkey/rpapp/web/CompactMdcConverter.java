package com.crosscert.passkey.rpapp.web;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * 값이 있는 MDC 키만 {@code [traceId=.. actorEmail=..]} 형태로 묶어 로그에 출력한다.
 * 빈 키는 생략하고, 전부 비면 빈 문자열. 값은 16자로 자른다.
 */
public class CompactMdcConverter extends ClassicConverter {

    // rp-app 이 MDC 에 넣는 키는 이 둘뿐이다
    // (TraceIdFilter=traceId, RequestLoggingFilter=actorEmail). 출력 순서도 이 배열을 따른다.
    private static final String[] KEYS = {"traceId", "actorEmail"};
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
