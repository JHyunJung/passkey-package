package com.crosscert.passkey.app.fido2.credential;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CredentialSelfServiceTest {

    private final CredentialRepository creds = mock(CredentialRepository.class);

    @Test
    void list_returnsCredentialsForUserHandle() {
        byte[] uh = {1, 2};
        Credential c = mock(Credential.class);
        when(c.getCredentialId()).thenReturn(new byte[]{9});
        when(c.getLabel()).thenReturn("MacBook");
        when(creds.findByUserHandle(uh)).thenReturn(List.of(c));

        CredentialSelfService svc = new CredentialSelfService(creds);
        var views = svc.list(uh);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).label()).isEqualTo("MacBook");
    }

    @Test
    void rename_setsLabel() {
        byte[] uh = {1, 2};
        byte[] cid = {9};
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c));

        CredentialSelfService svc = new CredentialSelfService(creds);
        svc.rename(uh, cid, "iPhone");

        verify(c).setLabel("iPhone");
    }

    @Test
    void delete_removesOwnedCredential() {
        byte[] uh = {1, 2};
        byte[] cid = {9};
        Credential c = mock(Credential.class);
        when(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c));

        CredentialSelfService svc = new CredentialSelfService(creds);
        svc.delete(uh, cid);

        verify(creds).delete(c);
    }

    @Test
    void delete_throwsWhenNotOwned() {
        when(creds.findOwnedForUpdate(any(), any())).thenReturn(Optional.empty());
        CredentialSelfService svc = new CredentialSelfService(creds);
        assertThatThrownBy(() -> svc.delete(new byte[]{1}, new byte[]{2}))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND);
    }
}
