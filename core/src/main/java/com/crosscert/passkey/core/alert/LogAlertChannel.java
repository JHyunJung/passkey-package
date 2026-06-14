package com.crosscert.passkey.core.alert;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Component;

/** 기본 채널 — 항상 등록. severity→로그레벨 매핑 + SECURITY_ALERT 마커. */
@Slf4j
@Component
public class LogAlertChannel implements AlertChannel {

    private static final Marker ALERT = MarkerFactory.getMarker("SECURITY_ALERT");

    @Override
    public boolean supports(SecurityAlertEvent.Severity severity) {
        return true;
    }

    @Override
    public void send(SecurityAlertEvent event) {
        // SECURITY_ALERT 마커로 operational 로그와 분리 라우팅 의도.
        String msg = "security alert: type={} severity={} summary={} context={}";
        if (event.severity().atLeast(SecurityAlertEvent.Severity.HIGH)) {
            log.error(ALERT, msg, event.type(), event.severity(), event.summary(), event.context());
        } else {
            log.warn(ALERT, msg, event.type(), event.severity(), event.summary(), event.context());
        }
    }
}
