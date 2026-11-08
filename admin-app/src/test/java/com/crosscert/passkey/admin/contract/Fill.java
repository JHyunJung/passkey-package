package com.crosscert.passkey.admin.contract;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 타입만 보고 결정적인 값을 채운다 — 형태 스냅샷용 서비스 스텁.
 *
 * <p>모든 필드를 non-null 로 채워(전역 {@code non_null} 포함 규칙에서 키가 빠지지 않게) 응답의 키 집합 전체를
 * 스냅샷에 드러낸다. 컬렉션은 원소 1개, Map 은 항목 1개(순회 순서가 결정적), Optional 은 값 있음.
 *
 * <p>객체는 공개(비합성) 생성자 중 인자가 가장 많은 것으로 만든다 — Java record 는 정규 생성자,
 * Kotlin data class 는 주 생성자가 잡힌다(전환 뒤에도 테스트를 고치지 않고 같은 값이 나오게).
 * 제네릭 타입 변수는 {@code PageView<CredentialView>} 처럼 실제 인자로 풀어 채운다.
 */
final class Fill {

    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000f111");
    static final OffsetDateTime ODT = OffsetDateTime.parse("2026-10-11T12:34:56+09:00");
    static final Instant INSTANT = Instant.parse("2026-10-11T03:34:56Z");
    static final LocalDate DATE = LocalDate.parse("2026-10-11");
    static final String STR = "x";

    /** 반환 타입으로 값을 채우는 Mockito Answer. void 는 null. */
    static final Answer<Object> ANSWER = inv -> {
        Class<?> raw = inv.getMethod().getReturnType();
        if (raw == void.class || raw == Void.class) return null;
        return value(inv.getMethod().getGenericReturnType());
    };

    private Fill() {}

    @SuppressWarnings("unchecked")
    static <T> T of(Class<T> type) {
        return (T) value(type);
    }

    static Object value(Type type) {
        return value(type, Map.of(), 0);
    }

    private static Object value(Type type, Map<TypeVariable<?>, Type> env, int depth) {
        if (depth > 12) throw new IllegalStateException("Fill: 중첩이 너무 깊다(순환 타입?): " + type);
        if (type instanceof TypeVariable<?> tv) {
            Type bound = env.get(tv);
            return value(bound != null ? bound : tv.getBounds()[0], env, depth + 1);
        }
        if (type instanceof WildcardType wt) {
            return value(wt.getUpperBounds()[0], env, depth + 1);
        }
        if (type instanceof GenericArrayType) {
            throw new IllegalArgumentException("Fill: 제네릭 배열 미지원: " + type);
        }
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            Type[] args = pt.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                Object e = value(args[0], env, depth + 1);
                if (SortedSet.class.isAssignableFrom(raw)) return new TreeSet<>(List.of(e));
                if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>(List.of(e));
                return List.of(e);
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<Object, Object> m = new LinkedHashMap<>();
                m.put(value(args[0], env, depth + 1), value(args[1], env, depth + 1));
                return m;
            }
            if (raw == Optional.class) return Optional.of(value(args[0], env, depth + 1));
            Map<TypeVariable<?>, Type> inner = new HashMap<>(env);
            TypeVariable<?>[] vars = raw.getTypeParameters();
            for (int i = 0; i < vars.length; i++) inner.put(vars[i], resolve(args[i], env));
            return construct(raw, inner, depth);
        }
        return scalarOrObject((Class<?>) type, env, depth);
    }

    private static Type resolve(Type t, Map<TypeVariable<?>, Type> env) {
        if (t instanceof TypeVariable<?> tv && env.containsKey(tv)) return env.get(tv);
        return t;
    }

    private static Object scalarOrObject(Class<?> c, Map<TypeVariable<?>, Type> env, int depth) {
        if (c == String.class || c == Object.class || c == CharSequence.class) return STR;
        if (c == boolean.class || c == Boolean.class) return Boolean.TRUE;
        if (c == int.class || c == Integer.class) return 1;
        if (c == long.class || c == Long.class) return 1L;
        if (c == double.class || c == Double.class) return 0.5d;
        if (c == float.class || c == Float.class) return 0.5f;
        if (c == short.class || c == Short.class) return (short) 1;
        if (c == byte.class || c == Byte.class) return (byte) 1;
        if (c == UUID.class) return ID;
        if (c == OffsetDateTime.class) return ODT;
        if (c == Instant.class) return INSTANT;
        if (c == LocalDate.class) return DATE;
        if (c == LocalDateTime.class) return ODT.toLocalDateTime();
        if (c == Duration.class) return Duration.ofHours(1);
        if (c == byte[].class) return new byte[] {1, 2, 3};
        if (c == OptionalLong.class) return OptionalLong.of(1L);
        if (c.isEnum()) return c.getEnumConstants()[0];
        if (List.class.isAssignableFrom(c) || Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c)) {
            throw new IllegalArgumentException("Fill: 원소 타입 없는 컬렉션: " + c);
        }
        return construct(c, env, depth);
    }

    private static Object construct(Class<?> c, Map<TypeVariable<?>, Type> env, int depth) {
        // JPA 엔티티(core, 전환 대상 아님)는 getter 가 같은 규칙으로 값을 내는 mock 으로 대신한다.
        if (c.isAnnotationPresent(jakarta.persistence.Entity.class)) return Mockito.mock(c, ANSWER);
        if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
            throw new IllegalArgumentException("Fill: 인터페이스·추상 타입은 직접 스텁하세요: " + c.getName());
        }
        Constructor<?> ctor = Arrays.stream(c.getConstructors())
                .filter(k -> !k.isSynthetic())
                .filter(k -> Arrays.stream(k.getParameterTypes())
                        .noneMatch(p -> p.getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")))
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new IllegalArgumentException("Fill: 공개 생성자 없음: " + c.getName()));
        Type[] params = ctor.getGenericParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) args[i] = value(params[i], env, depth + 1);
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Fill: 생성 실패 " + c.getName(), e);
        }
    }
}
