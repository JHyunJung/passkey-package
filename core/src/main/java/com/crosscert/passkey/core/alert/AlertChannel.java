package com.crosscert.passkey.core.alert;

/** 알림 발송 채널 (P1-3). 채널별 최소 severity 필터 + 발송. */
public interface AlertChannel {
    boolean supports(SecurityAlertEvent.Severity severity);
    void send(SecurityAlertEvent event);
}
