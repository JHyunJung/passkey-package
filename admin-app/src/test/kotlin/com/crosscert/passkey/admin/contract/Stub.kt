package com.crosscert.passkey.admin.contract

/**
 * 생성자 인자 이름으로 일부 값만 정하고 나머지는 [Fill] 로 채운 객체 — 화면 스냅샷의 결정적 스텁.
 *
 * [Fill] 과 같은 규칙으로 공개(비합성) 생성자 중 인자가 가장 많은 것을 쓴다(Java record 는 정규 생성자,
 * Kotlin data class 는 주 생성자). 인자 이름은 `-parameters`(Java)·`javaParameters = true`(Kotlin) 로
 * 클래스 파일에 남으므로 같은 객체가 만들어진다. 없는 이름을 주면 실패한다(오타 방지).
 * `null` 을 값으로 줄 수 있다.
 */
internal object Stub {

    @Suppress("UNCHECKED_CAST")
    fun <T> of(type: Class<T>, vararg namesAndValues: Any?): T {
        if (namesAndValues.size % 2 != 0) throw IllegalArgumentException("Stub: 이름·값 쌍이 아니다")
        val given = HashMap<String, Any?>()
        var i = 0
        while (i < namesAndValues.size) {
            given[namesAndValues[i] as String] = namesAndValues[i + 1]
            i += 2
        }
        val ctor = Fill.widestPublicConstructor(type)
            ?: throw IllegalArgumentException("Stub: 공개 생성자 없음: " + type.name)
        val params = ctor.parameters
        val args = arrayOfNulls<Any>(params.size)
        val used = HashSet<String>()
        for (k in params.indices) {
            val p = params[k]
            if (!p.isNamePresent) throw IllegalStateException("Stub: 인자 이름 없음(-parameters 꺼짐?): " + type.name)
            if (given.containsKey(p.name)) {
                args[k] = given[p.name]
                used.add(p.name)
            } else {
                args[k] = Fill.value(p.parameterizedType)
            }
        }
        val unknown = HashSet(given.keys)
        unknown.removeAll(used)
        if (unknown.isNotEmpty()) throw IllegalArgumentException("Stub: " + type.simpleName + " 에 없는 인자 " + unknown)
        try {
            return ctor.newInstance(*args) as T
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Stub: 생성 실패 " + type.name, e)
        }
    }
}
