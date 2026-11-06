package com.crosscert.passkey.app.fido2.mds;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 MdsVerifier 의 외부 관찰 가능 동작(null/비정상 AAGUID 처리, 빈 status 목록,
 * 마지막 status 만 판정, 로그 문구)을 현재 Java 구현 기준으로 고정하는 특성 테스트.
 */
class MdsVerifierCharacterizationTest {

    private static final byte[] AAGUID = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final String AAGUID_STR = "01020304-0506-0708-090a-0b0c0d0e0f10";

    private final MdsAaguidCache cache = mock(MdsAaguidCache.class);
    private final MdsVerifier verifier = new MdsVerifier(cache);
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(MdsVerifier.class);
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
    void notRequired_nullAaguid_trueWithoutCacheLookup() {
        assertThat(verifier.verify(false, null)).isTrue();
        verifyNoInteractions(cache);
    }

    @Test
    void required_absentEntry_logsCanonicalAaguid() {
        when(cache.lookup(any())).thenReturn(Optional.empty());
        assertThat(verifier.verify(true, AAGUID)).isFalse();
        assertThat(warns()).containsExactly("MDS entry absent for aaguid " + AAGUID_STR + " — fail-closed");
    }

    @Test
    void required_nullAaguid_absentEntry_canonicalizationThrowsIllegalArgument() {
        // 캐시가 null 을 받아 empty 를 돌려줘도, 로그용 canonicalAaguid(null) 이 IAE 를 던진다.
        when(cache.lookup(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> verifier.verify(true, null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("aaguid must be 16 bytes");
    }

    @Test
    void required_emptyStatuses_falseAndLogs() {
        when(cache.lookup(any())).thenReturn(Optional.of(new MdsAaguidCache.Entry(List.of())));
        assertThat(verifier.verify(true, AAGUID)).isFalse();
        assertThat(warns()).containsExactly(
                "MDS entry has empty status list for aaguid " + AAGUID_STR + " — fail-closed");
    }

    @Test
    void required_onlyLastStatusCounts_recertifiedPasses() {
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("REVOKED", "FIDO_CERTIFIED"))));
        assertThat(verifier.verify(true, AAGUID)).isTrue();
        assertThat(warns()).isEmpty();
    }

    @Test
    void required_blockingLastStatus_logsStatus() {
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED", "USER_KEY_REMOTE_COMPROMISE"))));
        assertThat(verifier.verify(true, AAGUID)).isFalse();
        assertThat(warns()).containsExactly(
                "MDS blocking status USER_KEY_REMOTE_COMPROMISE for aaguid " + AAGUID_STR);
    }

    @Test
    void required_nonBlockingUnknownStatus_passes() {
        when(cache.lookup(any())).thenReturn(Optional.of(
                new MdsAaguidCache.Entry(List.of("UPDATE_AVAILABLE"))));
        assertThat(verifier.verify(true, AAGUID)).isTrue();
    }
}
