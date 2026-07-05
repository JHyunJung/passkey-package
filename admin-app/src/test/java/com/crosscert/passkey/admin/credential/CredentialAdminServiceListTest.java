package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.PageView;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * G15-credN1 — CredentialAdminService.list() must resolve authenticatorName
 * for a page of rows via ONE batched Redis round-trip (MdsAaguidCache.multiLookup),
 * not one MdsAaguidCache.lookup() GET per row. A 200-row page previously
 * issued up to 200 sequential Redis round-trips before returning.
 */
class CredentialAdminServiceListTest {

    private final CredentialRepository creds = mock(CredentialRepository.class);
    private final MdsAaguidCache mds = mock(MdsAaguidCache.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final TenantBoundary tenantBoundary = mock(TenantBoundary.class);
    private final CredentialAuthEventRepository authEvents = mock(CredentialAuthEventRepository.class);

    private final CredentialAdminService svc =
            new CredentialAdminService(creds, mds, audit, tenantBoundary, authEvents);

    @Test
    void listResolvesAuthenticatorNamesWithSingleBatchedLookup() {
        UUID tenant = UUID.randomUUID();
        UUID aaguid1 = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID aaguid2 = UUID.fromString("22222222-2222-3333-4444-555555555555");

        // 3 rows: two distinct AAGUIDs (one repeated) + one credential with no AAGUID.
        List<Credential> rows = List.of(
                credential(tenant, aaguid1),
                credential(tenant, aaguid2),
                credential(tenant, aaguid1),
                credentialWithoutAaguid(tenant));

        when(creds.findAllByTenantId(eq(tenant), any()))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 200), rows.size()));

        when(mds.multiLookup(anyList())).thenReturn(Map.of(
                aaguid1, new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1")),
                aaguid2, new MdsAaguidCache.Entry(List.of("FIDO_CERTIFIED_L1", "REVOKED"))));

        PageView<CredentialAdminDto.CredentialView> page = svc.list(tenant, 0, 200, null);

        assertThat(page.content()).hasSize(4);
        assertThat(page.content().get(0).authenticatorName()).isEqualTo("FIDO_CERTIFIED_L1");
        assertThat(page.content().get(1).authenticatorName()).isEqualTo("FIDO_CERTIFIED_L1,REVOKED");
        assertThat(page.content().get(2).authenticatorName()).isEqualTo("FIDO_CERTIFIED_L1");
        assertThat(page.content().get(3).authenticatorName()).isNull();

        // The whole point of G15: exactly one batched call regardless of row count,
        // and the old per-row lookup() must never be invoked.
        verify(mds, times(1)).multiLookup(anyList());
        verify(mds, never()).lookup(any());
    }

    @Test
    void listWithNoAaguidRowsSkipsMdsCallEntirely() {
        UUID tenant = UUID.randomUUID();
        List<Credential> rows = List.of(credentialWithoutAaguid(tenant), credentialWithoutAaguid(tenant));

        when(creds.findAllByTenantId(eq(tenant), any()))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 200), rows.size()));

        PageView<CredentialAdminDto.CredentialView> page = svc.list(tenant, 0, 200, null);

        assertThat(page.content()).hasSize(2);
        assertThat(page.content()).allMatch(v -> v.authenticatorName() == null);
        verify(mds, never()).multiLookup(anyList());
        verify(mds, never()).lookup(any());
    }

    private static Credential credential(UUID tenant, UUID aaguid) {
        Credential c = mock(Credential.class);
        when(c.getTenantId()).thenReturn(tenant);
        when(c.getCredentialId()).thenReturn(randomBytes(16));
        when(c.getUserHandle()).thenReturn(randomBytes(16));
        when(c.getAaguid()).thenReturn(uuidBytes(aaguid));
        when(c.getLabel()).thenReturn(null);
        when(c.getAttestationFmt()).thenReturn("packed");
        when(c.getTransports()).thenReturn("usb");
        when(c.getSignCount()).thenReturn(1L);
        when(c.getLastUsedAt()).thenReturn(null);
        when(c.getCreatedAt()).thenReturn(OffsetDateTime.now());
        return c;
    }

    private static Credential credentialWithoutAaguid(UUID tenant) {
        Credential c = mock(Credential.class);
        when(c.getTenantId()).thenReturn(tenant);
        when(c.getCredentialId()).thenReturn(randomBytes(16));
        when(c.getUserHandle()).thenReturn(randomBytes(16));
        when(c.getAaguid()).thenReturn(null);
        when(c.getLabel()).thenReturn(null);
        when(c.getAttestationFmt()).thenReturn("none");
        when(c.getTransports()).thenReturn(null);
        when(c.getSignCount()).thenReturn(0L);
        when(c.getLastUsedAt()).thenReturn(null);
        when(c.getCreatedAt()).thenReturn(OffsetDateTime.now());
        return c;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new java.util.Random().nextBytes(b);
        return b;
    }

    private static byte[] uuidBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
