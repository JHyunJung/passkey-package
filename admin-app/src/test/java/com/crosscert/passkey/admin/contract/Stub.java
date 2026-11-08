package com.crosscert.passkey.admin.contract;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 생성자 인자 이름으로 일부 값만 정하고 나머지는 {@link Fill} 로 채운 객체 — 화면 스냅샷의 결정적 스텁.
 *
 * <p>{@link Fill} 과 같은 규칙으로 공개(비합성) 생성자 중 인자가 가장 많은 것을 쓴다(Java record 는 정규 생성자,
 * Kotlin data class 는 주 생성자). 인자 이름은 {@code -parameters}(Java)·{@code javaParameters = true}(Kotlin) 로
 * 클래스 파일에 남으므로 전환 뒤에도 테스트를 고치지 않고 같은 객체가 만들어진다. 없는 이름을 주면 실패한다(오타 방지).
 * {@code null} 을 값으로 줄 수 있다.
 */
final class Stub {

    private Stub() {}

    @SuppressWarnings("unchecked")
    static <T> T of(Class<T> type, Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) throw new IllegalArgumentException("Stub: 이름·값 쌍이 아니다");
        Map<String, Object> given = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) given.put((String) namesAndValues[i], namesAndValues[i + 1]);
        Constructor<?> ctor = Arrays.stream(type.getConstructors())
                .filter(k -> !k.isSynthetic())
                .filter(k -> Arrays.stream(k.getParameterTypes())
                        .noneMatch(p -> p.getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")))
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new IllegalArgumentException("Stub: 공개 생성자 없음: " + type.getName()));
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
        Set<String> used = new HashSet<>();
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            if (!p.isNamePresent()) throw new IllegalStateException("Stub: 인자 이름 없음(-parameters 꺼짐?): " + type.getName());
            if (given.containsKey(p.getName())) {
                args[i] = given.get(p.getName());
                used.add(p.getName());
            } else {
                args[i] = Fill.value(p.getParameterizedType());
            }
        }
        Set<String> unknown = new HashSet<>(given.keySet());
        unknown.removeAll(used);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Stub: " + type.getSimpleName() + " 에 없는 인자 " + unknown);
        try {
            return (T) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Stub: 생성 실패 " + type.getName(), e);
        }
    }
}
