package com.crosscert.passkey.admin.web.format

import com.crosscert.passkey.admin.web.format.ActivityDiff.DiffRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** pages/activityDiff.test.ts 의 케이스를 1:1 로 옮긴 것. */
class ActivityDiffTest {

    companion object {
        val M = ObjectMapper()

        fun j(s: String): JsonNode {
            try {
                return M.readTree(s)
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        const val TENANT_UPDATE = "{\"before\":{\"displayName\":\"Acme\",\"webauthnTimeoutMs\":60000,\"allowedOrigins\":[\"https://acme.com\"]}," +
            "\"after\":{\"displayName\":\"Acme\",\"webauthnTimeoutMs\":120000,\"allowedOrigins\":[\"https://acme.com\",\"https://b.acme.com\"]}," +
            "\"changedFields\":[\"allowedOrigins\",\"webauthnTimeoutMs\"]}"
    }

    @Test
    @DisplayName("diff 필드가 있으면 그대로 쓴다(서버 ③)")
    fun serverDiffWins() {
        val diff = listOf(DiffRow("rpName", j("\"A\""), j("\"B\"")))
        assertThat(ActivityDiff.buildDiffRows(diff, TENANT_UPDATE)).isEqualTo(diff)
    }

    @Test
    @DisplayName("diff 가 없으면 TENANT_UPDATE before/after 를 changedFields 순서로")
    fun changedFieldsOrder() {
        assertThat(ActivityDiff.buildDiffRows(null, TENANT_UPDATE)).containsExactly(
            DiffRow("allowedOrigins", j("[\"https://acme.com\"]"), j("[\"https://acme.com\",\"https://b.acme.com\"]")),
            DiffRow("webauthnTimeoutMs", j("60000"), j("120000")),
        )
    }

    @Test
    @DisplayName("changedFields 가 없으면 값이 다른 키만")
    fun noChangedFields() {
        assertThat(ActivityDiff.buildDiffRows(null, "{\"before\":{\"a\":1,\"b\":2},\"after\":{\"a\":1,\"b\":3}}"))
            .containsExactly(DiffRow("b", j("2"), j("3")))
    }

    @Test
    @DisplayName("before/after 가 없거나 JSON 이 아니면 빈 배열")
    fun emptyWhenNoBeforeAfterOrNotJson() {
        assertThat(ActivityDiff.buildDiffRows(null, "{\"ip\":\"1.2.3.4\"}")).isEmpty()
        assertThat(ActivityDiff.buildDiffRows(null, "not json")).isEmpty()
    }

    @Test
    @DisplayName("(추가) changedFields 가 문자열 배열이 아니면 값이 다른 키, 한쪽에만 있는 키는 undefined 로")
    fun extras() {
        // 숫자가 섞인 changedFields → 문자열 배열이 아니므로 값 비교
        assertThat(ActivityDiff.buildDiffRows(null, "{\"before\":{\"a\":1},\"after\":{\"a\":2,\"c\":3},\"changedFields\":[\"a\",1]}"))
            .containsExactly(DiffRow("a", j("1"), j("2")), DiffRow("c", null, j("3")))
        // 뒤따르는 쓰레기·빈 문자열은 JSON.parse 처럼 실패
        assertThat(ActivityDiff.buildDiffRows(null, "{\"before\":{},\"after\":{}} x")).isEmpty()
        assertThat(ActivityDiff.buildDiffRows(null, "")).isEmpty()
        assertThat(ActivityDiff.buildDiffRows(null, null)).isEmpty()
    }

    @Test
    @DisplayName("arrayChange: 추가/제거 원소")
    fun arrayChangeAddedRemoved() {
        val c = ActivityDiff.arrayChange(j("[\"a\",\"b\"]"), j("[\"b\",\"c\"]"))!!
        assertThat(c.added).containsExactly("c")
        assertThat(c.removed).containsExactly("a")
    }

    @Test
    @DisplayName("arrayChange: 배열이 아니면 null")
    fun arrayChangeNullWhenNotArrays() {
        assertThat(ActivityDiff.arrayChange(j("1"), j("2"))).isNull()
        assertThat(ActivityDiff.arrayChange(null, j("[]"))).isNull()
    }

    @Test
    @DisplayName("fmtVal / String(x) 규칙")
    fun fmtValAndStringify() {
        assertThat(ActivityDiff.fmtVal(null)).isEqualTo("—")
        assertThat(ActivityDiff.fmtVal(j("null"))).isEqualTo("—")
        assertThat(ActivityDiff.fmtVal(j("\"x\""))).isEqualTo("x")
        assertThat(ActivityDiff.fmtVal(j("60000"))).isEqualTo("60000")
        assertThat(ActivityDiff.fmtVal(j("{\"a\":[1]}"))).isEqualTo("{\"a\":[1]}")
        assertThat(ActivityDiff.arrayChange(j("[1,null,{\"a\":1}]"), j("[]"))!!.removed).containsExactly("1", "null", "[object Object]")
    }
}
