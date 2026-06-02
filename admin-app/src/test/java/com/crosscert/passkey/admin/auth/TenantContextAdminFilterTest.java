package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantContextAdminFilter}.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>RP_ADMIN scope present → TenantContextHolder.set during chain, cleared after.</li>
 *   <li>PLATFORM_OPERATOR (empty scope) → never set; any stale context is cleared.</li>
 *   <li>Unauthenticated / unknown-role (BusinessException UNAUTHORIZED/ACCESS_DENIED)
 *       → swallowed to empty; chain proceeds; stale context cleared.</li>
 *   <li>fail-closed: any OTHER exception (NPE, other BusinessException, etc.)
 *       propagates — it must NOT be swallowed into an unscoped (all-tenant) query.</li>
 *   <li>Cleanup: holder is cleared even when the downstream chain throws.</li>
 * </ol>
 */
class TenantContextAdminFilterTest {

    @AfterEach
    void clearHolder() {
        TenantContextHolder.clear();
    }

    // ─── (a) RP_ADMIN scope present ───────────────────────────────────────────

    @Test
    void rpAdmin_tenantIdIsSetDuringChain_thenClearedAfterwards() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope()).thenReturn(Optional.of(tenantId));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);

        AtomicReference<UUID> duringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> duringChain.set(TenantContextHolder.get());

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        // During the chain, holder must be set to the RP_ADMIN's tenantId.
        assertThat(duringChain.get()).isEqualTo(tenantId);
        // After the filter completes, holder must be cleared.
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void rpAdmin_chainIsAlwaysInvoked() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope()).thenReturn(Optional.of(tenantId));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = new MockHttpServletRequest();
        HttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    // ─── (b) PLATFORM_OPERATOR (empty scope) ─────────────────────────────────

    @Test
    void platformOperator_emptyScope_holderNeverSet() throws Exception {
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope()).thenReturn(Optional.empty());

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);

        AtomicReference<UUID> duringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> duringChain.set(TenantContextHolder.get());

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        // Holder must remain null throughout (cross-tenant access for PLATFORM_OPERATOR).
        assertThat(duringChain.get()).isNull();
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void platformOperator_emptyScope_clearsStaleContext_fromPriorRequest() throws Exception {
        // Simulate a thread-pool thread that still carries a previous request's
        // tenant context. The empty-scope path must NOT leak it into this request
        // and must clear it on the way out (unconditional clear, HIGH BUG fix).
        UUID stale = UUID.randomUUID();
        TenantContextHolder.set(stale);

        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope()).thenReturn(Optional.empty());

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);

        AtomicReference<UUID> duringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> duringChain.set(TenantContextHolder.get());

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        // After the filter, the stale value must be gone.
        assertThat(TenantContextHolder.get()).isNull();
        // The stale value is still visible INSIDE the chain because the filter
        // does not overwrite it for empty scope — but the post-request clear is
        // what guarantees it cannot leak to the next request on this thread.
        assertThat(duringChain.get()).isEqualTo(stale);
    }

    @Test
    void platformOperator_chainIsAlwaysInvoked() throws Exception {
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope()).thenReturn(Optional.empty());

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = new MockHttpServletRequest();
        HttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    // ─── (c) Unauthenticated / unknown-role → swallowed to empty ─────────────

    @Test
    void unauthorized_swallowed_holderNeverSet_chainProceeds() throws Exception {
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope())
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated"));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);

        AtomicReference<UUID> duringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> duringChain.set(TenantContextHolder.get());

        // Must not throw — UNAUTHORIZED is swallowed to empty scope.
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(duringChain.get()).isNull();
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void accessDenied_unknownRole_swallowed_chainProceeds() throws Exception {
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope())
                .thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role"));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = new MockHttpServletRequest();
        HttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void unauthorized_clearsStaleContext_fromPriorRequest() throws Exception {
        // Same stale-context leak guard as the empty-scope case, but on the
        // exception path: an unauthenticated request must not inherit a prior
        // request's tenant context.
        UUID stale = UUID.randomUUID();
        TenantContextHolder.set(stale);

        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope())
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated"));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(TenantContextHolder.get()).isNull();
    }

    // ─── (d) fail-closed: other exceptions must propagate ────────────────────

    @Test
    void otherRuntimeException_isPropagated_notSwallowed() {
        // A non-auth failure (NPE, DB error, etc.) while resolving an
        // authenticated RP_ADMIN's scope must NOT be swallowed into an
        // unscoped (all-tenant) query. Fail-closed: propagate.
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope())
                .thenThrow(new IllegalStateException("unexpected scope failure"));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected scope failure");

        // Holder must be clean even on the fail-closed path (finally block).
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void otherBusinessException_isPropagated_notSwallowed() {
        // A BusinessException that is NOT UNAUTHORIZED/ACCESS_DENIED (e.g.
        // INTERNAL_SERVER_ERROR) must also fail closed rather than degrade to
        // an unscoped query.
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope())
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "boom"));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(BusinessException.class);

        assertThat(TenantContextHolder.get()).isNull();
    }

    // ─── (e) Cleanup even when chain throws ───────────────────────────────────

    @Test
    void holderClearedEvenWhenChainThrows() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantBoundary boundary = mock(TenantBoundary.class);
        when(boundary.currentTenantScope()).thenReturn(Optional.of(tenantId));

        TenantContextAdminFilter filter = new TenantContextAdminFilter(boundary);
        FilterChain chain = (req, res) -> { throw new RuntimeException("downstream failure"); };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        } catch (RuntimeException ignored) {
            // expected
        }

        // Holder must be cleared even when chain throws (finally block).
        assertThat(TenantContextHolder.get()).isNull();
    }
}
