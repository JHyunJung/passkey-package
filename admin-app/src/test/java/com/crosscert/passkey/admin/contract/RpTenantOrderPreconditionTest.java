package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더 HTML 스냅샷의 전제: RP_ADMIN 주체의 허용 tenant 는 정렬하지 않은 순회 순서의 첫 값이 최소값(f111)이 아니다.
 * 그래서 {@code MeController} 가 {@code tenantIds} 를 정렬하지 않으면 RP 홈 리다이렉트({@code tenantIds.get(0)})가
 * 반드시 f111 이 아닌 곳으로 가고 스냅샷이 깨진다({@link HtmlContractSupport#RP_TENANTS}).
 */
class RpTenantOrderPreconditionTest {

    @Test
    void principalTenantIdsIterateWithNonMinimumFirst() {
        AdminUserDetails p = (AdminUserDetails) HtmlContractSupport.rpAdmin().getPrincipal();
        List<UUID> unsorted = new ArrayList<>(p.getAllowedTenantIds());
        List<UUID> sorted = new ArrayList<>(unsorted);
        Collections.sort(sorted);

        assertThat(sorted.get(0)).isEqualTo(HtmlContractSupport.TID);
        assertThat(unsorted.get(0)).as("정렬 없는 첫 원소").isNotEqualTo(HtmlContractSupport.TID);
        assertThat(p.getAllowedTenantIds()).containsAll(List.of(HtmlContractSupport.TID, HtmlContractSupport.TID2));
    }
}
