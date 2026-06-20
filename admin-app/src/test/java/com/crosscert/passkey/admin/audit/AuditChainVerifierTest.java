package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditChainVerifierTest {

    /** Stable UUID for row 2 — used in tamper/prevHash-mismatch assertions. */
    private static final UUID ROW_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private AuditLogRepository repo;
    private AuditChainVerifier verifier;
    // KST clock so the seeded createdAt + hashed timestamp carry the +09:00
    // offset, matching production (KstTime.ZONE). Absolute instant unchanged.
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE);

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
                middle.getTargetId(), null, null, null,
                "{\"x\":\"tampered\"}", middle.getCreatedAt());
        copyId(tampered, ROW_2_ID);
        rows.set(1, tampered);
        when(repo.findAllOrdered()).thenReturn(rows);

        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(ROW_2_ID);
    }

    @Test
    void prevHashMismatchIsDetected() {
        List<AuditLog> rows = buildValidChain(2);
        AuditLog second = rows.get(1);
        AuditLog rebound = new AuditLog(
                new byte[]{0,0,0}, // wrong prev_hash
                second.getHash(), second.getActorId(), second.getActorEmail(),
                second.getAction(), second.getTargetType(), second.getTargetId(),
                null, null, null,
                second.getPayload(), second.getCreatedAt());
        copyId(rebound, ROW_2_ID);
        rows.set(1, rebound);
        when(repo.findAllOrdered()).thenReturn(rows);

        AuditChainVerifier.Result r = verifier.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.brokenAt()).isEqualTo(ROW_2_ID);
    }

    /** Build a valid chain by reusing AuditLogService.computeHash. */
    private List<AuditLog> buildValidChain(int n) {
        List<AuditLog> chain = new ArrayList<>();
        byte[] prev = null;
        for (int i = 1; i <= n; i++) {
            UUID actorId = UUID.fromString(
                    String.format("00000000-0000-0000-0000-%012d", i));
            AuditAppendRequest req = new AuditAppendRequest(
                    actorId, "alice@example.com", "ACTION_" + i,
                    "TENANT", "T_" + i, null, Map.of("seq", i));
            String payload = "{\"seq\":" + i + "}";
            // Single now (KST, +09:00) shared by the hash input and the seeded
            // createdAt so the verifier recomputes an identical hash.
            OffsetDateTime now = OffsetDateTime.now(clock);
            byte[] hash = AuditLogService.computeHash(prev, req, payload, now);
            AuditLog row = new AuditLog(
                    prev, hash, req.actorId(), req.actorEmail(),
                    req.action(), req.targetType(), req.targetId(),
                    null, null, null,
                    payload, now);
            UUID rowId = UUID.fromString(
                    String.format("00000000-0000-0000-0000-%012d", i));
            copyId(row, rowId);
            chain.add(row);
            prev = hash;
        }
        return chain;
    }

    private static void copyId(AuditLog row, UUID id) {
        try {
            // id now lives on BaseEntity (Phase 8 T7).
            java.lang.reflect.Field f = AuditLog.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
