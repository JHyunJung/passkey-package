package com.crosscert.passkey.app.fido2.credential;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 CredentialSelfService 의 외부 관찰 가능 동작(뷰 매핑, null AAGUID, 미소유 예외 메시지,
 * 삭제 로그 문구/마스킹, 잠금 조회 사용)을 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class CredentialSelfServiceCharacterizationTest {

    private final CredentialRepository creds = mock(CredentialRepository.class);
    private final CredentialSelfService svc = new CredentialSelfService(creds);
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(CredentialSelfService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private List<String> warns() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void list_mapsAllFields_inRepoOrder_nullAaguidAndLabel() {
        OffsetDateTime used = OffsetDateTime.of(2026, 11, 6, 9, 0, 0, 0, ZoneOffset.ofHours(9));
        Credential a = mock(Credential.class);
        when(a.getCredentialId()).thenReturn(new byte[]{1, 2, 3, 4});
        when(a.getLabel()).thenReturn("Mac");
        when(a.getAaguid()).thenReturn(new byte[]{(byte) 0xab, 0x01});
        when(a.getLastUsedAt()).thenReturn(used);
        Credential b = mock(Credential.class);
        when(b.getCredentialId()).thenReturn(new byte[]{5});
        when(creds.findByUserHandle(any())).thenReturn(List.of(a, b));

        List<CredentialView> views = svc.list(new byte[]{9});

        assertThat(views).containsExactly(
                new CredentialView("AQIDBA", "Mac", "ab01", used),
                new CredentialView("BQ", null, null, null));
    }

    @Test
    void list_empty() {
        when(creds.findByUserHandle(any())).thenReturn(List.of());
        assertThat(svc.list(new byte[]{9})).isEmpty();
    }

    @Test
    void notOwned_message() {
        when(creds.findOwnedForUpdate(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.rename(new byte[]{1}, new byte[]{2}, "x"))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("credential not found or not owned");
        assertThatThrownBy(() -> svc.delete(new byte[]{1}, new byte[]{2}))
                .isExactlyInstanceOf(BusinessException.class)
                .hasMessage("credential not found or not owned");
        verify(creds, never()).delete(any());
        assertThat(warns()).isEmpty();
    }

    @Test
    void rename_usesLockedOwnershipLookup_acceptsNullLabel() {
        byte[] uh = {1};
        byte[] cid = {2};
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c));

        svc.rename(uh, cid, null);

        verify(creds).findOwnedForUpdate(cid, uh);
        verify(c).setLabel(null);
        verify(creds, never()).save(any());
    }

    @Test
    void delete_logsMaskedTail_longId() {
        byte[] cid = new byte[20];
        for (int i = 0; i < cid.length; i++) cid[i] = (byte) (i + 1);
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(any(), any())).thenReturn(Optional.of(c));

        svc.delete(new byte[]{1}, cid);

        verify(creds).delete(c);
        assertThat(warns()).containsExactly(
                "self-service credential deleted: id=...MDQ4PEBESExQ reason=rp-self-service");
    }

    @Test
    void delete_logsFullyMasked_shortId() {
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(any(), any())).thenReturn(Optional.of(c));

        svc.delete(new byte[]{1}, new byte[]{2});

        assertThat(warns()).containsExactly("self-service credential deleted: id=*** reason=rp-self-service");
    }

    @Test
    void delete_logsFullyMasked_exactlyTwelveCharId() {
        // 9 바이트 → base64url 12자: 경계(≤12)에서 전체 마스킹
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(any(), any())).thenReturn(Optional.of(c));

        svc.delete(new byte[]{1}, new byte[9]);

        assertThat(warns()).containsExactly("self-service credential deleted: id=*** reason=rp-self-service");
    }
}
