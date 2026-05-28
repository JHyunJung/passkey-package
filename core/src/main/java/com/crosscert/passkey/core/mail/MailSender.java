package com.crosscert.passkey.core.mail;

/**
 * 메일 발송 추상화.
 *
 * <p>운영 환경에서는 SMTP/SendGrid 구현체로 교체 가능.
 * 개발/CI 환경에서는 {@link LogMailSender} 가 기본 빈으로 등록됨.
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
