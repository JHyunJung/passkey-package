package com.crosscert.passkey.core.mail;

/**
 * 메일 발송 추상화.
 *
 * <p>구현체는 {@link MailSenderConfiguration} 이 고른다: {@code spring.mail.host} 가 있으면
 * {@link SmtpMailSender}, 없으면 {@link LogMailSender}(로그 출력만).
 */
public interface MailSender {

    /**
     * 단일 수신자에게 HTML/텍스트 메일을 발송한다.
     *
     * @param to      수신자 이메일 주소
     * @param subject 제목
     * @param body    본문 (HTML 허용)
     */
    void send(String to, String subject, String body);
}
