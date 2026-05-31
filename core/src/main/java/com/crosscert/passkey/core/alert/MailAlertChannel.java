package com.crosscert.passkey.core.alert;

import com.crosscert.passkey.core.mail.MailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 메일 채널 — passkey.alert.mail.enabled=true 일 때만 등록. 기존 MailSender 재사용. */
@Component
@ConditionalOnProperty(prefix = "passkey.alert.mail", name = "enabled", havingValue = "true")
public class MailAlertChannel implements AlertChannel {

    private final MailSender mailSender;
    private final AlertProperties props;

    public MailAlertChannel(MailSender mailSender, AlertProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public boolean supports(SecurityAlertEvent.Severity severity) {
        return severity.atLeast(props.mail().minSeverity());
    }

    @Override
    public void send(SecurityAlertEvent event) {
        String to = props.mail().to();
        if (to == null || to.isBlank()) return;
        String subject = "[보안 알림] " + event.type() + " (" + event.severity() + ")";
        String body = esc(event.summary()) + "<br>context: " + esc(String.valueOf(event.context()));
        mailSender.send(to, subject, body);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
