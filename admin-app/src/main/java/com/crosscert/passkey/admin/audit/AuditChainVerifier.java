package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On-demand tamper detector for the audit_log SHA-256 hash chain.
 *
 * <p>Walks all rows returned by {@link AuditLogRepository#findAllOrdered()}
 * (ascending by id) and, for each row, checks:
 * <ol>
 *   <li>The stored {@code prev_hash} matches the hash of the preceding row
 *       (or is null for the genesis row).</li>
 *   <li>The stored {@code hash} matches the value recomputed from the row's
 *       own fields using {@link AuditLogService#computeHash}.</li>
 * </ol>
 *
 * <p>Hash recomputation passes the stored payload string <em>directly</em> to
 * {@link AuditLogService#computeHash} — the same bytes that were hashed at
 * append time — so the result is always deterministic regardless of Jackson
 * number type widening. The payload is also parsed into a {@code Map} solely
 * to populate the {@link AuditAppendRequest} that {@code computeHash} requires;
 * the parsed {@code Map} itself is never re-serialized.
 *
 * <p>This class is {@code @Component} so Spring wires it automatically; no
 * extra configuration is needed.
 */
@Component
public class AuditChainVerifier {

    private final AuditLogRepository repo;
    private final ObjectMapper canonical;

    public AuditChainVerifier(AuditLogRepository repo, ObjectMapper baseMapper) {
        this.repo = repo;
        this.canonical = baseMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * Verify the entire hash chain.
     *
     * @return {@link Result#valid()} when the chain is intact, or
     *         {@link Result#broken(UUID)} with the id of the first failing row.
     */
    public Result verify() {
        List<AuditLog> rows = repo.findAllOrdered();
        byte[] expectedPrev = null;
        for (AuditLog row : rows) {
            // Check prev_hash linkage.
            if (!Arrays.equals(expectedPrev, row.getPrevHash())) {
                return Result.broken(row.getId());
            }
            // Recompute hash from the stored fields. We pass the stored
            // payload string directly to computeHash — the same bytes that
            // were hashed on append — so the result is always deterministic.
            byte[] expected = recomputeHash(row);
            if (!Arrays.equals(expected, row.getHash())) {
                return Result.broken(row.getId());
            }
            expectedPrev = row.getHash();
        }
        return Result.valid();
    }

    private byte[] recomputeHash(AuditLog row) {
        try {
            // Parse stored payload back to Map so we can build the request.
            // We do NOT re-serialize — we pass row.getPayload() directly to
            // computeHash, which is the exact string that was hashed on append.
            Map<String, Object> payload = canonical.readValue(
                    row.getPayload(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            AuditAppendRequest req = new AuditAppendRequest(
                    row.getActorId(), row.getActorEmail(), row.getAction(),
                    row.getTargetType(), row.getTargetId(),
                    row.getTenantId(),
                    payload);
            return AuditLogService.computeHash(
                    row.getPrevHash(), req, row.getPayload(), row.getCreatedAt());
        } catch (Exception e) {
            // Malformed JSON in the payload column is itself tamper evidence.
            return new byte[0];
        }
    }

    /**
     * Verify the hash chain for a specific tenant (or null for PLATFORM_OPERATOR rows).
     *
     * @param tenantId tenant UUID, or null for rows with no tenant
     * @return {@link TenantResult#valid(UUID)} when the chain is intact, or
     *         {@link TenantResult#broken(UUID, UUID)} with the id of the first failing row.
     */
    public TenantResult verifyTenant(UUID tenantId) {
        List<AuditLog> rows = repo.findAllByTenantOrdered(tenantId);
        byte[] expectedPrev = null;
        for (AuditLog row : rows) {
            if (!Arrays.equals(expectedPrev, row.getTenantPrevHash())) {
                return TenantResult.broken(tenantId, row.getId());
            }
            byte[] expected = recomputeTenantHash(row);
            if (!Arrays.equals(expected, row.getTenantHash())) {
                return TenantResult.broken(tenantId, row.getId());
            }
            expectedPrev = row.getTenantHash();
        }
        return TenantResult.valid(tenantId);
    }

    /**
     * Verify the hash chain for every distinct tenant found in the repository.
     *
     * @return list of {@link TenantResult} — one entry per distinct tenant_id value
     */
    public List<TenantResult> verifyAllTenants() {
        List<UUID> tenantIds = repo.findDistinctTenantIds();
        List<TenantResult> out = new java.util.ArrayList<>(tenantIds.size());
        for (UUID id : tenantIds) {
            out.add(verifyTenant(id));
        }
        return out;
    }

    private byte[] recomputeTenantHash(AuditLog row) {
        try {
            Map<String, Object> payload = canonical.readValue(
                    row.getPayload(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            AuditAppendRequest req = new AuditAppendRequest(
                    row.getActorId(), row.getActorEmail(), row.getAction(),
                    row.getTargetType(), row.getTargetId(),
                    row.getTenantId(),
                    payload);
            return AuditLogService.computeHash(
                    row.getTenantPrevHash(), req, row.getPayload(), row.getCreatedAt());
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * Verification result.
     *
     * @param ok       true when the entire chain is intact
     * @param brokenAt id of the first row that failed verification, or null
     */
    public record Result(boolean ok, UUID brokenAt) {
        public static Result valid() { return new Result(true, null); }
        public static Result broken(UUID id) { return new Result(false, id); }
    }

    /**
     * Per-tenant verification result.
     *
     * @param tenantId the tenant that was verified (null for PLATFORM_OPERATOR rows)
     * @param ok       true when the tenant's chain is intact
     * @param brokenAt id of the first row that failed verification, or null
     */
    public record TenantResult(UUID tenantId, boolean ok, UUID brokenAt) {
        public static TenantResult valid(UUID tenantId) { return new TenantResult(tenantId, true, null); }
        public static TenantResult broken(UUID tenantId, UUID id) { return new TenantResult(tenantId, false, id); }
    }
}
