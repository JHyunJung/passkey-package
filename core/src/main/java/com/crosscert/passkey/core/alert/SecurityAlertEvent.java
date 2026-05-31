package com.crosscert.passkey.core.alert;

import java.util.Map;

/**
 * 보안 이벤트 알림 (P1-3). 발행 지점은 ApplicationEventPublisher 로 이 이벤트를 publish 하고,
 * AlertDispatcher 가 @Async @EventListener 로 수신해 AlertChannel 들에 fan-out 한다.
 * context 에는 마스킹된 값만 담는다(평문 secret/전체 이메일 금지).
 */
public record SecurityAlertEvent(
        AlertType type,
        Severity severity,
        String summary,
        Map<String, String> context) {

    public enum AlertType {
        API_KEY_BRUTE_FORCE,
        COUNTER_REGRESSION,
        TENANT_BOUNDARY_VIOLATION,
        ADMIN_LOGIN_FAILURE,
        MDS_SYNC_FAILURE
    }

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL;

        public boolean atLeast(Severity min) {
            return this.ordinal() >= min.ordinal();
        }
    }
}
