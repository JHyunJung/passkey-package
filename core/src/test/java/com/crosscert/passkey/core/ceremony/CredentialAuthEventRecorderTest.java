package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CredentialAuthEventRecorderTest {

    private final CredentialAuthEventRepository repo = mock(CredentialAuthEventRepository.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    private CredentialAuthEventRecorder newRecorder() {
        // TransactionTemplate.executeWithoutResult 가 즉시 콜백 실행하도록 getTransaction stub
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new CredentialAuthEventRecorder(repo, tx);
    }

    @Test
    void recordSavesEvent() {
        CredentialAuthEventRecorder r = newRecorder();
        r.record(UUID.randomUUID(), UUID.randomUUID(), "SUCCESS", null, 5);
        verify(repo).save(any(CredentialAuthEvent.class));
    }

    @Test
    void recordSwallowsRepositoryFailure() {
        CredentialAuthEventRecorder r = newRecorder();
        doThrow(new RuntimeException("db down")).when(repo).save(any());
        // 예외가 전파되지 않아야 한다 (best-effort)
        assertThatCode(() -> r.record(UUID.randomUUID(), UUID.randomUUID(),
                "FAILED", "SIGN_COUNT_REPLAY", 0)).doesNotThrowAnyException();
        verify(repo).save(any(CredentialAuthEvent.class));
    }

    @Test
    void recordAfterCommitFallsBackToImmediateWhenNoTransaction() {
        CredentialAuthEventRecorder r = newRecorder();
        // no active synchronization in a plain unit test → immediate path
        r.recordAfterCommit(UUID.randomUUID(), UUID.randomUUID(), "SUCCESS", null, 7);
        verify(repo).save(any(CredentialAuthEvent.class));
    }
}
