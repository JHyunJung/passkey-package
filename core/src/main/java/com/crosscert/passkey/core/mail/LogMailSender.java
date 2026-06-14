package com.crosscert.passkey.core.mail;

import lombok.extern.slf4j.Slf4j;

/**
 * 개발/CI 용 메일 발송 구현체.
 *
 * <p>실제 메일을 보내지 않고 SLF4J INFO 로그로만 출력한다.
 * {@link MailSenderConfiguration} 이 {@code spring.mail.host} 미설정 시 이 구현체를 선택한다.
 */
@Slf4j
public class LogMailSender implements MailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[MAIL] to={} subject=\"{}\" body-length={}", to, subject,
                body == null ? 0 : body.length());
        log.debug("[MAIL] body={}", body);
    }
}
