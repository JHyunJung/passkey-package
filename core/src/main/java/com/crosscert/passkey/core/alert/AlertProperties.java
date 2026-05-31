package com.crosscert.passkey.core.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** passkey.alert.* 설정. mail 채널 on/off + 수신 주소 + 채널 min-severity. */
@ConfigurationProperties(prefix = "passkey.alert")
public record AlertProperties(Mail mail) {

    public record Mail(boolean enabled, String to, SecurityAlertEvent.Severity minSeverity) {
        public Mail {
            if (minSeverity == null) {
                minSeverity = SecurityAlertEvent.Severity.HIGH;
            }
        }
    }

    public AlertProperties {
        if (mail == null) {
            mail = new Mail(false, null, SecurityAlertEvent.Severity.HIGH);
        }
    }
}
