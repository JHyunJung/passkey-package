package com.crosscert.passkey.core.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 개발/CI 용 메일 발송 구현체.
 *
 * <p>실제 메일을 보내지 않고 SLF4J INFO 로그로만 출력한다.
 * {@link SmtpMailSender} 빈이 등록되면 이 빈은 물러난다.
 * ({@code @ConditionalOnMissingBean(MailSender.class)} 대신 명시적으로
 * {@code SmtpMailSender} 부재를 조건으로 삼아 빈 등록 순서에 무관하게 동작한다.)
 */
@Slf4j
@Component
@ConditionalOnMissingBean(SmtpMailSender.class)
public class LogMailSender implements MailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[MAIL] to={} subject=\"{}\" body-length={}", to, subject,
                body == null ? 0 : body.length());
        log.debug("[MAIL] body={}", body);
    }
}
