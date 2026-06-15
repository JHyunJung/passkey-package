package com.crosscert.passkey.core.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * 운영용 SMTP 메일 발송 구현체.
 *
 * <p>{@link MailSenderConfiguration} 이 {@code spring.mail.host} 설정 시 이 구현체를 생성한다.
 * 미설정 시에는 {@link LogMailSender} 가 선택된다.
 */
@Slf4j
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender, String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, true); // HTML 본문 (초대 URL <a href> 포함)
            message.saveChanges(); // Content-Type 헤더를 DataHandler 에서 플러시
            javaMailSender.send(message);
            log.info("[MAIL] sent to={} subject=\"{}\" body-length={}", to, subject,
                    body == null ? 0 : body.length());
        } catch (Exception e) {
            // 호출자(InvitationService 등)의 catch(Exception) 가 fallback 처리.
            log.warn("[MAIL] send failed to={} subject=\"{}\" reason={}", to, subject,
                    e.getMessage());
            throw new RuntimeException("mail send failed", e);
        }
    }
}
