package com.crosscert.passkey.core.mail;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SmtpMailSenderTest {

    @Test
    void send_buildsHtmlMimeMessage_andDelegatesToJavaMailSender() throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        // 실제 MimeMessage 인스턴스를 생성해 helper 가 채울 수 있게 한다.
        MimeMessage real = new JavaMailSenderImpl().createMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(real);

        SmtpMailSender sender = new SmtpMailSender(javaMailSender, "no-reply@passkey.test");

        sender.send("admin@example.com", "초대 제목", "<a href=\"x\">수락</a>");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("초대 제목");
        assertThat(sent.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("admin@example.com");
        assertThat(sent.getFrom()[0].toString()).isEqualTo("no-reply@passkey.test");
        // setText(body, true) → content-type 이 text/html
        assertThat(sent.getContentType()).contains("text/html");
    }

    @Test
    void send_wrapsMessagingExceptionInRuntimeException() {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MimeMessage real = new JavaMailSenderImpl().createMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(real);
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(javaMailSender).send(any(MimeMessage.class));

        SmtpMailSender sender = new SmtpMailSender(javaMailSender, "no-reply@passkey.test");

        // 호출자의 catch(Exception) 가 받을 수 있도록 RuntimeException 계열로 전파.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> sender.send("a@b.com", "s", "b"))
                .isInstanceOf(RuntimeException.class);
    }
}
