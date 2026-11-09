package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.auth.AdminUserDetails
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 렌더 HTML 스냅샷의 전제: RP_ADMIN 주체의 허용 tenant 는 정렬하지 않은 순회 순서의 첫 값이 최소값(f111)이 아니다.
 * 그래서 `MeController` 가 `tenantIds` 를 정렬하지 않으면 RP 홈 리다이렉트(`tenantIds.get(0)`)가
 * 반드시 f111 이 아닌 곳으로 가고 스냅샷이 깨진다([HtmlContractSupport.RP_TENANTS]).
 */
class RpTenantOrderPreconditionTest {

    @Test
    fun principalTenantIdsIterateWithNonMinimumFirst() {
        val p = HtmlContractSupport.rpAdmin().principal as AdminUserDetails
        val unsorted: List<UUID> = ArrayList(p.allowedTenantIds)
        val sorted = ArrayList(unsorted)
        sorted.sort()

        assertThat(sorted[0]).isEqualTo(HtmlContractSupport.TID)
        assertThat(unsorted[0]).`as`("정렬 없는 첫 원소").isNotEqualTo(HtmlContractSupport.TID)
        assertThat(p.allowedTenantIds).containsAll(listOf(HtmlContractSupport.TID, HtmlContractSupport.TID2))
    }
}
