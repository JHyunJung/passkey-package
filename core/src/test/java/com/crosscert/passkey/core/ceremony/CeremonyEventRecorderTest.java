package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.entity.CeremonyEvent;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CeremonyEventRecorderTest {

    private final CeremonyEventRepository repo = mock(CeremonyEventRepository.class);
    private final CeremonyEventRecorder recorder = new CeremonyEventRecorder(repo);

    @Test
    void record_persistsEvent() {
        UUID tenant = UUID.randomUUID();
        recorder.record(tenant, CeremonyAction.REGISTRATION_BEGIN);
        ArgumentCaptor<CeremonyEvent> captor = ArgumentCaptor.forClass(CeremonyEvent.class);
        verify(repo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenant);
        assertThat(captor.getValue().getAction()).isEqualTo(CeremonyAction.REGISTRATION_BEGIN);
    }

    @Test
    void record_swallowsRepositoryException() {
        UUID tenant = UUID.randomUUID();
        when(repo.save(any(CeremonyEvent.class)))
                .thenThrow(new RuntimeException("DB down"));
        // best-effort: 예외가 호출자로 전파되면 ceremony 가 깨진다 → 전파되면 안 됨
        assertThatCode(() -> recorder.record(tenant, CeremonyAction.REGISTRATION_BEGIN))
                .doesNotThrowAnyException();
    }
}
