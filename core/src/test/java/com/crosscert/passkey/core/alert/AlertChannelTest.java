package com.crosscert.passkey.core.alert;

import com.crosscert.passkey.core.mail.MailSender;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AlertChannelTest {

    private SecurityAlertEvent event(SecurityAlertEvent.Severity sev) {
        return new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.ADMIN_LOGIN_FAILURE, sev, "s", Map.of());
    }

    @Test
    void mail_channel_sends_only_at_or_above_min_severity() {
        MailSender mail = mock(MailSender.class);
        AlertProperties props = new AlertProperties(
                new AlertProperties.Mail(true, "ops@x.com", SecurityAlertEvent.Severity.HIGH));
        MailAlertChannel ch = new MailAlertChannel(mail, props);

        assertThat(ch.supports(SecurityAlertEvent.Severity.MEDIUM)).isFalse();
        assertThat(ch.supports(SecurityAlertEvent.Severity.HIGH)).isTrue();

        ch.send(event(SecurityAlertEvent.Severity.HIGH));
        verify(mail).send(eq("ops@x.com"), contains("ADMIN_LOGIN_FAILURE"), anyString());
    }
}
