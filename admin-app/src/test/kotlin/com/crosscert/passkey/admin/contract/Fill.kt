package com.crosscert.passkey.admin.contract

import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.lang.reflect.Constructor
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Optional
import java.util.OptionalLong
import java.util.SortedSet
import java.util.TreeSet
import java.util.UUID

/**
 * 타입만 보고 결정적인 값을 채운다 — 형태 스냅샷용 서비스 스텁.
 *
 * 모든 필드를 non-null 로 채워(전역 `non_null` 포함 규칙에서 키가 빠지지 않게) 응답의 키 집합 전체를
 * 스냅샷에 드러낸다. 컬렉션은 원소 1개, Map 은 항목 1개(순회 순서가 결정적), Optional 은 값 있음.
 *
 * 객체는 공개(비합성) 생성자 중 인자가 가장 많은 것으로 만든다 — Java record 는 정규 생성자,
 * Kotlin data class 는 주 생성자가 잡힌다(`DefaultConstructorMarker` 를 받는 기본값 생성자는 제외).
 * 제네릭 타입 변수는 `PageView<CredentialView>` 처럼 실제 인자로 풀어 채운다.
 *
 * (Kotlin 전환) 컬렉션 값은 원 Java 와 같은 구현(`java.util.List.of`·`TreeSet`·`LinkedHashSet`·`LinkedHashMap`)으로 만든다.
 */
internal object Fill {

    val ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f111")
    val ODT: OffsetDateTime = OffsetDateTime.parse("2026-10-11T12:34:56+09:00")
    val INSTANT: Instant = Instant.parse("2026-10-11T03:34:56Z")
    val DATE: LocalDate = LocalDate.parse("2026-10-11")
    const val STR = "x"

    /** 반환 타입으로 값을 채우는 Mockito Answer. void 는 null. */
    val ANSWER: Answer<Any?> = Answer { inv ->
        val raw = inv.method.returnType
        if (raw == java.lang.Void.TYPE || raw == java.lang.Void::class.java) null else value(inv.method.genericReturnType)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> of(type: Class<T>): T = value(type) as T

    fun value(type: Type): Any = value(type, emptyMap(), 0)

    private fun value(type: Type, env: Map<TypeVariable<*>, Type>, depth: Int): Any {
        if (depth > 12) throw IllegalStateException("Fill: 중첩이 너무 깊다(순환 타입?): $type")
        if (type is TypeVariable<*>) {
            val bound = env[type]
            return value(bound ?: type.bounds[0], env, depth + 1)
        }
        if (type is WildcardType) {
            return value(type.upperBounds[0], env, depth + 1)
        }
        if (type is GenericArrayType) {
            throw IllegalArgumentException("Fill: 제네릭 배열 미지원: $type")
        }
        if (type is ParameterizedType) {
            val raw = type.rawType as Class<*>
            val args = type.actualTypeArguments
            if (Collection::class.java.isAssignableFrom(raw)) {
                val e = value(args[0], env, depth + 1)
                if (SortedSet::class.java.isAssignableFrom(raw)) return TreeSet(java.util.List.of(e))
                if (Set::class.java.isAssignableFrom(raw)) return LinkedHashSet(java.util.List.of(e))
                return java.util.List.of(e)
            }
            if (Map::class.java.isAssignableFrom(raw)) {
                val m = LinkedHashMap<Any, Any>()
                m[value(args[0], env, depth + 1)] = value(args[1], env, depth + 1)
                return m
            }
            if (raw == Optional::class.java) return Optional.of(value(args[0], env, depth + 1))
            val inner = HashMap(env)
            val vars = raw.typeParameters
            for (i in vars.indices) inner[vars[i]] = resolve(args[i], env)
            return construct(raw, inner, depth)
        }
        return scalarOrObject(type as Class<*>, env, depth)
    }

    private fun resolve(t: Type, env: Map<TypeVariable<*>, Type>): Type {
        if (t is TypeVariable<*> && env.containsKey(t)) return env.getValue(t)
        return t
    }

    private fun scalarOrObject(c: Class<*>, env: Map<TypeVariable<*>, Type>, depth: Int): Any {
        if (c == String::class.java || c == Any::class.java || c == CharSequence::class.java) return STR
        // 기본형(`javaPrimitiveType`)과 박스형(`javaObjectType`) 둘 다 — 원 Java 의 `int.class || Integer.class` 와 같다.
        if (c == Boolean::class.javaPrimitiveType || c == Boolean::class.javaObjectType) return true
        if (c == Int::class.javaPrimitiveType || c == Int::class.javaObjectType) return 1
        if (c == Long::class.javaPrimitiveType || c == Long::class.javaObjectType) return 1L
        if (c == Double::class.javaPrimitiveType || c == Double::class.javaObjectType) return 0.5
        if (c == Float::class.javaPrimitiveType || c == Float::class.javaObjectType) return 0.5f
        if (c == Short::class.javaPrimitiveType || c == Short::class.javaObjectType) return 1.toShort()
        if (c == Byte::class.javaPrimitiveType || c == Byte::class.javaObjectType) return 1.toByte()
        if (c == UUID::class.java) return ID
        if (c == OffsetDateTime::class.java) return ODT
        if (c == Instant::class.java) return INSTANT
        if (c == LocalDate::class.java) return DATE
        if (c == LocalDateTime::class.java) return ODT.toLocalDateTime()
        if (c == Duration::class.java) return Duration.ofHours(1)
        if (c == ByteArray::class.java) return byteArrayOf(1, 2, 3)
        if (c == OptionalLong::class.java) return OptionalLong.of(1L)
        if (c.isEnum) return c.enumConstants[0]
        if (List::class.java.isAssignableFrom(c) || Collection::class.java.isAssignableFrom(c) ||
            Map::class.java.isAssignableFrom(c)
        ) {
            throw IllegalArgumentException("Fill: 원소 타입 없는 컬렉션: $c")
        }
        return construct(c, env, depth)
    }

    private fun construct(c: Class<*>, env: Map<TypeVariable<*>, Type>, depth: Int): Any {
        // JPA 엔티티(core, 전환 대상 아님)는 getter 가 같은 규칙으로 값을 내는 mock 으로 대신한다.
        if (c.isAnnotationPresent(jakarta.persistence.Entity::class.java)) return Mockito.mock(c, ANSWER)
        if (c.isInterface || Modifier.isAbstract(c.modifiers)) {
            throw IllegalArgumentException("Fill: 인터페이스·추상 타입은 직접 스텁하세요: " + c.name)
        }
        val ctor: Constructor<*> = widestPublicConstructor(c)
            ?: throw IllegalArgumentException("Fill: 공개 생성자 없음: " + c.name)
        val params = ctor.genericParameterTypes
        val args = arrayOfNulls<Any>(params.size)
        for (i in params.indices) args[i] = value(params[i], env, depth + 1)
        try {
            return ctor.newInstance(*args)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("Fill: 생성 실패 " + c.name, e)
        }
    }

    /**
     * 공개(비합성) 생성자 중 인자가 가장 많은 것 — Kotlin 기본값 생성자(`DefaultConstructorMarker`)는 제외.
     * 동률이면 `getConstructors()` 순서상 앞의 것(원 Java `Stream.max` 와 같다). [Stub] 도 같은 규칙을 쓴다.
     */
    fun widestPublicConstructor(c: Class<*>): Constructor<*>? =
        c.constructors
            .filter { !it.isSynthetic }
            .filter { k -> k.parameterTypes.none { p -> p.name == "kotlin.jvm.internal.DefaultConstructorMarker" } }
            .maxByOrNull { it.parameterCount }
}
