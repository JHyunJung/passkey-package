package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditChainVerifierTest {

    private AuditLogRepository repo;
    private AuditChainVerifier verifier;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        verifier = new AuditChainVerifier(repo, new ObjectMapper());
    }

    @Test
    void emptyLogIsValid() {
        when(repo.findAllOrdered()).thenReturn(List.of());
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isTrue();
        assertThat(r.brokenAt()).isNull();
    }

    @Test
    void freshChainOfThreeRowsVerifies() {
        List<AuditLog> rows = buildValidChain(3);
        when(repo.findAllOrdered()).thenReturn(rows);
        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isTrue();
    }

    @Test
    void tamperedPayloadIsDetectedAtThatRow() {
        List<AuditLog> rows = buildValidChain(3);
        // Tamper the middle row's payload — its stored hash no longer matches.
        AuditLog middle = rows.get(1);
        AuditLog tampered = new AuditLog(
                middle.getPrevHash(), middle.getHash(), middle.getActorId(),
                middle.getActorEmail(), middle.getAction(), middle.getTargetType(),
                middle.getTargetId(), "{\"x\":\"tampered\"}", middle.getCreatedAt());
        copyId(tampered, 2L);
        rows.set(1, tampered);
        when(repo.findAllOrdered()).thenReturn(rows);

        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(2L);
    }

    @Test
    void prevHashMismatchIsDetected() {
        List<AuditLog> rows = buildValidChain(2);
        AuditLog second = rows.get(1);
        AuditLog rebound = new AuditLog(
                new byte[]{0,0,0}, // wrong prev_hash
                second.getHash(), second.getActorId(), second.getActorEmail(),
                second.getAction(), second.getTargetType(), second.getTargetId(),
                second.getPayload(), second.getCreatedAt());
        copyId(rebound, 2L);
        rows.set(1, rebound);
        when(repo.findAllOrdered()).thenReturn(rows);

        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(2L);
    }

    /** Build a valid chain by reusing AuditLogService.computeHash. */
    private List<AuditLog> buildValidChain(int n) {
        List<AuditLog> chain = new ArrayList<>();
        byte[] prev = null;
        for (int i = 1; i <= n; i++) {
            AuditAppendRequest req = new AuditAppendRequest(
                    i, "alice@example.com", "ACTION_" + i,
                    "TENANT", "T_" + i, Map.of("seq", i));
            String payload = "{\"seq\":" + i + "}";
            byte[] hash = AuditLogService.computeHash(prev, req, payload, clock.instant());
            AuditLog row = new AuditLog(
                    prev, hash, req.actorId(), req.actorEmail(),
                    req.action(), req.targetType(), req.targetId(),
                    payload, clock.instant());
            copyId(row, (long) i);
            chain.add(row);
            prev = hash;
        }
        return chain;
    }

    private static void copyId(AuditLog row, long id) {
        try {
            java.lang.reflect.Field f = AuditLog.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
