package com.crosscert.passkey.core.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 개발/CI 용 메일 발송 구현체.
 *
 * <p>실제 메일을 보내지 않고 SLF4J INFO 로그로만 출력한다.
 * 다른 {@link MailSender} 빈({@link SmtpMailSender})이 등록되면 이 빈은 물러난다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(MailSender.class)
public class LogMailSender implements MailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[MAIL] to={} subject=\"{}\" body-length={}", to, subject,
                body == null ? 0 : body.length());
        log.debug("[MAIL] body={}", body);
    }
}
