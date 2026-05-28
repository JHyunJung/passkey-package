package com.crosscert.passkey.core.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 개발/CI 용 메일 발송 구현체.
 *
 * <p>실제 메일을 보내지 않고 SLF4J INFO 로그로만 출력한다.
 * 운영 프로파일에서는 실제 SMTP 구현체로 교체할 것.
 */
@Component
public class LogMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LogMailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("[MAIL] to={} subject=\"{}\" body-length={}", to, subject,
                body == null ? 0 : body.length());
        log.debug("[MAIL] body={}", body);
    }
}
