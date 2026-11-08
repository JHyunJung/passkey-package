package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.tenant.TenantContextHolder
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [TenantContextAdminFilter].
 *
 * Scenarios:
 *  1. RP_ADMIN scope present → TenantContextHolder.set during chain, cleared after.
 *  2. PLATFORM_OPERATOR (empty scope) → never set; any stale context is cleared.
 *  3. Unauthenticated / unknown-role (BusinessException UNAUTHORIZED/ACCESS_DENIED)
 *     → swallowed to empty; chain proceeds; stale context cleared.
 *  4. fail-closed: any OTHER exception (NPE, other BusinessException, etc.)
 *     propagates — it must NOT be swallowed into an unscoped (all-tenant) query.
 *  5. Cleanup: holder is cleared even when the downstream chain throws.
 */
class TenantContextAdminFilterTest {

    @AfterEach
    fun clearHolder() {
        TenantContextHolder.clear()
    }

    // ─── (a) RP_ADMIN scope present ───────────────────────────────────────────

    @Test
    fun rpAdmin_tenantIdIsSetDuringChain_thenClearedAfterwards() {
        val tenantId = UUID.randomUUID()
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope()).thenReturn(Optional.of(tenantId))

        val filter = TenantContextAdminFilter(boundary)

        val duringChain = AtomicReference<UUID>()
        val chain = FilterChain { _, _ -> duringChain.set(TenantContextHolder.get()) }

        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        // During the chain, holder must be set to the RP_ADMIN's tenantId.
        assertThat(duringChain.get()).isEqualTo(tenantId)
        // After the filter completes, holder must be cleared.
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun rpAdmin_chainIsAlwaysInvoked() {
        val tenantId = UUID.randomUUID()
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope()).thenReturn(Optional.of(tenantId))

        val filter = TenantContextAdminFilter(boundary)
        val chain: FilterChain = mock()
        val req: HttpServletRequest = MockHttpServletRequest()
        val res: HttpServletResponse = MockHttpServletResponse()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }

    // ─── (b) PLATFORM_OPERATOR (empty scope) ─────────────────────────────────

    @Test
    fun platformOperator_emptyScope_holderNeverSet() {
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope()).thenReturn(Optional.empty())

        val filter = TenantContextAdminFilter(boundary)

        val duringChain = AtomicReference<UUID>()
        val chain = FilterChain { _, _ -> duringChain.set(TenantContextHolder.get()) }

        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        // Holder must remain null throughout (cross-tenant access for PLATFORM_OPERATOR).
        assertThat(duringChain.get()).isNull()
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun platformOperator_emptyScope_clearsStaleContext_fromPriorRequest() {
        // Simulate a thread-pool thread that still carries a previous request's
        // tenant context. The empty-scope path must NOT leak it into this request
        // and must clear it on the way out (unconditional clear, HIGH BUG fix).
        val stale = UUID.randomUUID()
        TenantContextHolder.set(stale)

        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope()).thenReturn(Optional.empty())

        val filter = TenantContextAdminFilter(boundary)

        val duringChain = AtomicReference<UUID>()
        val chain = FilterChain { _, _ -> duringChain.set(TenantContextHolder.get()) }

        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        // After the filter, the stale value must be gone.
        assertThat(TenantContextHolder.get()).isNull()
        // The stale value is still visible INSIDE the chain because the filter
        // does not overwrite it for empty scope — but the post-request clear is
        // what guarantees it cannot leak to the next request on this thread.
        assertThat(duringChain.get()).isEqualTo(stale)
    }

    @Test
    fun platformOperator_chainIsAlwaysInvoked() {
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope()).thenReturn(Optional.empty())

        val filter = TenantContextAdminFilter(boundary)
        val chain: FilterChain = mock()
        val req: HttpServletRequest = MockHttpServletRequest()
        val res: HttpServletResponse = MockHttpServletResponse()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }

    // ─── (c) Unauthenticated / unknown-role → swallowed to empty ─────────────

    @Test
    fun unauthorized_swallowed_holderNeverSet_chainProceeds() {
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope())
            .thenThrow(BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated"))

        val filter = TenantContextAdminFilter(boundary)

        val duringChain = AtomicReference<UUID>()
        val chain = FilterChain { _, _ -> duringChain.set(TenantContextHolder.get()) }

        // Must not throw — UNAUTHORIZED is swallowed to empty scope.
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        assertThat(duringChain.get()).isNull()
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun accessDenied_unknownRole_swallowed_chainProceeds() {
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope())
            .thenThrow(BusinessException(ErrorCode.ACCESS_DENIED, "unknown role"))

        val filter = TenantContextAdminFilter(boundary)
        val chain: FilterChain = mock()
        val req: HttpServletRequest = MockHttpServletRequest()
        val res: HttpServletResponse = MockHttpServletResponse()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun unauthorized_clearsStaleContext_fromPriorRequest() {
        // Same stale-context leak guard as the empty-scope case, but on the
        // exception path: an unauthenticated request must not inherit a prior
        // request's tenant context.
        val stale = UUID.randomUUID()
        TenantContextHolder.set(stale)

        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope())
            .thenThrow(BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated"))

        val filter = TenantContextAdminFilter(boundary)
        val chain: FilterChain = mock()

        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        assertThat(TenantContextHolder.get()).isNull()
    }

    // ─── (d) fail-closed: other exceptions must propagate ────────────────────

    @Test
    fun otherRuntimeException_isPropagated_notSwallowed() {
        // A non-auth failure (NPE, DB error, etc.) while resolving an
        // authenticated RP_ADMIN's scope must NOT be swallowed into an
        // unscoped (all-tenant) query. Fail-closed: propagate.
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope())
            .thenThrow(IllegalStateException("unexpected scope failure"))

        val filter = TenantContextAdminFilter(boundary)
        val chain: FilterChain = mock()

        assertThatThrownBy {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("unexpected scope failure")

        // Holder must be clean even on the fail-closed path (finally block).
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun otherBusinessException_isPropagated_notSwallowed() {
        // A BusinessException that is NOT UNAUTHORIZED/ACCESS_DENIED (e.g.
        // INTERNAL_SERVER_ERROR) must also fail closed rather than degrade to
        // an unscoped query.
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope())
            .thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "boom"))

        val filter = TenantContextAdminFilter(boundary)
        val chain: FilterChain = mock()

        assertThatThrownBy {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)
        }
            .isInstanceOf(BusinessException::class.java)

        assertThat(TenantContextHolder.get()).isNull()
    }

    // ─── (e) Cleanup even when chain throws ───────────────────────────────────

    @Test
    fun holderClearedEvenWhenChainThrows() {
        val tenantId = UUID.randomUUID()
        val boundary: TenantBoundary = mock()
        whenever(boundary.currentTenantScope()).thenReturn(Optional.of(tenantId))

        val filter = TenantContextAdminFilter(boundary)
        val chain = FilterChain { _, _ -> throw RuntimeException("downstream failure") }

        try {
            filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)
        } catch (ignored: RuntimeException) {
            // expected
        }

        // Holder must be cleared even when chain throws (finally block).
        assertThat(TenantContextHolder.get()).isNull()
    }
}
