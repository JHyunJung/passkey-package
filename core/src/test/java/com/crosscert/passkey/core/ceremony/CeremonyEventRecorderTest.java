package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CeremonyEventRecorderTest {

    private final CeremonyEventRepository repo = mock(CeremonyEventRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final CeremonyEventRecorder recorder = new CeremonyEventRecorder(repo, txManager);

    @Test
    void record_persistsEvent() {
        // txManager mock: getTransaction returns a status; commit is a no-op.
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        UUID tenant = UUID.randomUUID();
        recorder.record(tenant, CeremonyAction.REGISTRATION_BEGIN);

        ArgumentCaptor<CeremonyEvent> captor = ArgumentCaptor.forClass(CeremonyEvent.class);
        verify(repo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenant);
        assertThat(captor.getValue().getAction()).isEqualTo(CeremonyAction.REGISTRATION_BEGIN);
    }

    @Test
    void record_swallowsExceptionFromTransaction() {
        // Simulate a rollback-only failure surfacing at commit (the exact bug:
        // an in-method try/catch would never see this, since it throws at the tx
        // boundary). UnexpectedRollbackException is a RuntimeException, so the
        // boundary-level catch swallows it.
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doThrow(new UnexpectedRollbackException("transaction rolled back")).when(txManager).commit(any());

        UUID tenant = UUID.randomUUID();
        assertThatCode(() -> recorder.record(tenant, CeremonyAction.AUTHENTICATION_BEGIN))
                .doesNotThrowAnyException();
        // The callback must actually run — guards against a regression that
        // swallows by simply not executing repo.save() at all.
        verify(repo, times(1)).save(any(CeremonyEvent.class));
    }

    @Test
    void recordAfterCommit_fallsBackToImmediateWhenNoTransaction() {
        when(txManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        UUID tenant = UUID.randomUUID();
        // no active synchronization in a plain unit test → immediate path
        recorder.recordAfterCommit(tenant, CeremonyAction.REGISTRATION_SUCCESS);
        verify(repo, times(1)).save(any(CeremonyEvent.class));
    }

    @Test
    void record_rejectsNullArgs() {
        assertThatCode(() -> recorder.record(null, CeremonyAction.REGISTRATION_BEGIN))
                .isInstanceOf(NullPointerException.class);
    }
}
